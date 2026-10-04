package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.work.*;

public record RefreshResult(StoreResult materials, StoreResult regions,
    Reconciliation<MaterialWorkDataset, MaterialTaskId> materialChanges,
    Reconciliation<RegionWorkDataset, RegionTaskId> regionChanges) {}
