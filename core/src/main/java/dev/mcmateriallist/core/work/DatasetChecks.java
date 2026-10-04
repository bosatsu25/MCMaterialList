package dev.mcmateriallist.core.work;

import dev.mcmateriallist.core.LocalPlacementId;
import java.util.Map;
import java.util.Objects;

final class DatasetChecks {
    private DatasetChecks() {}
    static <I extends TaskId, D> void validate(DatasetId id, LocalPlacementId placement, long generation,
        Map<I, D> definitions, Map<I, TaskState> states, Map<I, ArchivedTask<D>> archived) {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(placement, "placement");
        if (generation < 0 || !definitions.keySet().equals(states.keySet())) throw new IllegalArgumentException("Invalid dataset state coverage");
        states.forEach((key, state) -> { if (!key.equals(state.taskId())) throw new IllegalArgumentException("Task state identity mismatch"); });
        archived.forEach((key, task) -> {
            if (states.containsKey(key) || !key.equals(task.state().taskId())) throw new IllegalArgumentException("Invalid archived identity");
        });
    }
    static long next(long generation) { return Math.incrementExact(generation); }
}
