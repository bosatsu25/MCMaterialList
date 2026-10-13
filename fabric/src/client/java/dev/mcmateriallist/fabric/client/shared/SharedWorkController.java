package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Render-thread coordinator. Its service owns one bounded disk queue and immutable save units. */
public final class SharedWorkController {
    private static final int OPEN_PLACEMENTS = 16, REQUESTS = 16;
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
        Entry(LocalPlacementId placement) { this.placement = placement; }
        SharedClientArchive.Binding binding(DatasetKind kind) { return archive == null ? null : archive.binding(kind); }
    }
    private record Verified(NativeSharedCapture.Source source, String digest) {}
    private record Request(SharedWorkBackend backend, UUID actor, ClientPacketListener listener,
        SharedClientArchive.Reference previous, NativeSharedCapture.Capture capture, String digest,
        boolean select, long started) {}
    private record Admin(Entry entry, SharedClientArchive.Reference original, ClientPacketListener listener, long started) {}
    private final SharedClientService service;
    private final Map<LocalPlacementId, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Verified> verified = new HashMap<>();
    private final Map<UUID, Request> requests = new LinkedHashMap<>();
    private final Map<UUID, Admin> admins = new LinkedHashMap<>();
    private final ThreadPoolExecutor digests = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4), task -> { Thread thread = new Thread(task, "mcmateriallist-shared-source-read"); thread.setDaemon(false); return thread; });
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
        // Native parent screens can retain their backend until closed. Never orphan a live entry.
        if (entries.size() >= OPEN_PLACEMENTS) return null;
        Entry entry = new Entry(placement); entries.put(placement, entry);
        service.load(placement).whenComplete((result, failure) -> later(() -> {
            entry.loading = false; publish(entry, result, failure);
        }));
        return entry;
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
    String reason(SharedWorkBackend backend) {
        var entry = backend.entry();
        if (entry == null) return backend.nativeReady() ? "placement_capacity" : "placement_unavailable";
        if (entry.loading) return "loading";
        if (entry.storage != StoreStatus.OK && entry.storage != StoreStatus.MISSING) return "storage." + entry.storage.name();
        var ref = reference(entry, backend.kind());
        if (entry.busy > 0 || cachePending(backend)) return "saving";
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
        return snapshot(entry, backend.kind()).canWrite(actor()) ? "shared" : "viewer";
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
    public void viewer(SharedWorkBackend backend, UUID target, boolean granted) {
        var entry = backend.entry(); var snapshot = snapshot(entry, backend.kind());
        if (!backend.admin() || target == null || admins.size() >= REQUESTS) return;
        try {
            UUID request = SharedClientNetworking.setViewer(snapshot.scope(), snapshot.projectRevision(), target, granted);
            admins.put(request, new Admin(entry, reference(entry, backend.kind()), Minecraft.getInstance().getConnection(), System.nanoTime()));
            // The producer invalidates membership changes; only an explicit join restores writes.
            setReason(entry, "permissions_requested");
        } catch (IllegalStateException unavailable) { setReason(entry, "request_failed"); }
    }
    void command(SharedWorkBackend backend, TaskId task, TaskCommand intent) {
        if (!writable(backend) || task == null || backend.state(task) == null) return;
        Entry entry = backend.entry(); var original = reference(entry, backend.kind());
        var presentation = entry.binding(backend.kind()).cache();
        prepare(backend, false, (capture, digest) -> {
            var current = entry.binding(backend.kind()).cache();
            if (!Objects.equals(original, reference(entry, backend.kind())) || !synchronizedView(backend) || current == null
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
            SharedCommand.Intent wire = switch (intent) {
                case TaskCommand.Claim ignored -> new SharedCommand.Claim();
                case TaskCommand.Release ignored -> new SharedCommand.Release();
                case TaskCommand.Assign assign -> assign.assignee() == null ? new SharedCommand.ClearAssignment() : new SharedCommand.Assign(assign.assignee());
                case TaskCommand.SetDone done -> new SharedCommand.SetDone(done.done());
                case TaskCommand.SetNote note -> new SharedCommand.SetNote(note.note());
            };
            var command = new SharedCommand(original.scope(), task, UUID.randomUUID(), selected.rows().get(task).rowVersion(), selected.manifest().version(), wire);
                entry.busy++; setReason(entry, "saving_intent");
                SharedClientNetworking.submit(command, (context, value) -> {
                    // Exact producer presend boundary. No render-thread I/O and no Boolean-as-success conversion.
                    var persisted = service.admit(entry.placement, backend.kind(), original, context.actor(), context.connectionId(), value);
                    persisted.whenComplete((saved, failure) -> later(() -> publish(entry, saved, failure)));
                    return persisted.thenApply(SharedClientStore.Result::ok);
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
        if (SharedClientNetworking.state().capabilities() == null || SharedClientNetworking.state().capabilities().protocolVersion() != 1) { setReason(entry, availability()); return false; }
        return true;
    }
    @FunctionalInterface private interface Prepared { void accept(NativeSharedCapture.Capture capture, String digest); }
    private void prepare(SharedWorkBackend backend, boolean full, Prepared action) {
        var entry = backend.entry(); var captured = backend.capture(full);
        if (!captured.ok()) { setReason(entry, captured.reason()); return; }
        UUID actor = actor(); var listener = Minecraft.getInstance().getConnection(); var original = reference(entry, backend.kind());
        entry.busy++; setReason(entry, "checking_source");
        try {
            CompletableFuture.supplyAsync(() -> SchematicSourceDigest.read(captured.source().root(), captured.source().file()), digests)
                .whenComplete((read, failure) -> later(() -> {
                    entry.busy--; entry.version++;
                    var current = backend.capture(full);
                    if (!Objects.equals(actor, actor()) || listener != Minecraft.getInstance().getConnection()
                        || !Objects.equals(original, reference(entry, backend.kind()))) { setReason(entry, "selection_changed"); return; }
                    if (!current.ok() || !captured.source().equals(current.source()) || (full && !captured.materials().equals(current.materials()))) {
                        setReason(entry, current.ok() ? "baseline_mismatch" : current.reason()); return;
                    }
                    if (failure != null || read == null) { setReason(entry, "source.IO_FAILURE"); return; }
                    if (read.status() != SchematicSourceDigest.Status.OK) { setReason(entry, "source." + read.status().name()); return; }
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
            if (request.select()) {
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
            if (!state.synchronizedState() || state.snapshot() == null || entry.storage != StoreStatus.OK) continue;
            for (var kind : DatasetKind.values()) {
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
    private void validate(Entry entry, DatasetKind kind, NativeSharedCapture.Source source, String digest, ClientPacketListener listener) {
        var ref = reference(entry, kind); if (ref == null) return;
        var state = SharedClientNetworking.state();
        if (listener != Minecraft.getInstance().getConnection() || !ref.actor().equals(actor()) || !state.synchronizedState()
            || !ref.scope().equals(state.scope()) || !source.matches(state.snapshot().manifest().definition(), digest)) {
            hide(entry, ref.scope()); setReason(entry, "join_required"); return;
        }
        verified.put(ref.bindingId(), new Verified(source, digest)); entry.hidden.remove(ref.scope()); entry.reason = null; entry.version++;
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
    public void shutdown() { closed = true; requests.clear(); admins.clear(); verified.clear(); digests.shutdown(); service.shutdown(); }
}
