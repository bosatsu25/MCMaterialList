package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.work.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalDatasetStoreTest {
    @TempDir Path root;
    private final LocalPlacementId placement = LocalPlacementId.create();
    private final DatasetJsonCodec codec = new DatasetJsonCodec();

    @Test void savesIndependentDatasetsAndJapaneseStateDeterministically() throws Exception {
        var store = new LocalDatasetStore(root);
        MaterialWorkDataset materials = materials();
        RegionWorkDataset regions = RegionWorkDataset.create(placement).reconcile(List.of(region("Region 1", 0), region("Region 2", 10), region("Region 3", 20))).dataset();
        RegionTaskId second = regions.definitions().values().stream().filter(d -> d.descriptor().key().equals("Region 2")).findFirst().orElseThrow().id();
        regions = regions.apply(second, new TaskCommand.SetDone(true), UUID.randomUUID(), Instant.EPOCH).dataset();
        assertEquals(StoreStatus.OK, store.save(materials, -1).status());
        assertEquals(StoreStatus.OK, store.save(regions, -1).status());
        var recreated = new LocalDatasetStore(root);
        assertEquals(materials, recreated.load(placement, DatasetKind.MATERIALS).dataset());
        assertEquals(regions, recreated.load(placement, DatasetKind.REGIONS).dataset());
        assertNotEquals(materials.id(), regions.id());
        assertEquals(codec.encode(materials), codec.encode(codec.decode(codec.encode(materials))));
        var independent = MaterialWorkDataset.create(LocalPlacementId.create());
        assertEquals(StoreStatus.MISSING, recreated.load(independent.placementId(), DatasetKind.MATERIALS).status());
    }

    @Test void corruptAndFutureFilesAreNeverReplacedWithBlankState() throws Exception {
        var store = new LocalDatasetStore(root);
        assertEquals(StoreStatus.OK, store.save(materials(), -1).status());
        Path file = file();
        Files.writeString(file, "{broken");
        assertEquals(StoreStatus.CORRUPT, store.load(placement, DatasetKind.MATERIALS).status());
        assertEquals(StoreStatus.CORRUPT, store.save(MaterialWorkDataset.create(placement), -1).status());
        assertEquals("{broken", Files.readString(file));
        Files.writeString(file, "{\"schema\":99}");
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.load(placement, DatasetKind.MATERIALS).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(materials(), -1).status());
        assertEquals("{\"schema\":99}", Files.readString(file));
    }

    @Test void staleWritesFailAndRecoveryIsExplicit() throws Exception {
        var store = new LocalDatasetStore(root);
        MaterialWorkDataset first = materials();
        assertEquals(StoreStatus.OK, store.save(first, -1).status());
        TaskState state = first.states().values().iterator().next();
        MaterialWorkDataset next = first.apply((MaterialTaskId) state.taskId(), new TaskCommand.SetNote("次の世代"), UUID.randomUUID(), Instant.EPOCH).dataset();
        assertEquals(StoreStatus.OK, store.save(next, first.generation()).status());
        assertEquals(StoreStatus.CONFLICT, store.save(first, first.generation()).status());
        assertEquals(next, store.load(placement, DatasetKind.MATERIALS).dataset());
        Files.writeString(file(), "damaged");
        var load = store.load(placement, DatasetKind.MATERIALS);
        assertEquals(StoreStatus.RECOVERY_REQUIRED, load.status()); assertEquals(first, load.dataset());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.save(next, first.generation()).status());
        assertEquals(StoreStatus.OK, store.recover(placement, DatasetKind.MATERIALS).status());
        assertEquals(first, new LocalDatasetStore(root).load(placement, DatasetKind.MATERIALS).dataset());
    }

    @Test void interruptedReplacementLeavesTheValidPrimaryAndBackup() throws Exception {
        var store = new LocalDatasetStore(root);
        MaterialWorkDataset first = materials(); assertEquals(StoreStatus.OK, store.save(first, -1).status());
        var failing = new LocalDatasetStore(root, stage -> { if (stage == LocalDatasetStore.WriteStage.BEFORE_PRIMARY_REPLACE) throw new java.io.IOException("Injected write failure"); });
        var next = first.apply(first.definitions().keySet().iterator().next(), new TaskCommand.SetNote("after"), UUID.randomUUID(), Instant.EPOCH).dataset();
        assertEquals(StoreStatus.IO_FAILURE, failing.save(next, first.generation()).status());
        assertEquals(first, store.load(placement, DatasetKind.MATERIALS).dataset());
        assertTrue(Files.exists(file().resolveSibling("materials.json.bak")));
        assertEquals(StoreStatus.OK, store.save(next, first.generation()).status());
    }

    @Test void codecRejectsInvalidFieldsDuplicatesAndMismatchedIdentity() {
        String valid = codec.encode(materials());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(valid.replaceFirst("\"generation\":", "\"generation\":-1,\"duplicate\":")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"schema\":1,\"schema\":1}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(valid.replace("minecraft:stone", "STONE")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(valid.replace("\"done\":true", "\"done\":false")));
    }

    @Test void fileAsDirectoryAndSymlinkRootAreRejected() throws Exception {
        Path regular = root.resolve("not-a-directory"); Files.writeString(regular, "untouched");
        assertEquals(StoreStatus.INVALID_PATH, new LocalDatasetStore(regular).save(materials(), -1).status());
        assertEquals("untouched", Files.readString(regular));
        Path outside = Files.createDirectory(root.resolve("outside")); Path link = root.resolve("link");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable in this test environment");
        }
        assertEquals(StoreStatus.INVALID_PATH, new LocalDatasetStore(link).save(materials(), -1).status());
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }

    @Test void missingPrimaryWithBackupRequiresRecoveryAndFutureBackupBlocksWrites() throws Exception {
        var store = new LocalDatasetStore(root); var first = materials();
        assertEquals(StoreStatus.OK, store.save(first, -1).status());
        var next = first.apply(first.definitions().keySet().iterator().next(), new TaskCommand.SetNote("next"), UUID.randomUUID(), Instant.EPOCH).dataset();
        assertEquals(StoreStatus.OK, store.save(next, first.generation()).status());
        Files.delete(file());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.load(placement, DatasetKind.MATERIALS).status());
        assertEquals(StoreStatus.OK, store.recover(placement, DatasetKind.MATERIALS).status());
        Files.writeString(file().resolveSibling("materials.json.bak"), "{\"schema\":99}");
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(next, first.generation()).status());
        assertEquals(first, store.load(placement, DatasetKind.MATERIALS).dataset());
    }

    @Test void malformedUtf8ForeignPlacementAndHugeNumbersAreRejected() throws Exception {
        var store = new LocalDatasetStore(root); var data = materials(); assertEquals(StoreStatus.OK, store.save(data, -1).status());
        Files.write(file(), new byte[]{(byte) 0xc3, 0x28});
        assertEquals(StoreStatus.CORRUPT, store.load(placement, DatasetKind.MATERIALS).status());
        Files.writeString(file(), codec.encode(data).replace(placement.toString(), LocalPlacementId.create().toString()));
        assertEquals(StoreStatus.CORRUPT, store.load(placement, DatasetKind.MATERIALS).status());
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"schema\":1e999999999}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(codec.encode(data).replace("\"identityRule\":1", "\"identityRule\":2")));
    }

    private Path file() { return root.resolve("placements").resolve(placement.toString()).resolve("materials.json"); }
    private MaterialWorkDataset materials() {
        MaterialWorkDataset dataset = MaterialWorkDataset.create(placement).reconcile(List.of(new MaterialDefinition(new MaterialTaskId("minecraft:stone"), 1200, 1200, 0))).dataset();
        MaterialTaskId key = dataset.definitions().keySet().iterator().next();
        dataset = dataset.apply(key, new TaskCommand.SetDone(true), UUID.randomUUID(), Instant.parse("2026-10-04T00:00:00Z")).dataset();
        return dataset.apply(key, new TaskCommand.SetNote("日本語の完了メモ"), UUID.randomUUID(), Instant.EPOCH).dataset();
    }
    private TaskState done(TaskState state) { return ((TransitionResult.Changed) TaskTransitions.apply(state, new TaskCommand.SetDone(true), UUID.randomUUID(), Instant.parse("2026-10-04T00:00:00Z"))).state(); }
    private TaskState note(TaskState state, String note) { return ((TransitionResult.Changed) TaskTransitions.apply(state, new TaskCommand.SetNote(note), UUID.randomUUID(), Instant.EPOCH)).state(); }
    private RegionDescriptor region(String name, int x) { return new RegionDescriptor(name, new Vector3(x, 0, 0), new Vector3(2, 2, 2)); }
}
