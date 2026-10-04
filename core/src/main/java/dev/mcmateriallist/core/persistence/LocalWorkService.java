package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.work.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Lifecycle-owned serialized I/O; disk is authoritative, not a global map. */
public final class LocalWorkService implements AutoCloseable {
    private final LocalDatasetStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MCMaterialList local persistence"); thread.setDaemon(false); return thread;
    });
    public LocalWorkService(Path root) { store = new LocalDatasetStore(root); }

    public CompletableFuture<RefreshResult> refresh(WorkSnapshot snapshot) {
        return submit(() -> refreshNow(snapshot), () -> failedRefresh(StoreStatus.CLOSED));
    }
    public CompletableFuture<MaterialRefreshResult> refreshMaterials(WorkSnapshot snapshot) {
        return submit(() -> refreshMaterialsNow(snapshot),
            () -> new MaterialRefreshResult(StoreResult.failure(StoreStatus.CLOSED), null));
    }
    public CompletableFuture<RegionRefreshResult> refreshRegions(WorkSnapshot snapshot) {
        return submit(() -> refreshRegionsNow(snapshot),
            () -> new RegionRefreshResult(StoreResult.failure(StoreStatus.CLOSED), null));
    }
    /** Deliberately accept the active NEW definition, never transfer archived work. */
    public CompletableFuture<UpdateResult> acknowledgeRegion(LocalPlacementId placement, RegionTaskId task) {
        return submit(() -> acknowledgeRegionNow(placement, task),
            () -> new UpdateResult(StoreResult.failure(StoreStatus.CLOSED), null));
    }
    public CompletableFuture<UpdateResult> update(LocalPlacementId placement, TaskId task, TaskCommand command, UUID actor, Instant time) {
        return submit(() -> updateNow(placement, task, command, actor, time), () -> new UpdateResult(StoreResult.failure(StoreStatus.CLOSED), null));
    }
    public CompletableFuture<StoreResult> load(LocalPlacementId placement, DatasetKind kind) {
        return submit(() -> store.load(placement, kind), () -> StoreResult.failure(StoreStatus.CLOSED));
    }
    public CompletableFuture<StoreResult> recover(LocalPlacementId placement, DatasetKind kind) {
        return submit(() -> store.recover(placement, kind), () -> StoreResult.failure(StoreStatus.CLOSED));
    }
    private RefreshResult refreshNow(WorkSnapshot snapshot) {
        MaterialRefreshResult material = refreshMaterialsNow(snapshot);
        RegionRefreshResult region = refreshRegionsNow(snapshot);
        return new RefreshResult(material.storage(), region.storage(), material.reconciliation(), region.reconciliation());
    }
    private RegionRefreshResult refreshRegionsNow(WorkSnapshot snapshot) {
        StoreResult region = store.load(snapshot.placementId(), DatasetKind.REGIONS);
        Reconciliation<RegionWorkDataset, RegionTaskId> regionChanges = null;
        // Each side can succeed independently; a corrupt region never resets or blocks valid material work.
        try {
            if (region.ok() || region.status() == StoreStatus.MISSING) {
                RegionWorkDataset before = region.ok() ? (RegionWorkDataset) region.dataset() : RegionWorkDataset.create(snapshot.placementId());
                regionChanges = before.reconcile(snapshot.regions());
                if (!region.ok() || regionChanges.dataset() != before) region = store.save(regionChanges.dataset(), region.ok() ? before.generation() : -1);
            }
        } catch (IllegalArgumentException | ArithmeticException exception) { region = StoreResult.failure(StoreStatus.INVALID_DEFINITION); }
        return new RegionRefreshResult(region, regionChanges);
    }
    private UpdateResult acknowledgeRegionNow(LocalPlacementId placement, RegionTaskId task) {
        StoreResult loaded = store.load(placement, DatasetKind.REGIONS);
        if (!loaded.ok()) return new UpdateResult(loaded, null);
        RegionWorkDataset before = (RegionWorkDataset) loaded.dataset();
        if (task == null || !before.states().containsKey(task))
            return new UpdateResult(loaded, new TransitionResult.Rejected(task == null ? TransitionResult.Reason.INVALID_INPUT : TransitionResult.Reason.UNKNOWN_TASK));
        try {
            RegionWorkDataset accepted = before.acknowledge(task);
            if (accepted == before) return new UpdateResult(loaded, new TransitionResult.Unchanged(before.states().get(task)));
            StoreResult saved = store.save(accepted, before.generation());
            return new UpdateResult(saved, saved.ok() ? new TransitionResult.Changed(accepted.states().get(task)) : null);
        } catch (ArithmeticException exception) {
            return new UpdateResult(loaded, new TransitionResult.Rejected(TransitionResult.Reason.VERSION_EXHAUSTED));
        }
    }
    private MaterialRefreshResult refreshMaterialsNow(WorkSnapshot snapshot) {
        StoreResult stored = store.load(snapshot.placementId(), DatasetKind.MATERIALS);
        Reconciliation<MaterialWorkDataset, MaterialTaskId> changes = null;
        try {
            if (stored.ok() || stored.status() == StoreStatus.MISSING) {
                MaterialWorkDataset before = stored.ok() ? (MaterialWorkDataset) stored.dataset() : MaterialWorkDataset.create(snapshot.placementId());
                changes = before.reconcile(snapshot.materials());
                if (!stored.ok() || changes.dataset() != before)
                    stored = store.save(changes.dataset(), stored.ok() ? before.generation() : -1);
            }
        } catch (IllegalArgumentException | ArithmeticException exception) {
            stored = StoreResult.failure(StoreStatus.INVALID_DEFINITION);
        }
        return new MaterialRefreshResult(stored, changes);
    }
    private UpdateResult updateNow(LocalPlacementId placement, TaskId task, TaskCommand command, UUID actor, Instant time) {
        DatasetKind kind = task instanceof MaterialTaskId ? DatasetKind.MATERIALS : DatasetKind.REGIONS;
        StoreResult loaded = store.load(placement, kind); if (!loaded.ok()) return new UpdateResult(loaded, null);
        DatasetMutation<? extends WorkDataset> mutation;
        if (task instanceof MaterialTaskId material) mutation = ((MaterialWorkDataset) loaded.dataset()).apply(material, command, actor, time);
        else if (task instanceof RegionTaskId region) mutation = ((RegionWorkDataset) loaded.dataset()).apply(region, command, actor, time);
        else return new UpdateResult(loaded, new TransitionResult.Rejected(TransitionResult.Reason.INVALID_INPUT));
        if (!(mutation.result() instanceof TransitionResult.Changed)) return new UpdateResult(loaded, mutation.result());
        StoreResult saved = store.save(mutation.dataset(), loaded.dataset().generation());
        return new UpdateResult(saved, saved.ok() ? mutation.result() : null);
    }
    private <T> CompletableFuture<T> submit(Supplier<T> operation, Supplier<T> closed) {
        try { return CompletableFuture.supplyAsync(operation, io); }
        catch (RejectedExecutionException exception) { return CompletableFuture.completedFuture(closed.get()); }
    }
    private static RefreshResult failedRefresh(StoreStatus status) { return new RefreshResult(StoreResult.failure(status), StoreResult.failure(status), null, null); }
    @Override public void close() {
        io.shutdown();
        try {
            // Shutdown is a lifecycle boundary, not a per-frame wait. Never cancel an in-flight replace.
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Local persistence did not finish during shutdown");
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("Local persistence shutdown interrupted", exception); }
    }
}
