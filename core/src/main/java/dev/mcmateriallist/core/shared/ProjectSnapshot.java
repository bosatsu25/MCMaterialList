package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.*;
import java.util.*;

public record ProjectSnapshot(ProjectScope scope, String name, UUID creator, Set<UUID> viewers,
                              long projectRevision, SharedManifest manifest, Map<TaskId, TaskState> rows,
                              Set<TaskId> needsReconfirmation) {
    public ProjectSnapshot {
        Objects.requireNonNull(scope); Objects.requireNonNull(creator); Objects.requireNonNull(manifest);
        if (name == null || name.isBlank() || !TextLimits.valid(name, 256) || projectRevision < 0)
            throw new IllegalArgumentException("Invalid project");
        viewers = Set.copyOf(viewers); rows = Map.copyOf(rows); needsReconfirmation = Set.copyOf(needsReconfirmation);
        SharedLimits.count(viewers.size() + 1, SharedLimits.PARTICIPANTS);
        if (viewers.contains(creator) || !rows.keySet().equals(manifest.taskIds())
            || !rows.keySet().containsAll(needsReconfirmation)
            || rows.entrySet().stream().anyMatch(e -> !e.getKey().equals(e.getValue().taskId())))
            throw new IllegalArgumentException("Inconsistent project");
    }
    public boolean canRead(UUID actor) { return creator.equals(actor) || viewers.contains(actor); }
    public boolean canWrite(UUID actor) { return creator.equals(actor); }
    public Progress materialProgress() { return progress(true); }
    public Progress regionProgress() { return progress(false); }
    private Progress progress(boolean materials) {
        List<TaskState> selected = rows.values().stream().filter(r -> (r.taskId() instanceof MaterialTaskId) == materials).toList();
        return Progress.calculate(selected);
    }
}
