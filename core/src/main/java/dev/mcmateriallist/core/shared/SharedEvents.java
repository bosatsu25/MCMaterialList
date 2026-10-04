package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.TaskState;
import java.util.*;

public final class SharedEvents {
    private SharedEvents() {}
    public enum Status { OK, DENIED, MISSING, CONFLICT, INVALID_INPUT, SAVE_FAILED, UNAVAILABLE, CAPACITY, CLOSED }
    public record CommandResult(ProjectScope scope, UUID operationId, Status status, long projectRevision, TaskState row) {
        public CommandResult { Objects.requireNonNull(scope); Objects.requireNonNull(operationId); Objects.requireNonNull(status);
            if (projectRevision < 0) throw new IllegalArgumentException("Invalid revision"); }
    }
    public sealed interface Event permits ProjectDelta, ProjectInvalidated, AccessRevoked {}
    public record ProjectDelta(ProjectScope scope, long projectRevision, long manifestVersion, TaskState row, boolean needsReconfirmation) implements Event {
        public ProjectDelta { Objects.requireNonNull(scope); Objects.requireNonNull(row);
            if (projectRevision < 1 || manifestVersion < 0) throw new IllegalArgumentException("Invalid delta"); }
    }
    public record ProjectInvalidated(ProjectScope scope, long projectRevision, long manifestVersion) implements Event {
        public ProjectInvalidated { Objects.requireNonNull(scope);
            if (projectRevision < 1 || manifestVersion < 0) throw new IllegalArgumentException("Invalid invalidation"); }
    }
    /** No name, manifest, note, row or participant collection. */
    public record AccessRevoked(ProjectScope scope, UUID recipient) implements Event {
        public AccessRevoked { Objects.requireNonNull(scope); Objects.requireNonNull(recipient); }
    }
    public record Outcome(CommandResult result, List<Event> events) {
        public Outcome { Objects.requireNonNull(result); events = List.copyOf(events); }
    }
    public record Reply<T>(Status status, T value, List<Event> events) {
        public Reply { Objects.requireNonNull(status); events = List.copyOf(events); }
        public static <T> Reply<T> of(Status status, T value) { return new Reply<>(status, value, List.of()); }
    }
}
