package dev.mcmateriallist.core.work;

import java.util.Objects;

public record RegionDefinition(RegionTaskId id, RegionDescriptor descriptor) {
    public RegionDefinition { Objects.requireNonNull(id, "id"); Objects.requireNonNull(descriptor, "descriptor"); }
}
