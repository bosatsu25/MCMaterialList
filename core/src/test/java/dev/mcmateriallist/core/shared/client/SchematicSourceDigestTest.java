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
}
