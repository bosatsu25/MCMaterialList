package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.shared.protocol.SharedBinary;
import dev.mcmateriallist.core.work.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedStoreTest {
    @TempDir Path root;
    private ProjectArchive initial(SharedProjectStore store) {
        var world = store.openWorld(); assertEquals(StoreStatus.OK, world.status());
        SharedManifest manifest = SharedManifest.initial(SharedAuthorityTest.definition()); Map<TaskId, TaskState> rows = new HashMap<>();
        manifest.taskIds().forEach(id -> rows.put(id, TaskState.empty(id)));
        ProjectSnapshot snapshot = new ProjectSnapshot(ProjectScope.create(world.value().namespace()), "../display only", UUID.randomUUID(), Set.of(), 0, manifest, rows, Set.of());
        return new ProjectArchive(0, snapshot, List.of(), List.of());
    }
    private Path file(ProjectArchive a) { return root.resolve("mcmateriallist-shared").resolve("project-" + a.snapshot().scope().projectId() + ".json"); }
    private Path backup(ProjectArchive a) { return file(a).resolveSibling(file(a).getFileName() + ".bak"); }
    private SharedProjectStore.Result<ProjectArchive> load(SharedProjectStore store, ProjectArchive a) {
        return store.load(a.snapshot().scope().worldNamespace(), a.snapshot().scope().projectId());
    }
    private ProjectArchive next(ProjectArchive a) { return new ProjectArchive(a.generation() + 1, a.snapshot(), a.operations(), a.retired()); }
    @Test void controlledIdentityPathsDeterminismRestartAndCompareAndSwap() throws Exception {
        var store = new SharedProjectStore(root); var a = initial(store);
        assertEquals(StoreStatus.OK, store.save(a, -1).status());
        assertTrue(Files.isRegularFile(file(a))); assertFalse(Files.exists(root.resolve("display only")));
        assertEquals(a, load(new SharedProjectStore(root), a).value());
        assertEquals(a.snapshot().scope().worldNamespace(), new SharedProjectStore(root).openWorld().value().namespace());
        assertArrayEquals(SharedBinary.encodeArchive(a), SharedBinary.encodeArchive(SharedBinary.decodeArchive(SharedBinary.encodeArchive(a))));
        var b = next(a); assertEquals(StoreStatus.OK, store.save(b, 0).status());
        assertEquals(StoreStatus.CONFLICT, new SharedProjectStore(root).save(b, 0).status());
        assertEquals(StoreStatus.CONFLICT, store.save(new ProjectArchive(8, a.snapshot(), List.of(), List.of()), 1).status());
        assertEquals(b, load(store, a).value());
        Path lockPath = file(a).resolveSibling(file(a).getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE); FileLock held = channel.lock()) {
            assertTrue(held.isValid()); assertEquals(StoreStatus.BUSY, store.save(next(b), 1).status());
        }
    }
    @Test void corruptPrimaryRequiresExplicitRecoveryAndPreservesDamagedBytes() throws Exception {
        var store = new SharedProjectStore(root); var a = initial(store); store.save(a, -1); store.save(next(a), 0);
        byte[] damaged = "{broken-private-progress".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(file(a), damaged);
        assertEquals(StoreStatus.RECOVERY_REQUIRED, load(store, a).status());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.save(next(a), 0).status());
        assertArrayEquals(damaged, Files.readAllBytes(file(a)));
        assertEquals(StoreStatus.OK, store.recover(a.snapshot().scope().worldNamespace(), a.snapshot().scope().projectId()).status());
        assertEquals(a, load(store, a).value());
        try (var files = Files.list(root.resolve("mcmateriallist-shared"))) {
            var retained = files.filter(path -> path.getFileName().toString().endsWith(".retained")).toList();
            assertEquals(1, retained.size()); assertArrayEquals(damaged, Files.readAllBytes(retained.getFirst()));
        }
    }
    @Test void missingPrimaryWithBackupNeverCreatesBlankAndFutureSchemaNeverRollsBack() throws Exception {
        var store = new SharedProjectStore(root); var a = initial(store); store.save(a, -1); store.save(next(a), 0);
        Files.delete(file(a)); assertEquals(StoreStatus.RECOVERY_REQUIRED, load(store, a).status());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.save(a, -1).status());
        assertEquals(List.of(a.snapshot().scope().projectId()), store.openWorld().value().projects());
        assertEquals(StoreStatus.OK, store.recover(a.snapshot().scope().worldNamespace(), a.snapshot().scope().projectId()).status());
        String future = Files.readString(file(a)).replace("\"schema\":1", "\"schema\":2"); Files.writeString(file(a), future);
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, load(store, a).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recover(a.snapshot().scope().worldNamespace(), a.snapshot().scope().projectId()).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(next(a), 0).status()); assertEquals(future, Files.readString(file(a)));
    }
    @Test void corruptBackupCannotBeOverwrittenAndBothCorruptRemainNonblank() throws Exception {
        var store = new SharedProjectStore(root); var a = initial(store); store.save(a, -1); store.save(next(a), 0);
        Files.writeString(backup(a), "damaged-backup"); String primary = Files.readString(file(a));
        assertEquals(StoreStatus.CORRUPT, store.save(next(next(a)), 1).status()); assertEquals(primary, Files.readString(file(a)));
        Files.writeString(file(a), "damaged-primary"); assertEquals(StoreStatus.CORRUPT, load(store, a).status());
        assertEquals(StoreStatus.CORRUPT, store.save(a, -1).status()); assertEquals("damaged-primary", Files.readString(file(a)));
        assertEquals("damaged-backup", Files.readString(backup(a)));
    }
    @Test void primaryAtomicReplacementFailureLeavesPreviousCommittedGeneration() throws Exception {
        var store = new SharedProjectStore(root); var a = initial(store); store.save(a, -1);
        var failing = new SharedProjectStore(root, stage -> {
            if (stage == SharedProjectStore.WriteStage.BEFORE_PRIMARY_REPLACE) throw new AtomicMoveNotSupportedException("", "", "injected");
        });
        assertEquals(StoreStatus.ATOMIC_REPLACE_UNAVAILABLE, failing.save(next(a), 0).status()); assertEquals(a, load(store, a).value());
        try (var files = Files.list(root.resolve("mcmateriallist-shared"))) { assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp"))); }
    }
    @Test void wrongWorldScopeAndNonregularFilesAreRejected() throws Exception {
        var store = new SharedProjectStore(root); var a = initial(store); store.save(a, -1);
        assertEquals(StoreStatus.CORRUPT, store.load(UUID.randomUUID(), a.snapshot().scope().projectId()).status());
        Files.delete(file(a)); Files.createDirectory(file(a)); assertEquals(StoreStatus.INVALID_PATH, load(store, a).status());
        assertEquals(StoreStatus.INVALID_PATH, store.save(a, -1).status());
    }
    @Test void missingWorldMetadataWithProjectDataCannotGenerateAnotherNamespace() throws Exception {
        var store = new SharedProjectStore(root); var a = initial(store); store.save(a, -1);
        Files.delete(root.resolve("mcmateriallist-shared/world.json"));
        assertEquals(StoreStatus.CORRUPT, store.openWorld().status());
        assertFalse(Files.exists(root.resolve("mcmateriallist-shared/world.json")));
    }
    @Test void futureWorldAndMalformedDeepJsonAreRejectedWithoutRecursion() throws Exception {
        var store = new SharedProjectStore(root); store.openWorld();
        Files.writeString(root.resolve("mcmateriallist-shared/world.json"), "{\"schema\":2}");
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.openWorld().status());
        Files.writeString(root.resolve("mcmateriallist-shared/world.json"), "[".repeat(10_000));
        assertEquals(StoreStatus.CORRUPT, store.openWorld().status());
    }
    @Test void symlinkedRootIsRejectedWhenPlatformAllowsCreatingLinks() throws Exception {
        Path target = Files.createDirectory(root.resolve("target")), link = root.resolve("link");
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException exception) { Assumptions.abort("Symbolic links unavailable to test account"); }
        assertEquals(StoreStatus.INVALID_PATH, new SharedProjectStore(link).openWorld().status());
    }

    @Test void numericFutureProjectAndWorldEnvelopesCannotDowngradeInEitherFile() throws Exception {
        var store = new SharedProjectStore(root); var a = initial(store);
        assertTrue(store.save(a, -1).ok()); assertTrue(store.save(next(a), 0).ok());
        Path world = root.resolve("mcmateriallist-shared/world.json");
        for (Path primary : List.of(file(a), world)) {
            Path previous = primary.resolveSibling(primary.getFileName() + ".bak");
            byte[] current = Files.readAllBytes(primary);
            for (String version : List.of("2", "99.0", "1e2", "2147483648", "1e999999999999999999999")) {
                for (String future : List.of("{\"schema\":" + version + ",\"payload\":{},\"kind\":\"future\"}",
                    "{\"kind\":\"future\",\"payload\":{},\"schema\":" + version + "}")) {
                    for (boolean futurePrimary : new boolean[]{true, false}) {
                        Files.write(primary, futurePrimary ? future.getBytes(java.nio.charset.StandardCharsets.UTF_8) : "broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        Files.write(previous, futurePrimary ? current : future.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        byte[] before = Files.readAllBytes(primary), backup = Files.readAllBytes(previous);
                        if (primary.equals(world)) {
                            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.openWorld().status(), version);
                            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recoverWorld().status(), version);
                        } else {
                            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, load(store, a).status(), version);
                            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(next(next(a)), 1).status(), version);
                            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recover(a.snapshot().scope().worldNamespace(), a.snapshot().scope().projectId()).status(), version);
                        }
                        assertArrayEquals(before, Files.readAllBytes(primary)); assertArrayEquals(backup, Files.readAllBytes(previous));
                    }
                }
            }
            Files.write(primary, current); Files.write(previous, current);
        }
    }
    @Test void reorderedCurrentProjectAndWorldLoadAndFutureBackupCannotBeOverwritten() throws Exception {
        var store = new SharedProjectStore(root); var a = initial(store); assertTrue(store.save(a, -1).ok());
        for (Path path : List.of(file(a), root.resolve("mcmateriallist-shared/world.json"))) {
            var json = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            String reordered = "{\"payload\":" + json.get("payload") + ",\"kind\":" + json.get("kind") + ",\"schema\":1}";
            Files.writeString(path, reordered);
            assertTrue(path.equals(file(a)) ? load(store, a).ok() : store.openWorld().ok());
            assertEquals(reordered, Files.readString(path));
        }
        String primary = Files.readString(file(a));
        String future = "{\"payload\":{},\"schema\":99.0}"; Files.writeString(backup(a), future);
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(next(a), 0).status());
        assertEquals(primary, Files.readString(file(a))); assertEquals(future, Files.readString(backup(a)));
    }
}
