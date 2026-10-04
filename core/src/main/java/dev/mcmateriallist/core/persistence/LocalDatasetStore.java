package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.work.DatasetKind;
import dev.mcmateriallist.core.work.WorkDataset;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.DateTimeException;

/** Synchronous bounded I/O boundary. Call from an owned serial background queue. */
public final class LocalDatasetStore {
    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private final Path root;
    private final DatasetJsonCodec codec = new DatasetJsonCodec();
    private final WriteHook hook;

    enum WriteStage { BEFORE_BACKUP_REPLACE, BEFORE_PRIMARY_REPLACE }
    @FunctionalInterface interface WriteHook { void before(WriteStage stage) throws IOException; }
    public LocalDatasetStore(Path root) { this(root, stage -> {}); }
    LocalDatasetStore(Path root, WriteHook hook) { this.root = root.toAbsolutePath().normalize(); this.hook = hook; }

    public StoreResult load(LocalPlacementId placement, DatasetKind kind) {
        Path file = file(placement, kind); Path backup = backup(file);
        try {
            StoreResult primary = read(file, placement, kind);
            if (primary.ok() || primary.status() == StoreStatus.UNSUPPORTED_SCHEMA || primary.status() == StoreStatus.TOO_LARGE) return primary;
            StoreResult previous = read(backup, placement, kind);
            if (previous.status() == StoreStatus.UNSUPPORTED_SCHEMA) return previous;
            if (previous.ok()) return new StoreResult(StoreStatus.RECOVERY_REQUIRED, previous.dataset());
            return primary.status() == StoreStatus.MISSING && previous.status() != StoreStatus.MISSING ? previous : primary;
        } catch (UnsafePathException exception) { return StoreResult.failure(StoreStatus.INVALID_PATH); }
        catch (IOException exception) { return StoreResult.failure(StoreStatus.IO_FAILURE); }
    }

    /** expectedGeneration=-1 permits creation ONLY when neither snapshot nor backup exists. */
    public StoreResult save(WorkDataset dataset, long expectedGeneration) {
        Path file = file(dataset.placementId(), dataset.kind());
        try {
            ensureDirectories(file.getParent()); Path lock = file.resolveSibling(file.getFileName() + ".lock"); guard(lock, false);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) return StoreResult.failure(StoreStatus.BUSY);
                StoreResult current = load(dataset.placementId(), dataset.kind());
                if (!current.ok() && current.status() != StoreStatus.MISSING) return current;
                if (current.ok()) {
                    WorkDataset before = current.dataset();
                    if (before.generation() != expectedGeneration || !before.id().equals(dataset.id()) || dataset.generation() <= expectedGeneration) return StoreResult.failure(StoreStatus.CONFLICT);
                    StoreResult oldBackup = read(backup(file), dataset.placementId(), dataset.kind());
                    if (oldBackup.status() != StoreStatus.MISSING && !oldBackup.ok()) return oldBackup;
                } else if (expectedGeneration != -1) return StoreResult.failure(StoreStatus.CONFLICT);
                String json = codec.encode(dataset);
                if (json.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) return StoreResult.failure(StoreStatus.TOO_LARGE);
                WorkDataset validated = codec.decode(json);
                if (!dataset.equals(validated)) return StoreResult.failure(StoreStatus.CORRUPT);
                if (current.ok()) replace(backup(file), readText(file), WriteStage.BEFORE_BACKUP_REPLACE);
                replace(file, json, WriteStage.BEFORE_PRIMARY_REPLACE);
                return new StoreResult(StoreStatus.OK, dataset);
            }
        } catch (UnsafePathException exception) { return StoreResult.failure(StoreStatus.INVALID_PATH); }
        catch (AtomicMoveNotSupportedException exception) { return StoreResult.failure(StoreStatus.ATOMIC_REPLACE_UNAVAILABLE); }
        catch (OverlappingFileLockException exception) { return StoreResult.failure(StoreStatus.BUSY); }
        catch (IllegalArgumentException | ArithmeticException | DateTimeException exception) { return StoreResult.failure(StoreStatus.CORRUPT); }
        catch (IOException exception) { return StoreResult.failure(StoreStatus.IO_FAILURE); }
    }

    /** Explicit recovery preserves damaged bytes and restores only a validated known-schema backup. */
    public StoreResult recover(LocalPlacementId placement, DatasetKind kind) {
        Path file = file(placement, kind);
        try {
            ensureDirectories(file.getParent()); Path lock = file.resolveSibling(file.getFileName() + ".lock"); guard(lock, false);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.tryLock()) {
                if (held == null) return StoreResult.failure(StoreStatus.BUSY);
                StoreResult candidate = load(placement, kind);
                if (candidate.status() != StoreStatus.RECOVERY_REQUIRED) return candidate;
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    guard(file, false);
                    Path damaged = Files.createTempFile(file.getParent(), kind.name().toLowerCase(java.util.Locale.ROOT) + "-damaged-", ".json");
                    Files.copy(file, damaged, StandardCopyOption.REPLACE_EXISTING);
                }
                replace(file, codec.encode(candidate.dataset()), WriteStage.BEFORE_PRIMARY_REPLACE);
                return new StoreResult(StoreStatus.OK, candidate.dataset());
            }
        } catch (UnsafePathException exception) { return StoreResult.failure(StoreStatus.INVALID_PATH); }
        catch (AtomicMoveNotSupportedException exception) { return StoreResult.failure(StoreStatus.ATOMIC_REPLACE_UNAVAILABLE); }
        catch (OverlappingFileLockException exception) { return StoreResult.failure(StoreStatus.BUSY); }
        catch (IOException exception) { return StoreResult.failure(StoreStatus.IO_FAILURE); }
    }

    private StoreResult read(Path file, LocalPlacementId placement, DatasetKind kind) throws IOException {
        guard(file, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return StoreResult.failure(StoreStatus.MISSING);
        if (Files.size(file) > MAX_FILE_BYTES) return StoreResult.failure(StoreStatus.TOO_LARGE);
        try {
            WorkDataset dataset = codec.decode(readText(file));
            if (!dataset.placementId().equals(placement) || dataset.kind() != kind) return StoreResult.failure(StoreStatus.CORRUPT);
            return new StoreResult(StoreStatus.OK, dataset);
        } catch (UnsupportedSchemaException exception) { return StoreResult.failure(StoreStatus.UNSUPPORTED_SCHEMA); }
        catch (IllegalArgumentException | ArithmeticException | DateTimeException exception) { return StoreResult.failure(StoreStatus.CORRUPT); }
        catch (java.nio.charset.CharacterCodingException exception) { return StoreResult.failure(StoreStatus.CORRUPT); }
    }
    private String readText(Path file) throws IOException {
        // readNBytes also bounds a file which grows after the size check.
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Work snapshot size limit");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
    private void replace(Path target, String json, WriteStage stage) throws IOException {
        guard(target, false);
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            codec.decode(readText(temporary)); hook.before(stage); guard(target, false);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private Path file(LocalPlacementId placement, DatasetKind kind) {
        return root.resolve("placements").resolve(placement.toString()).resolve(kind == DatasetKind.MATERIALS ? "materials.json" : "regions.json");
    }
    private static Path backup(Path file) { return file.resolveSibling(file.getFileName() + ".bak"); }
    private void ensureDirectories(Path directory) throws IOException {
        guard(directory, true); Path cursor = directory.getRoot();
        for (Path part : directory) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(cursor);
            guard(cursor, true);
        }
    }
    private static void guard(Path path, boolean directory) throws IOException {
        Path normalized = path.toAbsolutePath().normalize(); Path cursor = normalized.getRoot();
        for (Path part : normalized) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attrs = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean last = cursor.equals(normalized);
            if (attrs.isSymbolicLink() || attrs.isOther() || !cursor.toRealPath().equals(cursor)
                || ((!last || directory) && !attrs.isDirectory()) || (last && !directory && !attrs.isRegularFile())) throw new UnsafePathException();
        }
    }
    private static final class UnsafePathException extends IOException {
        private static final long serialVersionUID = 1L;
        UnsafePathException() { super("Unsafe work snapshot path"); }
    }
}
