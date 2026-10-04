package dev.mcmateriallist.core.work;

import dev.mcmateriallist.core.LocalPlacementId;

/** Actual boundary consumed by the local snapshot store. */
public sealed interface WorkDataset permits MaterialWorkDataset, RegionWorkDataset {
    DatasetId id();
    LocalPlacementId placementId();
    long generation();
    DatasetKind kind();
    Progress progress();
}
