package dev.mcmateriallist.core.work;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Null assignee/completion metadata represent absence; never a fabricated player. */
public record TaskState(TaskId taskId, UUID assignee, boolean done, String note, UUID completedBy,
                        Instant completedAt, long rowVersion) {
    public TaskState {
        Objects.requireNonNull(taskId, "taskId");
        if (!TextLimits.valid(note, TextLimits.NOTE_BYTES)) throw new IllegalArgumentException("Invalid note text");
        if (rowVersion < 0) throw new IllegalArgumentException("Negative row version");
        if (done != (completedBy != null && completedAt != null)
            || (!done && (completedBy != null || completedAt != null))) {
            throw new IllegalArgumentException("Inconsistent completion metadata");
        }
    }
    public static TaskState empty(TaskId id) { return new TaskState(id, null, false, "", null, null, 0); }
}
