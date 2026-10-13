package dev.mcmateriallist.core.shared.persistence;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.protocol.SharedBinary;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Synchronous controlled storage boundary. SharedProjectService owns its background I/O lifecycle. */
public final class SharedProjectStore {
    public static final int SCHEMA = 2, WORLD_SCHEMA = 1, MAX_JSON_BYTES = 128 * 1024 * 1024 + 256;
    public record Result<T>(StoreStatus status, T value) {
        public boolean ok() { return status == StoreStatus.OK; }
        static <T> Result<T> failure(StoreStatus status) { return new Result<>(status, null); }
    }
    public record World(UUID namespace, List<UUID> projects) { public World { projects = List.copyOf(projects); } }
    private record Payload(int schema, byte[] bytes) {}
    public enum WriteStage { BEFORE_BACKUP_REPLACE, BEFORE_PRIMARY_REPLACE }
    @FunctionalInterface public interface WriteHook { void before(WriteStage stage) throws IOException; }
    private final Path root;
    private final WriteHook hook;
    public SharedProjectStore(Path configuredWorldRoot) { this(configuredWorldRoot, stage -> {}); }
    /** Hook is for deterministic fault tests; exceptions never contain private data from this store. */
    public SharedProjectStore(Path configuredWorldRoot, WriteHook hook) {
        root = Objects.requireNonNull(configuredWorldRoot).toAbsolutePath().normalize().resolve("mcmateriallist-shared"); this.hook = Objects.requireNonNull(hook);
    }
    public Result<World> openWorld() {
        try {
            directories(root);
            return locked(worldFile(), () -> {
                Result<Payload> world = loadBytes(worldFile(), "world");
                List<UUID> projects = projectIds();
                if (world.status() == StoreStatus.MISSING) {
                    if (!projects.isEmpty()) return Result.failure(StoreStatus.CORRUPT);
                    UUID namespace = UUID.randomUUID(); byte[] bytes = uuidBytes(namespace);
                    replace(worldFile(), envelope("world", bytes), "world", WriteStage.BEFORE_PRIMARY_REPLACE);
                    return new Result<>(StoreStatus.OK, new World(namespace, projects));
                }
                if (!world.ok()) return Result.failure(world.status());
                return new Result<>(StoreStatus.OK, new World(readUuid(world.value().bytes()), projects));
            });
        } catch (IOException | IllegalArgumentException exception) { return failure(exception); }
    }
    public Result<ProjectArchive> load(UUID world, UUID project) {
        try {
            Result<ProjectArchive> primary = readProject(projectFile(project), world, project);
            if (primary.ok() || primary.status() == StoreStatus.UNSUPPORTED_SCHEMA || primary.status() == StoreStatus.TOO_LARGE) return primary;
            Result<ProjectArchive> previous = readProject(backup(projectFile(project)), world, project);
            if (previous.status() == StoreStatus.UNSUPPORTED_SCHEMA || previous.status() == StoreStatus.TOO_LARGE) return previous;
            if (previous.ok()) return new Result<>(StoreStatus.RECOVERY_REQUIRED, previous.value());
            return primary.status() == StoreStatus.MISSING && previous.status() != StoreStatus.MISSING ? previous : primary;
        } catch (IOException | IllegalArgumentException exception) { return failure(exception); }
    }
    private Result<ProjectArchive> readProject(Path file, UUID world, UUID project) throws IOException {
        Result<Payload> bytes = read(file, "project");
        if (!bytes.ok()) return Result.failure(bytes.status());
        Payload payload = bytes.value();
        ProjectArchive archive = payload.schema() == 1 ? SharedBinary.decodeLegacyArchive(payload.bytes()) : SharedBinary.decodeArchive(payload.bytes());
        if (!archive.snapshot().scope().worldNamespace().equals(world) || !archive.snapshot().scope().projectId().equals(project))
            return Result.failure(StoreStatus.CORRUPT);
        return new Result<>(StoreStatus.OK, archive);
    }
    /** expectedGeneration=-1 creates only if primary AND backup are absent. */
    public Result<ProjectArchive> save(ProjectArchive archive, long expectedGeneration) {
        Path file = projectFile(archive.snapshot().scope().projectId());
        try {
            directories(root);
            return locked(file, () -> {
                Result<ProjectArchive> current = load(archive.snapshot().scope().worldNamespace(), archive.snapshot().scope().projectId());
                if (!current.ok() && current.status() != StoreStatus.MISSING) return current;
                if (current.ok()) {
                    if (current.value().generation() != expectedGeneration || archive.generation() != Math.incrementExact(expectedGeneration)
                        || !current.value().snapshot().scope().equals(archive.snapshot().scope())) return Result.failure(StoreStatus.CONFLICT);
                    Result<ProjectArchive> backup = readProject(backup(file), archive.snapshot().scope().worldNamespace(), archive.snapshot().scope().projectId());
                    if (!backup.ok() && backup.status() != StoreStatus.MISSING) return Result.failure(backup.status());
                } else if (expectedGeneration != -1 || archive.generation() != 0) return Result.failure(StoreStatus.CONFLICT);
                byte[] payload = SharedBinary.encodeArchive(archive);
                if (!archive.equals(SharedBinary.decodeArchive(payload))) return Result.failure(StoreStatus.CORRUPT);
                if (current.ok()) replace(backup(file), readText(file), "project", WriteStage.BEFORE_BACKUP_REPLACE);
                replace(file, envelope("project", payload), "project", WriteStage.BEFORE_PRIMARY_REPLACE);
                return new Result<>(StoreStatus.OK, archive);
            });
        } catch (IOException | IllegalArgumentException | ArithmeticException exception) { return failure(exception); }
    }
    /** Explicit administrator-confirmed offline recovery; never called automatically by the service. */
    public Result<ProjectArchive> recover(UUID world, UUID project) {
        Path file = projectFile(project);
        try {
            directories(root);
            return locked(file, () -> {
                Result<ProjectArchive> candidate = load(world, project);
                if (candidate.status() != StoreStatus.RECOVERY_REQUIRED) return candidate;
                retainDamaged(file);
                replace(file, envelope("project", SharedBinary.encodeArchive(candidate.value())), "project", WriteStage.BEFORE_PRIMARY_REPLACE);
                return new Result<>(StoreStatus.OK, candidate.value());
            });
        } catch (IOException | IllegalArgumentException exception) { return failure(exception); }
    }
    public Result<UUID> recoverWorld() {
        try {
            directories(root);
            return locked(worldFile(), () -> {
                Result<Payload> candidate = loadBytes(worldFile(), "world");
                if (candidate.status() != StoreStatus.RECOVERY_REQUIRED) return Result.failure(candidate.status());
                UUID namespace = readUuid(candidate.value().bytes()); retainDamaged(worldFile());
                replace(worldFile(), envelope("world", candidate.value().bytes()), "world", WriteStage.BEFORE_PRIMARY_REPLACE);
                return new Result<>(StoreStatus.OK, namespace);
            });
        } catch (IOException | IllegalArgumentException exception) { return failure(exception); }
    }
    private Result<Payload> loadBytes(Path file, String kind) throws IOException {
        Result<Payload> primary = read(file, kind);
        if (primary.ok() || primary.status() == StoreStatus.UNSUPPORTED_SCHEMA || primary.status() == StoreStatus.TOO_LARGE) return primary;
        Result<Payload> previous = read(backup(file), kind);
        if (previous.status() == StoreStatus.UNSUPPORTED_SCHEMA || previous.status() == StoreStatus.TOO_LARGE) return previous;
        if (previous.ok()) return new Result<>(StoreStatus.RECOVERY_REQUIRED, previous.value());
        return primary.status() == StoreStatus.MISSING && previous.status() != StoreStatus.MISSING ? previous : primary;
    }
    private Result<Payload> read(Path file, String kind) throws IOException {
        guard(file, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Result.failure(StoreStatus.MISSING);
        if (Files.size(file) > MAX_JSON_BYTES) return Result.failure(StoreStatus.TOO_LARGE);
        try { return parse(readText(file), kind); }
        catch (IllegalArgumentException | java.nio.charset.CharacterCodingException exception) { return Result.failure(StoreStatus.CORRUPT); }
    }
    private static Result<Payload> parse(String json, String kind) {
        var schema = SharedSchemaHeader.classify(json, Set.of("schema", "kind", "payload"), kind.equals("project") ? SCHEMA : WORLD_SCHEMA);
        if (schema != StoreStatus.OK) return Result.failure(schema);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(com.google.gson.Strictness.STRICT); reader.beginObject();
            String encoded = null, actualKind = null; int version = 0;
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "schema" -> version = Integer.parseInt(reader.nextString());
                    case "kind" -> {
                        if (reader.peek() != JsonToken.STRING) return Result.failure(StoreStatus.CORRUPT);
                        actualKind = reader.nextString();
                    }
                    case "payload" -> {
                        if (reader.peek() != JsonToken.STRING) return Result.failure(StoreStatus.CORRUPT);
                        encoded = reader.nextString();
                    }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (!kind.equals(actualKind) || encoded == null || reader.peek() != JsonToken.END_DOCUMENT) return Result.failure(StoreStatus.CORRUPT);
            if (encoded.length() > ((long) SharedLimits.ARCHIVE_BYTES + 2) / 3 * 4) return Result.failure(StoreStatus.TOO_LARGE);
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (kind.equals("project")) {
                // Opening never re-encodes a legacy payload, even near the archive byte limit.
                if (version == 1) SharedBinary.decodeLegacyArchive(bytes);
                else SharedBinary.decodeArchive(bytes);
            } else readUuid(bytes);
            return new Result<>(StoreStatus.OK, new Payload(version, bytes));
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) { return Result.failure(StoreStatus.CORRUPT); }
    }
    private static String envelope(String kind, byte[] payload) {
        int version = kind.equals("project") ? SCHEMA : WORLD_SCHEMA;
        String result = "{\"schema\":" + version + ",\"kind\":\"" + kind + "\",\"payload\":\"" + Base64.getEncoder().encodeToString(payload) + "\"}";
        if (result.length() > MAX_JSON_BYTES) throw new IllegalArgumentException("Shared store size limit"); return result;
    }
    private String readText(Path file) throws IOException {
        guard(file, false);
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new IOException("Shared store size limit");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
    private void replace(Path file, String json, String kind, WriteStage stage) throws IOException {
        guard(file, false);
        Path temporary = Files.createTempFile(root, "shared-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            if (!parse(readText(temporary), kind).ok()) throw new IOException("Shared store verification failed");
            hook.before(stage); guard(file, false);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void retainDamaged(Path file) throws IOException {
        guard(file, false);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            Path damaged = Files.createTempFile(root, "damaged-", ".retained");
            Files.copy(file, damaged, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(damaged, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        }
    }
    private List<UUID> projectIds() throws IOException {
        Set<UUID> ids = new HashSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.startsWith("project-")) continue;
                if (name.endsWith(".json.bak")) name = name.substring(0, name.length() - 4);
                if (!name.endsWith(".json")) continue;
                String id = name.substring(8, name.length() - 5); UUID parsed = UUID.fromString(id);
                if (!parsed.toString().equals(id)) throw new IllegalArgumentException("Invalid stored project identity");
                guard(file, false); ids.add(parsed); SharedLimits.count(ids.size(), SharedLimits.PROJECTS);
            }
        }
        return ids.stream().sorted().toList();
    }
    private Path worldFile() { return root.resolve("world.json"); }
    private Path projectFile(UUID project) { return root.resolve("project-" + Objects.requireNonNull(project) + ".json"); }
    private static Path backup(Path file) { return file.resolveSibling(file.getFileName() + ".bak"); }
    @FunctionalInterface private interface Locked<T> { Result<T> run() throws IOException; }
    private <T> Result<T> locked(Path file, Locked<T> action) throws IOException {
        Path lock = file.resolveSibling(file.getFileName() + ".lock"); guard(lock, false);
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock held = channel.tryLock()) {
            if (held == null) return Result.failure(StoreStatus.BUSY); return action.run();
        } catch (OverlappingFileLockException exception) { return Result.failure(StoreStatus.BUSY); }
    }
    private static byte[] uuidBytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID readUuid(byte[] bytes) {
        if (bytes.length != 16) throw new IllegalArgumentException("Invalid world identity");
        ByteBuffer buffer = ByteBuffer.wrap(bytes); return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static void directories(Path directory) throws IOException {
        guard(directory, true); Path cursor = directory.getRoot();
        for (Path part : directory) { cursor = cursor.resolve(part); if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(cursor); guard(cursor, true); }
    }
    private static void guard(Path path, boolean directory) throws IOException {
        Path normalized = path.toAbsolutePath().normalize(), cursor = normalized.getRoot();
        for (Path part : normalized) {
            cursor = cursor.resolve(part); if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attrs = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean last = cursor.equals(normalized);
            if (attrs.isSymbolicLink() || attrs.isOther() || !cursor.toRealPath().equals(cursor)
                || ((!last || directory) && !attrs.isDirectory()) || (last && !directory && !attrs.isRegularFile())) throw new UnsafePath();
        }
    }
    private static final class UnsafePath extends IOException {
        private static final long serialVersionUID = 1L;
        UnsafePath() { super("Unsafe shared storage path"); }
    }
    private static <T> Result<T> failure(Exception exception) {
        return Result.failure(exception instanceof UnsafePath ? StoreStatus.INVALID_PATH
            : exception instanceof AtomicMoveNotSupportedException ? StoreStatus.ATOMIC_REPLACE_UNAVAILABLE
            : exception instanceof IllegalArgumentException || exception instanceof ArithmeticException ? StoreStatus.CORRUPT : StoreStatus.IO_FAILURE);
    }
}
