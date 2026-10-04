package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import java.util.*;
import java.util.concurrent.*;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.shared.transport.*;
import dev.mcmateriallist.fabric.shared.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Client connection lifetime; kept separate from optional Litematica/local work. */
public final class SharedClientNetworking {
    private static SharedClientState state = new SharedClientState();
    private static Connection connection;
    private static SharedControlPayload.Reason diagnostic;
    private static final Map<UUID, SharedMessage.Snapshot> replies = new LinkedHashMap<>();
    private static final Map<UUID, Confirmed> results = new LinkedHashMap<>();
    private static final Map<UUID, Pending> unresolved = new LinkedHashMap<>();
    private static final int LIMIT = 64;
    private enum Kind { CREATE, JOIN, ADMIN, DISCOVERY }
    private record Request(Kind kind, ProjectScope scope, long started) {}
    private record Decoded(SharedMessage message) {}
    private record Admission(SharedCommand command, boolean durable, CompletableFuture<Boolean> result) {}
    private record Pending(SharedCommand command, AdmissionContext context) {}
    private record Confirmed(SharedEvents.CommandResult result, AdmissionContext context) {}
    private static final class Connection {
        final UUID token = UUID.randomUUID();
        final ClientPacketListener listener;
        final Map<UUID, Request> requests = new LinkedHashMap<>();
        final TransportBudget incomingBudget = new TransportBudget(128L * 1024 * 1024),
            frameBudget = new TransportBudget(2L * SharedTransfers.FRAME_BYTES * LIMIT),
            encoding = new TransportBudget(2L * SharedLimits.MESSAGE_BYTES),
            flight = new TransportBudget(8L * SharedTransfers.FRAME_BYTES * 8);
        final BoundedTransfers receiver = new BoundedTransfers(incomingBudget);
        final BoundedOutgoing outgoing = new BoundedOutgoing(new TransportBudget(64L * 1024 * 1024), LIMIT);
        final ArrayBlockingQueue<Decoded> decoded = new ArrayBlockingQueue<>(LIMIT);
        final ArrayBlockingQueue<Admission> admissions = new ArrayBlockingQueue<>(LIMIT);
        final AtomicBoolean failed = new AtomicBoolean();
        final AtomicLong epoch = new AtomicLong();
        final ThreadPoolExecutor worker;
        volatile boolean open = true;
        long helloStarted;
        UUID selectedRequest;
        int ticks;
        Connection(ClientPacketListener listener) {
            this.listener = listener;
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(LIMIT), job -> {
                Thread thread = new Thread(job, "mcmateriallist-shared-client-wire"); thread.setDaemon(false); return thread;
            });
        }
        boolean work(Runnable job) {
            if (!open) return false;
            try { worker.execute(job); return true; } catch (RejectedExecutionException capacity) { failed.set(true); return false; }
        }
        void receive(SharedTransfers.Fragment fragment) {
            long receivedEpoch = epoch.get();
            long bytes = 2L * SharedTransfers.FRAME_BYTES;
            if (!frameBudget.reserve(bytes)) { failed.set(true); return; }
            if (!work(() -> {
                try {
                    if (!open || receivedEpoch != epoch.get()) return;
                    receiver.accept(fragment, now(), message -> {
                        if (!decoded.offer(new Decoded(SharedProtocol.decode(message)))) failed.set(true);
                    });
                } catch (IllegalArgumentException invalid) { failed.set(true); }
                finally { frameBudget.release(bytes); }
            })) frameBudget.release(bytes);
        }
        boolean encode(SharedMessage message) {
            long preparedEpoch = epoch.get();
            long generation = outgoing.generation();
            return work(() -> {
                if (!open || preparedEpoch != epoch.get()) return;
                long bytes = 2L * SharedLimits.MESSAGE_BYTES;
                if (!encoding.reserve(bytes)) { failed.set(true); return; }
                ProjectScope scope = message instanceof SharedMessage.TaskCommand command ? command.command().scope() : null;
                try {
                    byte[] bytesToSend = SharedProtocol.encode(message);
                    if (!open || preparedEpoch != epoch.get()) return;
                    if (!outgoing.offer(token, scope, bytesToSend, now(), generation) && preparedEpoch == epoch.get()) failed.set(true);
                }
                catch (IllegalArgumentException invalid) { failed.set(true); }
                finally { encoding.release(bytes); }
            });
        }
        void clearTransfers() { clearTransfers(true); }
        void clearTransfers(boolean discardDecoded) {
            epoch.incrementAndGet(); outgoing.clear(); if (discardDecoded) decoded.clear();
            if (!work(receiver::clear)) failed.set(true);
        }
        void close() {
            open = false; outgoing.clear(); decoded.clear(); worker.shutdown();
            Thread drain = new Thread(() -> {
                try { while (!worker.awaitTermination(1, TimeUnit.SECONDS)) { /* accepted bounded decode work */ } receiver.clear(); outgoing.clear(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "mcmateriallist-shared-client-drain");
            drain.setDaemon(false); drain.start();
            Admission admission;
            while ((admission = admissions.poll()) != null) admission.result().complete(false);
        }
    }
    private SharedClientNetworking() {}
    public static void initialize() {
        ClientPlayConnectionEvents.INIT.register((listener, client) -> {
            // Capture the actual listener, so an old render callback cannot target a replacement connection.
            ClientPlayNetworking.registerReceiver(SharedPayload.TYPE, (payload, context) -> {
                Connection current = connection;
                if (current != null && current.listener == listener && listener == context.client().getConnection()) current.receive(payload.fragment());
            });
            ClientPlayNetworking.registerReceiver(SharedControlPayload.TYPE, (payload, context) -> {
                Connection current = connection;
                if (current == null || current.listener != listener || listener != context.client().getConnection()) return;
                diagnostic = payload.reason();
                switch (payload.reason()) {
                    case LOADING -> state.negotiating();
                    case UNSUPPORTED -> state.unsupported();
                    case UNAVAILABLE -> state.unavailable();
                    case CAPACITY -> state.capacity();
                    default -> state.failed();
                }
                current.clearTransfers();
            });
        });
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
            if (connection != null) connection.close();
            connection = new Connection(listener); state = new SharedClientState(); diagnostic = null; replies.clear(); results.clear();
            negotiate();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            if (connection != null && connection.listener == listener) { connection.close(); connection = null; state.disconnected(); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (connection != null) { connection.close(); connection = null; state.disconnected(); }
        });
    }
    /** Every public operation is render-thread owned; transport support never selects shared UI. */
    public static SharedClientState state() { return state; }
    public static SharedControlPayload.Reason diagnostic() { return diagnostic; }
    private static Connection current() {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) throw new IllegalStateException("Shared operations require the client thread");
        Connection current = connection;
        if (current == null || !current.open || current.listener != client.getConnection() || !ClientPlayNetworking.canSend(SharedPayload.TYPE))
            throw new IllegalStateException("Shared server unavailable");
        return current;
    }
    public static boolean negotiate() {
        Connection current = connection;
        if (current == null || !ClientPlayNetworking.canSend(SharedPayload.TYPE)) { state.unavailable(); return false; }
        state.negotiating(); current.helloStarted = now();
        return current.encode(new SharedMessage.Hello(SharedProtocol.VERSION));
    }
    private static UUID request(Kind kind, ProjectScope scope, java.util.function.Function<UUID, SharedMessage> message) {
        Connection current = current();
        if (state.capabilities() == null || state.capabilities().protocolVersion() != 1 || current.requests.size() >= LIMIT)
            throw new IllegalStateException("Shared request unavailable");
        if (kind == Kind.JOIN || kind == Kind.CREATE) {
            current.clearTransfers(); current.requests.clear(); replies.clear();
            if (scope != null) state.joining(scope); else state.failed();
        }
        UUID request = UUID.randomUUID(); current.requests.put(request, new Request(kind, scope, now()));
        if (kind == Kind.JOIN || kind == Kind.CREATE) current.selectedRequest = request;
        if (!current.encode(message.apply(request))) { current.requests.remove(request); state.failed(); }
        return request;
    }
    public static UUID discover() { return request(Kind.DISCOVERY, null,
        id -> new SharedMessage.ListProjects(id, state.capabilities().worldNamespace())); }
    public static UUID create(String name, SharedManifest.Definition definition) { return request(Kind.CREATE, null,
        id -> new SharedMessage.CreateProject(id, state.capabilities().worldNamespace(), name, definition)); }
    public static UUID join(ProjectScope scope) { return request(Kind.JOIN, scope, id -> new SharedMessage.JoinProject(id, scope)); }
    public static SharedMessage.Snapshot reply(UUID request) { return replies.get(request); }
    /** Current binding presentation; original journal confirmations use the explicit-context overload. */
    public static SharedEvents.CommandResult result(UUID operation) {
        Confirmed confirmed = results.get(operation);
        if (confirmed == null || !Objects.equals(confirmed.context().scope(), state.scope())) return null;
        return result(confirmed.context(), operation);
    }
    /** Bounded ephemeral confirmation for the exact original live actor/connection/journal scope. */
    public static SharedEvents.CommandResult result(AdmissionContext original, UUID operation) {
        Objects.requireNonNull(original); Objects.requireNonNull(operation);
        Confirmed confirmed = results.get(operation); Connection current = connection;
        if (confirmed == null || !confirmed.context().equals(original)
            || !confirmed.result().operationId().equals(operation) || !confirmed.result().scope().equals(original.scope())
            || current == null || !current.open || current.listener != Minecraft.getInstance().getConnection()
            || !current.token.equals(original.connectionId()) || Minecraft.getInstance().player == null
            || !Minecraft.getInstance().player.getUUID().equals(original.actor())) return null;
        return confirmed.result();
    }
    /** Only the original live actor/connection and currently selected scope may observe command contents. */
    public static List<SharedCommand> unconfirmedOperations() {
        Connection current = connection;
        if (current == null || !current.open || Minecraft.getInstance().player == null) return List.of();
        UUID actor = Minecraft.getInstance().player.getUUID();
        return unresolved.values().stream().filter(pending -> pending.context().connectionId().equals(current.token)
            && pending.context().actor().equals(actor) && Objects.equals(pending.context().scope(), state.scope()))
            .map(Pending::command).toList();
    }
    public static UUID setViewer(ProjectScope scope, long revision, UUID target, boolean granted) { return request(Kind.ADMIN, scope,
        id -> new SharedMessage.SetViewer(id, scope, revision, target, granted)); }
    public static UUID updateManifest(ProjectScope scope, long revision, long manifest, SharedManifest.Definition definition) { return request(Kind.ADMIN, scope,
        id -> new SharedMessage.UpdateManifest(id, scope, revision, manifest, definition)); }
    public record AdmissionContext(UUID actor, UUID connectionId, ProjectScope scope) {}
    @FunctionalInterface public interface DurableAdmission {
        /** Completes true only after persisting this exact immutable command durably. */
        CompletionStage<Boolean> persist(AdmissionContext context, SharedCommand command);
    }
    public static CompletionStage<Boolean> submit(SharedCommand command, DurableAdmission journal) {
        Objects.requireNonNull(command); Objects.requireNonNull(journal);
        Connection current = current();
        if (!canWrite(command) || unresolved.size() >= LIMIT || unresolved.containsKey(command.operationId()))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> sent = new CompletableFuture<>();
        CompletionStage<Boolean> durable;
        AdmissionContext context = new AdmissionContext(Minecraft.getInstance().player.getUUID(), current.token, command.scope());
        unresolved.put(command.operationId(), new Pending(command, context));
        try { durable = Objects.requireNonNull(journal.persist(context, command)); }
        catch (RuntimeException failure) { unresolved.remove(command.operationId()); return CompletableFuture.completedFuture(false); }
        durable.whenComplete((admitted, failure) -> {
            boolean accepted = failure == null && Boolean.TRUE.equals(admitted);
            if (!current.open) { sent.complete(false); return; }
            if (!current.admissions.offer(new Admission(command, accepted, sent))) { current.failed.set(true); sent.complete(false); }
        });
        return sent.minimalCompletionStage();
    }
    private static boolean canWrite(SharedCommand command) {
        ProjectSnapshot snapshot = state.snapshot();
        return Minecraft.getInstance().player != null && state.synchronizedState() && snapshot != null && snapshot.scope().equals(command.scope())
            && snapshot.canWrite(Minecraft.getInstance().player.getUUID()) && snapshot.rows().containsKey(command.taskId())
            && snapshot.rows().get(command.taskId()).rowVersion() == command.expectedRowVersion()
            && snapshot.manifest().version() == command.expectedManifestVersion();
    }
    private static <T> void retain(Map<UUID, T> history, UUID id, T value) {
        if (history.size() >= LIMIT && !history.containsKey(id)) history.remove(history.keySet().iterator().next());
        history.put(id, value);
    }
    private static void decoded(Connection current, SharedMessage message) {
        if (message instanceof SharedMessage.Snapshot snapshot) {
            Request request = current.requests.remove(snapshot.requestId());
            if (request == null || request.kind() == Kind.DISCOVERY) return;
            if (snapshot.status() == SharedEvents.Status.OK) {
                ProjectSnapshot value = snapshot.snapshot();
                if (state.capabilities() == null || !value.scope().worldNamespace().equals(state.capabilities().worldNamespace())
                    || (request.scope() != null && !request.scope().equals(value.scope()))) return;
                if (request.kind() != Kind.CREATE && !state.accepts(value)) {
                    if (Objects.equals(request.scope(), state.scope())) state.failed();
                    return;
                }
            }
            if (request.kind() != Kind.ADMIN && !snapshot.requestId().equals(current.selectedRequest)) return;
            if (request.kind() == Kind.ADMIN && !Objects.equals(request.scope(), state.scope())) return;
            retain(replies, snapshot.requestId(), snapshot);
            if (request.kind() == Kind.ADMIN) { state.failed(); return; }
            if (snapshot.status() == SharedEvents.Status.OK && request.kind() == Kind.CREATE) state.joining(snapshot.snapshot().scope());
            state.receive(snapshot);
        } else if (message instanceof SharedMessage.Projects projects) {
            Request request = current.requests.remove(projects.requestId());
            if (request == null || request.kind() != Kind.DISCOVERY) return;
            state.receive(message);
        } else if (message instanceof SharedMessage.CommandResult result) {
            Pending pending = unresolved.get(result.result().operationId());
            if (pending == null || !pending.context().connectionId().equals(current.token)
                || Minecraft.getInstance().player == null || !pending.context().actor().equals(Minecraft.getInstance().player.getUUID())) return;
            SharedCommand original = pending.command();
            if (!original.scope().equals(result.result().scope())
                || (result.result().row() != null && !original.taskId().equals(result.result().row().taskId()))) return;
            unresolved.remove(original.operationId()); retain(results, original.operationId(), new Confirmed(result.result(), pending.context())); state.receive(message);
        } else {
            state.receive(message);
            if (message instanceof SharedMessage.AccessRevoked revoked) {
                replies.values().removeIf(reply -> reply.snapshot() != null && reply.snapshot().scope().equals(revoked.scope()));
                results.values().removeIf(result -> result.context().scope().equals(revoked.scope()));
                current.requests.values().removeIf(request -> Objects.equals(request.scope(), revoked.scope()));
                if (revoked.scope().equals(state.scope())) current.clearTransfers();
            } else if (message instanceof SharedMessage.ProjectInvalidated invalidated && invalidated.invalidated().scope().equals(state.scope()))
                current.clearTransfers(false);
        }
    }
    private static void tick(Minecraft client) {
        Connection current = connection; if (current == null || !current.open || current.listener != client.getConnection()) return;
        if (current.failed.getAndSet(false)) { state.failed(); current.clearTransfers(); }
        for (int i = 0; i < 16; i++) {
            Decoded decoded = current.decoded.poll(); if (decoded == null) break; decoded(current, decoded.message());
        }
        for (int i = 0; i < 16; i++) {
            Admission admission = current.admissions.poll(); if (admission == null) break;
            if (!admission.durable()) { unresolved.remove(admission.command().operationId()); admission.result().complete(false); }
            else if (!canWrite(admission.command()) || !pendingHere(current, admission.command())) admission.result().complete(false);
            else admission.result().complete(current.encode(new SharedMessage.TaskCommand(admission.command())));
        }
        if (state.status() == SharedClientState.Status.NEGOTIATING && now() - current.helloStarted >= 30_000) state.unavailable();
        if (current.requests.values().removeIf(request -> now() - request.started() >= 30_000)) { state.failed(); current.clearTransfers(); }
        if (!current.outgoing.expire(now()).isEmpty()) { state.failed(); current.clearTransfers(); }
        if (++current.ticks % 20 == 0) current.work(() -> { if (current.receiver.expire(now())) current.failed.set(true); });
        TickAllowance sends = new TickAllowance(2);
        while (sends.take()) {
            var frame = current.outgoing.poll(now()); if (frame == null) break;
            if (frame.scope() != null && (!state.synchronizedState() || state.snapshot() == null
                    || !frame.scope().equals(state.snapshot().scope()))) { current.clearTransfers(); break; }
            if (!ClientPlayNetworking.canSend(SharedPayload.TYPE)) { state.unsupported(); current.clearTransfers(); break; }
            long bytes = 8L * SharedTransfers.FRAME_BYTES;
            TransportBudget.Reservation release = current.flight.reserveOnce(bytes);
            if (release == null) { state.failed(); current.clearTransfers(); break; }
            try { current.listener.getConnection().send(ClientPlayNetworking.createServerboundPacket(new SharedPayload(frame.fragment())), future -> release.run()); }
            catch (RuntimeException failure) { release.run(); state.failed(); current.clearTransfers(); }
        }
    }
    private static boolean pendingHere(Connection current, SharedCommand command) {
        Pending pending = unresolved.get(command.operationId());
        return pending != null && pending.command().equals(command) && pending.context().connectionId().equals(current.token)
            && Minecraft.getInstance().player != null && pending.context().actor().equals(Minecraft.getInstance().player.getUUID());
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
}
