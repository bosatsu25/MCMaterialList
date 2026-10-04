package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.work.WorkDataset;

/** A recovery candidate is never a writable current dataset until explicit recover(). */
public record StoreResult(StoreStatus status, WorkDataset dataset) {
    public static StoreResult failure(StoreStatus status) { return new StoreResult(status, null); }
    public boolean ok() { return status == StoreStatus.OK; }
}
