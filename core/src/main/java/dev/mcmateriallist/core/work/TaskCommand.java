package dev.mcmateriallist.core.work;

import java.util.UUID;

public sealed interface TaskCommand {
    record Claim() implements TaskCommand {}
    record Release() implements TaskCommand {}
    record Assign(UUID assignee) implements TaskCommand {}
    record SetDone(boolean done) implements TaskCommand {}
    record SetNote(String note) implements TaskCommand {}
}
