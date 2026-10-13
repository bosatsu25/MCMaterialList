package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.shared.protocol.SharedMessage.ProjectSummary;
import dev.mcmateriallist.core.work.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static dev.mcmateriallist.core.shared.SharedEvents.*;

/** Lifecycle-owned producer; all accepted work is serialized on one bounded background queue. */
public final class SharedProjectService implements AutoCloseable {
    public record Ready(StoreStatus status, UUID worldNamespace) {}
    private final SharedProjectStore store;
    private final Clock clock;
    private final ThreadPoolExecutor worker;
    private final CompletableFuture<Ready> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final Object publication = new Object();
    private final AtomicInteger admittedBytes = new AtomicInteger();
    // Worker-owned durable identities. Unpublishing damaged snapshots never releases admission slots.
    private final Set<UUID> reservedProjectIds = new HashSet<>();
    private Map<UUID, ProjectArchive> committed = Map.of();
    private volatile UUID world;
    private volatile StoreStatus storageStatus = StoreStatus.MISSING;
    private volatile boolean initialized;
    private volatile boolean closed;
    public SharedProjectService(SharedProjectStore store) { this(store, Clock.systemUTC()); }
    public SharedProjectService(SharedProjectStore store, Clock clock) {
        this.store = Objects.requireNonNull(store); this.clock = Objects.requireNonNull(clock);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(SharedLimits.QUEUED_WORK),
            runnable -> { Thread thread = new Thread(runnable, "mcmateriallist-shared-store"); thread.setDaemon(false); return thread; }) {
            @Override protected void terminated() { drained.complete(null); }
        };
        worker.execute(this::initialize);
    }
    public CompletionStage<Ready> ready() { return ready.minimalCompletionStage(); }
    public StoreStatus storageStatus() { return storageStatus; }
    private void initialize() {
        try {
            SharedProjectStore.Result<SharedProjectStore.World> result = store.openWorld(); storageStatus = result.status();
            if (!result.ok()) { ready.complete(new Ready(result.status(), null)); return; }
            reservedProjectIds.addAll(result.value().projects());
            world = result.value().namespace(); Map<UUID, ProjectArchive> loaded = new HashMap<>();
            for (UUID id : result.value().projects()) {
                SharedProjectStore.Result<ProjectArchive> project = store.load(world, id);
                if (!project.ok()) { storageStatus = project.status(); ready.complete(new Ready(project.status(), world)); return; }
                loaded.put(id, project.value());
            }
            synchronized (publication) { committed = Map.copyOf(loaded); }
            initialized = true;
            ready.complete(new Ready(StoreStatus.OK, world));
        } catch (RuntimeException exception) { storageStatus = StoreStatus.IO_FAILURE; ready.complete(new Ready(storageStatus, null)); }
    }
    public CompletionStage<Reply<ProjectSnapshot>> create(UUID actor, UUID expectedWorld, String name, SharedManifest.Definition definition) {
        return submit(() -> {
            if (!initialized) return Reply.of(Status.UNAVAILABLE, null);
            if (actor == null || !world.equals(expectedWorld)) return Reply.of(Status.DENIED, null);
            if (reservedProjectIds.size() >= SharedLimits.PROJECTS) return Reply.of(Status.CAPACITY, null);
            final ProjectSnapshot snapshot;
            try {
                SharedManifest manifest = SharedManifest.initial(definition); Map<TaskId, TaskState> rows = new HashMap<>();
                manifest.taskIds().forEach(id -> rows.put(id, TaskState.empty(id)));
                snapshot = new ProjectSnapshot(ProjectScope.create(world), name, actor, Map.of(actor, ProjectRole.ADMINISTRATOR), 0, manifest, rows, Set.of());
            } catch (IllegalArgumentException | NullPointerException exception) { return Reply.of(Status.INVALID_INPUT, null); }
            ProjectArchive archive = new ProjectArchive(0, snapshot, List.of(), List.of());
            return save(archive, -1) ? Reply.of(Status.OK, snapshot) : Reply.of(Status.SAVE_FAILED, null);
        }, Reply.of(Status.CAPACITY, null), Reply.of(Status.CLOSED, null), definitionBudget(definition));
    }
    public CompletionStage<Reply<List<ProjectSummary>>> discover(UUID actor, UUID expectedWorld) {
        return submit(() -> {
            if (!initialized) return Reply.of(Status.UNAVAILABLE, null);
            if (actor == null || !world.equals(expectedWorld)) return Reply.of(Status.DENIED, null);
            synchronized (publication) { return Reply.of(Status.OK, summaries(actor)); }
        }, Reply.of(Status.CAPACITY, null), Reply.of(Status.CLOSED, null));
    }
    public CompletionStage<Reply<ProjectSnapshot>> join(UUID actor, ProjectScope scope) {
        return submit(() -> {
            Current current = current(scope);
            if (current.archive() == null) return Reply.of(current.status(), null);
            ProjectSnapshot snapshot = current.archive().snapshot();
            return snapshot.canRead(actor) ? Reply.of(Status.OK, snapshot) : Reply.of(Status.DENIED, null);
        }, Reply.of(Status.CAPACITY, null), Reply.of(Status.CLOSED, null));
    }
    public CompletionStage<Outcome> command(UUID actor, SharedCommand command) {
        Objects.requireNonNull(command);
        return submit(() -> apply(actor, command), rejected(command, Status.CAPACITY), rejected(command, Status.CLOSED),
            512 + (command.intent() instanceof SharedCommand.SetNote note ? note.note().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0));
    }
    public CompletionStage<Reply<ProjectSnapshot>> setViewer(UUID actor, ProjectScope scope, long expectedRevision, UUID target, boolean grant) {
        return setMembership(actor, scope, expectedRevision, target, grant ? ProjectRole.VIEWER : null);
    }
    public CompletionStage<Reply<ProjectSnapshot>> setMembership(UUID actor, ProjectScope scope, long expectedRevision, UUID target, ProjectRole role) {
        return submit(() -> {
            Current current = current(scope);
            if (current.archive() == null) return Reply.of(current.status(), null);
            ProjectArchive archive = current.archive(); ProjectSnapshot before = archive.snapshot();
            if (!before.canAdminister(actor)) return Reply.of(Status.DENIED, null);
            if (target == null || expectedRevision < 0) return Reply.of(Status.INVALID_INPUT, null);
            if (before.projectRevision() != expectedRevision) return Reply.of(Status.CONFLICT, null);
            if (before.role(target) == role) return Reply.of(Status.OK, before);
            Map<UUID, ProjectRole> members = new HashMap<>(before.members());
            if (role == null) members.remove(target); else members.put(target, role);
            if (!members.containsValue(ProjectRole.ADMINISTRATOR)) return Reply.of(Status.INVALID_INPUT, null);
            if (members.size() > SharedLimits.PARTICIPANTS || exhausted(archive)) return Reply.of(Status.CAPACITY, null);
            ProjectSnapshot after = new ProjectSnapshot(scope, before.name(), before.creator(), members, before.projectRevision() + 1,
                before.manifest(), before.rows(), before.needsReconfirmation());
            if (!save(new ProjectArchive(archive.generation() + 1, after, retained(archive), archive.retired()), archive.generation()))
                return Reply.of(Status.SAVE_FAILED, null);
            List<Event> events = new ArrayList<>(); events.add(new ProjectInvalidated(scope, after.projectRevision(), after.manifest().version()));
            if (role == null) events.add(new AccessRevoked(scope, target));
            return new Reply<>(Status.OK, after, events);
        }, Reply.of(Status.CAPACITY, null), Reply.of(Status.CLOSED, null));
    }
    public CompletionStage<Reply<ProjectSnapshot>> updateManifest(UUID actor, ProjectScope scope, long expectedRevision, long expectedManifest,
                                                                  SharedManifest.Definition definition) {
        return submit(() -> {
            Current current = current(scope);
            if (current.archive() == null) return Reply.of(current.status(), null);
            ProjectArchive archive = current.archive(); ProjectSnapshot before = archive.snapshot();
            if (!before.canAdminister(actor)) return Reply.of(Status.DENIED, null);
            if (expectedRevision < 0 || expectedManifest < 0) return Reply.of(Status.INVALID_INPUT, null);
            if (before.projectRevision() != expectedRevision || before.manifest().version() != expectedManifest) return Reply.of(Status.CONFLICT, null);
            if (definition == null) return Reply.of(Status.INVALID_INPUT, null);
            if (before.manifest().definition().equals(definition)) return Reply.of(Status.OK, before);
            if (exhausted(archive) || before.manifest().version() == Long.MAX_VALUE) return Reply.of(Status.CAPACITY, null);
            SharedManifest manifest = SharedManifest.reconcile(before.manifest(), definition);
            Map<TaskId, TaskState> rows = new HashMap<>(); Set<TaskId> recheck = new HashSet<>();
            List<ProjectArchive.Retired> retired = new ArrayList<>(archive.retired());
            for (TaskId id : manifest.taskIds()) {
                TaskState row = before.rows().get(id);
                if (row == null) {
                    Optional<TaskState> fresh = freshRow(id, archive.retired());
                    if (fresh.isEmpty()) return Reply.of(Status.CAPACITY, null);
                    row = fresh.get();
                }
                rows.put(id, row);
                if (before.needsReconfirmation().contains(id)
                    || (row.done() && (id instanceof MaterialTaskId material
                        && !Objects.equals(before.manifest().definition().materials().get(material), definition.materials().get(material))
                        || !before.manifest().definition().baseline().equals(definition.baseline())
                        || !before.manifest().definition().schematicDigest().equals(definition.schematicDigest())))) recheck.add(id);
            }
            for (TaskState row : before.rows().values()) if (!rows.containsKey(row.taskId())) {
                retired.add(new ProjectArchive.Retired(before.manifest().version(), row,
                    row.taskId() instanceof RegionTaskId region ? before.manifest().regions().get(region) : null,
                    row.taskId() instanceof MaterialTaskId material ? before.manifest().definition().materials().get(material) : null));
            }
            if (retired.size() > SharedLimits.RETIRED_ROWS) return Reply.of(Status.CAPACITY, null);
            ProjectSnapshot after = new ProjectSnapshot(scope, before.name(), before.creator(), before.members(), before.projectRevision() + 1, manifest, rows, recheck);
            if (!save(new ProjectArchive(archive.generation() + 1, after, retained(archive), retired), archive.generation())) return Reply.of(Status.SAVE_FAILED, null);
            return new Reply<>(Status.OK, after, List.of(new ProjectInvalidated(scope, after.projectRevision(), manifest.version())));
        }, Reply.of(Status.CAPACITY, null), Reply.of(Status.CLOSED, null), definitionBudget(definition));
    }
    /** Stable material identities retain only their version watermark, never retired progress. */
    private static Optional<TaskState> freshRow(TaskId id, List<ProjectArchive.Retired> retired) {
        long previousVersion = -1;
        if (id instanceof MaterialTaskId) {
            for (ProjectArchive.Retired previous : retired) {
                if (previous.row().taskId().equals(id)) previousVersion = Math.max(previousVersion, previous.row().rowVersion());
            }
        }
        if (previousVersion == Long.MAX_VALUE) return Optional.empty();
        return Optional.of(new TaskState(id, null, false, "", null, null, previousVersion + 1));
    }
    private Outcome apply(UUID actor, SharedCommand command) {
        Current current = current(command.scope());
        if (current.archive() == null) return rejected(command, current.status());
        ProjectArchive archive = current.archive(); ProjectSnapshot before = archive.snapshot();
        // Authority precedes even durable replay, so revoked/read-only actors receive no historical rows.
        if (!before.canWrite(actor) || !before.canAdminister(actor) && ProjectPermissions.privileged(command.intent()))
            return rejected(command, Status.DENIED);
        List<ProjectArchive.Operation> operations = retained(archive);
        for (ProjectArchive.Operation op : operations) if (op.actor().equals(actor) && op.command().operationId().equals(command.operationId())) {
            if (!before.canAdminister(actor) && !op.memberAllowed()) return rejected(command, Status.DENIED);
            return op.command().equals(command) ? new Outcome(op.result(), List.of()) : rejected(command, Status.INVALID_INPUT);
        }
        TaskState oldRow = before.rows().get(command.taskId()); TaskState nextRow = oldRow;
        if (oldRow == null || command.intent() instanceof SharedCommand.Assign assign && !before.canRead(assign.target()))
            return rejected(command, Status.INVALID_INPUT);
        if (exhausted(archive)) return rejected(command, Status.CAPACITY);
        Status status = Status.OK;
        if (command.expectedManifestVersion() != before.manifest().version() || command.expectedRowVersion() != oldRow.rowVersion()) status = Status.CONFLICT;
        else if (!ProjectPermissions.allows(before, actor, oldRow, command.intent())) return rejected(command, Status.DENIED);
        else {
            TransitionResult transition = transition(oldRow, command.intent(), actor);
            if (transition instanceof TransitionResult.Rejected) status = Status.CONFLICT;
            else if (transition instanceof TransitionResult.Changed changed) nextRow = changed.state();
        }
        Set<TaskId> recheck = new HashSet<>(before.needsReconfirmation());
        if (status == Status.OK && command.intent() instanceof SharedCommand.SetDone && recheck.remove(command.taskId()) && oldRow.equals(nextRow)) {
            if (oldRow.rowVersion() == Long.MAX_VALUE) return rejected(command, Status.CAPACITY);
            nextRow = new TaskState(oldRow.taskId(), oldRow.assignee(), oldRow.done(), oldRow.note(), oldRow.completedBy(), oldRow.completedAt(), oldRow.rowVersion() + 1);
        }
        boolean changed = !Objects.equals(oldRow, nextRow); ProjectSnapshot after = before;
        if (changed) {
            Map<TaskId, TaskState> rows = new HashMap<>(before.rows()); rows.put(command.taskId(), nextRow);
            after = new ProjectSnapshot(before.scope(), before.name(), before.creator(), before.members(), before.projectRevision() + 1,
                before.manifest(), rows, recheck);
        }
        CommandResult result = new CommandResult(command.scope(), command.operationId(), status, after.projectRevision(), nextRow);
        operations.add(new ProjectArchive.Operation(actor, command, result, clock.instant(), ProjectPermissions.memberAllows(actor, oldRow, command.intent())));
        while (operations.size() > SharedLimits.OPERATIONS) operations.removeFirst();
        if (!save(new ProjectArchive(archive.generation() + 1, after, operations, archive.retired()), archive.generation())) return rejected(command, Status.SAVE_FAILED);
        return new Outcome(result, changed ? List.of(new ProjectDelta(command.scope(), after.projectRevision(), after.manifest().version(), nextRow,
            after.needsReconfirmation().contains(command.taskId()))) : List.of());
    }
    private TransitionResult transition(TaskState row, SharedCommand.Intent intent, UUID actor) {
        if (intent instanceof SharedCommand.ClearAssignment) {
            if (row.assignee() == null) return new TransitionResult.Unchanged(row);
            if (row.rowVersion() == Long.MAX_VALUE) return new TransitionResult.Rejected(TransitionResult.Reason.VERSION_EXHAUSTED);
            return new TransitionResult.Changed(new TaskState(row.taskId(), null, row.done(), row.note(), row.completedBy(), row.completedAt(), row.rowVersion() + 1));
        }
        TaskCommand local = switch (intent) {
            case SharedCommand.Claim ignored -> new TaskCommand.Claim();
            case SharedCommand.Release ignored -> new TaskCommand.Release();
            case SharedCommand.Assign assign -> new TaskCommand.Assign(assign.target());
            case SharedCommand.SetDone done -> new TaskCommand.SetDone(done.done());
            case SharedCommand.SetNote note -> new TaskCommand.SetNote(note.note());
            case SharedCommand.ClearAssignment ignored -> throw new IllegalArgumentException("Unexpected clear intent");
        };
        return TaskTransitions.apply(row, local, actor, clock.instant());
    }
    private List<ProjectArchive.Operation> retained(ProjectArchive archive) {
        var cutoff = clock.instant().minusSeconds(SharedLimits.RETENTION_SECONDS);
        return new ArrayList<>(archive.operations().stream().filter(op -> op.recordedAt().isAfter(cutoff)).toList());
    }
    private static boolean exhausted(ProjectArchive archive) { return archive.generation() == Long.MAX_VALUE || archive.snapshot().projectRevision() == Long.MAX_VALUE; }
    private record Current(Status status, ProjectArchive archive) {}
    private Current current(ProjectScope scope) {
        if (!initialized) return new Current(Status.UNAVAILABLE, null);
        if (scope == null || !scope.worldNamespace().equals(world)) return new Current(Status.DENIED, null);
        SharedProjectStore.Result<ProjectArchive> loaded = store.load(world, scope.projectId()); storageStatus = loaded.status();
        if (!loaded.ok()) {
            synchronized (publication) { Map<UUID, ProjectArchive> next = new HashMap<>(committed); next.remove(scope.projectId()); committed = Map.copyOf(next); }
            return new Current(loaded.status() == StoreStatus.MISSING ? Status.MISSING : Status.UNAVAILABLE, null);
        }
        if (!loaded.value().snapshot().scope().equals(scope)) return new Current(Status.DENIED, null);
        publish(loaded.value()); return new Current(Status.OK, loaded.value());
    }
    private boolean save(ProjectArchive archive, long expectedGeneration) {
        SharedProjectStore.Result<ProjectArchive> result = store.save(archive, expectedGeneration); storageStatus = result.status();
        if (!result.ok()) return false; publish(archive); return true;
    }
    private void publish(ProjectArchive archive) {
        reservedProjectIds.add(archive.snapshot().scope().projectId());
        synchronized (publication) { Map<UUID, ProjectArchive> next = new HashMap<>(committed); next.put(archive.snapshot().scope().projectId(), archive); committed = Map.copyOf(next); }
    }
    /** Run only a short transport enqueue/send here, never disk I/O, waiting, or reentrant mutation. */
    public boolean publishIfAuthorized(UUID actor, ProjectScope scope, Runnable send) {
        Objects.requireNonNull(send);
        synchronized (publication) {
            ProjectArchive current = committed.get(scope.projectId());
            if (closed || current == null || !current.snapshot().scope().equals(scope) || !current.snapshot().canRead(actor)) return false;
            send.run(); return true;
        }
    }
    /** Discovery must also be generated and queued under the publication gate. */
    public void publishDiscovery(UUID actor, Consumer<List<ProjectSummary>> send) {
        synchronized (publication) { if (!closed) send.accept(summaries(actor)); }
    }
    public Optional<ProjectSnapshot> committedSnapshot(UUID actor, ProjectScope scope) {
        synchronized (publication) {
            ProjectArchive current = committed.get(scope.projectId());
            return current != null && current.snapshot().scope().equals(scope) && current.snapshot().canRead(actor)
                ? Optional.of(current.snapshot()) : Optional.empty();
        }
    }
    private List<ProjectSummary> summaries(UUID actor) {
        return committed.values().stream().map(ProjectArchive::snapshot).filter(s -> s.canRead(actor))
            .sorted(Comparator.comparing(s -> s.scope().projectId())).map(s -> new ProjectSummary(s.scope(), s.name())).toList();
    }
    private static Outcome rejected(SharedCommand command, Status status) {
        return new Outcome(new CommandResult(command.scope(), command.operationId(), status, 0, null), List.of());
    }
    private <T> CompletionStage<T> submit(Supplier<T> operation, T full, T closed) {
        return submit(operation, full, closed, 256);
    }
    /** Conservative serialized-input reservation, not a claim about Java heap object size. */
    private static int definitionBudget(SharedManifest.Definition definition) {
        if (definition == null) return 512;
        return 512 + definition.materials().size() * 268 + definition.regions().size() * 4124;
    }
    private <T> CompletionStage<T> submit(Supplier<T> operation, T full, T closed, int bytes) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (worker.isShutdown()) return CompletableFuture.completedFuture(closed).minimalCompletionStage();
        int previous;
        do {
            previous = admittedBytes.get();
            if (bytes > SharedLimits.QUEUED_BYTES - previous) return CompletableFuture.completedFuture(full).minimalCompletionStage();
        } while (!admittedBytes.compareAndSet(previous, previous + bytes));
        try { worker.execute(() -> {
            try { result.complete(operation.get()); }
            catch (RuntimeException exception) { result.completeExceptionally(exception); }
            finally { admittedBytes.addAndGet(-bytes); }
        }); }
        catch (RejectedExecutionException exception) { admittedBytes.addAndGet(-bytes); result.complete(worker.isShutdown() ? closed : full); }
        return result.minimalCompletionStage();
    }
    /** Stop accepting work immediately; drain in the background. Await this off Minecraft tick threads. */
    public CompletionStage<Void> shutdown() {
        closed = true;
        worker.shutdown();
        return drained.minimalCompletionStage();
    }
    /** Off-tick lifecycle close; accepted work is drained. */
    @Override public void close() { shutdown().toCompletableFuture().join(); }
}
