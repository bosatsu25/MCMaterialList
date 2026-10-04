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
        dataset = dataset.apply(stone, new TaskCommand.SetDone(true), actor, time).dataset();
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
        dataset = dataset.apply(second, new TaskCommand.SetDone(true), actor, time).dataset();
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

    @Test void datasetCommandsRejectUnknownTasksAndRequireRegionReevaluation() {
        var empty = MaterialWorkDataset.create(LocalPlacementId.create());
        var result = empty.apply(stone, new TaskCommand.SetDone(true), actor, time);
        assertEquals(empty, result.dataset());
        assertEquals(TransitionResult.Reason.UNKNOWN_TASK, ((TransitionResult.Rejected) result.result()).reason());
        var dataset = RegionWorkDataset.create(LocalPlacementId.create()).reconcile(List.of(region("Region 1", 0))).dataset();
        dataset = dataset.reconcile(List.of(region("Region 1", 1))).dataset();
        RegionTaskId key = dataset.definitions().keySet().iterator().next();
        assertEquals(TransitionResult.Reason.REVIEW_REQUIRED, ((TransitionResult.Rejected) dataset.apply(key, new TaskCommand.SetDone(true), actor, time).result()).reason());
        dataset = dataset.acknowledge(key);
        assertInstanceOf(TransitionResult.Changed.class, dataset.apply(key, new TaskCommand.SetDone(true), actor, time).result());
    }

    @Test void repeatedAmbiguousRefreshDoesNotInventMoreTasksOrDiscardReview() {
        var duplicate = List.of(region("Repeated", 0), region("Repeated", 0));
        var dataset = RegionWorkDataset.create(LocalPlacementId.create()).reconcile(duplicate).dataset();
        assertEquals(2, dataset.reviewRequired().size());
        assertSame(dataset, dataset.reconcile(duplicate).dataset());
        var edited = dataset.reconcile(List.of(region("Repeated", 1))).dataset();
        assertEquals(1, edited.activeReviewRequired().size());
        assertSame(edited, edited.reconcile(List.of(region("Repeated", 1))).dataset());
        var expanded = dataset.reconcile(List.of(region("Repeated", 0), region("Repeated", 0), region("Added", 10))).dataset();
        assertTrue(expanded.definitions().keySet().containsAll(dataset.definitions().keySet()));
        assertEquals(0, expanded.archived().size());
    }

    @Test void removingAndRestoringBlockedRegionStillRequiresAcknowledgement() {
        var descriptor = region("Region 1", 1);
        var dataset = RegionWorkDataset.create(LocalPlacementId.create()).reconcile(List.of(region("Region 1", 0))).dataset().reconcile(List.of(descriptor)).dataset();
        RegionTaskId key = dataset.definitions().keySet().iterator().next();
        dataset = dataset.reconcile(List.of()).dataset();
        assertEquals(0, dataset.activeReviewRequired().size());
        dataset = dataset.reconcile(List.of(descriptor)).dataset();
        assertEquals(TransitionResult.Reason.REVIEW_REQUIRED, ((TransitionResult.Rejected) dataset.apply(key, new TaskCommand.SetDone(true), actor, time).result()).reason());
    }

    @Test void versionExhaustionAndImmutableCollectionsAreExplicit() {
        var full = new TaskState(stone, null, false, "", null, null, Long.MAX_VALUE);
        assertEquals(TransitionResult.Reason.VERSION_EXHAUSTED, ((TransitionResult.Rejected) TaskTransitions.apply(full, new TaskCommand.SetDone(true), actor, time)).reason());
        assertInstanceOf(TransitionResult.Unchanged.class, TaskTransitions.apply(full, new TaskCommand.SetDone(false), actor, time));
        var dataset = MaterialWorkDataset.create(LocalPlacementId.create()).reconcile(List.of(material("minecraft:stone", 1))).dataset();
        assertThrows(UnsupportedOperationException.class, () -> dataset.states().clear());
        assertThrows(UnsupportedOperationException.class, () -> dataset.definitions().clear());
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
