package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.*;
import java.util.*;

public record ProjectSnapshot(ProjectScope scope, String name, UUID creator, Map<UUID, ProjectRole> members,
                              long projectRevision, SharedManifest manifest, Map<TaskId, TaskState> rows,
                              Set<TaskId> needsReconfirmation) {
    public ProjectSnapshot {
        Objects.requireNonNull(scope); Objects.requireNonNull(creator); Objects.requireNonNull(manifest);
        if (name == null || name.isBlank() || !TextLimits.valid(name, 256) || projectRevision < 0)
            throw new IllegalArgumentException("Invalid project");
        members = Map.copyOf(members); rows = Map.copyOf(rows); needsReconfirmation = Set.copyOf(needsReconfirmation);
        SharedLimits.count(members.size(), SharedLimits.PARTICIPANTS);
        if (!members.containsValue(ProjectRole.ADMINISTRATOR) || !rows.keySet().equals(manifest.taskIds())
            || !rows.keySet().containsAll(needsReconfirmation)
            || rows.entrySet().stream().anyMatch(e -> !e.getKey().equals(e.getValue().taskId())))
            throw new IllegalArgumentException("Inconsistent project");
    }
    public ProjectSnapshot(ProjectScope scope, String name, UUID creator, Set<UUID> viewers,
                           long projectRevision, SharedManifest manifest, Map<TaskId, TaskState> rows,
                           Set<TaskId> needsReconfirmation) {
        this(scope, name, creator, legacyMembers(creator, viewers), projectRevision, manifest, rows, needsReconfirmation);
    }
    private static Map<UUID, ProjectRole> legacyMembers(UUID creator, Set<UUID> viewers) {
        if (viewers.contains(creator)) throw new IllegalArgumentException("Creator duplicated in legacy viewers");
        Map<UUID, ProjectRole> result = new HashMap<>(); result.put(creator, ProjectRole.ADMINISTRATOR);
        viewers.forEach(viewer -> result.put(viewer, ProjectRole.VIEWER)); return result;
    }
    public Set<UUID> viewers() {
        Set<UUID> result = new HashSet<>(); members.forEach((id, role) -> { if (role == ProjectRole.VIEWER) result.add(id); });
        return Set.copyOf(result);
    }
    public ProjectRole role(UUID actor) { return actor == null ? null : members.get(actor); }
    public boolean canRead(UUID actor) { return role(actor) != null; }
    public boolean canWrite(UUID actor) { return role(actor) == ProjectRole.MEMBER || canAdminister(actor); }
    public boolean canAdminister(UUID actor) { return role(actor) == ProjectRole.ADMINISTRATOR; }
    public Progress materialProgress() { return progress(true); }
    public Progress regionProgress() { return progress(false); }
    private Progress progress(boolean materials) {
        List<TaskState> selected = rows.values().stream().filter(r -> (r.taskId() instanceof MaterialTaskId) == materials).toList();
        return Progress.calculate(selected);
    }
}
