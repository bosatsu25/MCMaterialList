package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.work.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * State transition testing ST-B of docs/test-design.md (JSTQB FL v4.0, 4.2.4): the regions binding states of
 * docs/phase-4c-unshare.md I1 (ORIGINAL, SHARED, DETACHED) under the SharedClientService events select, local, unshare,
 * updateDetached, rejoin, cache and clearCaches (3 x 7 = 21 cells). This class implements the cells no existing test covers
 * and one path through every state; docs/test-design.md maps the other cells to SharedClientServiceTest and
 * SharedClientUnshareTest, so together they reach all-transitions coverage.
 */
class BindingStateTransitionTest {
    @TempDir Path root;
    private final LocalPlacementId placement = LocalPlacementId.create();
    private final UUID actor = new UUID(0, 1);
    private final MaterialTaskId stone = new MaterialTaskId("minecraft:stone");
    private final RegionTaskId tower = RegionTaskId.create();
    private final DatasetId localMaterials = DatasetId.create(), localRegions = DatasetId.create();
    private final ProjectScope materialScope = ProjectScope.create(UUID.randomUUID()), regionScope = ProjectScope.create(materialScope.worldNamespace());
    private final SharedClientArchive.Reference materialRef = new SharedClientArchive.Reference(UUID.randomUUID(), actor, materialScope),
        regionRef = new SharedClientArchive.Reference(UUID.randomUUID(), actor, regionScope);

    enum State { ORIGINAL, SHARED, DETACHED }

    private ProjectSnapshot snapshot(ProjectScope scope, long revision) {
        var region = new RegionDescriptor("Tower", new Vector3(0, 0, 0), new Vector3(2, 2, 2));
        var definition = new SharedManifest.Definition("b".repeat(64), new SharedManifest.Baseline("minecraft:overworld", new Vector3(0, 64, 0),
            SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), Map.of(stone, 1L), List.of(region));
        var manifest = new SharedManifest(0, definition, Map.of(tower, region));
        Map<TaskId, TaskState> rows = new HashMap<>(); manifest.taskIds().forEach(id -> rows.put(id, TaskState.empty(id)));
        return new ProjectSnapshot(scope, "st-b", actor, Set.of(), revision, manifest, rows, Set.of());
    }
    private SharedClientArchive install(SharedClientArchive.Binding regions) {
        var archive = new SharedClientArchive(0, placement,
            new SharedClientArchive.Binding(localMaterials, materialRef, new SharedClientArchive.Cache(snapshot(materialScope, 0), 0)), regions, List.of());
        assertEquals(StoreStatus.OK, new SharedClientStore(root).save(archive, -1).status());
        return archive;
    }
    private SharedClientArchive.Binding original() { return new SharedClientArchive.Binding(localRegions, null, null); }
    private SharedClientArchive.Binding shared() { return new SharedClientArchive.Binding(localRegions, regionRef, new SharedClientArchive.Cache(snapshot(regionScope, 0), 0)); }
    private Path file() { return root.resolve("shared-client").resolve("placement-" + placement + ".json"); }
    private SharedClientArchive stored() { return new SharedClientStore(root).load(placement).value(); }
    private static State state(SharedClientArchive.Binding binding) {
        if (binding.detached() != null) { assertNull(binding.shared()); assertNull(binding.cache()); return State.DETACHED; }
        if (binding.shared() != null) return State.SHARED;
        assertNull(binding.cache()); return State.ORIGINAL;
    }
    /** Refused events must leave the save unit byte for byte as it was. */
    private void assertRefusedWithoutWrite(java.util.function.Supplier<SharedClientStore.Result> event, String label) throws Exception {
        byte[] before = Files.readAllBytes(file());
        assertEquals(StoreStatus.CONFLICT, event.get().status(), label);
        assertArrayEquals(before, Files.readAllBytes(file()), label + " wrote");
    }

    /** ST-B-02..07: ORIGINAL --local--> ORIGINAL; updateDetached, rejoin and cache are invalid; clearCaches writes nothing. */
    @Test void stB_originalStateEvents() throws Exception {
        install(original());
        try (var service = new SharedClientService(root)) {
            var local = service.local(placement, DatasetKind.REGIONS, localRegions).join();
            assertTrue(local.ok()); assertEquals(State.ORIGINAL, state(local.value().regions())); assertEquals(localRegions, local.value().regions().localDataset());
            assertRefusedWithoutWrite(() -> service.local(placement, DatasetKind.REGIONS, DatasetId.create()).join(), "local with another original dataset");
            assertRefusedWithoutWrite(() -> service.updateDetached(placement, DatasetId.create(), 0, dataset -> dataset).join(), "ORIGINAL updateDetached");
            assertRefusedWithoutWrite(() -> service.rejoin(placement, DatasetId.create(), 0, localRegions, actor, snapshot(regionScope, 1), 1).join(), "ORIGINAL rejoin");
            assertRefusedWithoutWrite(() -> service.cache(placement, DatasetKind.REGIONS, regionRef, snapshot(regionScope, 1), 1).join(), "ORIGINAL cache");
            byte[] before = Files.readAllBytes(file());
            // Scoped to the region project: the shared materials cache of this placement must stay.
            assertTrue(service.clearCaches(placement, regionScope).join().ok());
            assertArrayEquals(before, Files.readAllBytes(file()), "clearCaches rewrote an archive without region cache");
            assertEquals(State.ORIGINAL, state(stored().regions()));
        }
    }

    /** ST-B-11, ST-B-12: SHARED --updateDetached--> invalid, SHARED --rejoin--> invalid (rejoin is only for a detached copy). */
    @Test void stB_sharedStateRejectsDetachedOnlyEvents() throws Exception {
        install(shared());
        try (var service = new SharedClientService(root)) {
            assertRefusedWithoutWrite(() -> service.updateDetached(placement, localRegions, 0, dataset -> dataset).join(), "SHARED updateDetached");
            assertRefusedWithoutWrite(() -> service.rejoin(placement, localRegions, 0, localRegions, actor, snapshot(regionScope, 1), 1).join(), "SHARED rejoin");
            assertEquals(State.SHARED, state(stored().regions()));
        }
    }

    /**
     * ST-B path: ORIGINAL -select-> SHARED -cache-> SHARED -unshare-> DETACHED -updateDetached-> DETACHED -rejoin-> SHARED
     * -clearCaches-> SHARED (no cache) -local-> ORIGINAL. Visits every state and returns to the start; after each step the
     * stored binding is in exactly one state (I1), the materials binding is untouched (I3) and the original id is kept.
     */
    @Test void stB_lifecyclePathThroughEveryState() {
        var start = install(original());
        try (var service = new SharedClientService(root)) {
            List<State> visited = new ArrayList<>(List.of(State.ORIGINAL));
            var s1 = service.select(placement, DatasetKind.REGIONS, localRegions, actor, snapshot(regionScope, 0), 0).join(); assertTrue(s1.ok());
            visited.add(state(s1.value().regions())); var ref = s1.value().regions().shared();
            var s2 = service.cache(placement, DatasetKind.REGIONS, ref, snapshot(regionScope, 1), 1).join(); assertTrue(s2.ok());
            visited.add(state(s2.value().regions())); assertEquals(1, s2.value().regions().cache().cursor());
            var copyId = DatasetId.create();
            var s3 = service.unshare(placement, ref, 1, actor, copyId).join(); assertTrue(s3.ok());
            visited.add(state(s3.value().regions()));
            var s4 = service.updateDetached(placement, copyId, 0, d -> d.apply(tower, new TaskCommand.SetDone(true), actor, Instant.EPOCH).dataset()).join(); assertTrue(s4.ok());
            visited.add(state(s4.value().regions())); assertTrue(s4.value().regions().detached().dataset().states().get(tower).done());
            var s5 = service.rejoin(placement, copyId, 1, localRegions, actor, snapshot(regionScope, 2), 2).join(); assertTrue(s5.ok());
            visited.add(state(s5.value().regions())); assertFalse(s5.value().regions().cache().snapshot().rows().get(tower).done(), "Rejoin merged the detached edit (I5)");
            var s6 = service.clearCaches(placement, regionScope).join(); assertTrue(s6.ok());
            visited.add(state(s6.value().regions())); assertNull(s6.value().regions().cache());
            var s7 = service.local(placement, DatasetKind.REGIONS, localRegions).join(); assertTrue(s7.ok());
            visited.add(state(s7.value().regions()));
            assertEquals(List.of(State.ORIGINAL, State.SHARED, State.SHARED, State.DETACHED, State.DETACHED, State.SHARED, State.SHARED, State.ORIGINAL), visited);
            for (var step : List.of(s1, s2, s3, s4, s5, s7)) {
                assertEquals(start.materials(), step.value().materials(), "I3: materials binding changed");
                assertEquals(localRegions, step.value().regions().localDataset(), "Original local region dataset id lost");
            }
            assertEquals(s7.value(), stored());
        }
    }
}
