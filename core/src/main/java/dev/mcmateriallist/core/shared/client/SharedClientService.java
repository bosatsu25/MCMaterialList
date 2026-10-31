package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.work.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** One client lifecycle owns the controlled binding/cache/original-journal persistence queue. */
public final class SharedClientService implements AutoCloseable {
    public static final int QUEUED_WRITES = 64, UNKNOWN_OPERATIONS = 64, CONFIRMED_OPERATIONS = 64;
    private final SharedClientStore store;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUED_WRITES), task -> {
            Thread thread = new Thread(task, "mcmateriallist-shared-client-store"); thread.setDaemon(false); return thread;
        });
    public SharedClientService(Path configuredConfigRoot) { store = new SharedClientStore(configuredConfigRoot); }
    SharedClientService(Path configuredConfigRoot, SharedClientStore.WriteHook hook) { store = new SharedClientStore(configuredConfigRoot, hook); }
    public CompletableFuture<SharedClientStore.Result> load(LocalPlacementId placement) { return submit(() -> store.load(placement)); }
    public CompletableFuture<SharedClientStore.Result> recover(LocalPlacementId placement) { return submit(() -> store.recover(placement)); }
    public CompletableFuture<SharedClientStore.Result> select(LocalPlacementId placement, DatasetKind kind,
        DatasetId local, UUID actor, ProjectSnapshot snapshot, long cursor) {
        Objects.requireNonNull(kind); Objects.requireNonNull(actor); Objects.requireNonNull(snapshot);
        return submit(() -> change(placement, true, before -> {
            var original = before.binding(kind);
            // A detached copy is dropped only by rejoin, which exports it first.
            require(!original.isDetached() && (original.localDataset() == null || original.localDataset().equals(local)));
            require(snapshot.canRead(actor));
            var reference = new SharedClientArchive.Reference(UUID.randomUUID(), actor, snapshot.scope());
            return before.replace(kind, new SharedClientArchive.Binding(local, reference, new SharedClientArchive.Cache(snapshot, cursor)));
        }));
    }
    /** Select the preserved original local work; this does not copy or detach shared region work. */
    public CompletableFuture<SharedClientStore.Result> local(LocalPlacementId placement, DatasetKind kind, DatasetId local) {
        Objects.requireNonNull(kind);
        return submit(() -> change(placement, false, before -> {
            require(!before.binding(kind).isDetached() && (before.binding(kind).localDataset() == null || before.binding(kind).localDataset().equals(local)));
            return before.replace(kind, new SharedClientArchive.Binding(local, null, null));
        }));
    }
    public CompletableFuture<SharedClientStore.Result> admit(LocalPlacementId placement, DatasetKind kind,
        SharedClientArchive.Reference original, UUID actor, UUID connection, SharedCommand command) {
        Objects.requireNonNull(kind); Objects.requireNonNull(original); Objects.requireNonNull(actor);
        Objects.requireNonNull(connection); Objects.requireNonNull(command);
        return submit(() -> change(placement, false, before -> {
            var binding = before.binding(kind);
            require(!before.recoveredJournalUncertain() && original.equals(binding.shared()) && actor.equals(original.actor()) && command.scope().equals(original.scope())
                && binding.cache() != null && (kind == DatasetKind.MATERIALS) == (command.taskId() instanceof MaterialTaskId));
            var snapshot = binding.cache().snapshot(); var row = snapshot.rows().get(command.taskId());
            require(ProjectPermissions.allows(snapshot, actor, row, command.intent()) && row.rowVersion() == command.expectedRowVersion()
                && snapshot.manifest().version() == command.expectedManifestVersion());
            require(!(command.intent() instanceof SharedCommand.Assign assign) || snapshot.canRead(assign.target()));
            require(!hasUnknown(before, actor, command.scope())
                && before.operations().stream().noneMatch(operation -> operation.command().operationId().equals(command.operationId())));
            if (before.operations().stream().filter(operation -> operation.confirmation() == null).count() >= UNKNOWN_OPERATIONS)
                throw new Refused(StoreStatus.BUSY);
            List<SharedClientArchive.Operation> operations = new ArrayList<>(before.operations());
            while (operations.stream().filter(operation -> operation.confirmation() != null).count() >= CONFIRMED_OPERATIONS) {
                int oldest = 0;
                while (operations.get(oldest).confirmation() == null) oldest++;
                operations.remove(oldest);
            }
            operations.add(new SharedClientArchive.Operation(kind, original, connection, command, null));
            return before.journal(operations);
        }));
    }
    public CompletableFuture<SharedClientStore.Result> confirm(LocalPlacementId placement, UUID actor, UUID connection,
        SharedEvents.CommandResult result) {
        Objects.requireNonNull(actor); Objects.requireNonNull(connection); Objects.requireNonNull(result);
        return submit(() -> change(placement, false, before -> {
            var found = before.operations().stream().filter(operation -> operation.command().operationId().equals(result.operationId())).findFirst();
            require(found.isPresent()); var operation = found.orElseThrow();
            require(operation.original().actor().equals(actor) && operation.connection().equals(connection)
                && operation.original().scope().equals(result.scope())
                && (result.row() == null || operation.command().taskId().equals(result.row().taskId())));
            var confirmation = new SharedClientArchive.Confirmation(result.status(), result.projectRevision());
            if (operation.confirmation() != null) { require(operation.confirmation().equals(confirmation)); return before; }
            List<SharedClientArchive.Operation> operations = new ArrayList<>(before.operations());
            operations.set(operations.indexOf(operation), new SharedClientArchive.Operation(operation.kind(), operation.original(),
                operation.connection(), operation.command(), confirmation));
            // No cache/binding adoption here: the response is solely the original journal's result.
            return before.journal(operations);
        }));
    }
    /**
     * Settles an unknown original from a replay of that exact command by the same authenticated actor, typically on a
     * replacement connection after reconnecting. The server keys durable replay by project + actor + operation ID, so
     * any connection of that actor receives the original retained outcome, executes a never-delivered command once, or
     * reports CONFLICT once the row has moved on. Only {@link #definitive} answers settle; a transient refusal of the
     * replay itself proves nothing about the original send and leaves it unknown for a later replay.
     */
    public CompletableFuture<SharedClientStore.Result> resolve(LocalPlacementId placement, UUID actor, SharedEvents.CommandResult result) {
        Objects.requireNonNull(actor); Objects.requireNonNull(result);
        return submit(() -> change(placement, false, before -> {
            require(definitive(result.status()));
            var found = before.operations().stream().filter(operation -> operation.command().operationId().equals(result.operationId())).findFirst();
            require(found.isPresent()); var operation = found.orElseThrow();
            require(operation.original().actor().equals(actor) && operation.original().scope().equals(result.scope())
                && (result.row() == null || operation.command().taskId().equals(result.row().taskId())));
            var confirmation = new SharedClientArchive.Confirmation(result.status(), result.projectRevision());
            if (operation.confirmation() != null) { require(operation.confirmation().equals(confirmation)); return before; }
            List<SharedClientArchive.Operation> operations = new ArrayList<>(before.operations());
            operations.set(operations.indexOf(operation), new SharedClientArchive.Operation(operation.kind(), operation.original(),
                operation.connection(), operation.command(), confirmation));
            // Like confirm: the replayed answer resolves only the original journal, never the binding or its cache.
            return before.journal(operations);
        }));
    }
    /** Server answers that end an original command's uncertainty when returned for its exact replay. */
    public static boolean definitive(SharedEvents.Status status) {
        return switch (Objects.requireNonNull(status)) {
            case OK, CONFLICT, DENIED, MISSING, INVALID_INPUT -> true;
            case SAVE_FAILED, UNAVAILABLE, CAPACITY, CLOSED -> false;
        };
    }
    /** Unknown originals of this actor/scope in journal order; replay resends each unchanged. */
    public static List<SharedClientArchive.Operation> unknown(SharedClientArchive archive, UUID actor, ProjectScope scope) {
        return archive.operations().stream().filter(operation -> operation.confirmation() == null
            && operation.original().actor().equals(actor) && operation.original().scope().equals(scope)).toList();
    }
    public CompletableFuture<SharedClientStore.Result> cache(LocalPlacementId placement, DatasetKind kind,
        SharedClientArchive.Reference original, ProjectSnapshot snapshot, long cursor) {
        Objects.requireNonNull(kind); Objects.requireNonNull(original); Objects.requireNonNull(snapshot);
        return submit(() -> change(placement, false, before -> {
            var binding = before.binding(kind);
            require(original.equals(binding.shared()) && original.scope().equals(snapshot.scope()) && snapshot.canRead(original.actor()));
            var cache = new SharedClientArchive.Cache(snapshot, cursor);
            if (cache.equals(binding.cache())) return before;
            if (binding.cache() != null) {
                var previous = binding.cache().snapshot();
                require(cursor >= binding.cache().cursor() && snapshot.manifest().version() >= previous.manifest().version());
                snapshot.rows().forEach((id, row) -> {
                    var old = previous.rows().get(id); require(old == null || row.rowVersion() >= old.rowVersion());
                });
            }
            return before.replace(kind, binding.withCache(cache));
        }));
    }
    public CompletableFuture<SharedClientStore.Result> clearCaches(LocalPlacementId placement, ProjectScope scope) {
        return submit(() -> change(placement, false, before -> {
            SharedClientArchive after = before;
            for (var kind : DatasetKind.values()) {
                var binding = after.binding(kind);
                if (binding.cache() != null && (scope == null || binding.shared().scope().equals(scope)))
                    after = after.replace(kind, binding.withCache(null));
            }
            return after;
        }));
    }
    /** Any unknown original outcome in this actor/scope blocks dependent edits even after reselection. */
    public static boolean hasUnknown(SharedClientArchive archive, UUID actor, ProjectScope scope) {
        return archive.operations().stream().anyMatch(operation -> operation.confirmation() == null
            && operation.original().actor().equals(actor) && operation.original().scope().equals(scope));
    }
    /** Why unshare is refused, in the Shared work screen's order; the controller adds saving and storage reasons (P6). */
    public enum UnshareReason {
        NOT_SHARED, ACCOUNT_MISMATCH, RECOVERED_UNCERTAIN, UNRESOLVED_OPERATIONS, JOIN_REQUIRED, REFRESH_REQUIRED;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    /** reason == null permits unshare; unresolved counts every REGIONS original still awaiting a definitive answer. */
    public record UnshareCheck(UnshareReason reason, int unresolved) {
        public boolean allowed() { return reason == null; }
    }
    /**
     * P1-P5 of #11 against one archive. P1: the regions binding is SHARED for this actor. P2: its cache is at liveCursor.
     * P3: no REGIONS entry of any account or scope is unconfirmed (a disconnect never resolves one). P4: no recovered
     * journal uncertainty. P5: every OK REGIONS confirmation in the bound scope is already inside the cache.
     */
    public static UnshareCheck unshareCheck(SharedClientArchive archive, UUID actor, long liveCursor) {
        var binding = archive.regions(); var ref = binding.shared();
        int unresolved = (int) archive.operations().stream().filter(operation -> operation.kind() == DatasetKind.REGIONS && operation.confirmation() == null).count();
        UnshareReason reason;
        if (ref == null) reason = UnshareReason.NOT_SHARED;
        else if (!ref.actor().equals(actor)) reason = UnshareReason.ACCOUNT_MISMATCH;
        else if (archive.recoveredJournalUncertain()) reason = UnshareReason.RECOVERED_UNCERTAIN;
        else if (unresolved > 0) reason = UnshareReason.UNRESOLVED_OPERATIONS;
        else if (binding.cache() == null || binding.cache().cursor() != liveCursor) reason = UnshareReason.JOIN_REQUIRED;
        else if (archive.operations().stream().anyMatch(operation -> operation.kind() == DatasetKind.REGIONS && operation.original().scope().equals(ref.scope())
            && operation.confirmation().status() == SharedEvents.Status.OK && operation.confirmation().projectRevision() > binding.cache().cursor()))
            reason = UnshareReason.REFRESH_REQUIRED;
        else reason = null;
        return new UnshareCheck(reason, unresolved);
    }
    /**
     * Replace the SHARED regions binding by a local copy of its confirmed cache in one atomic save (I3/I4: materials,
     * journal and the original local regions are untouched; nothing is sent). P1-P5 are re-checked against the on-disk
     * archive on the serial queue, so an admitted command either blocks this (P3) or fails its own binding check.
     * A retry with the same newId after success returns the archive unchanged.
     */
    public CompletableFuture<SharedClientStore.Result> unshare(LocalPlacementId placement, SharedClientArchive.Reference expectedRef,
        long expectedCursor, UUID actor, DatasetId newId) {
        Objects.requireNonNull(expectedRef); Objects.requireNonNull(actor); Objects.requireNonNull(newId);
        return submit(() -> change(placement, false, before -> {
            var binding = before.regions();
            if (binding.isDetached() && binding.detached().dataset().id().equals(newId)) {
                require(binding.detached().from().equals(expectedRef.scope())); return before;
            }
            require(expectedRef.equals(binding.shared()) && unshareCheck(before, actor, expectedCursor).allowed());
            var snapshot = binding.cache().snapshot();
            var copy = RegionDetachment.copy(newId, placement, snapshot);
            return before.replace(DatasetKind.REGIONS, new SharedClientArchive.Binding(binding.localDataset(), null, null,
                new SharedClientArchive.Detached(copy, snapshot.scope())));
        }));
    }
    /**
     * A local edit of the detached copy (apply, acknowledge or reconcile). The journal never blocks it: nothing is sent.
     * The function runs on the store queue and must be pure; returning the same dataset writes nothing.
     */
    public CompletableFuture<SharedClientStore.Result> updateDetached(LocalPlacementId placement, DatasetId datasetId,
        long expectedGeneration, UnaryOperator<RegionWorkDataset> edit) {
        Objects.requireNonNull(datasetId); Objects.requireNonNull(edit);
        return submit(() -> change(placement, false, before -> {
            var binding = before.regions(); var detached = binding.detached();
            require(detached != null && detached.dataset().id().equals(datasetId) && detached.dataset().generation() == expectedGeneration);
            var next = edit.apply(detached.dataset());
            require(next != null && next.id().equals(datasetId) && next.placementId().equals(placement));
            if (next.equals(detached.dataset())) return before;
            require(next.generation() > expectedGeneration);
            return before.replace(DatasetKind.REGIONS, new SharedClientArchive.Binding(binding.localDataset(), null, null,
                new SharedClientArchive.Detached(next, detached.from())));
        }));
    }
    /**
     * After an explicit successful join of detached.from: under the store lock, first export the exact detached copy
     * (see {@link #detachedExport}), then replace the binding with a fresh reference and the server snapshot. Nothing
     * merges (I5); a failure before the archive move leaves the copy DETACHED and a retry rewrites the same export.
     */
    public CompletableFuture<SharedClientStore.Result> rejoin(LocalPlacementId placement, DatasetId expectedDetachedId, long expectedGeneration,
        DatasetId local, UUID actor, ProjectSnapshot snapshot, long cursor) {
        Objects.requireNonNull(expectedDetachedId); Objects.requireNonNull(actor); Objects.requireNonNull(snapshot);
        return submit(() -> change(placement, false, true, before -> {
            var binding = before.regions(); var detached = binding.detached();
            require(detached != null && detached.dataset().id().equals(expectedDetachedId) && detached.dataset().generation() == expectedGeneration
                && detached.from().equals(snapshot.scope()) && snapshot.canRead(actor)
                && (binding.localDataset() == null || binding.localDataset().equals(local)));
            var reference = new SharedClientArchive.Reference(UUID.randomUUID(), actor, snapshot.scope());
            return before.replace(DatasetKind.REGIONS, new SharedClientArchive.Binding(local, reference, new SharedClientArchive.Cache(snapshot, cursor)));
        }));
    }
    /** Where rejoin writes (or wrote) this copy: shared-client/detached/placement-P-dataset-D-gN.json, DatasetJsonCodec format. */
    public Path detachedExport(LocalPlacementId placement, RegionWorkDataset dataset) { return store.export(placement, dataset); }
    private SharedClientStore.Result change(LocalPlacementId placement, boolean create, UnaryOperator<SharedClientArchive> mutation) {
        return change(placement, create, false, mutation);
    }
    private SharedClientStore.Result change(LocalPlacementId placement, boolean create, boolean export, UnaryOperator<SharedClientArchive> mutation) {
        var loaded = store.load(placement);
        if (!loaded.ok() && !(create && loaded.status() == StoreStatus.MISSING)) return loaded;
        var before = loaded.ok() ? loaded.value() : SharedClientArchive.empty(placement);
        try {
            var changed = mutation.apply(before);
            if (before == changed) return loaded;
            long generation = loaded.ok() ? Math.incrementExact(before.generation()) : 0;
            var after = new SharedClientArchive(generation, placement, changed.materials(), changed.regions(), changed.operations(), changed.recoveredJournalUncertain());
            return store.save(after, loaded.ok() ? before.generation() : -1, export ? before.regions().detached().dataset() : null);
        } catch (Refused refused) { return SharedClientStore.Result.failure(refused.status); }
        catch (IllegalArgumentException | ArithmeticException invalid) { return SharedClientStore.Result.failure(StoreStatus.INVALID_DEFINITION); }
    }
    private CompletableFuture<SharedClientStore.Result> submit(Supplier<SharedClientStore.Result> operation) {
        try { return CompletableFuture.supplyAsync(operation, io); }
        catch (RejectedExecutionException unavailable) {
            return CompletableFuture.completedFuture(SharedClientStore.Result.failure(io.isShutdown() ? StoreStatus.CLOSED : StoreStatus.BUSY));
        }
    }
    private static void require(boolean condition) { if (!condition) throw new Refused(StoreStatus.CONFLICT); }
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final StoreStatus status;
        Refused(StoreStatus status) { super("Client shared state refused"); this.status = status; }
    }
    public CompletableFuture<Void> shutdown() {
        io.shutdown();
        return CompletableFuture.runAsync(() -> {
            try { while (!io.awaitTermination(1, TimeUnit.SECONDS)) { /* drain accepted atomic writes */ } }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CompletionException(interrupted); }
        });
    }
    /** Off render/tick threads; the client lifecycle uses nonblocking shutdown(). */
    @Override public void close() { shutdown().join(); }
}
