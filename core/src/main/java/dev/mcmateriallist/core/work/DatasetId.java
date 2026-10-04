package dev.mcmateriallist.core.work;

import dev.mcmateriallist.core.LocalPlacementId;
import java.util.Objects;
import java.util.UUID;

public record DatasetId(UUID value) {
    public DatasetId { Objects.requireNonNull(value, "value"); }
    public static DatasetId create() { return new DatasetId(UUID.randomUUID()); }
    public static DatasetId parse(String text) { return new DatasetId(LocalPlacementId.parse(text).value()); }
    @Override public String toString() { return value.toString(); }
}
