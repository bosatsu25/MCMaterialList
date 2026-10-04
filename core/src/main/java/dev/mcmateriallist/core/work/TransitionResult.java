package dev.mcmateriallist.core.work;

public sealed interface TransitionResult {
    record Changed(TaskState state) implements TransitionResult {}
    record Unchanged(TaskState state) implements TransitionResult {}
    record Rejected(Reason reason) implements TransitionResult {}
    enum Reason { ALREADY_ASSIGNED, NOT_ASSIGNEE, INVALID_INPUT, VERSION_EXHAUSTED, UNKNOWN_TASK, REVIEW_REQUIRED }
}
