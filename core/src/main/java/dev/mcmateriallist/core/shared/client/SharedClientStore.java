package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Synchronous boundary; the lifecycle-owned service invokes this off the client thread. */
public final class SharedClientStore {
    public static final int MAX_JSON_BYTES = ((SharedClientCodec.MAX_BYTES + 2) / 3) * 4 + 128;
    public record Result(StoreStatus status, SharedClientArchive value) {
        public boolean ok() { return status == StoreStatus.OK; }
        static Result failure(StoreStatus status) { return new Result(status, null); }
    }
    private final Path root;
    public SharedClientStore(Path configuredConfigRoot) {
        root = Objects.requireNonNull(configuredConfigRoot).toAbsolutePath().normalize().resolve("shared-client");
    }
    public Result load(LocalPlacementId placement) {
        try {
            Result primary = read(file(placement), placement);
            if (primary.ok() || primary.status() == StoreStatus.UNSUPPORTED_SCHEMA || primary.status() == StoreStatus.TOO_LARGE) return primary;
            Result previous = read(backup(file(placement)), placement);
            if (previous.status() == StoreStatus.UNSUPPORTED_SCHEMA || previous.status() == StoreStatus.TOO_LARGE) return previous;
            // A recovery candidate is never returned as usable private presentation.
            if (previous.ok()) return Result.failure(StoreStatus.RECOVERY_REQUIRED);
            return primary.status() == StoreStatus.MISSING && previous.status() != StoreStatus.MISSING ? previous : primary;
        } catch (IOException | IllegalArgumentException failure) { return failure(failure); }
    }
    public Result save(SharedClientArchive archive, long expectedGeneration) {
        Path file = file(archive.placement());
        try {
            directories(root); Path lock = file.resolveSibling(file.getFileName() + ".lock"); guard(lock, false);
            try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) return Result.failure(StoreStatus.BUSY);
                Result current = load(archive.placement());
                if (!current.ok() && current.status() != StoreStatus.MISSING) return current;
                if (current.ok()) {
                    if (current.value().generation() != expectedGeneration || archive.generation() != Math.incrementExact(expectedGeneration))
                        return Result.failure(StoreStatus.CONFLICT);
                    Result previous = read(backup(file), archive.placement());
                    if (!previous.ok() && previous.status() != StoreStatus.MISSING) return previous;
                } else if (expectedGeneration != -1 || archive.generation() != 0) return Result.failure(StoreStatus.CONFLICT);
                String json = encode(archive);
                if (!archive.equals(parse(json, archive.placement()).value())) return Result.failure(StoreStatus.CORRUPT);
                // Derived private snapshots need not survive permission removal in a previous-good backup.
                // Preserve the exact prior binding/journal generation; recovery always needs a live snapshot.
                if (current.ok()) replace(backup(file), encode(withoutCaches(current.value())), archive.placement());
                replace(file, json, archive.placement());
                return new Result(StoreStatus.OK, archive);
            }
        } catch (IOException | IllegalArgumentException | ArithmeticException | OverlappingFileLockException failure) { return failure(failure); }
    }
    public Result recover(LocalPlacementId placement) {
        Path file = file(placement);
        try {
            directories(root); Path lock = file.resolveSibling(file.getFileName() + ".lock"); guard(lock, false);
            try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) return Result.failure(StoreStatus.BUSY);
                var loaded = load(placement); if (loaded.status() != StoreStatus.RECOVERY_REQUIRED) return loaded;
                var previous = read(backup(file), placement); if (!previous.ok()) return previous;
                var candidate = withoutCaches(previous.value());
                // A backup can predate a sent command. A fresh snapshot cannot prove that lost intent's outcome.
                var restored = new SharedClientArchive(Math.incrementExact(candidate.generation()), placement,
                    candidate.materials(), candidate.regions(), candidate.operations(), true);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    guard(file, false); Path retained = Files.createTempFile(root, "journal-damaged-", ".retained");
                    try (var input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                         var output = FileChannel.open(retained, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024); long total = 0;
                        while (input.read(buffer) != -1) {
                            buffer.flip(); total += buffer.remaining();
                            if (total > MAX_JSON_BYTES) throw new IOException("Damaged journal size limit");
                            while (buffer.hasRemaining()) output.write(buffer); buffer.clear();
                        }
                        output.force(true);
                    }
                }
                replace(file, encode(restored), placement); return new Result(StoreStatus.OK, restored);
            }
        } catch (IOException | IllegalArgumentException | ArithmeticException | OverlappingFileLockException failure) { return failure(failure); }
    }
    private static SharedClientArchive withoutCaches(SharedClientArchive archive) {
        var material = archive.materials(); var region = archive.regions();
        return new SharedClientArchive(archive.generation(), archive.placement(),
            new SharedClientArchive.Binding(material.localDataset(), material.shared(), null),
            new SharedClientArchive.Binding(region.localDataset(), region.shared(), null), archive.operations(), archive.recoveredJournalUncertain());
    }
    private Result read(Path file, LocalPlacementId placement) throws IOException {
        guard(file, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Result.failure(StoreStatus.MISSING);
        if (Files.size(file) > MAX_JSON_BYTES) return Result.failure(StoreStatus.TOO_LARGE);
        try { return parse(readText(file), placement); }
        catch (java.nio.charset.CharacterCodingException invalid) { return Result.failure(StoreStatus.CORRUPT); }
    }
    private static Result parse(String json, LocalPlacementId placement) {
        // Inspect top-level schema before interpreting a payload whose format may be newer.
        // JSON member order is not a version contract. This pass never builds a payload tree.
        try (var header = new JsonReader(new StringReader(json))) {
            header.setStrictness(com.google.gson.Strictness.STRICT); header.beginObject();
            boolean schemaSeen = false, payloadSeen = false, invalidFields = false;
            int schema = -1;
            while (header.hasNext()) {
                String name = header.nextName();
                if (name.equals("schema")) {
                    invalidFields |= schemaSeen; schemaSeen = true;
                    if (header.peek() != JsonToken.NUMBER) return Result.failure(StoreStatus.CORRUPT);
                    String version = header.nextString();
                    if (version.isEmpty() || !version.chars().allMatch(character -> character >= '0' && character <= '9'))
                        return Result.failure(StoreStatus.CORRUPT);
                    if (version.length() > 1) return Result.failure(StoreStatus.UNSUPPORTED_SCHEMA);
                    schema = Integer.parseInt(version);
                    if (schema > 1) return Result.failure(StoreStatus.UNSUPPORTED_SCHEMA);
                } else {
                    if (name.equals("payload")) { invalidFields |= payloadSeen; payloadSeen = true; }
                    else invalidFields = true;
                    header.skipValue();
                }
            }
            header.endObject();
            if (invalidFields || !schemaSeen || !payloadSeen || schema != 1 || header.peek() != JsonToken.END_DOCUMENT)
                return Result.failure(StoreStatus.CORRUPT);
        } catch (IOException | IllegalArgumentException | IllegalStateException invalid) { return Result.failure(StoreStatus.CORRUPT); }
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(com.google.gson.Strictness.STRICT); reader.beginObject();
            String payload = null;
            while (reader.hasNext()) {
                if (reader.nextName().equals("payload")) {
                    if (reader.peek() != JsonToken.STRING) return Result.failure(StoreStatus.CORRUPT);
                    payload = reader.nextString();
                    if (payload.length() > ((long) SharedClientCodec.MAX_BYTES + 2) / 3 * 4) return Result.failure(StoreStatus.TOO_LARGE);
                } else reader.skipValue();
            }
            reader.endObject(); if (reader.peek() != JsonToken.END_DOCUMENT) return Result.failure(StoreStatus.CORRUPT);
            if (payload == null) return Result.failure(StoreStatus.CORRUPT);
            var archive = SharedClientCodec.decode(Base64.getDecoder().decode(payload));
            return archive.placement().equals(placement) ? new Result(StoreStatus.OK, archive) : Result.failure(StoreStatus.CORRUPT);
        } catch (IOException | IllegalArgumentException | IllegalStateException invalid) { return Result.failure(StoreStatus.CORRUPT); }
    }
    private static String encode(SharedClientArchive archive) {
        return "{\"schema\":1,\"payload\":\"" + Base64.getEncoder().encodeToString(SharedClientCodec.encode(archive)) + "\"}";
    }
    private static String readText(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new IOException("Client shared state capacity");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
    private static void replace(Path target, String json, LocalPlacementId placement) throws IOException {
        guard(target, false);
        Path temporary = Files.createTempFile(target.getParent(), "shared-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
            }
            if (!parse(readText(temporary), placement).ok()) throw new IOException("Client shared replacement verification failed");
            guard(target, false); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private Path file(LocalPlacementId placement) { return root.resolve("placement-" + Objects.requireNonNull(placement) + ".json"); }
    private static Path backup(Path file) { return file.resolveSibling(file.getFileName() + ".bak"); }
    private static void directories(Path directory) throws IOException {
        guard(directory, true); Path cursor = directory.getRoot();
        for (Path part : directory) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(cursor);
            guard(cursor, true);
        }
    }
    private static void guard(Path path, boolean directory) throws IOException {
        Path normalized = path.toAbsolutePath().normalize(), cursor = normalized.getRoot();
        for (Path part : normalized) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean last = cursor.equals(normalized);
            if (attributes.isSymbolicLink() || attributes.isOther() || !cursor.toRealPath().equals(cursor)
                || ((!last || directory) && !attributes.isDirectory()) || (last && !directory && !attributes.isRegularFile()))
                throw new UnsafePathException();
        }
    }
    private static Result failure(Exception failure) {
        return Result.failure(failure instanceof UnsafePathException ? StoreStatus.INVALID_PATH
            : failure instanceof AtomicMoveNotSupportedException ? StoreStatus.ATOMIC_REPLACE_UNAVAILABLE
            : failure instanceof OverlappingFileLockException ? StoreStatus.BUSY
            : failure instanceof IllegalArgumentException || failure instanceof ArithmeticException ? StoreStatus.CORRUPT : StoreStatus.IO_FAILURE);
    }
    private static final class UnsafePathException extends IOException {
        private static final long serialVersionUID = 1L;
        UnsafePathException() { super("Unsafe client shared store path"); }
    }
}
