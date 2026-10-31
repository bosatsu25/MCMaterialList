package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Render-thread coordinator. Its service owns one bounded disk queue and immutable save units. */
public final class SharedWorkController {
    private static final int OPEN_PLACEMENTS = 16, REQUESTS = 16, REPLAYS = 64;
    static final class Entry {
        final LocalPlacementId placement;
        SharedClientArchive archive;
        StoreStatus storage;
        boolean loading = true;
        int busy;
        long version;
        String reason;
        final Set<ProjectScope> hidden = new HashSet<>();
        final Set<UUID> confirming = new HashSet<>();
        final Set<DatasetKind> caching = EnumSet.noneOf(DatasetKind.class);
        /** Latest native backend per list; weak, so a closed screen is never kept alive for reconnect restoration. */
        final Map<DatasetKind, WeakReference<SharedWorkBackend>> backends = new EnumMap<>(DatasetKind.class);
        Entry(LocalPlacementId placement) { this.placement = placement; }
        SharedClientArchive.Binding binding(DatasetKind kind) { return archive == null ? null : archive.binding(kind); }
    }
    private record Verified(NativeSharedCapture.Source source, String digest) {}
    /** rejoin: an explicit Rejoin shared regions join of a detached copy's original scope (#11); never select(). */
    private record Request(SharedWorkBackend backend, UUID actor, ClientPacketListener listener,
        SharedClientArchive.Reference previous, NativeSharedCapture.Capture capture, String digest,
        boolean select, long started, boolean rejoin) {
        Request(SharedWorkBackend backend, UUID actor, ClientPacketListener listener, SharedClientArchive.Reference previous,
                NativeSharedCapture.Capture capture, String digest, boolean select, long started) {
            this(backend, actor, listener, previous, capture, digest, select, started, false);
        }
    }
    private record Admin(Entry entry, SharedClientArchive.Reference original, ClientPacketListener listener, long started) {}
    /** One replay per original command, connection and fresh snapshot installation. */
    private record Replayed(ClientPacketListener listener, long installation) {}
    private final SharedClientService service;
    private final Map<LocalPlacementId, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Verified> verified = new HashMap<>();
    private final Map<UUID, Request> requests = new LinkedHashMap<>();
    private final Map<UUID, Admin> admins = new LinkedHashMap<>();
    private final ThreadPoolExecutor digests = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4), task -> { Thread thread = new Thread(task, "mcmateriallist-shared-source-read"); thread.setDaemon(false); return thread; });
    private final Map<UUID, Replayed> replays = new LinkedHashMap<>();
    /** Connection and installation for which each binding's source proof was last validated. */
    private final Map<UUID, Replayed> proofs = new HashMap<>();
    /** Scope the latest connection was synchronized to; reconnect restores it once, before any new selection. */
    private ProjectScope subscribed;
    private UUID subscribedActor;
    private ClientPacketListener restored;
    private UUID lastActor;
    private ProjectScope lastScope;
    private SharedClientState.Status lastStatus;
    private boolean closed;
    public SharedWorkController(Path configuredConfigRoot) {
        service = new SharedClientService(configuredConfigRoot);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }
    Entry attach(LocalPlacementId placement) {
        if (placement == null || closed) return null;
        Entry existing = entries.get(placement); if (existing != null) return existing;
        // Native parent screens can retain their backend until closed. Never orphan a live entry; only an idle entry with
        // nothing to replay, restore or hide is dropped, and it reloads from disk (detached copy included) when reopened.
        if (entries.size() >= OPEN_PLACEMENTS) entries.values().stream().filter(this::evictable).findFirst()
            .ifPresent(idle -> entries.remove(idle.placement));
        if (entries.size() >= OPEN_PLACEMENTS) return null;
        Entry entry = new Entry(placement); entries.put(placement, entry);
        service.load(placement).whenComplete((result, failure) -> later(() -> {
            entry.loading = false; publish(entry, result, failure);
        }));
        return entry;
    }
    void register(SharedWorkBackend backend) {
        var entry = backend.entry(); if (entry != null && !closed) entry.backends.put(backend.kind(), new WeakReference<>(backend));
    }
    static UUID actor() { var player = Minecraft.getInstance().player; return player == null ? null : player.getUUID(); }
    static SharedClientArchive.Reference reference(Entry entry, DatasetKind kind) {
        var binding = entry == null ? null : entry.binding(kind); return binding == null ? null : binding.shared();
    }
    ProjectSnapshot snapshot(Entry entry, DatasetKind kind) {
        var binding = entry == null ? null : entry.binding(kind);
        if (binding == null || binding.shared() == null || binding.cache() == null || entry.hidden.contains(binding.shared().scope())
            || !binding.shared().actor().equals(actor())) return null;
        return binding.cache().snapshot();
    }
    boolean synchronizedView(SharedWorkBackend backend) {
        var entry = backend.entry(); var ref = reference(entry, backend.kind());
        if (closed || ref == null || !ref.actor().equals(actor()) || entry.storage != StoreStatus.OK || entry.hidden.contains(ref.scope())) return false;
        var state = SharedClientNetworking.state(); var proof = verified.get(ref.bindingId());
        if (!state.synchronizedState() || !ref.scope().equals(state.scope()) || proof == null || snapshot(entry, backend.kind()) == null) return false;
        var cache = entry.binding(backend.kind()).cache();
        if (cache.cursor() != state.cursor() || !cache.snapshot().equals(state.snapshot())) return false;
        var current = backend.capture(false);
        return current.ok() && proof.source().equals(current.source());
    }
    boolean cachePending(SharedWorkBackend backend) {
        var entry = backend.entry(); var ref = reference(entry, backend.kind());
        if (entry == null || entry.archive == null || ref == null || !ref.actor().equals(actor())
            || entry.hidden.contains(ref.scope()) || SharedClientService.hasUnknown(entry.archive, ref.actor(), ref.scope())) return false;
        var state = SharedClientNetworking.state(); var cache = entry.binding(backend.kind()).cache();
        return state.synchronizedState() && ref.scope().equals(state.scope()) && cache != null
            && (cache.cursor() != state.cursor() || !cache.snapshot().equals(state.snapshot()));
    }
    boolean writable(SharedWorkBackend backend) {
        var entry = backend.entry(); var snapshot = snapshot(entry, backend.kind());
        return synchronizedView(backend) && entry.busy == 0 && !entry.archive.recoveredJournalUncertain()
            && !SharedClientService.hasUnknown(entry.archive, actor(), snapshot.scope()) && snapshot.canWrite(actor()) && backend.nativeReady();
    }
    boolean allowed(SharedWorkBackend backend, TaskId task, TaskCommand intent) {
        var snapshot = snapshot(backend.entry(), backend.kind());
        if (!writable(backend) || snapshot == null || task == null || intent == null
                || (backend.kind() == DatasetKind.MATERIALS) != (task instanceof MaterialTaskId)) return false;
        try {
            var row = snapshot.rows().get(task);
            if (row == null || intent instanceof TaskCommand.Claim && row.assignee() != null
                    || intent instanceof TaskCommand.Release && !actor().equals(row.assignee())
                    || intent instanceof TaskCommand.Assign assign && assign.assignee() != null
                        && !snapshot.canRead(assign.assignee())) return false;
            return ProjectPermissions.allows(snapshot, actor(), row, wire(intent));
        }
        catch (IllegalArgumentException invalid) { return false; }
    }
    boolean administer(SharedWorkBackend backend, boolean definition) {
        var entry = backend.entry(); var ref = reference(entry, backend.kind());
        if (closed || entry == null || ref == null || entry.loading || entry.busy != 0 || entry.storage != StoreStatus.OK
                || !ref.actor().equals(actor()) || entry.hidden.contains(ref.scope()) || entry.archive.recoveredJournalUncertain()
                || SharedClientService.hasUnknown(entry.archive, actor(), ref.scope()) || admins.size() >= REQUESTS) return false;
        var state = SharedClientNetworking.state(); var selected = snapshot(entry, backend.kind());
        if (!state.synchronizedState() || selected == null || !ref.scope().equals(state.scope())
                || !selected.equals(state.snapshot()) || entry.binding(backend.kind()).cache().cursor() != state.cursor()
                || !selected.canAdminister(actor()) || !backend.nativeReady()) return false;
        return definition || synchronizedView(backend);
    }
    String reason(SharedWorkBackend backend) {
        var entry = backend.entry();
        if (entry == null) return backend.nativeReady() ? "placement_capacity" : "placement_unavailable";
        if (entry.loading) return "loading";
        if (entry.storage != StoreStatus.OK && entry.storage != StoreStatus.MISSING) return "storage." + entry.storage.name();
        var ref = reference(entry, backend.kind());
        if (entry.busy > 0 || cachePending(backend)) return "saving";
        if (ref == null && detached(entry, backend.kind()) != null)
            return entry.reason == null || entry.reason.equals("result.OK") || entry.reason.equals("local_preserved") ? "detached" : entry.reason;
        if (ref == null) return SharedClientNetworking.state().capabilities() == null || entry.reason == null ? availability() : entry.reason;
        if (!ref.actor().equals(actor())) return "account_mismatch";
        if (entry.archive.recoveredJournalUncertain()) return "recovered_uncertain";
        if (SharedClientService.hasUnknown(entry.archive, ref.actor(), ref.scope())) return "unknown_outcome";
        var state = SharedClientNetworking.state();
        if (state.status() == SharedClientState.Status.REVOKED && ref.scope().equals(state.scope())) return "revoked";
        if (state.capabilities() == null) return availability();
        if (!state.capabilities().worldNamespace().equals(ref.scope().worldNamespace())) return "wrong_world";
        var current = backend.capture(false);
        if (!current.ok()) return current.reason();
        var proof = verified.get(ref.bindingId());
        if (proof != null && !proof.source().equals(current.source())) return "baseline_mismatch";
        if (entry.reason != null && !entry.reason.equals("result.OK")) return entry.reason;
        if (!synchronizedView(backend)) return "join_required";
        if (!backend.nativeReady()) return "capture.MATERIALS_NOT_READY";
        return snapshot(entry, backend.kind()).role(actor()) == ProjectRole.VIEWER ? "viewer" : "shared";
    }
    static String availability() {
        return switch (SharedClientNetworking.state().status()) {
            case UNAVAILABLE -> "server_unavailable";
            case UNSUPPORTED -> "protocol_unsupported";
            case DISCONNECTED -> "disconnected";
            case NEGOTIATING -> "negotiating";
            case CAPACITY -> "capacity";
            default -> "local_preserved";
        };
    }
    public void discover(SharedWorkBackend backend) {
        if (!requestReady(backend)) return;
        try { SharedClientNetworking.discover(); setReason(backend.entry(), "discovering"); }
        catch (IllegalStateException unavailable) { setReason(backend.entry(), availability()); }
    }
    public void create(SharedWorkBackend backend, String name) {
        if (!requestReady(backend)) return;
        if (detached(backend.entry(), backend.kind()) != null) { setReason(backend.entry(), "detached_rejoin_required"); return; }
        if (name == null || name.isBlank() || !TextLimits.valid(name, 256)) { setReason(backend.entry(), "invalid_name"); return; }
        prepare(backend, true, (capture, digest) -> {
            try {
                var definition = new SharedManifest.Definition(digest, capture.source().baseline(), capture.materials(), capture.source().regions());
                UUID request = SharedClientNetworking.create(name, definition);
                requests.put(request, new Request(backend, actor(), Minecraft.getInstance().getConnection(), reference(backend.entry(), backend.kind()),
                    capture, digest, true, System.nanoTime()));
                setReason(backend.entry(), "creating");
            } catch (IllegalArgumentException | IllegalStateException unavailable) { setReason(backend.entry(), "request_failed"); }
        });
    }
    public void join(SharedWorkBackend backend, ProjectScope scope, boolean select) {
        if (!requestReady(backend)) return;
        // A detached copy is replaced only by Rejoin shared regions, which exports it first (#11).
        if (detached(backend.entry(), backend.kind()) != null) { setReason(backend.entry(), "detached_rejoin_required"); return; }
        if (!SharedClientNetworking.state().capabilities().worldNamespace().equals(scope.worldNamespace())) { setReason(backend.entry(), "wrong_world"); return; }
        prepare(backend, select && backend.kind() == DatasetKind.MATERIALS, (capture, digest) -> {
            try {
                UUID request = SharedClientNetworking.join(scope);
                requests.put(request, new Request(backend, actor(), Minecraft.getInstance().getConnection(), reference(backend.entry(), backend.kind()),
                    capture, digest, select, System.nanoTime()));
                setReason(backend.entry(), "joining");
            } catch (IllegalStateException unavailable) { setReason(backend.entry(), "request_failed"); }
        });
    }
    public void local(SharedWorkBackend backend) {
        Entry entry = backend.entry(); if (entry == null || backend.pending()) return;
        var original = reference(entry, backend.kind());
        if (original == null) { setReason(entry, "local_preserved"); return; }
        loadLocal(backend, local -> complete(entry, service.local(entry.placement, backend.kind(), local), result -> {
            if (result.ok()) { verified.remove(original.bindingId()); setReason(entry, "local_preserved"); }
        }));
    }
    public void recover(SharedWorkBackend backend) {
        Entry entry = backend.entry(); if (entry == null || entry.busy > 0 || entry.storage != StoreStatus.RECOVERY_REQUIRED) return;
        complete(entry, service.recover(entry.placement), result -> { if (result.ok()) setReason(entry, "recovered_uncertain"); });
    }
    public void membership(SharedWorkBackend backend, UUID target, ProjectRole role) {
        var entry = backend.entry(); var snapshot = snapshot(entry, backend.kind());
        if (!administer(backend, false) || target == null) return;
        try {
            UUID request = SharedClientNetworking.setMembership(snapshot.scope(), snapshot.projectRevision(), target, role);
            admins.put(request, new Admin(entry, reference(entry, backend.kind()), Minecraft.getInstance().getConnection(), System.nanoTime()));
            // The producer invalidates membership changes; only an explicit join restores writes.
            setReason(entry, "permissions_requested");
        } catch (IllegalStateException unavailable) { setReason(entry, "request_failed"); }
    }
    public void viewer(SharedWorkBackend backend, UUID target, boolean granted) {
        membership(backend, target, granted ? ProjectRole.VIEWER : null);
    }
    public void updateDefinition(SharedWorkBackend backend) {
        if (!administer(backend, true)) return;
        var entry = backend.entry(); var original = reference(entry, backend.kind());
        var selected = snapshot(entry, backend.kind());
        prepare(backend, true, (capture, digest) -> {
            if (!administer(backend, true) || !Objects.equals(original, reference(entry, backend.kind()))
                    || !selected.equals(snapshot(entry, backend.kind()))) { setReason(entry, "state_changed"); return; }
            try {
                var definition = new SharedManifest.Definition(digest, capture.source().baseline(), capture.materials(), capture.source().regions());
                UUID request = SharedClientNetworking.updateManifest(selected.scope(), selected.projectRevision(), selected.manifest().version(), definition);
                admins.put(request, new Admin(entry, original, Minecraft.getInstance().getConnection(), System.nanoTime()));
                setReason(entry, "definition_requested");
            } catch (IllegalArgumentException | IllegalStateException unavailable) { setReason(entry, "request_failed"); }
        });
    }
    // Issue #11: unshare regions to a detached local copy, and rejoin. REGIONS only; materials never detach.
    static SharedClientArchive.Detached detached(Entry entry, DatasetKind kind) {
        var binding = entry == null || kind != DatasetKind.REGIONS ? null : entry.binding(kind);
        return binding == null ? null : binding.detached();
    }
    /** Sharing metadata was loaded but cannot be read or trusted: a detached copy might be hidden in it. */
    static boolean metadataBlocked(Entry entry) {
        return entry != null && !entry.loading && entry.storage != StoreStatus.OK && entry.storage != StoreStatus.MISSING;
    }
    /** No live screen, no work in flight, no shared reference, no unresolved original and nothing hidden. */
    private boolean evictable(Entry entry) {
        if (entry.loading || saving(entry) || !entry.hidden.isEmpty()
            || entry.backends.values().stream().anyMatch(held -> held.get() != null)) return false;
        var archive = entry.archive;
        return archive == null || (archive.materials().shared() == null && archive.regions().shared() == null
            && archive.operations().stream().allMatch(operation -> operation.confirmation() != null));
    }
    /** P6: no save, confirmation, administration or open request for this placement. */
    private boolean saving(Entry entry) {
        return entry.busy > 0 || !entry.confirming.isEmpty() || !entry.caching.isEmpty()
            || requests.values().stream().anyMatch(request -> request.backend().entry() == entry)
            || admins.values().stream().anyMatch(admin -> admin.entry() == entry);
    }
    private SharedClientService.UnshareCheck unshareCheck(Entry entry, SharedClientArchive.Reference ref) {
        var state = SharedClientNetworking.state();
        long live = ref != null && state.synchronizedState() && ref.scope().equals(state.scope()) ? state.cursor() : -1;
        return SharedClientService.unshareCheck(entry.archive, actor(), live);
    }
    /** Null permits Unshare regions; otherwise a translation key. P1-P5 come from core, P6 and storage from here. */
    String unshareReason(SharedWorkBackend backend) {
        var entry = backend.entry();
        if (closed || backend.kind() != DatasetKind.REGIONS) return "unshare.not_shared";
        if (entry == null) return backend.nativeReady() ? "placement_capacity" : "placement_unavailable";
        if (entry.loading) return "loading";
        if (entry.storage == StoreStatus.MISSING || entry.archive == null) return "unshare.not_shared";
        if (entry.storage != StoreStatus.OK) return "storage." + entry.storage.name();
        var ref = reference(entry, DatasetKind.REGIONS); UUID actor = actor();
        if (ref == null) return "unshare.not_shared";
        // Offline (no player) still reports the journal reasons first: a disconnect never resolves an original.
        if (actor != null && !ref.actor().equals(actor)) return "unshare.account_mismatch";
        if (entry.archive.recoveredJournalUncertain()) return "unshare.recovered_uncertain";
        if (unresolved(backend) > 0) return "unshare.unresolved_operations";
        if (actor == null) return "unshare.join_required";
        if (saving(entry) || cachePending(backend)) return "unshare.saving";
        // The core check stays the authority for P1-P5 once online.
        var check = unshareCheck(entry, ref);
        if (check.reason() == SharedClientService.UnshareReason.JOIN_REQUIRED || !synchronizedView(backend)) return "unshare.join_required";
        if (check.reason() != null) return "unshare." + check.reason().key();
        return null;
    }
    /** Unresolved REGIONS originals of any account or scope (P3), for the disabled reason. */
    int unresolved(SharedWorkBackend backend) {
        var entry = backend.entry();
        return entry == null || entry.archive == null ? 0 : (int) entry.archive.operations().stream()
            .filter(operation -> operation.kind() == DatasetKind.REGIONS && operation.confirmation() == null).count();
    }
    public void unshare(SharedWorkBackend backend) {
        if (unshareReason(backend) != null) return;
        var entry = backend.entry(); var binding = entry.binding(DatasetKind.REGIONS); var ref = binding.shared();
        // The core re-checks P1-P5 against the on-disk archive on the same serial queue as admission. Nothing is sent.
        complete(entry, service.unshare(entry.placement, ref, binding.cache().cursor(), actor(), DatasetId.create()), result -> {
            if (result.ok()) { verified.remove(ref.bindingId()); proofs.remove(ref.bindingId()); setReason(entry, null); }
            else reload(entry, "unshare.state_changed");
        });
    }
    /** Null permits Rejoin shared regions; otherwise a translation key. */
    String rejoinReason(SharedWorkBackend backend) {
        var entry = backend.entry();
        if (closed || entry == null) return "unshare.not_detached";
        if (entry.loading) return "loading";
        if (entry.storage != StoreStatus.OK) return entry.storage == StoreStatus.MISSING ? "unshare.not_detached" : "storage." + entry.storage.name();
        var copy = detached(entry, backend.kind());
        if (copy == null) return "unshare.not_detached";
        if (saving(entry) || requests.size() >= REQUESTS) return "unshare.saving";
        var state = SharedClientNetworking.state();
        if (actor() == null || state.capabilities() == null
            || state.capabilities().protocolVersion() != dev.mcmateriallist.core.shared.protocol.SharedProtocol.VERSION) return availability();
        if (!state.capabilities().worldNamespace().equals(copy.from().worldNamespace())) return "wrong_world";
        return null;
    }
    /** An explicit source-verified join of the copy's original scope; on success rejoin() exports, then installs the snapshot. */
    public void rejoin(SharedWorkBackend backend) {
        if (rejoinReason(backend) != null || !requestReady(backend)) return;
        var entry = backend.entry(); var from = detached(entry, backend.kind()).from();
        prepare(backend, false, (capture, digest) -> {
            var copy = detached(entry, backend.kind());
            if (copy == null || !copy.from().equals(from)) { setReason(entry, "state_changed"); return; }
            try {
                UUID request = SharedClientNetworking.join(from);
                requests.put(request, new Request(backend, actor(), Minecraft.getInstance().getConnection(), null, capture, digest, false, System.nanoTime(), true));
                setReason(entry, "joining");
            } catch (IllegalStateException unavailable) { setReason(entry, "request_failed"); }
        });
    }
    /** Local edits of the copy are allowed whatever the journal says, but never while a save or request is open. */
    boolean detachedWritable(SharedWorkBackend backend) {
        var entry = backend.entry();
        return !closed && entry != null && !entry.loading && entry.storage == StoreStatus.OK
            && detached(entry, backend.kind()) != null && !saving(entry);
    }
    /**
     * Apply one pure edit to the detached copy on the store queue; after receives the store status on the client thread.
     * Returns false (and never calls after) when the copy is not editable now. Nothing is sent to the server.
     */
    boolean updateDetached(SharedWorkBackend backend, UnaryOperator<RegionWorkDataset> edit, Consumer<StoreStatus> after) {
        var entry = backend.entry(); var copy = detached(entry, backend.kind());
        if (copy == null || edit == null || !detachedWritable(backend)) return false;
        entry.busy++; entry.version++;
        service.updateDetached(entry.placement, copy.dataset().id(), copy.dataset().generation(), edit).whenComplete((result, failure) -> later(() -> {
            entry.busy--; publish(entry, result, failure);
            StoreStatus status = failure != null || result == null ? StoreStatus.IO_FAILURE : result.status();
            if (status != StoreStatus.OK) reload(entry, null);
            after.accept(status);
        }));
        return true;
    }
    java.nio.file.Path detachedExport(SharedWorkBackend backend, RegionWorkDataset dataset) {
        var entry = backend.entry(); return entry == null ? null : service.detachedExport(entry.placement, dataset);
    }
    /** A refused write must not leave the last confirmed archive marked CONFLICT; re-read it. */
    private void reload(Entry entry, String reason) {
        complete(entry, service.load(entry.placement), loaded -> setReason(entry, reason));
    }
    private static SharedCommand.Intent wire(TaskCommand intent) {
        return switch (intent) {
            case TaskCommand.Claim ignored -> new SharedCommand.Claim();
            case TaskCommand.Release ignored -> new SharedCommand.Release();
            case TaskCommand.Assign assign -> assign.assignee() == null ? new SharedCommand.ClearAssignment() : new SharedCommand.Assign(assign.assignee());
            case TaskCommand.SetDone done -> new SharedCommand.SetDone(done.done());
            case TaskCommand.SetNote note -> new SharedCommand.SetNote(note.note());
        };
    }
    void command(SharedWorkBackend backend, TaskId task, TaskCommand intent) {
        if (!allowed(backend, task, intent)) return;
        Entry entry = backend.entry(); var original = reference(entry, backend.kind());
        var presentation = entry.binding(backend.kind()).cache();
        prepare(backend, false, (capture, digest) -> {
            var current = entry.binding(backend.kind()).cache();
            if (!Objects.equals(original, reference(entry, backend.kind())) || !allowed(backend, task, intent) || current == null
                || current.snapshot().manifest().version() != presentation.snapshot().manifest().version()
                || !Objects.equals(current.snapshot().rows().get(task), presentation.snapshot().rows().get(task))) {
                setReason(entry, "state_changed"); return;
            }
            var selected = presentation.snapshot();
            if (!capture.source().matches(selected.manifest().definition(), digest) || !selected.rows().containsKey(task)) {
                verified.remove(original.bindingId());
                setReason(entry, "baseline_mismatch"); return;
            }
            try {
            var command = new SharedCommand(original.scope(), task, UUID.randomUUID(), selected.rows().get(task).rowVersion(), selected.manifest().version(), wire(intent));
                entry.busy++; setReason(entry, "saving_intent");
                SharedClientNetworking.submit(command, new SharedClientNetworking.DurableAdmission() {
                  @Override public CompletionStage<Boolean> persist(SharedClientNetworking.AdmissionContext context, SharedCommand value) {
                    // Exact producer presend boundary. No render-thread I/O and no Boolean-as-success conversion.
                    var persisted = service.admit(entry.placement, backend.kind(), original, context.actor(), context.connectionId(), value);
                    persisted.whenComplete((saved, failure) -> later(() -> publish(entry, saved, failure)));
                    return persisted.thenApply(SharedClientStore.Result::ok);
                  }
                  @Override public CompletionStage<Boolean> validate() {
                    try {
                        return CompletableFuture.supplyAsync(() -> SchematicSourceDigest.read(capture.source().root(), capture.source().file(),
                            capture.source().loaded().file(), capture.source().loaded().completedDigest()).status() == SchematicSourceDigest.Status.OK, digests)
                            .completeOnTimeout(false, 30, TimeUnit.SECONDS).exceptionally(failure -> false);
                    } catch (RejectedExecutionException unavailable) { return CompletableFuture.completedFuture(false); }
                  }
                  @Override public boolean current() {
                    var current = backend.capture(false);
                    return !closed && Objects.equals(original, reference(entry, backend.kind()))
                        && current.ok() && capture.source().equals(current.source());
                  }
                }).whenComplete((admitted, failure) -> later(() -> {
                    entry.busy--; entry.version++;
                    boolean unknown = entry.archive != null && SharedClientService.hasUnknown(entry.archive, original.actor(), original.scope());
                    setReason(entry, failure == null && Boolean.TRUE.equals(admitted) ? "awaiting_result" : unknown ? "unknown_outcome" : "request_failed");
                }));
            } catch (IllegalArgumentException | IllegalStateException unavailable) {
                entry.busy = Math.max(0, entry.busy - 1); setReason(entry, "request_failed");
            }
        });
    }
    private boolean requestReady(SharedWorkBackend backend) {
        var entry = backend.entry();
        if (closed || entry == null || backend.pending() || actor() == null || requests.size() >= REQUESTS
            || requests.values().stream().anyMatch(request -> request.backend().entry() == entry)
            || (entry.storage != StoreStatus.OK && entry.storage != StoreStatus.MISSING)) return false;
        if (SharedClientNetworking.state().capabilities() == null || SharedClientNetworking.state().capabilities().protocolVersion() != dev.mcmateriallist.core.shared.protocol.SharedProtocol.VERSION) { setReason(entry, availability()); return false; }
        return true;
    }
    @FunctionalInterface private interface Prepared { void accept(NativeSharedCapture.Capture capture, String digest); }
    private void prepare(SharedWorkBackend backend, boolean full, Prepared action) {
        var entry = backend.entry(); var captured = backend.capture(full);
        if (!captured.ok()) { setReason(entry, captured.reason()); return; }
        UUID actor = actor(); var listener = Minecraft.getInstance().getConnection(); var original = reference(entry, backend.kind());
        entry.busy++; setReason(entry, "checking_source");
        try {
            CompletableFuture.supplyAsync(() -> SchematicSourceDigest.read(captured.source().root(), captured.source().file(),
                captured.source().loaded().file(), captured.source().loaded().completedDigest()), digests)
                .whenComplete((read, failure) -> later(() -> {
                    entry.busy--; entry.version++;
                    var current = backend.capture(full);
                    if (!Objects.equals(actor, actor()) || listener != Minecraft.getInstance().getConnection()
                        || !Objects.equals(original, reference(entry, backend.kind()))) { setReason(entry, "selection_changed"); return; }
                    if (!current.ok() || !captured.source().equals(current.source()) || (full && !captured.materials().equals(current.materials()))) {
                        setReason(entry, current.ok() ? "baseline_mismatch" : current.reason()); return;
                    }
                    if (failure != null || read == null) { setReason(entry, "source.IO_FAILURE"); return; }
                    if (read.status() != SchematicSourceDigest.Status.OK) {
                        setReason(entry, read.status() == SchematicSourceDigest.Status.CHANGED_DURING_READ ? "source_reload_required" : "source." + read.status().name()); return;
                    }
                    action.accept(captured, read.digest());
                }));
        } catch (RejectedExecutionException capacity) { entry.busy--; setReason(entry, "capacity"); }
    }
    private void loadLocal(SharedWorkBackend backend, Consumer<DatasetId> consumer) {
        Entry entry = backend.entry(); entry.busy++; entry.version++;
        MCMaterialListClient.work().load(entry.placement, backend.kind()).whenComplete((local, failure) -> later(() -> {
            entry.busy--; entry.version++;
            if (failure != null || local == null || (!local.ok() && local.status() != StoreStatus.MISSING)) {
                setReason(entry, "local_storage_blocked"); return;
            }
            consumer.accept(local.ok() ? local.dataset().id() : null);
        }));
    }
    private void tick() {
        if (closed) return;
        var state = SharedClientNetworking.state(); UUID actor = actor();
        if (actor != null && lastActor != null && !actor.equals(lastActor)) {
            verified.clear(); for (Entry entry : entries.values()) hide(entry, null);
        }
        if (actor != null) lastActor = actor;
        if (state.synchronizedState() && state.scope() != null && actor != null) { subscribed = state.scope(); subscribedActor = actor; }
        if (state.status() == SharedClientState.Status.REVOKED && Objects.equals(state.scope(), subscribed)) subscribed = null;
        restore(state, actor);
        if (state.status() != lastStatus || !Objects.equals(state.scope(), lastScope)) {
            if (state.status() == SharedClientState.Status.REVOKED || state.status() == SharedClientState.Status.RESYNC_REQUIRED) {
                for (Entry entry : entries.values()) hide(entry, state.scope());
            }
            for (Entry entry : entries.values()) entry.version++;
            lastStatus = state.status(); lastScope = state.scope();
        }
        for (var pending : List.copyOf(requests.entrySet())) {
            var reply = SharedClientNetworking.reply(pending.getKey()); var request = pending.getValue();
            if (reply == null && System.nanoTime() - request.started() < TimeUnit.SECONDS.toNanos(30)) continue;
            requests.remove(pending.getKey()); Entry entry = request.backend().entry();
            if (reply == null) { setReason(entry, "request_unknown"); continue; }
            if (!request.actor().equals(actor) || request.listener() != Minecraft.getInstance().getConnection()
                || !Objects.equals(request.previous(), reference(entry, request.backend().kind()))) { setReason(entry, "selection_changed"); continue; }
            if (reply.status() != SharedEvents.Status.OK) {
                if (request.previous() != null) hide(entry, request.previous().scope());
                setReason(entry, "result." + reply.status().name()); continue;
            }
            var snapshot = reply.snapshot(); var current = request.backend().capture(false);
            if (!current.ok() || !current.source().equals(request.capture().source())
                || !request.capture().source().matches(snapshot.manifest().definition(), request.digest())
                || (!request.capture().materials().isEmpty() && !request.capture().materials().equals(snapshot.manifest().definition().materials()))) {
                setReason(entry, "definitions_mismatch"); continue;
            }
            if (request.rejoin()) {
                var kind = request.backend().kind();
                loadLocal(request.backend(), local -> {
                    var copy = detached(entry, kind);
                    if (copy == null || reference(entry, kind) != null || !copy.from().equals(snapshot.scope()) || !request.actor().equals(actor())) {
                        setReason(entry, "selection_changed"); return;
                    }
                    // Export first, then the fresh server snapshot replaces the copy; nothing is merged (I5).
                    complete(entry, service.rejoin(entry.placement, copy.dataset().id(), copy.dataset().generation(), local, request.actor(),
                        snapshot, snapshot.projectRevision()), result -> {
                        if (result.ok()) validate(entry, kind, request.capture().source(), request.digest(), request.listener());
                        else reload(entry, "unshare.state_changed");
                    });
                });
            } else if (request.select()) {
                loadLocal(request.backend(), local -> {
                    if (!Objects.equals(request.previous(), reference(entry, request.backend().kind())) || !request.actor().equals(actor())) {
                        setReason(entry, "selection_changed"); return;
                    }
                    complete(entry, service.select(entry.placement, request.backend().kind(), local, request.actor(), snapshot, snapshot.projectRevision()), result -> {
                        if (result.ok()) {
                            if (request.previous() != null) verified.remove(request.previous().bindingId());
                            validate(entry, request.backend().kind(), request.capture().source(), request.digest(), request.listener());
                        }
                    });
                });
            } else if (request.previous() != null) {
                complete(entry, service.cache(entry.placement, request.backend().kind(), request.previous(), snapshot, snapshot.projectRevision()), result -> {
                    if (result.ok()) validate(entry, request.backend().kind(), request.capture().source(), request.digest(), request.listener());
                });
            }
        }
        for (var pending : List.copyOf(admins.entrySet())) {
            var response = SharedClientNetworking.reply(pending.getKey()); var admin = pending.getValue();
            if (response == null && System.nanoTime() - admin.started() < TimeUnit.SECONDS.toNanos(30)) continue;
            admins.remove(pending.getKey());
            if (!admin.original().actor().equals(actor) || admin.listener() != Minecraft.getInstance().getConnection()) continue;
            hide(admin.entry(), admin.original().scope());
            setReason(admin.entry(), response == null ? "request_unknown" : response.status() == SharedEvents.Status.OK ? "permissions_saved" : "result." + response.status().name());
        }
        for (Entry entry : entries.values()) {
            if (entry.archive == null) continue;
            for (var operation : entry.archive.operations()) {
                if (operation.confirmation() != null || !operation.original().actor().equals(actor) || entry.confirming.contains(operation.command().operationId())) continue;
                UUID id = operation.command().operationId();
                var replayed = SharedClientNetworking.replayed(actor, operation.original().scope(), id);
                if (replayed != null) {
                    // A transient refusal of the replay itself proves nothing; the original stays unknown until a later installation.
                    if (!SharedClientService.definitive(replayed.status())) continue;
                    entry.confirming.add(id);
                    complete(entry, service.resolve(entry.placement, actor, replayed), saved -> {
                        entry.confirming.remove(id);
                        if (!saved.ok()) return;
                        replays.remove(id);
                        // The replay was answered after a fresh contiguous installation, so the view is not stale and
                        // stays visible; access or definition changes still arrive as their own invalidation/revocation.
                        var current = reference(entry, operation.kind());
                        if (current != null && current.actor().equals(operation.original().actor()) && current.scope().equals(operation.original().scope()))
                            setReason(entry, "result." + replayed.status().name());
                    });
                    continue;
                }
                var original = new SharedClientNetworking.AdmissionContext(operation.original().actor(), operation.connection(), operation.original().scope());
                var result = SharedClientNetworking.result(original, operation.command().operationId()); if (result == null) continue;
                entry.confirming.add(operation.command().operationId());
                complete(entry, service.confirm(entry.placement, original.actor(), original.connectionId(), result), saved -> {
                    entry.confirming.remove(operation.command().operationId());
                    if (saved.ok() && operation.original().equals(reference(entry, operation.kind()))) {
                        if (result.status() == SharedEvents.Status.CONFLICT || result.status() == SharedEvents.Status.DENIED || result.status() == SharedEvents.Status.MISSING)
                            hide(entry, operation.original().scope());
                        setReason(entry, "result." + result.status().name());
                    }
                });
            }
            replay(entry, state, actor);
            if (!state.synchronizedState() || state.snapshot() == null || entry.storage != StoreStatus.OK) continue;
            for (var kind : DatasetKind.values()) {
                // A detached copy has no reference and never receives server rows (#11 I5).
                var binding = entry.binding(kind); var ref = binding.shared();
                if (ref == null || !ref.actor().equals(actor) || !ref.scope().equals(state.scope()) || entry.hidden.contains(ref.scope())
                    || !verified.containsKey(ref.bindingId()) || entry.caching.contains(kind)
                    || SharedClientService.hasUnknown(entry.archive, actor, ref.scope())
                    || entry.archive.operations().stream().anyMatch(operation -> operation.original().scope().equals(ref.scope())
                        && operation.confirmation() != null && operation.confirmation().projectRevision() > state.cursor())) continue;
                var cache = new SharedClientArchive.Cache(state.snapshot(), state.cursor());
                if (cache.equals(binding.cache())) continue;
                entry.caching.add(kind);
                complete(entry, service.cache(entry.placement, kind, ref, state.snapshot(), state.cursor()), saved -> entry.caching.remove(kind));
            }
        }
    }
    /**
     * Resends each unknown original once per fresh installation of its scope, for the same account, only while that
     * scope's binding holds source provenance verified against the installed manifest. Anything the previous transport
     * sent is either reflected in that snapshot or lost with its cleared transfers, so the server's replay answer
     * settles it; the journal is never cancelled, remapped or answered locally.
     */
    private void replay(Entry entry, SharedClientState state, UUID actor) {
        if (actor == null || entry.archive == null || entry.busy != 0 || !state.synchronizedState() || state.snapshot() == null) return;
        var snapshot = state.snapshot();
        var due = new Replayed(Minecraft.getInstance().getConnection(), state.installations());
        proofs.keySet().retainAll(verified.keySet());
        boolean proven = false;
        for (var kind : DatasetKind.values()) {
            var ref = reference(entry, kind); var proof = ref == null ? null : verified.get(ref.bindingId());
            // The proof must come from this installation's own join validation, never from an earlier connection.
            if (ref == null || proof == null || !ref.actor().equals(actor) || !ref.scope().equals(state.scope())
                || entry.hidden.contains(ref.scope()) || !due.equals(proofs.get(ref.bindingId()))
                || !proof.source().matches(snapshot.manifest().definition(), proof.digest())) continue;
            var held = entry.backends.get(kind); var backend = held == null ? null : held.get();
            if (backend != null) {
                var current = backend.capture(false);
                if (!current.ok() || !proof.source().equals(current.source())) continue;
            }
            proven = true;
        }
        // A replay answer of DENIED cannot distinguish "not executed" from "retained outcome withheld after a role
        // change", so only replay what the current role could still submit.
        if (!proven || !snapshot.canWrite(actor)) return;
        for (var operation : SharedClientService.unknown(entry.archive, actor, state.scope())) {
            UUID id = operation.command().operationId(); var intent = operation.command().intent();
            if ((intent instanceof SharedCommand.Assign || intent instanceof SharedCommand.ClearAssignment) && !snapshot.canAdminister(actor)) continue;
            if (entry.confirming.contains(id) || due.equals(replays.get(id))) continue;
            if (!SharedClientNetworking.replay(operation.command(), actor)) continue;
            if (replays.size() >= REPLAYS && !replays.containsKey(id)) replays.remove(replays.keySet().iterator().next());
            replays.put(id, due);
        }
    }
    /**
     * Reconnect: rejoin the scope the previous connection was synchronized to, once per connection and only while the
     * user has not selected anything on it. This is the same source-verified join as Refresh work, so a changed
     * definition, revoked access or wrong world still blocks shared edits instead of being accepted silently.
     */
    private void restore(SharedClientState state, UUID actor) {
        var listener = Minecraft.getInstance().getConnection();
        if (listener == null || listener == restored || actor == null) return;
        if (state.status() == SharedClientState.Status.UNAVAILABLE || state.status() == SharedClientState.Status.NEGOTIATING) return;
        var target = subscribed;
        if (state.status() != SharedClientState.Status.AVAILABLE || target == null || !actor.equals(subscribedActor)
            || !state.capabilities().worldNamespace().equals(target.worldNamespace())) { restored = listener; return; }
        for (Entry entry : entries.values()) for (var kind : DatasetKind.values()) {
            // ref == null also covers a detached copy: reconnect never rejoins it; only Rejoin shared regions does (#11).
            var held = entry.backends.get(kind); var backend = held == null ? null : held.get(); var ref = reference(entry, kind);
            if (backend == null || ref == null || !ref.actor().equals(actor) || !ref.scope().equals(target)) continue;
            // Wait for this entry to settle; reopening its screen supplies a fresh backend if this one is gone.
            if (backend.pending() || entry.storage != StoreStatus.OK || requests.size() >= REQUESTS
                || requests.values().stream().anyMatch(request -> request.backend().entry() == entry)) return;
            restored = listener;
            prepare(backend, false, (capture, digest) -> {
                if (listener != Minecraft.getInstance().getConnection() || closed || !Objects.equals(ref, reference(entry, kind))
                    || SharedClientNetworking.state().status() != SharedClientState.Status.AVAILABLE) { setReason(entry, null); return; }
                try {
                    UUID request = SharedClientNetworking.join(ref.scope());
                    requests.put(request, new Request(backend, actor, listener, ref, capture, digest, false, System.nanoTime()));
                    setReason(entry, "joining");
                } catch (IllegalStateException unavailable) { setReason(entry, "request_failed"); }
            });
            return;
        }
    }
    private void validate(Entry entry, DatasetKind kind, NativeSharedCapture.Source source, String digest, ClientPacketListener listener) {
        var ref = reference(entry, kind); if (ref == null) return;
        var state = SharedClientNetworking.state();
        if (listener != Minecraft.getInstance().getConnection() || !ref.actor().equals(actor()) || !state.synchronizedState()
            || !ref.scope().equals(state.scope()) || !source.matches(state.snapshot().manifest().definition(), digest)) {
            hide(entry, ref.scope()); setReason(entry, "join_required"); return;
        }
        verified.put(ref.bindingId(), new Verified(source, digest)); proofs.put(ref.bindingId(), new Replayed(listener, state.installations()));
        entry.hidden.remove(ref.scope()); entry.reason = null; entry.version++;
    }
    private void hide(Entry entry, ProjectScope scope) {
        if (entry.archive == null) return;
        boolean found = false;
        for (var kind : DatasetKind.values()) {
            var binding = entry.binding(kind); var ref = binding.shared();
            if (ref != null && (scope == null || ref.scope().equals(scope))) {
                entry.hidden.add(ref.scope()); verified.remove(ref.bindingId()); found |= binding.cache() != null;
            }
        }
        entry.reason = null; entry.version++;
        if (found) complete(entry, service.clearCaches(entry.placement, scope), ignored -> {});
    }
    private void complete(Entry entry, CompletableFuture<SharedClientStore.Result> future, Consumer<SharedClientStore.Result> after) {
        entry.busy++; entry.version++;
        future.whenComplete((result, failure) -> later(() -> {
            entry.busy--; publish(entry, result, failure);
            if (failure == null && result != null) after.accept(result);
        }));
    }
    private static void publish(Entry entry, SharedClientStore.Result result, Throwable failure) {
        if (failure != null || result == null) { entry.storage = StoreStatus.IO_FAILURE; entry.reason = "storage.IO_FAILURE"; }
        else {
            // Queue/lock refusal is transient; it does not invalidate the last confirmed archive.
            if (result.status() != StoreStatus.BUSY || entry.archive == null) entry.storage = result.status();
            if (result.ok()) {
                entry.archive = result.value();
                entry.hidden.removeIf(scope -> Arrays.stream(DatasetKind.values()).noneMatch(kind -> {
                    var ref = reference(entry, kind); return ref != null && ref.scope().equals(scope);
                }));
            }
            else if (result.status() != StoreStatus.MISSING) entry.reason = "storage." + result.status().name();
        }
        entry.version++;
    }
    static void setReason(Entry entry, String reason) { if (entry != null) { entry.reason = reason; entry.version++; } }
    private void later(Runnable callback) { Minecraft.getInstance().schedule(() -> { if (!closed) callback.run(); }); }
    public void shutdown() { closed = true; requests.clear(); admins.clear(); verified.clear(); replays.clear(); proofs.clear(); digests.shutdown(); service.shutdown(); }
}
