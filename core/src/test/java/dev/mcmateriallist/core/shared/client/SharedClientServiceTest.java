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

class SharedClientServiceTest {
    @TempDir Path root;
    private final LocalPlacementId placement = LocalPlacementId.create();
    private final UUID actor = UUID.randomUUID(), connection = UUID.randomUUID();
    private final MaterialTaskId stone = new MaterialTaskId("minecraft:stone");
    private ProjectSnapshot snapshot() {
        var manifest = SharedManifest.initial(new SharedManifest.Definition("a".repeat(64),
            new SharedManifest.Baseline("minecraft:overworld", new Vector3(0, 64, 0), SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE),
            Map.of(stone, 5L), List.of(new RegionDescriptor("Region", new Vector3(0, 0, 0), new Vector3(1, 1, 5)))));
        Map<TaskId, TaskState> rows = new HashMap<>(); manifest.taskIds().forEach(id -> rows.put(id, TaskState.empty(id)));
        return new ProjectSnapshot(ProjectScope.create(UUID.randomUUID()), "fixture", actor, Set.of(), 0, manifest, rows, Set.of());
    }
    private SharedClientArchive initial(ProjectSnapshot snapshot) {
        var ref = new SharedClientArchive.Reference(UUID.randomUUID(), actor, snapshot.scope());
        var value = new SharedClientArchive(0, placement,
            new SharedClientArchive.Binding(DatasetId.create(), ref, new SharedClientArchive.Cache(snapshot, 0)),
            new SharedClientArchive.Binding(DatasetId.create(), null, null), List.of());
        assertEquals(StoreStatus.OK, new SharedClientStore(root).save(value, -1).status());
        return value;
    }
    private SharedCommand command(ProjectSnapshot snapshot) {
        return new SharedCommand(snapshot.scope(), stone, UUID.randomUUID(), 0, 0, new SharedCommand.SetDone(true));
    }

    @Test void independentSelectionIsDurableWithoutImportingLocalRowsAndOnlyOneSideSwitches() {
        var material = snapshot(); var region = snapshot();
        var localMaterials = DatasetId.create(); var localRegions = DatasetId.create();
        try (var service = new SharedClientService(root)) {
            var first = service.select(placement, DatasetKind.MATERIALS, localMaterials, actor, material, 0).join();
            assertTrue(first.ok()); assertNull(first.value().regions().shared());
            var second = service.select(placement, DatasetKind.REGIONS, localRegions, actor, region, 0).join();
            assertTrue(second.ok()); assertEquals(first.value().materials(), second.value().materials());
            assertNotEquals(second.value().materials().shared().scope(), second.value().regions().shared().scope());
            var local = service.local(placement, DatasetKind.REGIONS, localRegions).join();
            assertTrue(local.ok()); assertEquals(second.value().materials(), local.value().materials());
            assertNull(local.value().regions().shared()); assertEquals(localRegions, local.value().regions().localDataset());
        }
        var restored = new SharedClientStore(root).load(placement).value();
        assertEquals(localMaterials, restored.materials().localDataset());
        assertEquals(localRegions, restored.regions().localDataset());
        assertFalse(Files.exists(root.resolve("placements")), "Shared work wrote/relabelled the ordinary local dataset store");
    }

    @Test void presendRecordsExactOriginalContextAndLateConfirmationCannotFollowNewBindingOrActor() {
        var snapshot = snapshot(); var before = initial(snapshot); var command = command(snapshot);
        var original = before.materials().shared();
        try (var service = new SharedClientService(root)) {
            var admitted = service.admit(placement, DatasetKind.MATERIALS, original, actor, connection, command).join();
            assertTrue(admitted.ok());
            var durable = new SharedClientStore(root).load(placement).value().operations().getFirst();
            assertEquals(command, durable.command()); assertEquals(original, durable.original()); assertEquals(connection, durable.connection());
            assertNull(durable.confirmation());
            assertEquals(StoreStatus.CONFLICT, service.admit(placement, DatasetKind.MATERIALS, original, actor, connection, command(snapshot)).join().status(),
                "A second dependent edit bypassed the unknown outcome");
            var switched = service.select(placement, DatasetKind.MATERIALS, before.materials().localDataset(), actor, snapshot(), 0).join();
            assertTrue(switched.ok()); assertEquals(durable, switched.value().operations().getFirst());
            var result = new SharedEvents.CommandResult(snapshot.scope(), command.operationId(), SharedEvents.Status.OK, 1,
                new TaskState(stone, actor, true, "", actor, java.time.Instant.EPOCH, 1));
            assertEquals(StoreStatus.CONFLICT, service.confirm(placement, UUID.randomUUID(), connection, result).join().status());
            assertEquals(StoreStatus.CONFLICT, service.confirm(placement, actor, UUID.randomUUID(), result).join().status());
            var confirmed = service.confirm(placement, actor, connection, result).join();
            assertTrue(confirmed.ok()); assertEquals(SharedEvents.Status.OK, confirmed.value().operations().getFirst().confirmation().status());
            assertEquals(switched.value().materials(), confirmed.value().materials(), "Old ACK changed a new binding/cache");
            assertEquals(before.regions(), confirmed.value().regions());
        }
    }

    @Test void admissionRejectsChangedAccountBindingScopeVersionsAndMissingCacheWithoutJournalMutation() {
        var snapshot = snapshot(); var before = initial(snapshot); var command = command(snapshot); var original = before.materials().shared();
        try (var service = new SharedClientService(root)) {
            assertEquals(StoreStatus.CONFLICT, service.admit(placement, DatasetKind.MATERIALS, original, UUID.randomUUID(), connection, command).join().status());
            var altered = new SharedClientArchive.Reference(UUID.randomUUID(), actor, snapshot.scope());
            assertEquals(StoreStatus.CONFLICT, service.admit(placement, DatasetKind.MATERIALS, altered, actor, connection, command).join().status());
            var stale = new SharedCommand(snapshot.scope(), stone, UUID.randomUUID(), 1, 0, new SharedCommand.Claim());
            assertEquals(StoreStatus.CONFLICT, service.admit(placement, DatasetKind.MATERIALS, original, actor, connection, stale).join().status());
            assertEquals(before, new SharedClientStore(root).load(placement).value());
            var cleared = service.clearCaches(placement, snapshot.scope()).join(); assertTrue(cleared.ok());
            assertNull(cleared.value().materials().cache());
            assertEquals(StoreStatus.CONFLICT, service.admit(placement, DatasetKind.MATERIALS, original, actor, connection, command).join().status());
            assertTrue(new SharedClientStore(root).load(placement).value().operations().isEmpty());
        }
    }

    @Test void shutdownDrainsAcceptedSaveAndNeverConfirmsUnknownCommandOnRestart() {
        var snapshot = snapshot(); var before = initial(snapshot); var command = command(snapshot);
        var service = new SharedClientService(root);
        var save = service.admit(placement, DatasetKind.MATERIALS, before.materials().shared(), actor, connection, command);
        service.close(); assertTrue(save.join().ok());
        assertEquals(StoreStatus.CLOSED, service.load(placement).join().status());
        try (var reopened = new SharedClientService(root)) {
            var restored = reopened.load(placement).join(); assertTrue(restored.ok());
            assertNull(restored.value().operations().getFirst().confirmation());
            assertEquals(StoreStatus.CONFLICT, reopened.admit(placement, DatasetKind.MATERIALS,
                before.materials().shared(), actor, UUID.randomUUID(), command(snapshot)).join().status());
        }
    }
    @Test void backupUncertaintySurvivesFreshSnapshotsLocalSelectionAndAnotherActor() throws Exception {
        var snapshot = snapshot(); var before = initial(snapshot);
        var store = new SharedClientStore(root);
        assertTrue(store.save(new SharedClientArchive(1, placement, before.materials(), before.regions(), List.of()), 0).ok());
        Files.writeString(root.resolve("shared-client").resolve("placement-" + placement + ".json"), "damaged original");
        try (var service = new SharedClientService(root)) {
            var restored = service.recover(placement).join(); assertTrue(restored.ok());
            var refreshed = service.cache(placement, DatasetKind.MATERIALS, before.materials().shared(), snapshot, 0).join();
            assertTrue(refreshed.ok()); assertTrue(refreshed.value().recoveredJournalUncertain());
            assertEquals(StoreStatus.CONFLICT, service.admit(placement, DatasetKind.MATERIALS, before.materials().shared(), actor, connection, command(snapshot)).join().status());
            assertTrue(service.local(placement, DatasetKind.MATERIALS, before.materials().localDataset()).join().ok());
            UUID another = UUID.randomUUID();
            var next = new ProjectSnapshot(ProjectScope.create(snapshot.scope().worldNamespace()), "another actor", another,
                Set.of(), 0, snapshot.manifest(), snapshot.rows(), Set.of());
            var switched = service.select(placement, DatasetKind.MATERIALS, before.materials().localDataset(), another, next, 0).join();
            assertTrue(switched.ok()); assertTrue(switched.value().recoveredJournalUncertain());
            assertEquals(StoreStatus.CONFLICT, service.admit(placement, DatasetKind.MATERIALS,
                switched.value().materials().shared(), another, UUID.randomUUID(), command(next)).join().status());
        }
        assertTrue(new SharedClientStore(root).load(placement).value().recoveredJournalUncertain());
    }
    @Test void unknownJournalCapacityRefusesAdditionalIntentWithoutDiscardingOriginalContexts() {
        var snapshot = snapshot(); var before = initial(snapshot);
        List<SharedClientArchive.Operation> unknown = new ArrayList<>();
        for (int i = 0; i < SharedClientService.UNKNOWN_OPERATIONS; i++) {
            var scope = ProjectScope.create(snapshot.scope().worldNamespace());
            var reference = new SharedClientArchive.Reference(UUID.randomUUID(), actor, scope);
            unknown.add(new SharedClientArchive.Operation(DatasetKind.MATERIALS, reference, UUID.randomUUID(),
                new SharedCommand(scope, stone, UUID.randomUUID(), 0, 0, new SharedCommand.Claim()), null));
        }
        var full = new SharedClientArchive(1, placement, before.materials(), before.regions(), unknown);
        assertTrue(new SharedClientStore(root).save(full, 0).ok());
        try (var service = new SharedClientService(root)) {
            assertEquals(StoreStatus.BUSY, service.admit(placement, DatasetKind.MATERIALS, before.materials().shared(), actor, connection, command(snapshot)).join().status());
        }
        assertEquals(full, new SharedClientStore(root).load(placement).value());
    }
}
