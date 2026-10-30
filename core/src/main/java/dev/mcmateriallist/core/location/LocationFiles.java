package dev.mcmateriallist.core.location;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Same path guard and replacement contract as the Phase 1 store, kept separate so that store is untouched. */
final class LocationFiles {
    private LocationFiles() {}

    @FunctionalInterface interface Validator { void check(byte[] bytes); }
    @FunctionalInterface interface Step { void run() throws IOException; }

    static byte[] read(Path file, int limit) throws IOException {
        // readNBytes also bounds a file which grows after the size check.
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Location file size limit");
            return bytes;
        }
    }
    static void replace(Path target, byte[] bytes, int limit, Validator validator, Step beforeMove) throws IOException {
        guard(target, false);
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            validator.check(read(temporary, limit)); beforeMove.run(); guard(target, false);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void ensureDirectories(Path directory) throws IOException {
        guard(directory, true); Path cursor = directory.getRoot();
        for (Path part : directory) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(cursor);
            guard(cursor, true);
        }
    }
    static void guard(Path path, boolean directory) throws IOException {
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
    static final class UnsafePathException extends IOException {
        private static final long serialVersionUID = 1L;
        UnsafePathException() { super("Unsafe location file path"); }
    }
}
