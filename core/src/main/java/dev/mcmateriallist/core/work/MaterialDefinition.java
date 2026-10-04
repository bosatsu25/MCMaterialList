package dev.mcmateriallist.core.work;

import java.util.Objects;

public record MaterialDefinition(MaterialTaskId id, long total, long missing, long available) {
    public MaterialDefinition {
        Objects.requireNonNull(id, "id");
        if (total < 0 || missing < 0 || available < 0) throw new IllegalArgumentException("Negative material count");
    }
}
