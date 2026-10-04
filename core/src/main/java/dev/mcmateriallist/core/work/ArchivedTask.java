package dev.mcmateriallist.core.work;

import java.util.Objects;

public record ArchivedTask<D>(D definition, TaskState state) {
    public ArchivedTask { Objects.requireNonNull(definition, "definition"); Objects.requireNonNull(state, "state"); }
}
