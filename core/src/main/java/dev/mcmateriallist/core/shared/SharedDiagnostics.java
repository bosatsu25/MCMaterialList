package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.TaskId;
import java.util.Objects;
import java.util.UUID;

/**
 * Design §13 investigation traces for the shared authority (Phase 5A). A trace correlates one serialized operation by
 * projectId, taskId, operationId, intent, result, revision and timing. By construction it carries no note text, project or
 * region names, material counts, actor or target player identities, or inventory contents: callers can only pass the fields
 * of {@link Trace}, and {@link #intent} reduces a command to its kind.
 */
public final class SharedDiagnostics {
    /** Operations at or above this queue + execution time are reported as non-routine. */
    public static final long SLOW_NANOS = 250_000_000L;
    public static final String PREFIX = "MCMaterialList trace";
    public enum Operation { CREATE, DISCOVER, JOIN, COMMAND, MEMBERSHIP, MANIFEST }
    /**
     * result is null when the operation threw. revision is the project revision the reply reports (-1 when none), rowVersion the
     * confirmed row version of a command (-1 when none), manifestVersion the manifest version after a definition change (-1 otherwise).
     */
    public record Trace(Operation operation, UUID projectId, TaskId taskId, UUID operationId, String intent, SharedEvents.Status result,
                        long revision, long rowVersion, long manifestVersion, long queuedNanos, long durationNanos) {
        public Trace { Objects.requireNonNull(operation); Objects.requireNonNull(intent); }
        /** OK commands and discovery that were not slow; everything else (joins, administration, refusals, failures) is not. */
        public boolean routine() {
            return (operation == Operation.COMMAND || operation == Operation.DISCOVER) && result == SharedEvents.Status.OK
                && queuedNanos + durationNanos < SLOW_NANOS;
        }
        /** One stable key=value line, e.g. for an INFO/DEBUG log record. */
        public String format() {
            return PREFIX + " op=" + operation.name().toLowerCase(java.util.Locale.ROOT)
                + " project=" + (projectId == null ? "-" : projectId)
                + " task=" + (taskId == null ? "-" : taskId.externalForm())
                + " operation=" + (operationId == null ? "-" : operationId)
                + " intent=" + intent
                + " result=" + (result == null ? "FAILED" : result.name())
                + " revision=" + revision + " rowVersion=" + rowVersion + " manifest=" + manifestVersion
                + " queuedMicros=" + queuedNanos / 1_000 + " durationMicros=" + durationNanos / 1_000;
        }
    }
    @FunctionalInterface public interface Sink { void trace(Trace trace); }
    public static final Sink NONE = trace -> {};
    private SharedDiagnostics() {}
    /** The command kind only: never a note body, note length or assignment target. */
    public static String intent(SharedCommand.Intent intent) {
        return switch (intent) {
            case SharedCommand.Claim ignored -> "Claim";
            case SharedCommand.Release ignored -> "Release";
            case SharedCommand.Assign ignored -> "Assign";
            case SharedCommand.ClearAssignment ignored -> "ClearAssignment";
            case SharedCommand.SetDone done -> done.done() ? "SetDone(true)" : "SetDone(false)";
            case SharedCommand.SetNote ignored -> "SetNote";
        };
    }
}
