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
    public CompletableFuture<SharedClientStore.Result> load(LocalPlacementId placement) { return submit(() -> store.load(placement)); }
    public CompletableFuture<SharedClientStore.Result> recover(LocalPlacementId placement) { return submit(() -> store.recover(placement)); }
    public CompletableFuture<SharedClientStore.Result> select(LocalPlacementId placement, DatasetKind kind,
        DatasetId local, UUID actor, ProjectSnapshot snapshot, long cursor) {
        Objects.requireNonNull(kind); Objects.requireNonNull(actor); Objects.requireNonNull(snapshot);
        return submit(() -> change(placement, true, before -> {
            var original = before.binding(kind);
            require(original.localDataset() == null || original.localDataset().equals(local));
            require(snapshot.canRead(actor));
            var reference = new SharedClientArchive.Reference(UUID.randomUUID(), actor, snapshot.scope());
            return before.replace(kind, new SharedClientArchive.Binding(local, reference, new SharedClientArchive.Cache(snapshot, cursor)));
        }));
    }
    /** Select the preserved original local work; this does not copy or detach shared region work. */
    public CompletableFuture<SharedClientStore.Result> local(LocalPlacementId placement, DatasetKind kind, DatasetId local) {
        Objects.requireNonNull(kind);
        return submit(() -> change(placement, false, before -> {
            require(before.binding(kind).localDataset() == null || before.binding(kind).localDataset().equals(local));
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
            require(snapshot.canWrite(actor) && row != null && row.rowVersion() == command.expectedRowVersion()
                && snapshot.manifest().version() == command.expectedManifestVersion());
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
            return before.replace(kind, new SharedClientArchive.Binding(binding.localDataset(), original, cache));
        }));
    }
    public CompletableFuture<SharedClientStore.Result> clearCaches(LocalPlacementId placement, ProjectScope scope) {
        return submit(() -> change(placement, false, before -> {
            SharedClientArchive after = before;
            for (var kind : DatasetKind.values()) {
                var binding = after.binding(kind);
                if (binding.cache() != null && (scope == null || binding.shared().scope().equals(scope)))
                    after = after.replace(kind, new SharedClientArchive.Binding(binding.localDataset(), binding.shared(), null));
            }
            return after;
        }));
    }
    /** Any unknown original outcome in this actor/scope blocks dependent edits even after reselection. */
    public static boolean hasUnknown(SharedClientArchive archive, UUID actor, ProjectScope scope) {
        return archive.operations().stream().anyMatch(operation -> operation.confirmation() == null
            && operation.original().actor().equals(actor) && operation.original().scope().equals(scope));
    }
    private SharedClientStore.Result change(LocalPlacementId placement, boolean create, UnaryOperator<SharedClientArchive> mutation) {
        var loaded = store.load(placement);
        if (!loaded.ok() && !(create && loaded.status() == StoreStatus.MISSING)) return loaded;
        var before = loaded.ok() ? loaded.value() : SharedClientArchive.empty(placement);
        try {
            var changed = mutation.apply(before);
            if (before == changed) return loaded;
            long generation = loaded.ok() ? Math.incrementExact(before.generation()) : 0;
            var after = new SharedClientArchive(generation, placement, changed.materials(), changed.regions(), changed.operations(), changed.recoveredJournalUncertain());
            return store.save(after, loaded.ok() ? before.generation() : -1);
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
