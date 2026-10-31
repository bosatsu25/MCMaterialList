package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.work.*;
import java.util.*;

/** One client save unit: independent bindings, confirmed caches and original command journal. */
public record SharedClientArchive(long generation, LocalPlacementId placement,
    Binding materials, Binding regions, List<Operation> operations, boolean recoveredJournalUncertain) {
    public static final int MAX_OPERATIONS = 128;
    public record Reference(UUID bindingId, UUID actor, ProjectScope scope) {
        public Reference { Objects.requireNonNull(bindingId); Objects.requireNonNull(actor); Objects.requireNonNull(scope); }
    }
    public record Cache(ProjectSnapshot snapshot, long cursor) {
        public Cache {
            Objects.requireNonNull(snapshot);
            if (cursor < 0 || cursor != snapshot.projectRevision()) throw new IllegalArgumentException("Invalid contiguous cache cursor");
        }
    }
    /**
     * A purely local region copy made from a synchronized shared snapshot (#11). It never merges back: rejoin exports it
     * and installs a fresh server snapshot. Its ID is neither the original local dataset nor the shared region list.
     */
    public record Detached(RegionWorkDataset dataset, ProjectScope from) {
        public Detached {
            Objects.requireNonNull(dataset); Objects.requireNonNull(from);
            if (dataset.id().value().equals(from.regionListId())) throw new IllegalArgumentException("Detached copy reuses the shared list identity");
        }
    }
    /**
     * localDataset always refers to the original local store, never to a shared list ID. Exactly one state (I1):
     * SHARED (shared, optional cache), DETACHED (detached only) or ORIGINAL (neither).
     */
    public record Binding(DatasetId localDataset, Reference shared, Cache cache, Detached detached) {
        public Binding {
            if (cache != null && (shared == null || !shared.scope().equals(cache.snapshot().scope())
                || !cache.snapshot().canRead(shared.actor()))) throw new IllegalArgumentException("Invalid private cache binding");
            if (detached != null && (shared != null || cache != null || detached.dataset().id().equals(localDataset)))
                throw new IllegalArgumentException("Invalid detached binding");
        }
        public Binding(DatasetId localDataset, Reference shared, Cache cache) { this(localDataset, shared, cache, null); }
        public boolean isDetached() { return detached != null; }
        /** Every cache rebuild goes through here so no other field (notably a detached copy) is ever dropped. */
        public Binding withCache(Cache next) { return new Binding(localDataset, shared, next, detached); }
    }
    public record Confirmation(SharedEvents.Status status, long projectRevision) {
        public Confirmation {
            Objects.requireNonNull(status);
            if (projectRevision < 0) throw new IllegalArgumentException("Invalid confirmed revision");
        }
    }
    public record Operation(DatasetKind kind, Reference original, UUID connection,
        SharedCommand command, Confirmation confirmation) {
        public Operation {
            Objects.requireNonNull(kind); Objects.requireNonNull(original); Objects.requireNonNull(connection); Objects.requireNonNull(command);
            if (!original.scope().equals(command.scope()) || (kind == DatasetKind.MATERIALS) != (command.taskId() instanceof MaterialTaskId))
                throw new IllegalArgumentException("Invalid original command binding");
        }
    }
    public SharedClientArchive {
        if (generation < 0) throw new IllegalArgumentException("Invalid client generation");
        Objects.requireNonNull(placement); Objects.requireNonNull(materials); Objects.requireNonNull(regions);
        if (materials.detached() != null || (regions.detached() != null && !regions.detached().dataset().placementId().equals(placement)))
            throw new IllegalArgumentException("Invalid detached placement or kind");
        operations = List.copyOf(operations);
        if (operations.size() > MAX_OPERATIONS || operations.stream().map(operation -> operation.command().operationId()).distinct().count() != operations.size())
            throw new IllegalArgumentException("Invalid client journal capacity or identity");
    }
    public SharedClientArchive(long generation, LocalPlacementId placement, Binding materials, Binding regions, List<Operation> operations) {
        this(generation, placement, materials, regions, operations, false);
    }
    public static SharedClientArchive empty(LocalPlacementId placement) {
        return new SharedClientArchive(0, placement, new Binding(null, null, null), new Binding(null, null, null), List.of());
    }
    public Binding binding(DatasetKind kind) { return kind == DatasetKind.MATERIALS ? materials : regions; }
    public SharedClientArchive replace(DatasetKind kind, Binding binding) {
        return new SharedClientArchive(Math.incrementExact(generation), placement,
            kind == DatasetKind.MATERIALS ? binding : materials, kind == DatasetKind.REGIONS ? binding : regions, operations, recoveredJournalUncertain);
    }
    public SharedClientArchive journal(List<Operation> next) {
        return new SharedClientArchive(Math.incrementExact(generation), placement, materials, regions, next, recoveredJournalUncertain);
    }
}
