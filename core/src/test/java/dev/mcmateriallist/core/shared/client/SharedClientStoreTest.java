package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.work.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SharedClientStoreTest {
    @TempDir Path root;
    private final UUID actor = UUID.randomUUID();
    private final LocalPlacementId placement = LocalPlacementId.create();
    private final MaterialTaskId stone = new MaterialTaskId("minecraft:stone");
    private ProjectSnapshot snapshot(String name) {
        var definition = new SharedManifest.Definition("a".repeat(64),
            new SharedManifest.Baseline("minecraft:overworld", new Vector3(0, 64, 0),
                SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE),
            Map.of(stone, 12L), List.of(new RegionDescriptor("../display-only", new Vector3(0, 0, 0), new Vector3(2, 3, 2))));
        var manifest = SharedManifest.initial(definition);
        Map<TaskId, TaskState> rows = new HashMap<>(); manifest.taskIds().forEach(id -> rows.put(id, TaskState.empty(id)));
        return new ProjectSnapshot(ProjectScope.create(UUID.randomUUID()), name, actor, Set.of(), 0, manifest, rows, Set.of());
    }
    private SharedClientArchive archive() {
        var materials = snapshot("../material display"); var regions = snapshot("../region display");
        var materialRef = new SharedClientArchive.Reference(UUID.randomUUID(), actor, materials.scope());
        var regionRef = new SharedClientArchive.Reference(UUID.randomUUID(), actor, regions.scope());
        var command = new SharedCommand(materials.scope(), stone, UUID.randomUUID(), 0, 0, new SharedCommand.SetNote("private original intent"));
        return new SharedClientArchive(0, placement,
            new SharedClientArchive.Binding(DatasetId.create(), materialRef, new SharedClientArchive.Cache(materials, 0)),
            new SharedClientArchive.Binding(DatasetId.create(), regionRef, new SharedClientArchive.Cache(regions, 0)),
            List.of(new SharedClientArchive.Operation(DatasetKind.MATERIALS, materialRef, UUID.randomUUID(), command, null)));
    }
    private Path file() { return root.resolve("shared-client").resolve("placement-" + placement + ".json"); }
    private SharedClientArchive next(SharedClientArchive before) {
        return new SharedClientArchive(before.generation() + 1, placement, before.materials(), before.regions(), before.operations());
    }

    @Test void restartRetainsIndependentProjectListsOriginalLocalIdsAndUnknownOriginalIntent() throws Exception {
        var before = archive(); var store = new SharedClientStore(root);
        assertEquals(StoreStatus.MISSING, store.load(placement).status());
        assertFalse(Files.exists(root.resolve("shared-client")), "Opening must be read-only");
        assertEquals(StoreStatus.OK, store.save(before, -1).status());
        var restored = new SharedClientStore(root).load(placement);
        assertEquals(StoreStatus.OK, restored.status()); assertEquals(before, restored.value());
        assertNotEquals(restored.value().materials().shared().scope(), restored.value().regions().shared().scope());
        assertNull(restored.value().operations().getFirst().confirmation());
        assertTrue(Files.isRegularFile(file()));
        assertFalse(Files.exists(root.resolve("material display")));
    }

    @Test void compareAndSwapAndSwitchingOneBindingCannotAlterOtherOrRemapUnknownJournal() {
        var store = new SharedClientStore(root); var before = archive();
        assertEquals(StoreStatus.OK, store.save(before, -1).status());
        var switched = new SharedClientArchive(1, placement,
            new SharedClientArchive.Binding(before.materials().localDataset(), null, null), before.regions(), before.operations());
        assertEquals(StoreStatus.OK, store.save(switched, 0).status());
        assertEquals(StoreStatus.CONFLICT, store.save(next(before), 0).status());
        var current = new SharedClientStore(root).load(placement).value();
        assertEquals(before.regions(), current.regions()); assertEquals(before.operations(), current.operations());
        assertEquals(before.materials().localDataset(), current.materials().localDataset());
    }

    @Test void futureAndCorruptFilesNeverBecomeBlankOrOverwriteOriginalJournal() throws Exception {
        var store = new SharedClientStore(root); var before = archive();
        assertEquals(StoreStatus.OK, store.save(before, -1).status());
        assertEquals(StoreStatus.OK, store.save(next(before), 0).status());
        String future = Files.readString(file()).replace("\"schema\":1", "\"schema\":99");
        Files.writeString(file(), future);
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.load(placement).status());
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(next(next(before)), 1).status());
        assertEquals(future, Files.readString(file()));
        Files.writeString(file(), "damaged journal");
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.load(placement).status());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.save(before, -1).status());
        assertEquals("damaged journal", Files.readString(file()));
        Files.delete(file());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.load(placement).status());
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.save(before, -1).status());
    }

    @Test void previousGoodBackupPreservesOriginalJournalAndBindingsWithoutRetainingPrivateDerivedCache() throws Exception {
        var store = new SharedClientStore(root); var before = archive();
        assertEquals(StoreStatus.OK, store.save(before, -1).status());
        var cleared = new SharedClientArchive(1, placement,
            new SharedClientArchive.Binding(before.materials().localDataset(), before.materials().shared(), null),
            new SharedClientArchive.Binding(before.regions().localDataset(), before.regions().shared(), null), before.operations());
        assertEquals(StoreStatus.OK, store.save(cleared, 0).status());
        String json = Files.readString(file().resolveSibling(file().getFileName() + ".bak"));
        String payload = com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("payload").getAsString();
        var backup = SharedClientCodec.decode(Base64.getDecoder().decode(payload));
        assertNull(backup.materials().cache()); assertNull(backup.regions().cache());
        assertEquals(before.operations(), backup.operations());
        assertEquals(before.materials().shared(), backup.materials().shared());
        assertEquals(before.regions().shared(), backup.regions().shared());
        assertEquals(0, backup.generation());
    }

    @Test void reorderedCurrentEnvelopeLoadsWithoutChangingEitherFile() throws Exception {
        var store = new SharedClientStore(root); var before = archive();
        assertTrue(store.save(before, -1).ok()); assertTrue(store.save(next(before), 0).ok());
        String payload = com.google.gson.JsonParser.parseString(Files.readString(file())).getAsJsonObject().get("payload").getAsString();
        String reordered = "{\"payload\":\"" + payload + "\",\"schema\":1}";
        Files.writeString(file(), reordered);
        byte[] backup = Files.readAllBytes(file().resolveSibling(file().getFileName() + ".bak"));
        assertEquals(next(before), store.load(placement).value());
        assertEquals(reordered, Files.readString(file()));
        assertArrayEquals(backup, Files.readAllBytes(file().resolveSibling(file().getFileName() + ".bak")));
    }

    @Test void reorderedFutureSchemaBlocksLoadSaveAndRecoveryBeforeInterpretingPayload() throws Exception {
        var store = new SharedClientStore(root); var before = archive();
        assertTrue(store.save(before, -1).ok()); assertTrue(store.save(next(before), 0).ok());
        Path backupFile = file().resolveSibling(file().getFileName() + ".bak");
        byte[] backup = Files.readAllBytes(backupFile);
        // A future payload need not use this version's Base64 encoding or string type.
        for (String payload : List.of("\"future format\"", "{\"schema\":1,\"text\":\"schema: 1\"}")) {
          for (String version : List.of("99", "2147483648")) {
            String future = "{\"payload\":" + payload + ",\"schema\":" + version + "}";
            Files.writeString(file(), future);
            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.load(placement).status());
            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.save(next(next(before)), 1).status());
            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recover(placement).status());
            assertEquals(future, Files.readString(file())); assertArrayEquals(backup, Files.readAllBytes(backupFile));
          }
        }
        try (var files = Files.list(file().getParent())) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".retained")), "Future format must never enter recovery");
        }
    }

    @Test void duplicateUnknownAndMisleadingNestedSchemaCannotBecomeCurrentEnvelope() throws Exception {
        var store = new SharedClientStore(root); var before = archive(); assertTrue(store.save(before, -1).ok());
        String payload = com.google.gson.JsonParser.parseString(Files.readString(file())).getAsJsonObject().get("payload").getAsString();
        for (String invalid : List.of(
            "{\"schema\":1,\"schema\":1,\"payload\":\"" + payload + "\"}",
            "{\"payload\":\"" + payload + "\",\"payload\":\"" + payload + "\",\"schema\":1}",
            "{\"unknown\":1,\"payload\":\"" + payload + "\",\"schema\":1}",
            "{\"payload\":{\"schema\":1}}")) {
            Files.writeString(file(), invalid);
            assertEquals(StoreStatus.CORRUPT, store.load(placement).status());
            assertEquals(invalid, Files.readString(file()));
        }
    }

    @Test void deliberateRecoveryPreservesOriginalUnknownMetadataAndDamageButDoesNotInventCompleteJournal() throws Exception {
        var store = new SharedClientStore(root); var before = archive();
        assertEquals(StoreStatus.OK, store.save(before, -1).status());
        assertEquals(StoreStatus.OK, store.save(next(before), 0).status());
        Files.writeString(file(), "damaged original journal");
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.load(placement).status());
        assertNull(store.load(placement).value(), "Private backup must not be exposed as current");
        var recovered = store.recover(placement); assertEquals(StoreStatus.OK, recovered.status());
        assertEquals(before.operations(), recovered.value().operations());
        assertEquals(before.materials().shared(), recovered.value().materials().shared());
        assertEquals(before.regions().localDataset(), recovered.value().regions().localDataset());
        assertNull(recovered.value().materials().cache()); assertNull(recovered.value().regions().cache());
        assertTrue(recovered.value().recoveredJournalUncertain(), "Backup can predate a sent intent and must remain quarantined");
        assertEquals(recovered.value(), new SharedClientStore(root).load(placement).value());
        try (var files = Files.list(file().getParent())) {
            var retained = files.filter(path -> path.toString().endsWith(".retained")).toList();
            assertEquals(1, retained.size()); assertEquals("damaged original journal", Files.readString(retained.getFirst()));
        }
        String future = Files.readString(file()).replace("\"schema\":1", "\"schema\":99"); Files.writeString(file(), future);
        assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, store.recover(placement).status()); assertEquals(future, Files.readString(file()));
    }
}
