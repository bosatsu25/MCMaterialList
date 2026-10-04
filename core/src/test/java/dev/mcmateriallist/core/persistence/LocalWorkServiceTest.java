package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.work.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalWorkServiceTest {
    @TempDir Path root;
    @Test void regionRefreshPreservesValidMaterialBytesAndRejectsCorruptRegion() throws Exception {
        var placement = LocalPlacementId.create();
        var stone = new MaterialTaskId("minecraft:stone");
        var snapshot = new WorkSnapshot(placement, List.of(new MaterialDefinition(stone, 10, 10, 0)), List.of(new RegionDescriptor("Region 1", new Vector3(0, 0, 0), new Vector3(1, 1, 1))));
        Path directory = root.resolve("placements").resolve(placement.toString());
        try (var service = new LocalWorkService(root)) {
            service.refreshMaterials(snapshot).get();
            byte[] before = java.nio.file.Files.readAllBytes(directory.resolve("materials.json"));
            assertTrue(service.refreshRegions(snapshot).get().storage().ok());
            assertArrayEquals(before, java.nio.file.Files.readAllBytes(directory.resolve("materials.json")));
            var changed = new WorkSnapshot(placement, List.of(), List.of());
            assertTrue(service.refreshRegions(changed).get().storage().ok());
            assertArrayEquals(before, java.nio.file.Files.readAllBytes(directory.resolve("materials.json")));
        }
        var other = LocalPlacementId.create();
        Path broken = root.resolve("placements").resolve(other.toString()); java.nio.file.Files.createDirectories(broken);
        java.nio.file.Files.writeString(broken.resolve("regions.json"), "{bad region without backup");
        try (var service = new LocalWorkService(root)) {
            var result = service.refreshRegions(new WorkSnapshot(other, List.of(), List.of())).get();
            assertEquals(StoreStatus.CORRUPT, result.storage().status()); assertNull(result.reconciliation());
            assertEquals(StoreStatus.CORRUPT, service.acknowledgeRegion(other, RegionTaskId.create()).get().storage().status());
            assertEquals("{bad region without backup", java.nio.file.Files.readString(broken.resolve("regions.json")));
            assertEquals(StoreStatus.MISSING, service.load(other, DatasetKind.MATERIALS).get().status());
        }
    }
    @Test void regionAcknowledgementRejectsExhaustedGenerationWithoutWriting() throws Exception {
        var placement = LocalPlacementId.create(); var task = RegionTaskId.create();
        var descriptor = new RegionDescriptor("Region 1", new Vector3(0, 0, 0), new Vector3(1, 1, 1));
        var dataset = new RegionWorkDataset(DatasetId.create(), placement, Long.MAX_VALUE, java.util.Map.of(task, new RegionDefinition(task, descriptor)), java.util.Map.of(task, TaskState.empty(task)), java.util.Map.of(), java.util.Set.of(task));
        assertTrue(new LocalDatasetStore(root).save(dataset, -1).ok());
        Path primary = root.resolve("placements").resolve(placement.toString()).resolve("regions.json");
        byte[] before = java.nio.file.Files.readAllBytes(primary);
        try (var service = new LocalWorkService(root)) {
            var result = service.acknowledgeRegion(placement, task).get();
            assertEquals(TransitionResult.Reason.VERSION_EXHAUSTED, ((TransitionResult.Rejected) result.transition()).reason());
            assertEquals(dataset, result.storage().dataset());
            assertArrayEquals(before, java.nio.file.Files.readAllBytes(primary));
        }
    }
    @Test void regionRefreshIsIndependentAndAcknowledgementIsDurable() throws Exception {
        var placement = LocalPlacementId.create();
        var original = new RegionDescriptor("Region 2", new Vector3(0, 0, 0), new Vector3(2, 2, -2));
        var snapshot = new WorkSnapshot(placement, List.of(), List.of(original));
        RegionWorkDataset accepted;
        try (var service = new LocalWorkService(root)) {
            var first = service.refreshRegions(snapshot).get();
            assertTrue(first.storage().ok());
            assertEquals(StoreStatus.MISSING, service.load(placement, DatasetKind.MATERIALS).get().status());
            var old = ((RegionWorkDataset) first.storage().dataset()).definitions().keySet().iterator().next();
            var actor = UUID.randomUUID();
            service.update(placement, old, new TaskCommand.SetDone(true), actor, Instant.EPOCH).get();
            service.update(placement, old, new TaskCommand.SetNote("古い作業"), actor, Instant.EPOCH).get();
            var changed = new WorkSnapshot(placement, List.of(), List.of(new RegionDescriptor("Region 2", new Vector3(1, 0, 0), original.size())));
            var refreshed = service.refreshRegions(changed).get();
            var dataset = (RegionWorkDataset) refreshed.storage().dataset();
            var next = dataset.definitions().keySet().iterator().next();
            assertNotEquals(old, next);
            assertTrue(dataset.activeReviewRequired().contains(next));
            assertTrue(dataset.archived().get(old).state().done());
            assertEquals(TaskState.empty(next), dataset.states().get(next));
            assertEquals(dataset, service.refreshRegions(changed).get().storage().dataset());
            assertEquals(TransitionResult.Reason.UNKNOWN_TASK, ((TransitionResult.Rejected) service.acknowledgeRegion(placement, old).get().transition()).reason());
            assertEquals(TransitionResult.Reason.INVALID_INPUT, ((TransitionResult.Rejected) service.acknowledgeRegion(placement, null).get().transition()).reason());
            var result = service.acknowledgeRegion(placement, next).get();
            assertTrue(result.storage().ok());
            accepted = (RegionWorkDataset) result.storage().dataset();
            assertFalse(accepted.activeReviewRequired().contains(next));
            assertEquals(dataset.states(), accepted.states());
            assertEquals(dataset.archived(), accepted.archived());
            assertEquals(accepted, service.acknowledgeRegion(placement, next).get().storage().dataset());
            Path material = root.resolve("placements").resolve(placement.toString()).resolve("materials.json");
            java.nio.file.Files.writeString(material, "{broken material");
            assertTrue(service.refreshRegions(changed).get().storage().ok());
            assertEquals("{broken material", java.nio.file.Files.readString(material));
            Path region = material.resolveSibling("regions.json");
            java.nio.file.Files.writeString(region, "{broken region");
            assertEquals(StoreStatus.RECOVERY_REQUIRED, service.refreshRegions(changed).get().storage().status());
            assertEquals(StoreStatus.RECOVERY_REQUIRED, service.acknowledgeRegion(placement, next).get().storage().status());
            assertEquals("{broken region", java.nio.file.Files.readString(region));
            assertTrue(service.recover(placement, DatasetKind.REGIONS).get().ok());
        }
        try (var restarted = new LocalWorkService(root)) {
            // Recovery restores the previous good generation; explicitly accept again.
            var recovered = (RegionWorkDataset) restarted.load(placement, DatasetKind.REGIONS).get().dataset();
            var next = recovered.definitions().keySet().iterator().next();
            restarted.acknowledgeRegion(placement, next).get();
        }
        var closed = new LocalWorkService(root); closed.close();
        assertEquals(StoreStatus.CLOSED, closed.refreshRegions(snapshot).get().storage().status());
        assertEquals(StoreStatus.CLOSED, closed.acknowledgeRegion(placement, accepted.definitions().keySet().iterator().next()).get().storage().status());
        try (var restarted = new LocalWorkService(root)) {
            assertEquals(accepted, restarted.load(placement, DatasetKind.REGIONS).get().dataset());
        }
    }
    @Test void materialScreenRefreshDoesNotCreateOrReadRegionWork() throws Exception {
        LocalPlacementId placement = LocalPlacementId.create();
        MaterialTaskId stone = new MaterialTaskId("minecraft:stone");
        var snapshot = new WorkSnapshot(placement, List.of(new MaterialDefinition(stone, 10, 10, 0)),
            List.of(new RegionDescriptor("Region", new Vector3(0, 0, 0), new Vector3(1, 1, 1))));
        try (var service = new LocalWorkService(root)) {
            assertTrue(service.refreshMaterials(snapshot).get().storage().ok());
            assertEquals(StoreStatus.MISSING, service.load(placement, DatasetKind.REGIONS).get().status());
            UUID actor = UUID.randomUUID();
            assertTrue(service.update(placement, stone, new TaskCommand.SetDone(true), actor, Instant.EPOCH).get().storage().ok());
            Path region = root.resolve("placements").resolve(placement.toString()).resolve("regions.json");
            java.nio.file.Files.writeString(region, "{broken region");
            var changed = new WorkSnapshot(placement, List.of(new MaterialDefinition(stone, 20, 20, 0)), List.of());
            var refreshed = service.refreshMaterials(changed).get();
            assertTrue(refreshed.storage().ok());
            var materials = (MaterialWorkDataset) refreshed.storage().dataset();
            assertEquals(20, materials.definitions().get(stone).total());
            assertTrue(materials.states().get(stone).done());
            assertEquals(actor, materials.states().get(stone).assignee());
            assertTrue(refreshed.reconciliation().changed().contains(stone));
            assertEquals("{broken region", java.nio.file.Files.readString(region));
            java.nio.file.Files.writeString(region.resolveSibling("materials.json"), "{broken material");
            assertEquals(StoreStatus.RECOVERY_REQUIRED, service.refreshMaterials(changed).get().storage().status());
            assertEquals("{broken material", java.nio.file.Files.readString(region.resolveSibling("materials.json")));
        }
        var closed = new LocalWorkService(root); closed.close();
        assertEquals(StoreStatus.CLOSED, closed.refreshMaterials(snapshot).get().storage().status());
    }
    @Test void serializedCommandsAreDurableAndClosedServicesRejectNewWork() throws Exception {
        LocalPlacementId placement = LocalPlacementId.create(); MaterialTaskId stone = new MaterialTaskId("minecraft:stone");
        var snapshot = new WorkSnapshot(placement, List.of(new MaterialDefinition(stone, 10, 10, 0)), List.of());
        LocalWorkService service = new LocalWorkService(root);
        assertTrue(service.refresh(snapshot).get().materials().ok());
        var first = service.update(placement, stone, new TaskCommand.SetDone(true), UUID.randomUUID(), Instant.EPOCH);
        var second = service.update(placement, stone, new TaskCommand.SetNote("永続化済み"), UUID.randomUUID(), Instant.EPOCH);
        assertTrue(first.get().storage().ok()); assertTrue(second.get().storage().ok());
        service.close();
        assertEquals(StoreStatus.CLOSED, service.refresh(snapshot).get().materials().status());
        try (var recreated = new LocalWorkService(root)) {
            var loaded = (MaterialWorkDataset) recreated.refresh(snapshot).get().materials().dataset();
            assertTrue(loaded.states().get(stone).done()); assertEquals(2, loaded.states().get(stone).rowVersion());
            assertEquals("永続化済み", loaded.states().get(stone).note());
            assertEquals(TransitionResult.Reason.UNKNOWN_TASK, ((TransitionResult.Rejected) recreated.update(placement, new MaterialTaskId("minecraft:glass"), new TaskCommand.SetDone(true), UUID.randomUUID(), Instant.EPOCH).get().transition()).reason());
        }
    }
    @Test void corruptRegionDoesNotResetMaterialAndDoesNotPublishSuccessfulMutation() throws Exception {
        LocalPlacementId placement = LocalPlacementId.create(); MaterialTaskId stone = new MaterialTaskId("minecraft:stone");
        var region = new RegionDescriptor("Region", new Vector3(0, 0, 0), new Vector3(1, 1, 1));
        var snapshot = new WorkSnapshot(placement, List.of(new MaterialDefinition(stone, 1, 1, 0)), List.of(region));
        try (var service = new LocalWorkService(root)) {
            var initial = service.refresh(snapshot).get();
            var regionId = ((RegionWorkDataset) initial.regions().dataset()).definitions().keySet().iterator().next();
            UUID actor = UUID.randomUUID();
            assertTrue(service.update(placement, stone, new TaskCommand.SetDone(true), actor, Instant.EPOCH).get().storage().ok());
            Path file = root.resolve("placements").resolve(placement.toString()).resolve("regions.json");
            java.nio.file.Files.writeString(file, "{broken");
            var result = service.refresh(snapshot).get();
            assertTrue(result.materials().ok()); assertTrue(((MaterialWorkDataset) result.materials().dataset()).states().get(stone).done());
            assertEquals(StoreStatus.CORRUPT, result.regions().status());
            var rejected = service.update(placement, regionId, new TaskCommand.SetDone(true), actor, Instant.EPOCH).get();
            assertEquals(StoreStatus.CORRUPT, rejected.storage().status()); assertNull(rejected.transition());
            assertEquals("{broken", java.nio.file.Files.readString(file));
        }
    }
}
