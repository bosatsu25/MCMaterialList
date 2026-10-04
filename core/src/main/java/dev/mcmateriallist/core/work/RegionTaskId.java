package dev.mcmateriallist.core.work;

import dev.mcmateriallist.core.LocalPlacementId;
import java.util.Objects;
import java.util.UUID;

public record RegionTaskId(UUID value) implements TaskId {
    public RegionTaskId { Objects.requireNonNull(value, "value"); }
    public static RegionTaskId create() { return new RegionTaskId(UUID.randomUUID()); }
    public static RegionTaskId parse(String text) { return new RegionTaskId(LocalPlacementId.parse(text).value()); }
    @Override public String externalForm() { return value.toString(); }
}
