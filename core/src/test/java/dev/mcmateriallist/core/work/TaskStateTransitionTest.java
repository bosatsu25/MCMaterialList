package dev.mcmateriallist.core.work;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * State transition testing ST-T of docs/test-design.md (JSTQB FL v4.0, 4.2.4) for TaskTransitions.apply, design section 6.1.
 * States are the row's (assignee, done) seen from the acting player: S0 unassigned open, S1 mine open, S2 mine done,
 * S3 unassigned done, S4 someone else's open, S5 someone else's done. Events: Claim, Release, SetDone(true), SetDone(false)
 * (undo), SetNote, Assign(me), Assign(other). The literal table below has all 6 x 7 = 42 cells, so the test reaches
 * all-transitions coverage (every valid transition and every invalid/no-op cell). Role checks are not part of this function;
 * they are DT-P (PermissionDecisionTableTest).
 */
class TaskStateTransitionTest {
    private static final UUID ME = new UUID(0, 1), OTHER = new UUID(0, 2), EARLIER = new UUID(0, 3);
    private static final Instant BEFORE = Instant.parse("2026-11-01T00:00:00Z"), NOW = Instant.parse("2026-11-09T00:00:00Z");
    private static final MaterialTaskId STONE = new MaterialTaskId("minecraft:stone");

    enum S { S0, S1, S2, S3, S4, S5 }
    enum E { CLAIM, RELEASE, DONE, UNDO, NOTE, ASSIGN_ME, ASSIGN_OTHER }

    /** Target state, "=" for Unchanged, or "!REASON" for Rejected. Columns in E order. */
    private static final Map<S, List<String>> TABLE = Map.of(
        S.S0, List.of("S1", "=", "S2", "=", "S0", "S1", "S4"),
        S.S1, List.of("=", "S0", "S2", "=", "S1", "=", "S4"),
        S.S2, List.of("=", "S3", "=", "S1", "S2", "=", "S5"),
        S.S3, List.of("S2", "=", "=", "S0", "S3", "S2", "S5"),
        S.S4, List.of("!ALREADY_ASSIGNED", "!NOT_ASSIGNEE", "S5", "=", "S4", "S1", "="),
        S.S5, List.of("!ALREADY_ASSIGNED", "!NOT_ASSIGNEE", "=", "S4", "S5", "S2", "="));

    private static TaskState state(S s) {
        UUID assignee = switch (s) { case S1, S2 -> ME; case S4, S5 -> OTHER; default -> null; };
        boolean done = s == S.S2 || s == S.S3 || s == S.S5;
        return new TaskState(STONE, assignee, done, "kept", done ? EARLIER : null, done ? BEFORE : null, 7);
    }
    private static S classify(TaskState state) {
        boolean done = state.done(); UUID a = state.assignee();
        if (a == null) return done ? S.S3 : S.S0;
        if (a.equals(ME)) return done ? S.S2 : S.S1;
        return done ? S.S5 : S.S4;
    }
    private static TaskCommand command(E e) {
        return switch (e) {
            case CLAIM -> new TaskCommand.Claim();
            case RELEASE -> new TaskCommand.Release();
            case DONE -> new TaskCommand.SetDone(true);
            case UNDO -> new TaskCommand.SetDone(false);
            case NOTE -> new TaskCommand.SetNote("changed");
            case ASSIGN_ME -> new TaskCommand.Assign(ME);
            case ASSIGN_OTHER -> new TaskCommand.Assign(OTHER);
        };
    }

    /** ST-T-1..42: every cell of the state table, with the field invariants of each changed transition. */
    @Test void stT_allTransitionsOfTheTaskTable() {
        int cells = 0, valid = 0;
        for (S from : S.values()) for (E event : E.values()) {
            String expected = TABLE.get(from).get(event.ordinal()); String label = from + " --" + event + "--> " + expected;
            TaskState before = state(from);
            TransitionResult result = TaskTransitions.apply(before, command(event), ME, NOW);
            cells++;
            if (expected.equals("=")) {
                assertEquals(before, assertInstanceOf(TransitionResult.Unchanged.class, result, label).state(), label);
            } else if (expected.startsWith("!")) {
                assertEquals(TransitionResult.Reason.valueOf(expected.substring(1)), assertInstanceOf(TransitionResult.Rejected.class, result, label).reason(), label);
            } else {
                valid++;
                TaskState after = assertInstanceOf(TransitionResult.Changed.class, result, label).state();
                assertEquals(S.valueOf(expected), classify(after), label);
                assertEquals(before.rowVersion() + 1, after.rowVersion(), label + " version");
                assertEquals(event == E.NOTE ? "changed" : "kept", after.note(), label + " note");
                if (after.done() == before.done()) {
                    assertEquals(before.completedBy(), after.completedBy(), label + " completion metadata must be kept");
                    assertEquals(before.completedAt(), after.completedAt(), label);
                } else if (after.done()) {
                    assertEquals(ME, after.completedBy(), label); assertEquals(NOW, after.completedAt(), label);
                } else {
                    assertNull(after.completedBy(), label); assertNull(after.completedAt(), label);
                }
            }
        }
        assertEquals(42, cells); assertEquals(24, valid);
    }

    /** ST-T-43: an undo followed by a repeated SetDone(true) completes again; SetDone is idempotent, never a toggle (design 6.1). */
    @Test void stT_setDoneIsIdempotentAcrossUndo() {
        TaskState s = state(S.S1);
        TaskState done = ((TransitionResult.Changed) TaskTransitions.apply(s, new TaskCommand.SetDone(true), ME, NOW)).state();
        assertInstanceOf(TransitionResult.Unchanged.class, TaskTransitions.apply(done, new TaskCommand.SetDone(true), ME, NOW.plusSeconds(1)));
        TaskState undone = ((TransitionResult.Changed) TaskTransitions.apply(done, new TaskCommand.SetDone(false), ME, NOW)).state();
        assertEquals(S.S1, classify(undone)); assertEquals(s.rowVersion() + 2, undone.rowVersion());
    }

    /** ST-T-44: invalid events in any state: missing actor/time/command and Assign(null) are rejected without a transition; same note is a no-op. */
    @Test void stT_invalidEventsNeverTransition() {
        for (S from : S.values()) {
            TaskState before = state(from);
            assertEquals(TransitionResult.Reason.INVALID_INPUT, ((TransitionResult.Rejected) TaskTransitions.apply(before, new TaskCommand.Assign(null), ME, NOW)).reason());
            assertEquals(TransitionResult.Reason.INVALID_INPUT, ((TransitionResult.Rejected) TaskTransitions.apply(before, new TaskCommand.Claim(), null, NOW)).reason());
            assertEquals(TransitionResult.Reason.INVALID_INPUT, ((TransitionResult.Rejected) TaskTransitions.apply(before, new TaskCommand.Claim(), ME, null)).reason());
            assertEquals(TransitionResult.Reason.INVALID_INPUT, ((TransitionResult.Rejected) TaskTransitions.apply(before, null, ME, NOW)).reason());
            assertInstanceOf(TransitionResult.Unchanged.class, TaskTransitions.apply(before, new TaskCommand.SetNote("kept"), ME, NOW), from.name());
        }
    }
}
