package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.work.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.mcmateriallist.core.shared.SharedAuthorityTest.*;
import static dev.mcmateriallist.core.shared.SharedEvents.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedLifecycleRegressionTest {
    @TempDir Path root;
    private final UUID owner = UUID.randomUUID();

    @Test void reintroducedMaterialCannotAdoptHistoricalAckAndKeepsFreshWorkAfterRestart() throws Exception {
        ProjectSnapshot reintroduced;
        SharedCommand original;
        SharedEvents.CommandResult originalResult;
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            UUID world = await(service.ready()).worldNamespace();
            var initial = await(service.create(owner, world, "shared", definition())).value();
            await(service.command(owner, command(initial, STONE, 0, new SharedCommand.SetNote("old progress"))));
            await(service.command(owner, command(initial, STONE, 1, new SharedCommand.Claim())));
            original = command(initial, STONE, 2, new SharedCommand.SetDone(true));
            originalResult = await(service.command(owner, original)).result();
            assertEquals(3, originalResult.row().rowVersion());
            var withoutStone = new SharedManifest.Definition(definition().schematicDigest(), definition().baseline(),
                Map.of(DIRT, 20L), definition().regions());
            assertEquals(OK, await(service.updateManifest(owner, initial.scope(), 3, 0, withoutStone)).status());
            reintroduced = await(service.updateManifest(owner, initial.scope(), 4, 1, definition())).value();
            TaskState fresh = reintroduced.rows().get(STONE);
            assertEquals(initial.scope().materialListId(), reintroduced.scope().materialListId());
            assertFalse(fresh.done()); assertEquals("", fresh.note()); assertNull(fresh.assignee());
            assertNull(fresh.completedBy()); assertNull(fresh.completedAt());
            var replay = await(service.command(owner, original));
            assertEquals(originalResult, replay.result()); assertTrue(replay.events().isEmpty());
            // Exactly the documented consumer adoption rule, applied after installing the fresh snapshot.
            TaskState adopted = replay.result().row().rowVersion() > fresh.rowVersion() ? replay.result().row() : fresh;
            assertEquals(fresh, adopted, "A historical ACK must not replace reintroduced empty work");
            assertEquals(4, fresh.rowVersion());
        }
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            assertEquals(StoreStatus.OK, await(service.ready()).status());
            assertEquals(reintroduced, await(service.join(owner, reintroduced.scope())).value());
            assertEquals(originalResult, await(service.command(owner, original)).result());
            assertEquals(CONFLICT, await(service.command(owner, command(reintroduced, STONE, 0, new SharedCommand.SetDone(true)))).result().status());
            var freshEdit = await(service.command(owner, command(reintroduced, STONE, 4, new SharedCommand.SetNote("new work"))));
            assertEquals(OK, freshEdit.result().status()); assertEquals(5, freshEdit.result().row().rowVersion());
            assertFalse(freshEdit.result().row().done()); assertNull(freshEdit.result().row().assignee());
            var withoutStone = new SharedManifest.Definition(definition().schematicDigest(), definition().baseline(),
                Map.of(DIRT, 20L), definition().regions());
            assertEquals(OK, await(service.updateManifest(owner, reintroduced.scope(), 6, 2, withoutStone)).status());
            var secondReintroduction = await(service.updateManifest(owner, reintroduced.scope(), 7, 3, definition())).value();
            assertEquals(6, secondReintroduction.rows().get(STONE).rowVersion());
            assertEquals("", secondReintroduction.rows().get(STONE).note());
        }
    }

    @Test void exhaustedMaterialWatermarkRejectsReintroductionWithoutWrappingOrCopyingWork() throws Exception {
        var store = new SharedProjectStore(root);
        ProjectSnapshot initial;
        try (var service = new SharedProjectService(store)) {
            UUID world = await(service.ready()).worldNamespace();
            initial = await(service.create(owner, world, "shared", definition())).value();
        }
        Map<TaskId, TaskState> rows = new HashMap<>(initial.rows());
        rows.put(STONE, new TaskState(STONE, null, false, "", null, null, Long.MAX_VALUE));
        var exhausted = new ProjectSnapshot(initial.scope(), initial.name(), owner, Set.of(), 0, initial.manifest(), rows, Set.of());
        assertEquals(StoreStatus.OK, store.save(new ProjectArchive(1, exhausted, List.of(), List.of()), 0).status());
        try (var service = new SharedProjectService(store)) {
            assertEquals(StoreStatus.OK, await(service.ready()).status());
            var withoutStone = new SharedManifest.Definition(definition().schematicDigest(), definition().baseline(),
                Map.of(DIRT, 20L), definition().regions());
            var removed = await(service.updateManifest(owner, initial.scope(), 0, 0, withoutStone));
            assertEquals(OK, removed.status());
            assertEquals(CAPACITY, await(service.updateManifest(owner, initial.scope(), 1, 1, definition())).status());
            assertEquals(removed.value(), await(service.join(owner, initial.scope())).value());
        }
    }

    @Test void corruptPrimaryWithRetainedBackupDoesNotFreeProjectCapacity() throws Exception {
        damagedProjectKeepsCapacity(false);
    }

    @Test void missingPrimaryWithRetainedBackupDoesNotFreeProjectCapacity() throws Exception {
        damagedProjectKeepsCapacity(true);
    }

    private void damagedProjectKeepsCapacity(boolean missingPrimary) throws Exception {
        var store = new SharedProjectStore(root);
        UUID world = store.openWorld().value().namespace();
        var empty = new SharedManifest.Definition(definition().schematicDigest(), definition().baseline(), Map.of(), List.of());
        ProjectArchive victim = null;
        for (int i = 0; i < SharedLimits.PROJECTS; i++) {
            var snapshot = new ProjectSnapshot(ProjectScope.create(world), "project", owner, Set.of(), 0,
                SharedManifest.initial(empty), Map.of(), Set.of());
            var archive = new ProjectArchive(0, snapshot, List.of(), List.of());
            assertEquals(StoreStatus.OK, store.save(archive, -1).status());
            if (victim == null) victim = archive;
        }
        assertNotNull(victim);
        assertEquals(StoreStatus.OK, store.save(new ProjectArchive(1, victim.snapshot(), List.of(), List.of()), 0).status());
        ProjectScope scope = victim.snapshot().scope();
        Path primary = root.resolve("mcmateriallist-shared").resolve("project-" + scope.projectId() + ".json");
        try (var service = new SharedProjectService(store)) {
            assertEquals(StoreStatus.OK, await(service.ready()).status());
            assertEquals(SharedLimits.PROJECTS, await(service.discover(owner, world)).value().size());
            if (missingPrimary) Files.delete(primary); else Files.writeString(primary, "damaged authority");
            assertEquals(UNAVAILABLE, await(service.join(owner, scope)).status());
            assertFalse(service.publishIfAuthorized(owner, scope, () -> fail("Damaged authority publication")));
            assertEquals(SharedLimits.PROJECTS - 1, await(service.discover(owner, world)).value().size());
            assertEquals(CAPACITY, await(service.create(owner, world, "must not be admitted", empty)).status());
            var inventory = store.openWorld(); assertEquals(StoreStatus.OK, inventory.status());
            assertEquals(SharedLimits.PROJECTS, inventory.value().projects().size());
        }
        assertEquals(StoreStatus.RECOVERY_REQUIRED, store.load(world, scope.projectId()).status());
        assertEquals(StoreStatus.OK, store.recover(world, scope.projectId()).status());
        try (var restarted = new SharedProjectService(store)) {
            assertEquals(StoreStatus.OK, await(restarted.ready()).status());
            assertEquals(SharedLimits.PROJECTS, await(restarted.discover(owner, world)).value().size());
            assertEquals(CAPACITY, await(restarted.create(owner, world, "still full", empty)).status());
        }
    }
}
