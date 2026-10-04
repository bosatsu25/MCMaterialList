package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.work.MaterialTaskId;
import dev.mcmateriallist.core.work.MaterialWorkDataset;
import dev.mcmateriallist.core.work.Reconciliation;

/** Reconciliation is diagnostic only: publish its dataset only when storage is OK. */
public record MaterialRefreshResult(StoreResult storage,
    Reconciliation<MaterialWorkDataset, MaterialTaskId> reconciliation) {}
