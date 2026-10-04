package dev.mcmateriallist.core.work;

import dev.mcmateriallist.core.LocalPlacementId;
import java.util.List;
import java.util.Objects;

/** Immutable transfer boundary; never holds a live Minecraft object. */
public record WorkSnapshot(LocalPlacementId placementId, List<MaterialDefinition> materials, List<RegionDescriptor> regions) {
    public WorkSnapshot { Objects.requireNonNull(placementId, "placementId"); materials = List.copyOf(materials); regions = List.copyOf(regions); }
}
