package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.TaskState;
import java.util.UUID;

/** Shared pure policy for authority and client admission. */
public final class ProjectPermissions {
    private ProjectPermissions() {}
    public static boolean allows(ProjectSnapshot snapshot, UUID actor, TaskState row, SharedCommand.Intent intent) {
        return snapshot != null && row != null && intent != null && snapshot.canWrite(actor)
            && (snapshot.canAdminister(actor) || memberAllows(actor, row, intent));
    }
    static boolean privileged(SharedCommand.Intent intent) {
        return intent instanceof SharedCommand.Assign || intent instanceof SharedCommand.ClearAssignment;
    }
    /** Original-row evidence for durable replay, independent of the actor's current role. */
    static boolean memberAllows(UUID actor, TaskState row, SharedCommand.Intent intent) {
        if (actor == null || row == null || intent == null || privileged(intent)) return false;
        if (intent instanceof SharedCommand.Claim) return row.assignee() == null;
        if (actor.equals(row.assignee())) return true;
        return row.assignee() == null && !row.done() && intent instanceof SharedCommand.SetDone done && done.done();
    }
}
