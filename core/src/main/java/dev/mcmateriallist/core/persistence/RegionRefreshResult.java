package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.work.Reconciliation;
import dev.mcmateriallist.core.work.RegionTaskId;
import dev.mcmateriallist.core.work.RegionWorkDataset;

public record RegionRefreshResult(StoreResult storage, Reconciliation<RegionWorkDataset, RegionTaskId> reconciliation) {}
