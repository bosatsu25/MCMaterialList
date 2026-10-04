package dev.mcmateriallist.core.work;

import dev.mcmateriallist.core.LocalPlacementId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkDomainTest {
    private final UUID actor = UUID.randomUUID();
    private final Instant time = Instant.parse("2026-10-04T00:00:00Z");
    private final MaterialTaskId stone = new MaterialTaskId("minecraft:stone");

    @Test void explicitTransitionsPreserveIndependentFields() {
        TaskState state = TaskState.empty(stone);
        assertFalse(state.done()); assertEquals("", state.note()); assertNull(state.assignee());
        state = changed(state, new TaskCommand.Claim());
        assertEquals(actor, state.assignee()); assertEquals(1, state.rowVersion());
        state = changed(state, new TaskCommand.SetNote("資材の担当メモ"));
        state = changed(state, new TaskCommand.SetDone(true));
        assertEquals(actor, state.completedBy()); assertEquals(time, state.completedAt());
        assertEquals(3, state.rowVersion());
        assertInstanceOf(TransitionResult.Unchanged.class, TaskTransitions.apply(state, new TaskCommand.SetDone(true), actor, time.plusSeconds(1)));
        state = changed(state, new TaskCommand.Release());
        assertTrue(state.done()); assertNull(state.assignee()); assertEquals("資材の担当メモ", state.note());
        state = changed(state, new TaskCommand.SetDone(false));
        assertNull(state.completedBy()); assertNull(state.completedAt()); assertEquals(5, state.rowVersion());
    }

    @Test void assignmentCompletionAndFailuresAreExplicit() {
        UUID owner = UUID.randomUUID();
        TaskState state = changed(TaskState.empty(stone), new TaskCommand.Assign(owner));
        var rejected = assertInstanceOf(TransitionResult.Rejected.class, TaskTransitions.apply(state, new TaskCommand.Claim(), actor, time));
        assertEquals(TransitionResult.Reason.ALREADY_ASSIGNED, rejected.reason());
        assertInstanceOf(TransitionResult.Rejected.class, TaskTransitions.apply(state, new TaskCommand.Release(), actor, time));
        state = changed(state, new TaskCommand.SetDone(true));
        assertEquals(owner, state.assignee()); assertEquals(actor, state.completedBy());
        state = changed(state, new TaskCommand.SetDone(false));
        assertEquals(owner, state.assignee());
        TaskState auto = changed(TaskState.empty(stone), new TaskCommand.SetDone(true));
        assertEquals(actor, auto.assignee());
        assertThrows(IllegalArgumentException.class, () -> new TaskState(stone, null, false, "", actor, time, 0));
        assertInstanceOf(TransitionResult.Rejected.class, TaskTransitions.apply(auto, new TaskCommand.SetNote("あ".repeat(2000)), actor, time));
        assertThrows(IllegalArgumentException.class, () -> new MaterialTaskId("../../escape"));
        assertEquals(stone, new MaterialTaskId("minecraft:stone"));
        DatasetId id = DatasetId.create(); assertEquals(id, DatasetId.parse(id.toString()));
    }

    @Test void progressUsesTrackedTasksAndFloorsPercentages() {
        assertEquals(21, progress(4, 19).percentage()); assertEquals(8, progress(5, 62).percentage());
        assertEquals(0, progress(0, 0).percentage()); assertEquals(100, progress(19, 19).percentage());
    }

    @Test void materialsReconcileByRegistryIdentityAndRetainArchivedState() {
        MaterialWorkDataset dataset = MaterialWorkDataset.create(LocalPlacementId.create());
        var first = dataset.reconcile(List.of(material("minecraft:stone", 1000), material("minecraft:glass", 1)));
        dataset = first.dataset();
        TaskState done = changed(dataset.states().get(stone), new TaskCommand.SetDone(true));
        dataset = dataset.withState(done);
        var next = dataset.reconcile(List.of(material("minecraft:glass", 2), material("minecraft:stone", 1200), material("minecraft:dirt", 3)));
        assertEquals(done, next.dataset().states().get(stone)); assertTrue(next.changed().contains(stone));
        assertEquals(1, next.added().size()); assertEquals(33, next.dataset().progress().percentage());
        dataset = next.dataset().reconcile(List.of(material("minecraft:glass", 2))).dataset();
        assertFalse(dataset.states().containsKey(stone)); assertEquals(done, dataset.archived().get(stone).state());
        dataset = dataset.reconcile(List.of(material("minecraft:stone", 1))).dataset();
        assertEquals(done, dataset.states().get(stone));
    }

    @Test void regionMatchingNeverGuessesOnRenamesOrMoves() {
        RegionWorkDataset dataset = RegionWorkDataset.create(LocalPlacementId.create());
        var descriptors = List.of(region("Region 1", 0), region("Region 2", 10), region("Region 3", 20));
        dataset = dataset.reconcile(descriptors).dataset();
        RegionTaskId second = dataset.definitions().values().stream().filter(d -> d.descriptor().key().equals("Region 2")).findFirst().orElseThrow().id();
        TaskState done = changed(dataset.states().get(second), new TaskCommand.SetDone(true));
        dataset = dataset.withState(done);
        var unchanged = dataset.reconcile(List.of(descriptors.get(2), descriptors.get(1), descriptors.get(0)));
        assertEquals(done, unchanged.dataset().states().get(second));
        var renamed = dataset.reconcile(List.of(region("Renamed", 10), region("Region 1", 0)));
        assertEquals(1, renamed.reviewRequired().size());
        assertEquals(done, renamed.dataset().archived().get(second).state());
        assertEquals(0, renamed.dataset().progress().completed());
        var moved = dataset.reconcile(List.of(region("Region 2", 11)));
        assertEquals(1, moved.reviewRequired().size());
        var ambiguous = dataset.reconcile(List.of(region("Region 1", 0), region("Region 1", 0)));
        assertEquals(2, ambiguous.reviewRequired().size());
        assertEquals(0, ambiguous.dataset().progress().completed());
    }

    private TaskState changed(TaskState state, TaskCommand command) {
        return assertInstanceOf(TransitionResult.Changed.class, TaskTransitions.apply(state, command, actor, time)).state();
    }
    private Progress progress(int completed, int total) {
        return Progress.calculate(java.util.stream.IntStream.range(0, total).mapToObj(i -> {
            TaskState state = TaskState.empty(new RegionTaskId(UUID.randomUUID()));
            return i < completed ? changed(state, new TaskCommand.SetDone(true)) : state;
        }).toList());
    }
    static MaterialDefinition material(String id, long count) { return new MaterialDefinition(new MaterialTaskId(id), count, count, 0); }
    static RegionDescriptor region(String name, int x) { return new RegionDescriptor(name, new Vector3(x, 0, 0), new Vector3(2, 2, 2)); }
}
