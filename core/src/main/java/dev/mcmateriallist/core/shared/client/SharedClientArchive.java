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
    /** localDataset always refers to the original local store, never to a shared list ID. */
    public record Binding(DatasetId localDataset, Reference shared, Cache cache) {
        public Binding {
            if (cache != null && (shared == null || !shared.scope().equals(cache.snapshot().scope())
                || !cache.snapshot().canRead(shared.actor()))) throw new IllegalArgumentException("Invalid private cache binding");
        }
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
