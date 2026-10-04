package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.*;
import java.util.*;

/** Actor deliberately absent; supplied separately from the authenticated transport. */
public record SharedCommand(ProjectScope scope, TaskId taskId, UUID operationId,
                            long expectedRowVersion, long expectedManifestVersion, Intent intent) {
    public sealed interface Intent permits Claim, Release, Assign, ClearAssignment, SetDone, SetNote {}
    public record Claim() implements Intent {}
    public record Release() implements Intent {}
    public record Assign(UUID target) implements Intent { public Assign { Objects.requireNonNull(target); } }
    public record ClearAssignment() implements Intent {}
    public record SetDone(boolean done) implements Intent {}
    public record SetNote(String note) implements Intent {
        public SetNote { if (!TextLimits.valid(note, TextLimits.NOTE_BYTES)) throw new IllegalArgumentException("Invalid note"); }
    }
    public SharedCommand {
        Objects.requireNonNull(scope); Objects.requireNonNull(taskId); Objects.requireNonNull(operationId); Objects.requireNonNull(intent);
        if (expectedRowVersion < 0 || expectedManifestVersion < 0) throw new IllegalArgumentException("Invalid expected version");
    }
}
