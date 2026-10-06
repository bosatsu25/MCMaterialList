package dev.mcmateriallist.core.shared.client;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Read-only streaming digest of a persistent native source within its configured source root. */
public final class SchematicSourceDigest {
    public static final long MAX_SOURCE_BYTES = 512L * 1024 * 1024;
    public enum Status { OK, UNSAVED, UNSAFE_SOURCE, TOO_LARGE, CHANGED_DURING_READ, IO_FAILURE }
    public record Result(Status status, String digest) {}
    private SchematicSourceDigest() {}
    public static Result read(Path configuredSourceRoot, Path source) {
        if (source == null) return new Result(Status.UNSAVED, null);
        Path root = Objects.requireNonNull(configuredSourceRoot).toAbsolutePath().normalize();
        Path file = source.toAbsolutePath().normalize();
        if (file.equals(root) || !file.startsWith(root)) return new Result(Status.UNSAFE_SOURCE, null);
        try {
            guard(root, true); guard(file, false);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Result(Status.UNSAVED, null);
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() > MAX_SOURCE_BYTES) return new Result(Status.TOO_LARGE, null);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_SOURCE_BYTES) return new Result(Status.TOO_LARGE, null);
                    digest.update(buffer, 0, count);
                }
            }
            guard(file, false);
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) return new Result(Status.CHANGED_DURING_READ, null);
            return new Result(Status.OK, HexFormat.of().formatHex(digest.digest()));
        } catch (UnsafeSource invalid) { return new Result(Status.UNSAFE_SOURCE, null); }
        catch (IOException failure) { return new Result(Status.IO_FAILURE, null); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static void guard(Path normalized, boolean directory) throws IOException {
        Path cursor = normalized.getRoot();
        for (Path part : normalized) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
            var attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean last = cursor.equals(normalized);
            if (attributes.isSymbolicLink() || attributes.isOther() || !cursor.toRealPath().equals(cursor)
                || ((!last || directory) && !attributes.isDirectory()) || (last && !directory && !attributes.isRegularFile())) throw new UnsafeSource();
        }
    }
    private static final class UnsafeSource extends IOException {
        private static final long serialVersionUID = 1L;
        UnsafeSource() { super("Unsafe schematic source"); }
    }
}
