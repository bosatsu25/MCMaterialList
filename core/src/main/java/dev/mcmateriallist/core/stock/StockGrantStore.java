package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.LocationFiles;
import dev.mcmateriallist.core.location.LocationResult;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.persistence.UnsupportedSchemaException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Durable per-world grant registry with the LocationProfileStore contract: lock, expected generation, verified atomic replace,
 * backup, explicit recovery that preserves damaged bytes, and a future schema that is never overwritten. Call from an owned serial
 * background queue. Layout: &lt;world&gt;/mcmateriallist-stock/grants/&lt;project-uuid&gt;.json; the only path input is the UUID.
 */
public final class StockGrantStore {
    public static final int MAX_FILE_BYTES = StockGrantCodec.MAX_BYTES;
    private final Path grants;
    private final StockGrantCodec codec = new StockGrantCodec();
    private final WriteHook hook;

    enum WriteStage { BEFORE_BACKUP_REPLACE, BEFORE_PRIMARY_REPLACE }
    @FunctionalInterface interface WriteHook { void before(WriteStage stage) throws IOException; }
    /** worldRoot is the server's world directory (LevelResource.ROOT). */
    public StockGrantStore(Path worldRoot) { this(worldRoot, stage -> {}); }
    StockGrantStore(Path worldRoot, WriteHook hook) {
        grants = worldRoot.toAbsolutePath().normalize().resolve("mcmateriallist-stock").resolve("grants"); this.hook = Objects.requireNonNull(hook);
    }

    public LocationResult<StockGrants> load(UUID projectId) {
        Path file = file(projectId);
        try {
            LocationResult<StockGrants> primary = read(file, projectId);
            if (primary.ok() || primary.status() == StoreStatus.UNSUPPORTED_SCHEMA || primary.status() == StoreStatus.TOO_LARGE) return primary;
            LocationResult<StockGrants> previous = read(backup(file), projectId);
            if (previous.status() == StoreStatus.UNSUPPORTED_SCHEMA) return previous;
            if (previous.ok()) return new LocationResult<>(StoreStatus.RECOVERY_REQUIRED, previous.value());
            return primary.status() == StoreStatus.MISSING && previous.status() != StoreStatus.MISSING ? previous : primary;
        } catch (LocationFiles.UnsafePathException exception) { return LocationResult.failure(StoreStatus.INVALID_PATH); }
        catch (IOException exception) { return LocationResult.failure(StoreStatus.IO_FAILURE); }
    }
    /** expectedGeneration=-1 creates generation 0 ONLY when neither primary nor backup exists. */
    public LocationResult<StockGrants> save(StockGrants value, long expectedGeneration) {
        Path file = file(value.projectId());
        try {
            LocationFiles.ensureDirectories(file.getParent()); Path lock = lock(file);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) return LocationResult.failure(StoreStatus.BUSY);
                LocationResult<StockGrants> current = load(value.projectId());
                if (expectedGeneration == -1) {
                    if (current.status() != StoreStatus.MISSING)
                        return current.ok() || current.status() == StoreStatus.RECOVERY_REQUIRED ? LocationResult.failure(StoreStatus.CONFLICT) : current;
                    if (value.generation() != 0) return LocationResult.failure(StoreStatus.CONFLICT);
                } else {
                    if (current.status() == StoreStatus.MISSING) return LocationResult.failure(StoreStatus.CONFLICT);
                    if (!current.ok()) return current;
                    if (current.value().generation() != expectedGeneration || value.generation() != Math.incrementExact(expectedGeneration))
                        return LocationResult.failure(StoreStatus.CONFLICT);
                    LocationResult<StockGrants> oldBackup = read(backup(file), value.projectId());
                    if (oldBackup.status() != StoreStatus.MISSING && !oldBackup.ok()) return oldBackup;
                }
                byte[] bytes = codec.encode(value).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_FILE_BYTES) return LocationResult.failure(StoreStatus.TOO_LARGE);
                if (!value.equals(decode(bytes, value.projectId()))) return LocationResult.failure(StoreStatus.CORRUPT);
                if (current.ok()) replace(backup(file), LocationFiles.read(file, MAX_FILE_BYTES), value.projectId(), WriteStage.BEFORE_BACKUP_REPLACE);
                replace(file, bytes, value.projectId(), WriteStage.BEFORE_PRIMARY_REPLACE);
                return new LocationResult<>(StoreStatus.OK, value);
            }
        } catch (LocationFiles.UnsafePathException exception) { return LocationResult.failure(StoreStatus.INVALID_PATH); }
        catch (AtomicMoveNotSupportedException exception) { return LocationResult.failure(StoreStatus.ATOMIC_REPLACE_UNAVAILABLE); }
        catch (OverlappingFileLockException exception) { return LocationResult.failure(StoreStatus.BUSY); }
        catch (IllegalArgumentException | ArithmeticException exception) { return LocationResult.failure(StoreStatus.CORRUPT); }
        catch (IOException exception) { return LocationResult.failure(StoreStatus.IO_FAILURE); }
    }
    /**
     * Load, apply and save against the loaded generation (a concurrent writer yields CONFLICT). A MISSING file starts from
     * StockGrants.empty and is created at generation 0; an unchanged result is returned without writing. change may throw
     * IllegalArgumentException beyond MAX_GRANTS, reported as TOO_LARGE.
     */
    public LocationResult<StockGrants> update(UUID projectId, UnaryOperator<StockGrants> change) {
        LocationResult<StockGrants> current = load(projectId);
        if (!current.ok() && current.status() != StoreStatus.MISSING) return current;
        StockGrants base = current.ok() ? current.value() : StockGrants.empty(projectId), next;
        try { next = change.apply(base); } catch (IllegalArgumentException | ArithmeticException exception) { return LocationResult.failure(StoreStatus.TOO_LARGE); }
        if (!next.projectId().equals(projectId)) return LocationResult.failure(StoreStatus.CORRUPT);
        if (next.equals(base)) return new LocationResult<>(StoreStatus.OK, base);
        return current.ok() ? save(next, base.generation()) : save(new StockGrants(projectId, 0, next.grants()), -1);
    }
    /** Explicit recovery preserves damaged bytes and restores only a validated known-schema backup. */
    public LocationResult<StockGrants> recover(UUID projectId) {
        Path file = file(projectId);
        try {
            LocationFiles.ensureDirectories(file.getParent()); Path lock = lock(file);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) return LocationResult.failure(StoreStatus.BUSY);
                LocationResult<StockGrants> candidate = load(projectId);
                if (candidate.status() != StoreStatus.RECOVERY_REQUIRED) return candidate;
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    LocationFiles.guard(file, false);
                    Path damaged = Files.createTempFile(file.getParent(), "grants-damaged-", ".json");
                    Files.copy(file, damaged, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                }
                replace(file, codec.encode(candidate.value()).getBytes(StandardCharsets.UTF_8), projectId, WriteStage.BEFORE_PRIMARY_REPLACE);
                return new LocationResult<>(StoreStatus.OK, candidate.value());
            }
        } catch (LocationFiles.UnsafePathException exception) { return LocationResult.failure(StoreStatus.INVALID_PATH); }
        catch (AtomicMoveNotSupportedException exception) { return LocationResult.failure(StoreStatus.ATOMIC_REPLACE_UNAVAILABLE); }
        catch (OverlappingFileLockException exception) { return LocationResult.failure(StoreStatus.BUSY); }
        catch (IllegalArgumentException exception) { return LocationResult.failure(StoreStatus.CORRUPT); }
        catch (IOException exception) { return LocationResult.failure(StoreStatus.IO_FAILURE); }
    }

    private LocationResult<StockGrants> read(Path file, UUID projectId) throws IOException {
        LocationFiles.guard(file, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return LocationResult.failure(StoreStatus.MISSING);
        if (Files.size(file) > MAX_FILE_BYTES) return LocationResult.failure(StoreStatus.TOO_LARGE);
        byte[] bytes = LocationFiles.read(file, MAX_FILE_BYTES);
        try { return new LocationResult<>(StoreStatus.OK, decode(bytes, projectId)); }
        catch (UnsupportedSchemaException exception) { return LocationResult.failure(StoreStatus.UNSUPPORTED_SCHEMA); }
        catch (IllegalArgumentException | ArithmeticException exception) { return LocationResult.failure(StoreStatus.CORRUPT); }
    }
    /** Content identity must match the path. */
    private StockGrants decode(byte[] bytes, UUID projectId) {
        StockGrants value = codec.decode(bytes);
        if (!value.projectId().equals(projectId)) throw new IllegalArgumentException("Grant document does not match its path");
        return value;
    }
    private void replace(Path target, byte[] bytes, UUID projectId, WriteStage stage) throws IOException {
        LocationFiles.replace(target, bytes, MAX_FILE_BYTES, content -> decode(content, projectId), () -> hook.before(stage));
    }
    private Path file(UUID projectId) { return grants.resolve(Objects.requireNonNull(projectId, "projectId") + ".json"); }
    private static Path lock(Path file) throws IOException { Path lock = file.resolveSibling(file.getFileName() + ".lock"); LocationFiles.guard(lock, false); return lock; }
    private static Path backup(Path file) { return file.resolveSibling(file.getFileName() + ".bak"); }
}
