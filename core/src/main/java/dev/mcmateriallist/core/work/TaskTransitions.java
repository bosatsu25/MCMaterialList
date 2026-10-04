package dev.mcmateriallist.core.work;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class TaskTransitions {
    private TaskTransitions() {}
    public static TransitionResult apply(TaskState state, TaskCommand command, UUID actor, Instant time) {
        Objects.requireNonNull(state, "state");
        if (command == null || actor == null || time == null) return new TransitionResult.Rejected(TransitionResult.Reason.INVALID_INPUT);
        UUID assignee = state.assignee(); boolean done = state.done(); String note = state.note();
        UUID by = state.completedBy(); Instant at = state.completedAt();
        if (command instanceof TaskCommand.Claim) {
            if (assignee != null && !assignee.equals(actor)) return new TransitionResult.Rejected(TransitionResult.Reason.ALREADY_ASSIGNED);
            assignee = actor;
        } else if (command instanceof TaskCommand.Release) {
            if (assignee != null && !assignee.equals(actor)) return new TransitionResult.Rejected(TransitionResult.Reason.NOT_ASSIGNEE);
            assignee = null;
        } else if (command instanceof TaskCommand.Assign assign) {
            if (assign.assignee() == null) return new TransitionResult.Rejected(TransitionResult.Reason.INVALID_INPUT);
            assignee = assign.assignee();
        } else if (command instanceof TaskCommand.SetNote edit) {
            if (!TextLimits.valid(edit.note(), TextLimits.NOTE_BYTES)) return new TransitionResult.Rejected(TransitionResult.Reason.INVALID_INPUT);
            note = edit.note();
        } else if (command instanceof TaskCommand.SetDone edit) {
            if (edit.done() != done) {
                done = edit.done(); by = done ? actor : null; at = done ? time : null;
                if (done && assignee == null) assignee = actor;
            }
        }
        if (Objects.equals(assignee, state.assignee()) && done == state.done() && note.equals(state.note())) return new TransitionResult.Unchanged(state);
        if (state.rowVersion() == Long.MAX_VALUE) return new TransitionResult.Rejected(TransitionResult.Reason.VERSION_EXHAUSTED);
        return new TransitionResult.Changed(new TaskState(state.taskId(), assignee, done, note, by, at, state.rowVersion() + 1));
    }
}
