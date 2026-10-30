package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.persistence.UnsupportedSchemaException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

/**
 * Synchronous bounded I/O boundary with the Phase 1 snapshot contract. Call from an owned serial background queue.
 * Layout: location-files/worlds/&lt;world-uuid&gt;/profiles/&lt;profile-uuid&gt;.json and .../&lt;world-uuid&gt;/current.json.
 */
public final class LocationProfileStore {
    public static final int MAX_FILE_BYTES = LocationProfileCodec.MAX_BYTES;
    static final int MAX_SCANNED_ENTRIES = 4096;
    // A profile whose primary is gone but whose backup survives is still listed (as RECOVERY_REQUIRED), never hidden.
    private static final Pattern PROFILE_FILE = Pattern.compile("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.json(\\.bak)?");
    private final Path worlds;
    private final LocationProfileCodec codec = new LocationProfileCodec();
    private final WriteHook hook;

    enum WriteStage { BEFORE_BACKUP_REPLACE, BEFORE_PRIMARY_REPLACE }
    @FunctionalInterface interface WriteHook { void before(WriteStage stage) throws IOException; }
    /** configRoot is the client's config/mcmateriallist directory. */
    public LocationProfileStore(Path configRoot) { this(configRoot, stage -> {}); }
    LocationProfileStore(Path configRoot, WriteHook hook) {
        worlds = configRoot.toAbsolutePath().normalize().resolve("location-files").resolve("worlds"); this.hook = hook;
    }

    /** Path components come only from canonical UUIDs; content identity must match the path. */
    private record Document<T>(Path file, Function<byte[], T> decoder, Predicate<T> identity, Function<T, String> encoder,
        ToLongFunction<T> generation, String damagedPrefix) {}

    public LocationResult<LocationProfile> load(WorldIdentity world, LocationProfileId id) { return load(profile(world, id)); }
    /** expectedGeneration=-1 creates generation 0 ONLY when neither primary nor backup exists. */
    public LocationResult<LocationProfile> save(LocationProfile profile, long expectedGeneration) { return save(profile(profile.world(), profile.id()), profile, expectedGeneration); }
    public LocationResult<LocationProfile> recover(WorldIdentity world, LocationProfileId id) { return recover(profile(world, id)); }
    public LocationResult<LocationSelection> loadSelection(WorldIdentity world) { return load(selection(world)); }
    public LocationResult<LocationSelection> saveSelection(LocationSelection selection, long expectedGeneration) { return save(selection(selection.world()), selection, expectedGeneration); }
    public LocationResult<LocationSelection> recoverSelection(WorldIdentity world) { return recover(selection(world)); }

    /** Lists without following links; non-canonical names are ignored and unreadable profiles keep their status. */
    public LocationResult<List<ProfileEntry>> list(WorldIdentity world) {
        Path directory = profiles(world);
        try {
            LocationFiles.guard(directory, true);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return new LocationResult<>(StoreStatus.OK, List.of());
            Set<LocationProfileId> ids = new HashSet<>(); int scanned = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    if (++scanned > MAX_SCANNED_ENTRIES) return LocationResult.failure(StoreStatus.TOO_LARGE);
                    var name = PROFILE_FILE.matcher(entry.getFileName().toString());
                    if (!name.matches()) continue;
                    var id = LocationProfileId.parse(name.group(1));
                    if (!ids.contains(id) && ids.size() == LocationLimits.MAX_PROFILES) return LocationResult.failure(StoreStatus.TOO_LARGE);
                    ids.add(id);
                }
            }
            List<ProfileEntry> result = new ArrayList<>(ids.size());
            for (LocationProfileId id : ids.stream().sorted(Comparator.comparing(LocationProfileId::toString)).toList()) { var loaded = load(world, id); result.add(new ProfileEntry(id, loaded.status(), loaded.value())); }
            return new LocationResult<>(StoreStatus.OK, List.copyOf(result));
        } catch (LocationFiles.UnsafePathException exception) { return LocationResult.failure(StoreStatus.INVALID_PATH); }
        catch (IOException | DirectoryIteratorException exception) { return LocationResult.failure(StoreStatus.IO_FAILURE); }
    }

    private <T> LocationResult<T> load(Document<T> document) {
        Path file = document.file();
        try {
            LocationResult<T> primary = read(document, file);
            if (primary.ok() || primary.status() == StoreStatus.UNSUPPORTED_SCHEMA || primary.status() == StoreStatus.TOO_LARGE) return primary;
            LocationResult<T> previous = read(document, backup(file));
            if (previous.status() == StoreStatus.UNSUPPORTED_SCHEMA) return previous;
            if (previous.ok()) return new LocationResult<>(StoreStatus.RECOVERY_REQUIRED, previous.value());
            return primary.status() == StoreStatus.MISSING && previous.status() != StoreStatus.MISSING ? previous : primary;
        } catch (LocationFiles.UnsafePathException exception) { return LocationResult.failure(StoreStatus.INVALID_PATH); }
        catch (IOException exception) { return LocationResult.failure(StoreStatus.IO_FAILURE); }
    }
    private <T> LocationResult<T> save(Document<T> document, T value, long expectedGeneration) {
        Path file = document.file();
        try {
            LocationFiles.ensureDirectories(file.getParent()); Path lock = file.resolveSibling(file.getFileName() + ".lock"); LocationFiles.guard(lock, false);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) return LocationResult.failure(StoreStatus.BUSY);
                LocationResult<T> current = load(document);
                if (expectedGeneration == -1) {
                    if (current.status() != StoreStatus.MISSING)
                        return current.ok() || current.status() == StoreStatus.RECOVERY_REQUIRED ? LocationResult.failure(StoreStatus.CONFLICT) : current;
                    if (document.generation().applyAsLong(value) != 0) return LocationResult.failure(StoreStatus.CONFLICT);
                } else {
                    if (current.status() == StoreStatus.MISSING) return LocationResult.failure(StoreStatus.CONFLICT);
                    if (!current.ok()) return current;
                    if (document.generation().applyAsLong(current.value()) != expectedGeneration
                        || document.generation().applyAsLong(value) != Math.incrementExact(expectedGeneration)) return LocationResult.failure(StoreStatus.CONFLICT);
                    LocationResult<T> oldBackup = read(document, backup(file));
                    if (oldBackup.status() != StoreStatus.MISSING && !oldBackup.ok()) return oldBackup;
                }
                byte[] bytes = document.encoder().apply(value).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_FILE_BYTES) return LocationResult.failure(StoreStatus.TOO_LARGE);
                if (!value.equals(decode(document, bytes))) return LocationResult.failure(StoreStatus.CORRUPT);
                if (current.ok()) replace(document, backup(file), LocationFiles.read(file, MAX_FILE_BYTES), WriteStage.BEFORE_BACKUP_REPLACE);
                replace(document, file, bytes, WriteStage.BEFORE_PRIMARY_REPLACE);
                return new LocationResult<>(StoreStatus.OK, value);
            }
        } catch (LocationFiles.UnsafePathException exception) { return LocationResult.failure(StoreStatus.INVALID_PATH); }
        catch (AtomicMoveNotSupportedException exception) { return LocationResult.failure(StoreStatus.ATOMIC_REPLACE_UNAVAILABLE); }
        catch (OverlappingFileLockException exception) { return LocationResult.failure(StoreStatus.BUSY); }
        catch (IllegalArgumentException | ArithmeticException exception) { return LocationResult.failure(StoreStatus.CORRUPT); }
        catch (IOException exception) { return LocationResult.failure(StoreStatus.IO_FAILURE); }
    }
    /** Explicit recovery preserves damaged bytes and restores only a validated known-schema backup. */
    private <T> LocationResult<T> recover(Document<T> document) {
        Path file = document.file();
        try {
            LocationFiles.ensureDirectories(file.getParent()); Path lock = file.resolveSibling(file.getFileName() + ".lock"); LocationFiles.guard(lock, false);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) return LocationResult.failure(StoreStatus.BUSY);
                LocationResult<T> candidate = load(document);
                if (candidate.status() != StoreStatus.RECOVERY_REQUIRED) return candidate;
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    LocationFiles.guard(file, false);
                    Path damaged = Files.createTempFile(file.getParent(), document.damagedPrefix(), ".json");
                    Files.copy(file, damaged, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                }
                replace(document, file, document.encoder().apply(candidate.value()).getBytes(StandardCharsets.UTF_8), WriteStage.BEFORE_PRIMARY_REPLACE);
                return new LocationResult<>(StoreStatus.OK, candidate.value());
            }
        } catch (LocationFiles.UnsafePathException exception) { return LocationResult.failure(StoreStatus.INVALID_PATH); }
        catch (AtomicMoveNotSupportedException exception) { return LocationResult.failure(StoreStatus.ATOMIC_REPLACE_UNAVAILABLE); }
        catch (OverlappingFileLockException exception) { return LocationResult.failure(StoreStatus.BUSY); }
        catch (IllegalArgumentException exception) { return LocationResult.failure(StoreStatus.CORRUPT); }
        catch (IOException exception) { return LocationResult.failure(StoreStatus.IO_FAILURE); }
    }
    private <T> LocationResult<T> read(Document<T> document, Path file) throws IOException {
        LocationFiles.guard(file, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return LocationResult.failure(StoreStatus.MISSING);
        if (Files.size(file) > MAX_FILE_BYTES) return LocationResult.failure(StoreStatus.TOO_LARGE);
        byte[] bytes = LocationFiles.read(file, MAX_FILE_BYTES);
        try { return new LocationResult<>(StoreStatus.OK, decode(document, bytes)); }
        catch (UnsupportedSchemaException exception) { return LocationResult.failure(StoreStatus.UNSUPPORTED_SCHEMA); }
        catch (IllegalArgumentException | ArithmeticException exception) { return LocationResult.failure(StoreStatus.CORRUPT); }
    }
    private static <T> T decode(Document<T> document, byte[] bytes) {
        T value = document.decoder().apply(bytes);
        if (!document.identity().test(value)) throw new IllegalArgumentException("Location document does not match its path");
        return value;
    }
    private <T> void replace(Document<T> document, Path target, byte[] bytes, WriteStage stage) throws IOException {
        LocationFiles.replace(target, bytes, MAX_FILE_BYTES, content -> decode(document, content), () -> hook.before(stage));
    }
    private Document<LocationProfile> profile(WorldIdentity world, LocationProfileId id) {
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(id, "id");
        return new Document<>(profiles(world).resolve(id + ".json"), codec::decodeProfile,
            profile -> profile.world().equals(world) && profile.id().equals(id), codec::encode, LocationProfile::generation, "profile-damaged-");
    }
    private Document<LocationSelection> selection(WorldIdentity world) {
        Objects.requireNonNull(world, "world");
        return new Document<>(directory(world).resolve("current.json"), codec::decodeSelection,
            selection -> selection.world().equals(world), codec::encode, LocationSelection::generation, "current-damaged-");
    }
    private Path directory(WorldIdentity world) { return worlds.resolve(world.namespace().toString()); }
    private Path profiles(WorldIdentity world) { return directory(world).resolve("profiles"); }
    private static Path backup(Path file) { return file.resolveSibling(file.getFileName() + ".bak"); }
}
