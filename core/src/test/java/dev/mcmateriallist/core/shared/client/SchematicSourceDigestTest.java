package dev.mcmateriallist.core.shared.client;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SchematicSourceDigestTest {
    @TempDir Path root;
    @Test void hashesActualWholeContentReadOnlyAndNeverTreatsNameAsIdentity() throws Exception {
        Path owned = Files.createDirectory(root.resolve("schematics")); Path file = owned.resolve("display.litematic");
        byte[] bytes = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(file, bytes);
        var read = SchematicSourceDigest.read(owned, file);
        assertEquals(SchematicSourceDigest.Status.OK, read.status());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", read.digest());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        Path renamed = Files.move(file, owned.resolve("renamed.litematic"));
        assertEquals(read, SchematicSourceDigest.read(owned, renamed));
        Files.writeString(renamed, "abcd");
        assertNotEquals(read.digest(), SchematicSourceDigest.read(owned, renamed).digest());
    }
    @Test void rejectsOutsideRootMissingTemporaryAndDirectorySourcesWithoutReadingThem() throws Exception {
        Path owned = Files.createDirectory(root.resolve("owned")); Path outside = root.resolve("outside.litematic");
        Files.writeString(outside, "private unrelated data");
        assertEquals(SchematicSourceDigest.Status.UNSAFE_SOURCE, SchematicSourceDigest.read(owned, outside).status());
        assertEquals(SchematicSourceDigest.Status.UNSAFE_SOURCE, SchematicSourceDigest.read(owned, owned.resolve("../outside.litematic")).status());
        assertEquals(SchematicSourceDigest.Status.UNSAVED, SchematicSourceDigest.read(owned, null).status());
        assertEquals(SchematicSourceDigest.Status.UNSAVED, SchematicSourceDigest.read(owned, owned.resolve("missing.litematic")).status());
        assertEquals(SchematicSourceDigest.Status.UNSAFE_SOURCE, SchematicSourceDigest.read(owned, owned).status());
        assertEquals("private unrelated data", Files.readString(outside));
    }
    @Test void digestCannotAdoptAReplacementOntoTheOriginalLoadedFileVersion() throws Exception {
        Path file = root.resolve("source.litematic"); Files.writeString(file, "source A");
        var loaded = SchematicSourceDigest.stamp(file); assertNotNull(loaded);
        String loadedDigest;
        var digests = new LoadedSourceDigests();
        try {
            var actual = digests.observe(Files.newInputStream(file));
            assertEquals('s', actual.read()); actual.close(); // Native parser may leave an unread tail.
            loadedDigest = actual.digest().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { digests.close(); assertTrue(digests.awaitClosed(5, java.util.concurrent.TimeUnit.SECONDS)); }
        assertEquals(SchematicSourceDigest.Status.OK, SchematicSourceDigest.read(root, file, loaded).status());
        Files.writeString(file, "source B"); Files.setLastModifiedTime(file, loaded.modified());
        assertTrue(loaded.current(), "Same-file same-size overwrite must retain the complete metadata stamp");
        assertEquals(SchematicSourceDigest.Status.CHANGED_DURING_READ, SchematicSourceDigest.read(root, file, loaded, loadedDigest).status());
        Path replacement = root.resolve("replacement.litematic"); Files.writeString(replacement, "source B");
        Files.setLastModifiedTime(replacement, loaded.modified()); // Same size/time still needs the same actual file.
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        // Windows may tunnel all metadata including creation time; consumed-byte identity still rejects B.
        assertEquals(SchematicSourceDigest.Status.CHANGED_DURING_READ, SchematicSourceDigest.read(root, file, loaded, loadedDigest).status());
        assertEquals("source B", Files.readString(file));
        var reloaded = SchematicSourceDigest.stamp(file);
        assertEquals(SchematicSourceDigest.Status.OK, SchematicSourceDigest.read(root, file, reloaded).status());
        assertEquals(SchematicSourceDigest.Status.CHANGED_DURING_READ, SchematicSourceDigest.read(root, file, null).status());
    }
}
