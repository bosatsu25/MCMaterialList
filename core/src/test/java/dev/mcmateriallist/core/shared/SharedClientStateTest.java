package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.core.work.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedClientStateTest {
    static final MaterialTaskId STONE = new MaterialTaskId("minecraft:stone");
    final UUID owner = UUID.randomUUID();
    final ProjectScope scope = ProjectScope.create(UUID.randomUUID());
    ProjectSnapshot initial() {
        var definition = new SharedManifest.Definition("a".repeat(64),
            new SharedManifest.Baseline("minecraft:overworld", new Vector3(0, 0, 0),
                SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), Map.of(STONE, 1L), List.of());
        return new ProjectSnapshot(scope, "work", owner, Set.of(), 0,
            SharedManifest.initial(definition), Map.of(STONE, TaskState.empty(STONE)), Set.of());
    }
    @Test void installedSnapshotEnablesOnlyContiguousDeltasAndAckNeverAdvancesCursor() {
        var state = new SharedClientState(); state.joining(scope);
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, initial()));
        assertTrue(state.synchronizedState()); assertEquals(0, state.cursor());
        var row = new TaskState(STONE, owner, false, "confirmed", null, null, 1);
        state.receive(new SharedMessage.CommandResult(new SharedEvents.CommandResult(scope, UUID.randomUUID(), SharedEvents.Status.OK, 1, row)));
        assertEquals(0, state.cursor()); assertEquals("confirmed", state.snapshot().rows().get(STONE).note());
        state.receive(new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(scope, 1, 0, row, false)));
        assertEquals(1, state.cursor()); assertTrue(state.synchronizedState());
        state.receive(new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(scope, 3, 0, row, false)));
        assertFalse(state.synchronizedState());
    }
    @Test void bufferedDeltasApplyAfterSnapshotAndRevocationDropsPrivateState() {
        var state = new SharedClientState(); state.joining(scope);
        var row = new TaskState(STONE, null, false, "next", null, null, 1);
        state.receive(new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(scope, 1, 0, row, false)));
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, initial()));
        assertEquals(1, state.cursor()); assertEquals("next", state.snapshot().rows().get(STONE).note());
        state.receive(new SharedMessage.AccessRevoked(scope));
        assertNull(state.snapshot()); assertFalse(state.synchronizedState());
    }
    @Test void wrongListScopeAndOlderAckCannotReplaceCurrentRow() {
        var state = new SharedClientState(); state.joining(scope);
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, initial()));
        var newer = new TaskState(STONE, null, false, "new", null, null, 7);
        state.receive(new SharedMessage.CommandResult(new SharedEvents.CommandResult(scope, UUID.randomUUID(), SharedEvents.Status.OK, 9, newer)));
        var wrong = new ProjectScope(scope.worldNamespace(), scope.projectId(), UUID.randomUUID(), scope.regionListId());
        var old = new TaskState(STONE, null, false, "old", null, null, 1);
        state.receive(new SharedMessage.CommandResult(new SharedEvents.CommandResult(wrong, UUID.randomUUID(), SharedEvents.Status.OK, 99, old)));
        state.receive(new SharedMessage.CommandResult(new SharedEvents.CommandResult(scope, UUID.randomUUID(), SharedEvents.Status.OK, 99, old)));
        assertEquals("new", state.snapshot().rows().get(STONE).note()); assertEquals(0, state.cursor());
        state.receive(new SharedMessage.CommandResult(new SharedEvents.CommandResult(scope, UUID.randomUUID(), SharedEvents.Status.OK, 99,
            new TaskState(new MaterialTaskId("minecraft:dirt"), null, false, "absent", null, null, 20))));
        assertEquals(1, state.snapshot().rows().size());
    }
    @Test void invalidationAndSnapshotBufferOverflowRequireExplicitResync() {
        var state = new SharedClientState(); state.joining(scope);
        var delta = new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(scope, 1, 0, TaskState.empty(STONE), false));
        for (int i = 0; i < 65; i++) state.receive(delta);
        assertEquals(SharedClientState.Status.RESYNC_REQUIRED, state.status());
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, initial()));
        assertFalse(state.synchronizedState());
        state.joining(scope); state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, initial()));
        state.receive(new SharedMessage.ProjectInvalidated(new SharedEvents.ProjectInvalidated(scope, 1, 1)));
        assertFalse(state.synchronizedState());
        state.disconnected(); assertEquals(SharedClientState.Status.DISCONNECTED, state.status()); assertNotNull(state.snapshot());
    }
    @Test void knownInvalidationAndGapFenceOlderCorrelatedSnapshotsAcrossResync() {
        var state = new SharedClientState(); state.joining(scope);
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, initial()));
        state.receive(new SharedMessage.ProjectInvalidated(new SharedEvents.ProjectInvalidated(scope, 4, 1)));
        state.joining(scope);
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, initial()));
        assertFalse(state.synchronizedState()); assertNull(state.snapshot());
        var old = initial();
        var fresh = new ProjectSnapshot(scope, old.name(), owner, Set.of(), 4,
            new SharedManifest(1, old.manifest().definition(), old.manifest().regions()), old.rows(), Set.of());
        state.joining(scope);
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, fresh));
        assertTrue(state.synchronizedState());
        state.receive(new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(scope, 7, 1, TaskState.empty(STONE), false)));
        state.joining(scope);
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, fresh));
        assertFalse(state.synchronizedState());
        var latest = new ProjectSnapshot(scope, old.name(), owner, Set.of(), 7, fresh.manifest(), old.rows(), Set.of());
        state.joining(scope);
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, latest));
        assertTrue(state.synchronizedState()); assertEquals(7, state.cursor());
    }
}
