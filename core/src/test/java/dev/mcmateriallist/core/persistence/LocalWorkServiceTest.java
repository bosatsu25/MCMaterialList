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
