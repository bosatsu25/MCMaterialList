package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.persistence.StoreStatus;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocationProfileStoreTest {
    @TempDir Path root;
    private final WorldIdentity world = new WorldIdentity(UUID.randomUUID());
    private final LocationProfileCodec codec = new LocationProfileCodec();

    @Test void hostileDisplayNamesNeverInfluencePaths() throws Exception {
        var store = new LocationProfileStore(root); Set<String> ids = new HashSet<>();
        for (String name : List.of("../../evil", "..\\x", "C:\\x", "CON", "/abs", "profiles/../../current.json", "NUL.json", "a\\..\\..\\b")) {
            var profile = LocationProfile.create(world, "minecraft:overworld", name);
            assertEquals(StoreStatus.OK, store.save(profile, -1).status(), name); ids.add(profile.id().toString());
            assertEquals(profile, new LocationProfileStore(root).load(world, profile.id()).value());
        }
        Path worldDir = root.resolve("location-files").resolve("worlds").resolve(world.toString()), profiles = worldDir.resolve("profiles");
        Set<Path> allowedDirs = Set.of(root, root.resolve("location-files"), root.resolve("location-files").resolve("worlds"), worldDir, profiles);
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.toList()) {
                if (Files.isDirectory(path)) { assertTrue(allowedDirs.contains(path), path.toString()); continue; }
                assertEquals(profiles, path.getParent(), path.toString());
                String name = path.getFileName().toString();
                assertTrue(name.endsWith(".json") || name.endsWith(".json.lock"), name);
                assertTrue(ids.contains(name.substring(0, 36)), name);
            }
        }
        try (Stream<Path> files = Files.list(profiles)) { assertEquals(ids.size(), files.filter(p -> p.toString().endsWith(".json")).count()); }
        var listed = store.list(world);
        assertTrue(listed.ok()); assertEquals(ids.size(), listed.value().size());
        assertTrue(listed.value().stream().allMatch(entry -> entry.status() == StoreStatus.OK && ids.contains(entry.id().toString())));
    }

    @Test void contentMustMatchItsWorldAndProfilePath() throws Exception {
        var store = new LocationProfileStore(root); var profile = saved(store);
        var other = new WorldIdentity(UUID.randomUUID());
        Path foreign = file(other, profile.id()); Files.createDirectories(foreign.getParent()); Files.copy(file(world, profile.id()), foreign);
        assertEquals(StoreStatus.CORRUPT, store.load(other, profile.id()).status());
        assertEquals(StoreStatus.CORRUPT, store.list(other).value().get(0).status());
        var renamed = LocationProfileId.create(); Files.copy(file(world, profile.id()), file(world, renamed));
        assertEquals(StoreStatus.CORRUPT, store.load(world, renamed).status());
        Path selection = file(world, profile.id()).getParent().resolveSibling("current.json");
        assertTrue(store.saveSelection(new LocationSelection(world, profile.id(), 0), -1).ok());
        Path copied = foreign.getParent().resolveSibling("current.json"); Files.copy(selection, copied);
        assertEquals(StoreStatus.CORRUPT, store.loadSelection(other).status());
        assertEquals(StoreStatus.CORRUPT, store.saveSelection(new LocationSelection(other, null, 1), 0).status());
        assertArrayEquals(Files.readAllBytes(selection), Files.readAllBytes(copied));
    }

    @Test void symlinkedDirectoriesAreRejected() throws Exception {
        var store = new LocationProfileStore(root); var profile = saved(store);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path otherWorld = root.resolve("location-files").resolve("worlds").resolve(UUID.randomUUID().toString());
        Files.createDirectories(otherWorld);
        try { Files.createSymbolicLink(otherWorld.resolve("profiles"), outside); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) { Assumptions.assumeTrue(false, "Symbolic links unavailable"); }
        var linkedWorld = new WorldIdentity(UUID.fromString(otherWorld.getFileName().toString()));
        var target = LocationProfile.create(linkedWorld, "minecraft:overworld", "x");
        assertEquals(StoreStatus.INVALID_PATH, store.save(target, -1).status());
        assertEquals(StoreStatus.INVALID_PATH, store.load(linkedWorld, target.id()).status());
        assertEquals(StoreStatus.INVALID_PATH, store.list(linkedWorld).status());
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        Path link = root.resolve("link"); Files.createSymbolicLink(link, outside);
        assertEquals(StoreStatus.INVALID_PATH, new LocationProfileStore(link).save(LocationProfile.create(world, "minecraft:overworld", "x"), -1).status());
        assertEquals(StoreStatus.INVALID_PATH, new LocationProfileStore(link).saveSelection(new LocationSelection(world, null, 0), -1).status());
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        Path profiles = file(world, profile.id()).getParent();
        Path linkedEntry = profiles.resolve(UUID.randomUUID() + ".json"); Files.createSymbolicLink(linkedEntry, file(world, profile.id()));
        var entries = store.list(world).value();
        assertEquals(StoreStatus.INVALID_PATH, entries.stream().filter(e -> !e.id().equals(profile.id())).findFirst().orElseThrow().status());
    }

    @Test void listingIgnoresStrayNamesReportsUnreadableEntriesAndIsBounded() throws Exception {
        var store = new LocationProfileStore(root); var good = saved(store);
        Path profiles = file(world, good.id()).getParent();
        Files.copy(file(world, good.id()), profiles.resolve(good.id().toString().toUpperCase(java.util.Locale.ROOT) + ".json"));
        Files.writeString(profiles.resolve("README.txt"), "x"); Files.writeString(profiles.resolve(UUID.randomUUID() + ".JSON"), "x");
        var orphan = LocationProfileId.create(); Files.writeString(profiles.resolve(orphan + ".json.bak"), "x"); Files.writeString(profiles.resolve("not-a-uuid.json"), "x");
        Files.writeString(profiles.resolve("{" + UUID.randomUUID() + "}.json"), "x");
        var broken = LocationProfileId.create(); Files.writeString(file(world, broken), "{broken");
        var directory = LocationProfileId.create(); Files.createDirectory(file(world, directory));
        var listed = store.list(world); assertTrue(listed.ok());
        // A backup without its primary is listed with the backup's own status, never hidden.
        assertEquals(4, listed.value().size()); assertEquals(StoreStatus.CORRUPT, status(listed.value(), orphan));
        assertEquals(StoreStatus.OK, status(listed.value(), good.id())); assertEquals(good, listed.value().stream().filter(e -> e.id().equals(good.id())).findFirst().orElseThrow().profile());
        assertEquals(StoreStatus.CORRUPT, status(listed.value(), broken)); assertEquals(StoreStatus.INVALID_PATH, status(listed.value(), directory));
        assertEquals(List.of(), store.list(new WorldIdentity(UUID.randomUUID())).value());
        for (int i = 0; i < LocationLimits.MAX_PROFILES; i++) Files.writeString(profiles.resolve(UUID.randomUUID() + ".json"), "");
        assertEquals(StoreStatus.TOO_LARGE, store.list(world).status());
        Files.write(file(world, good.id()), new byte[LocationProfileStore.MAX_FILE_BYTES + 1]);
        assertEquals(StoreStatus.TOO_LARGE, store.load(world, good.id()).status());
    }

    @Test void recoveryIsExplicitAndPreservesDamagedBytes() throws Exception {
        var store = new LocationProfileStore(root); var first = saved(store);
        var second = first.rename("second"); assertTrue(store.save(second, first.generation()).ok());
        assertEquals(StoreStatus.CONFLICT, store.save(second.rename("stale"), first.generation()).status());
        assertEquals(StoreStatus.CONFLICT, store.save(second.rename("x").rename("skip"), second.generation()).status());
        Path primary = file(world, first.id());
        Files.writeString(primary, "damaged");
        var load = store.load(world, first.id());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, load.status()); assertEquals(first, load.value());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.save(first.rename("next"), first.generation()).status());
        assertEquals("damaged", Files.readString(primary));
        assertEquals(StoreStatus.OK, store.recover(world, first.id()).status());
        assertEquals(first, new LocationProfileStore(root).load(world, first.id()).value());
        try (var files = Files.list(primary.getParent())) {
            assertEquals(List.of("damaged"), files.filter(p -> p.getFileName().toString().startsWith("profile-damaged-")).map(LocationProfileStoreTest::text).toList());
        }
        assertEquals(1, store.list(world).value().size());
        assertEquals(StoreStatus.OK, store.save(first.rename("after"), first.generation()).status());
        Files.delete(primary);
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.load(world, first.id()).status());
        // Backup-only profiles stay listed so the UI can offer explicit recovery.
        var listed = store.list(world).value();
        assertEquals(1, listed.size()); assertEquals(first.id(), listed.get(0).id()); assertEquals(StoreStatus.RECOVERY_REQUIRED, listed.get(0).status());
        assertEquals(StoreStatus.CONFLICT, store.save(first, -1).status());
        assertEquals(StoreStatus.OK, store.recover(world, first.id()).status());
        assertEquals(StoreStatus.CONFLICT, store.save(LocationProfile.create(world, "minecraft:overworld", "x"), 0).status());
        assertEquals(StoreStatus.CONFLICT, store.save(new LocationProfile(first.id(), 0, "x", world, "minecraft:overworld", null, List.of()), -1).status());
        var corruptWithoutBackup = LocationProfileId.create(); Files.writeString(file(world, corruptWithoutBackup), "{bad");
        assertEquals(StoreStatus.CORRUPT, store.recover(world, corruptWithoutBackup).status());
        assertEquals("{bad", Files.readString(file(world, corruptWithoutBackup)));
    }

    @Test void futurePrimaryOrBackupBlocksWritesWithBytesUnchanged() throws Exception {
        var store = new LocationProfileStore(root); var first = saved(store); var second = first.rename("second");
        assertTrue(store.save(second, first.generation()).ok());
        Path primary = file(world, first.id()), backup = primary.resolveSibling(primary.getFileName() + ".bak");
        String original = Files.readString(primary), future = codec.encode(second).replace("\"schema\":1", "\"schema\":2");
        Files.writeString(primary, future);
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.load(world, first.id()).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(second.rename("third"), second.generation()).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recover(world, first.id()).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.list(world).value().get(0).status());
        assertEquals(future, Files.readString(primary));
        Files.writeString(primary, original); String backupFuture = "{\"schema\":2}"; Files.writeString(backup, backupFuture);
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(second.rename("third"), second.generation()).status());
        assertEquals(original, Files.readString(primary)); assertEquals(backupFuture, Files.readString(backup));
        Files.writeString(primary, "{broken");
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.load(world, first.id()).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recover(world, first.id()).status());
        assertEquals("{broken", Files.readString(primary)); assertEquals(backupFuture, Files.readString(backup));
    }

    @Test void injectedFailureBeforeReplaceKeepsPriorPrimary() throws Exception {
        var store = new LocationProfileStore(root); var first = saved(store); var next = first.rename("after");
        Path primary = file(world, first.id()); byte[] before = Files.readAllBytes(primary);
        for (var stage : LocationProfileStore.WriteStage.values()) {
            var failing = new LocationProfileStore(root, s -> { if (s == stage) throw new IOException("Injected write failure"); });
            assertEquals(StoreStatus.IO_FAILURE, failing.save(next, first.generation()).status());
            assertArrayEquals(before, Files.readAllBytes(primary)); assertEquals(first, store.load(world, first.id()).value());
        }
        try (var files = Files.list(primary.getParent())) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp"))); }
        assertEquals(StoreStatus.OK, store.save(next, first.generation()).status());
        assertArrayEquals(before, Files.readAllBytes(primary.resolveSibling(primary.getFileName() + ".bak")));
    }

    @Test void heldLockReportsBusy() throws Exception {
        var store = new LocationProfileStore(root); var first = saved(store);
        Path lock = file(world, first.id()).resolveSibling(first.id() + ".json.lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE); var held = channel.lock()) {
            assertTrue(held.isValid());
            assertEquals(StoreStatus.BUSY, store.save(first.rename("x"), first.generation()).status());
            assertEquals(StoreStatus.BUSY, store.recover(world, first.id()).status());
            assertEquals(StoreStatus.OK, store.load(world, first.id()).status());
        }
        assertTrue(store.saveSelection(new LocationSelection(world, null, 0), -1).ok());
        Path selectionLock = lock.getParent().resolveSibling("current.json.lock");
        try (var channel = FileChannel.open(selectionLock, StandardOpenOption.WRITE); var held = channel.lock()) {
            assertTrue(held.isValid());
            assertEquals(StoreStatus.BUSY, store.saveSelection(new LocationSelection(world, first.id(), 1), 0).status());
        }
    }

    @Test void selectionIsPerWorldAndCorruptOrFutureSelectionIsNeverReplaced() throws Exception {
        var store = new LocationProfileStore(root); var profile = saved(store);
        var selection = new LocationSelection(world, profile.id(), 0);
        assertEquals(StoreStatus.MISSING, store.loadSelection(world).status());
        assertTrue(store.saveSelection(selection, -1).ok());
        assertEquals(StoreStatus.CONFLICT, store.saveSelection(selection, -1).status());
        assertEquals(StoreStatus.CONFLICT, store.saveSelection(selection.select(null).select(profile.id()), 0).status());
        assertEquals(StoreStatus.MISSING, store.loadSelection(new WorldIdentity(UUID.randomUUID())).status());
        Path current = file(world, profile.id()).getParent().resolveSibling("current.json");
        Files.writeString(current, "{bad");
        assertEquals(StoreStatus.CORRUPT, store.loadSelection(world).status());
        assertEquals(StoreStatus.CORRUPT, store.saveSelection(selection.select(null), 0).status());
        assertEquals(StoreStatus.CORRUPT, store.saveSelection(new LocationSelection(world, null, 0), -1).status());
        assertEquals("{bad", Files.readString(current));
        Files.writeString(current, "{\"schema\":3,\"kind\":\"LOCATION_SELECTION\"}");
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.loadSelection(world).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.saveSelection(selection.select(null), 0).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recoverSelection(world).status());
        Files.writeString(current, codec.encode(selection));
        assertTrue(store.saveSelection(selection.select(null), 0).ok());
        Files.writeString(current, "{damaged");
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.loadSelection(world).status());
        assertEquals(StoreStatus.OK, store.recoverSelection(world).status());
        assertEquals(selection, store.loadSelection(world).value());
        try (var files = Files.list(current.getParent())) {
            assertEquals(List.of("{damaged"), files.filter(p -> p.getFileName().toString().startsWith("current-damaged-")).map(LocationProfileStoreTest::text).toList());
        }
    }

    private LocationProfile saved(LocationProfileStore store) {
        var profile = LocationProfile.create(world, "minecraft:overworld", "taichi_ms").withContainer(new ContainerPosition(10, 64, -3, "minecraft:chest"));
        profile = new LocationProfile(profile.id(), 0, profile.displayName(), world, profile.dimension(), null, profile.positions());
        assertEquals(StoreStatus.OK, store.save(profile, -1).status()); return profile;
    }
    private Path file(WorldIdentity target, LocationProfileId id) {
        return root.resolve("location-files").resolve("worlds").resolve(target.toString()).resolve("profiles").resolve(id + ".json");
    }
    private static StoreStatus status(List<ProfileEntry> entries, LocationProfileId id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().status();
    }
    private static String text(Path path) {
        try { return Files.readString(path); } catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }
}
