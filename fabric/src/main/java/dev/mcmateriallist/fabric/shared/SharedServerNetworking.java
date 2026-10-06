package dev.mcmateriallist.fabric.shared;

import dev.mcmateriallist.core.shared.SharedProjectService;
import net.minecraft.server.MinecraftServer;
import dev.mcmateriallist.core.shared.SharedEvents;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.shared.transport.*;
import dev.mcmateriallist.core.persistence.StoreStatus;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lifecycle-owned common server transport. */
public final class SharedServerNetworking {
    public static final int CONNECTIONS = 20, WORK = 64, RESPONSES = 64, FRAMES_PER_TICK = 4;
    public static final long INBOUND_BYTES = 128L * 1024 * 1024, OUTBOUND_BYTES = 128L * 1024 * 1024;
    private static final Map<MinecraftServer, Runtime> RUNTIMES = new ConcurrentHashMap<>();
    private static final PacketContext.Key<ServerGamePacketListenerImpl> ATTEMPTED = PacketContext.key(
        Identifier.fromNamespaceAndPath("mcmateriallist", "shared_admission_listener"));
    private SharedServerNetworking() {}
    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> RUNTIMES.put(server,
            new Runtime(new SharedProjectService(new SharedProjectStore(server.getWorldPath(LevelResource.ROOT))))));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Runtime runtime = RUNTIMES.get(server); if (runtime != null) runtime.stop();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RUNTIMES.remove(server));
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            Runtime runtime = RUNTIMES.get(server); if (runtime != null) runtime.connected(listener.player);
        });
        ClientboundPlayChannelEvents.REGISTER.register((listener, sender, server, channels) -> {
            // Registration can occur inside addon construction. Do not query that addon until a later tick/receiver.
            Runtime runtime = RUNTIMES.get(server);
            if (runtime != null && (channels.contains(SharedPayload.TYPE.id()) || channels.contains(SharedControlPayload.TYPE.id())))
                runtime.registration(listener);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
            Runtime runtime = RUNTIMES.get(server); if (runtime != null) runtime.disconnected(listener);
        });
        ServerPlayNetworking.registerGlobalReceiver(SharedPayload.TYPE, (payload, context) -> {
            Runtime runtime = RUNTIMES.get(context.server());
            if (runtime != null) runtime.received(context.player(), payload.fragment());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Runtime runtime = RUNTIMES.get(server); if (runtime != null) runtime.tick();
        });
    }
    /** Owning server-thread access for administration/lifecycle; never a payload actor override. */
    public static SharedProjectService service(MinecraftServer server) {
        Runtime runtime = RUNTIMES.get(server);
        if (runtime == null) throw new IllegalStateException("Shared service unavailable");
        return runtime.processor.service;
    }
    /** Owning server-thread publication for outcomes from administrative service callers. */
    public static void publishEvents(MinecraftServer server, List<SharedEvents.Event> events) {
        Runtime runtime = RUNTIMES.get(server); if (runtime != null) runtime.events(events);
    }
    private record Hello(UUID token, int version) implements Completion {}
    private record Started(UUID token, long sequence, ProjectScope scope) implements Completion {}
    private record SnapshotReply(UUID token, UUID request, long sequence, boolean selects, SharedEvents.Reply<ProjectSnapshot> reply) implements Completion {}
    private record Discovery(UUID token, UUID request, SharedEvents.Status status) implements Completion {}
    private record Outcome(UUID token, SharedEvents.Outcome outcome) implements Completion {}
    private record Failure(UUID token, SharedControlPayload.Reason reason) implements Completion {}
    private sealed interface Completion permits Hello, Started, SnapshotReply, Discovery, Outcome, Failure {}
    /** Contains only Minecraft-free data; worker never captures players/listeners/world objects. */
    private static final class Processor {
        final SharedProjectService service;
        final Set<UUID> live = ConcurrentHashMap.newKeySet(), capable = ConcurrentHashMap.newKeySet();
        final Map<UUID, ProjectScope> subscriptions = new ConcurrentHashMap<>();
        final ArrayBlockingQueue<Completion> completions = new ArrayBlockingQueue<>(RESPONSES);
        final AtomicBoolean overflow = new AtomicBoolean();
        final TransportBudget inbound = new TransportBudget(INBOUND_BYTES), encoding = new TransportBudget(2L * SharedLimits.MESSAGE_BYTES),
            frames = new TransportBudget(2L * SharedTransfers.FRAME_BYTES * WORK), requests = new TransportBudget(RESPONSES);
        final BoundedOutgoing outgoing = new BoundedOutgoing(new TransportBudget(OUTBOUND_BYTES), RESPONSES);
        final Map<UUID, BoundedTransfers> receivers = new HashMap<>();
        final Map<UUID, Long> sequences = new HashMap<>();
        final ThreadPoolExecutor worker;
        volatile SharedProjectService.Ready ready;
        volatile boolean open = true;
        Processor(SharedProjectService service) {
            this.service = service;
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(WORK), runnable -> {
                Thread thread = new Thread(runnable, "mcmateriallist-shared-wire"); thread.setDaemon(false); return thread;
            });
            service.ready().whenComplete((value, failure) -> ready = failure == null ? value : null);
        }
        void completion(Completion completion) {
            if (open && !completions.offer(completion)) overflow.set(true);
        }
        boolean work(Runnable job) {
            if (!open) return false;
            try { worker.execute(job); return true; } catch (RejectedExecutionException full) { return false; }
        }
        void frame(UUID token, UUID actor, SharedTransfers.Fragment fragment) {
            long bytes = 2L * SharedTransfers.FRAME_BYTES;
            if (!frames.reserve(bytes)) { completion(new Failure(token, SharedControlPayload.Reason.CAPACITY)); return; }
            if (!work(() -> {
                try {
                    discardDisconnected();
                    if (!live.contains(token)) return;
                    receivers.computeIfAbsent(token, ignored -> new BoundedTransfers(inbound)).accept(fragment, now(),
                        message -> dispatch(token, actor, SharedProtocol.decode(message)));
                } catch (BoundedTransfers.Expired failure) { completion(new Failure(token, SharedControlPayload.Reason.RESYNC_REQUIRED)); }
                catch (IllegalArgumentException failure) { completion(new Failure(token, SharedControlPayload.Reason.INVALID_INPUT)); }
                finally { frames.release(bytes); }
            })) { frames.release(bytes); completion(new Failure(token, SharedControlPayload.Reason.CAPACITY)); }
        }
        void dispatch(UUID token, UUID actor, SharedMessage message) {
            if (message instanceof SharedMessage.Hello hello) { completion(new Hello(token, hello.protocolVersion())); return; }
            if (!capable.contains(token)) { completion(new Failure(token, SharedControlPayload.Reason.UNSUPPORTED)); return; }
            if (!requests.reserve(1)) { completion(new Failure(token, SharedControlPayload.Reason.CAPACITY)); return; }
            if (message instanceof SharedMessage.ListProjects request) {
                service.discover(actor, request.worldNamespace()).whenComplete((reply, failure) -> {
                    completion(new Discovery(token, request.requestId(), failure == null ? reply.status() : SharedEvents.Status.UNAVAILABLE));
                    requests.release(1);
                });
            } else if (message instanceof SharedMessage.JoinProject request) {
                long sequence = sequence(token); completion(new Started(token, sequence, request.scope()));
                snapshot(token, request.requestId(), sequence, true, service.join(actor, request.scope()));
            } else if (message instanceof SharedMessage.CreateProject request) {
                long sequence = sequence(token); completion(new Started(token, sequence, null));
                snapshot(token, request.requestId(), sequence, true, service.create(actor, request.worldNamespace(), request.name(), request.definition()));
            } else if (message instanceof SharedMessage.TaskCommand request && request.command().scope().equals(subscriptions.get(token))) {
                service.command(actor, request.command()).whenComplete((reply, failure) -> {
                    if (failure == null) completion(new Outcome(token, reply));
                    else completion(new Failure(token, SharedControlPayload.Reason.UNAVAILABLE));
                    requests.release(1);
                });
            } else if (message instanceof SharedMessage.SetViewer request && request.scope().equals(subscriptions.get(token))) {
                snapshot(token, request.requestId(), 0, false, service.setViewer(actor, request.scope(), request.expectedProjectRevision(), request.target(), request.granted()));
            } else if (message instanceof SharedMessage.UpdateManifest request && request.scope().equals(subscriptions.get(token))) {
                snapshot(token, request.requestId(), 0, false, service.updateManifest(actor, request.scope(), request.expectedProjectRevision(), request.expectedManifestVersion(), request.definition()));
            } else { requests.release(1); completion(new Failure(token, SharedControlPayload.Reason.INVALID_INPUT)); }
        }
        long sequence(UUID token) { long next = sequences.getOrDefault(token, 0L) + 1; sequences.put(token, next); return next; }
        void snapshot(UUID token, UUID request, long sequence, boolean selects, CompletionStage<SharedEvents.Reply<ProjectSnapshot>> stage) {
            stage.whenComplete((reply, failure) -> {
                completion(new SnapshotReply(token, request, sequence, selects,
                    failure == null ? reply : SharedEvents.Reply.of(SharedEvents.Status.UNAVAILABLE, null)));
                requests.release(1);
            });
        }
        void encode(UUID token, ProjectScope scope, SharedMessage message) {
            long generation = outgoing.generation();
            if (!work(() -> {
                if (!live.contains(token)) return;
                long bytes = 2L * SharedLimits.MESSAGE_BYTES;
                if (!encoding.reserve(bytes)) { completion(new Failure(token, SharedControlPayload.Reason.CAPACITY)); return; }
                try {
                    if (!outgoing.offer(token, scope, SharedProtocol.encode(message), now(), generation) && live.contains(token))
                        completion(new Failure(token, SharedControlPayload.Reason.CAPACITY));
                } catch (IllegalArgumentException failure) { completion(new Failure(token, SharedControlPayload.Reason.INVALID_INPUT)); }
                finally { encoding.release(bytes); }
            })) completion(new Failure(token, SharedControlPayload.Reason.CAPACITY));
        }
        void expire() {
            if (!work(() -> {
                discardDisconnected();
                for (var entry : receivers.entrySet()) if (entry.getValue().expire(now()))
                    completion(new Failure(entry.getKey(), SharedControlPayload.Reason.RESYNC_REQUIRED));
            })) overflow.set(true);
        }
        void discardDisconnected() {
            receivers.entrySet().removeIf(entry -> {
                if (live.contains(entry.getKey())) return false;
                entry.getValue().clear(); sequences.remove(entry.getKey()); return true;
            });
        }
        void stop() {
            open = false; live.clear(); capable.clear(); subscriptions.clear(); outgoing.clear(); completions.clear();
            worker.shutdown(); service.shutdown();
            // One lifetime-owned drain thread; accepted durable writes finish off all Minecraft threads.
            Thread drain = new Thread(() -> {
                try {
                    while (!worker.awaitTermination(1, TimeUnit.SECONDS)) { /* drain accepted bounded jobs */ }
                    for (var receiver : receivers.values()) receiver.clear();
                    receivers.clear(); outgoing.clear(); service.close();
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "mcmateriallist-shared-drain");
            drain.setDaemon(false); drain.start();
        }
    }
    private static final class Session {
        final UUID token = UUID.randomUUID(), actor;
        final ServerGamePacketListenerImpl listener;
        final TickAllowance incoming = new TickAllowance(4);
        final Deque<UUID> discovery = new ArrayDeque<>();
        ProjectScope scope;
        long sequence;
        boolean capable, waiting;
        SharedControlPayload.Reason control;
        Session(ServerPlayer player) { listener = player.connection; actor = player.getUUID(); }
    }
    private static final class Runtime {
        final Processor processor;
        final Map<UUID, Session> sessions = new LinkedHashMap<>();
        final Deque<ServerPlayer> capacityDiagnostics = new ArrayDeque<>();
        final Deque<ServerGamePacketListenerImpl> registrations = new ArrayDeque<>();
        final TransportBudget connections = new TransportBudget(CONNECTIONS), flight = new TransportBudget(8L * SharedTransfers.FRAME_BYTES * 8);
        final TickAllowance sends = new TickAllowance(FRAMES_PER_TICK), incoming = new TickAllowance(64);
        int ticks;
        Runtime(SharedProjectService service) { processor = new Processor(service); }
        boolean compatible(Session session) {
            ServerPlayer player = session.listener.player;
            return processor.open && sessions.get(session.actor) == session && player != null && player.getUUID().equals(session.actor)
                && player.connection == session.listener && session.listener.isAcceptingMessages()
                && ServerPlayNetworking.canSend(player, SharedPayload.TYPE)
                && ServerPlayNetworking.canSend(player, SharedControlPayload.TYPE);
        }
        void connected(ServerPlayer player) {
            if (!ServerPlayNetworking.canSend(player, SharedPayload.TYPE) || !ServerPlayNetworking.canSend(player, SharedControlPayload.TYPE)) return;
            if (player.connection.getPacketContext().get(ATTEMPTED) == player.connection) return;
            player.connection.getPacketContext().set(ATTEMPTED, player.connection);
            Session previous = sessions.get(player.getUUID()); if (previous != null) disconnected(previous.listener);
            if (!connections.reserve(1)) {
                if (capacityDiagnostics.size() < CONNECTIONS) capacityDiagnostics.addLast(player);
                return;
            }
            Session session = new Session(player); sessions.put(session.actor, session); processor.live.add(session.token);
        }
        void registration(ServerGamePacketListenerImpl listener) {
            if (registrations.size() < CONNECTIONS && !registrations.contains(listener)) registrations.addLast(listener);
        }
        void disconnected(ServerGamePacketListenerImpl listener) {
            Session session = sessions.values().stream().filter(value -> value.listener == listener).findFirst().orElse(null);
            if (session == null) return;
            sessions.remove(session.actor); connections.release(1); processor.live.remove(session.token);
            processor.capable.remove(session.token); processor.subscriptions.remove(session.token); processor.outgoing.remove(session.token);
        }
        void received(ServerPlayer player, SharedTransfers.Fragment fragment) {
            Session session = sessions.get(player.getUUID());
            if (session == null) { connected(player); session = sessions.get(player.getUUID()); }
            if (session == null || session.listener != player.connection || session.listener.player != player || !compatible(session)) return;
            if (!incoming.take() || !session.incoming.take()) { fail(session, SharedControlPayload.Reason.CAPACITY); return; }
            processor.frame(session.token, session.actor, fragment);
        }
        Session session(UUID token) { return sessions.values().stream().filter(value -> value.token.equals(token)).findFirst().orElse(null); }
        void fail(Session session, SharedControlPayload.Reason reason) {
            processor.outgoing.remove(session.token); session.discovery.clear(); session.control = reason;
            if (reason == SharedControlPayload.Reason.UNSUPPORTED || reason == SharedControlPayload.Reason.UNAVAILABLE) {
                session.capable = false; processor.capable.remove(session.token);
            }
        }
        void hello(Session session, int version) {
            if (version != SharedProtocol.VERSION) { fail(session, SharedControlPayload.Reason.UNSUPPORTED); return; }
            var ready = processor.ready;
            if (ready == null) { session.waiting = true; session.control = SharedControlPayload.Reason.LOADING; return; }
            session.waiting = false;
            if (ready.status() != StoreStatus.OK || ready.worldNamespace() == null) { fail(session, SharedControlPayload.Reason.UNAVAILABLE); return; }
            session.capable = true; processor.capable.add(session.token);
            processor.encode(session.token, null, SharedMessage.Capabilities.current(ready.worldNamespace()));
        }
        void enqueue(Session session, ProjectScope scope, SharedMessage message) {
            if (!compatible(session) || !session.capable) return;
            if (scope == null) processor.encode(session.token, null, message);
            else if (scope.equals(session.scope)) processor.service.publishIfAuthorized(session.actor, scope,
                () -> processor.encode(session.token, scope, message));
        }
        void completion(Completion value) {
            if (!processor.open) return;
            if (value instanceof Outcome item) events(item.outcome().events());
            requesterCompletion(value);
            // Queue an eligible admin snapshot before invalidation, without making events depend on that eligibility.
            if (value instanceof SnapshotReply item) events(item.reply().events());
        }
        void requesterCompletion(Completion value) {
            UUID token = switch (value) {
                case Hello item -> item.token(); case Started item -> item.token(); case SnapshotReply item -> item.token();
                case Discovery item -> item.token(); case Outcome item -> item.token(); case Failure item -> item.token();
            };
            Session session = session(token);
            if (session == null || !compatible(session)) return;
            switch (value) {
                case Hello item -> hello(session, item.version());
                case Started item -> {
                    session.sequence = item.sequence(); session.scope = item.scope(); processor.outgoing.remove(token);
                    if (item.scope() == null) processor.subscriptions.remove(token); else processor.subscriptions.put(token, item.scope());
                }
                case SnapshotReply item -> {
                    if (item.selects() && item.sequence() != session.sequence) return;
                    ProjectSnapshot snapshot = item.reply().status() == SharedEvents.Status.OK ? item.reply().value() : null;
                    ProjectScope scope = snapshot == null ? null : snapshot.scope();
                    if (item.selects() && snapshot != null) { session.scope = scope; processor.subscriptions.put(token, scope); }
                    enqueue(session, scope, new SharedMessage.Snapshot(item.request(), item.reply().status(), snapshot));
                }
                case Discovery item -> {
                    if (item.status() != SharedEvents.Status.OK) processor.encode(token, null,
                        new SharedMessage.Projects(item.request(), item.status(), List.of()));
                    else if (session.discovery.size() < 4) session.discovery.addLast(item.request());
                    else fail(session, SharedControlPayload.Reason.CAPACITY);
                }
                case Outcome item -> {
                    enqueue(session, item.outcome().result().scope(), new SharedMessage.CommandResult(item.outcome().result()));
                }
                case Failure item -> fail(session, item.reason());
            }
        }
        void events(List<SharedEvents.Event> events) {
            for (var event : events) {
                if (event instanceof SharedEvents.AccessRevoked revoked) {
                    Session session = sessions.get(revoked.recipient());
                    if (session == null || !revoked.scope().equals(session.scope) || !compatible(session)) continue;
                    processor.outgoing.remove(session.token); processor.subscriptions.remove(session.token);
                    session.scope = null; session.discovery.clear();
                    processor.encode(session.token, null, new SharedMessage.AccessRevoked(revoked.scope()));
                } else {
                    ProjectScope scope = event instanceof SharedEvents.ProjectDelta delta ? delta.scope() : ((SharedEvents.ProjectInvalidated) event).scope();
                    SharedMessage message = event instanceof SharedEvents.ProjectDelta delta ? new SharedMessage.ProjectDelta(delta)
                        : new SharedMessage.ProjectInvalidated((SharedEvents.ProjectInvalidated) event);
                    for (Session session : sessions.values()) if (scope.equals(session.scope) && session.capable && compatible(session)) {
                        enqueue(session, scope, message);
                    }
                }
            }
        }
        void send(Session session, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
            long bytes = 8L * SharedTransfers.FRAME_BYTES;
            TransportBudget.Reservation release = flight.reserveOnce(bytes);
            if (release == null) { fail(session, SharedControlPayload.Reason.CAPACITY); return; }
            try {
                session.listener.send(ServerPlayNetworking.createClientboundPacket(payload), future -> release.run());
            } catch (RuntimeException failure) { release.run(); fail(session, SharedControlPayload.Reason.RESYNC_REQUIRED); }
        }
        void tick() {
            if (!processor.open) return;
            sends.reset(); incoming.reset(); for (Session session : sessions.values()) session.incoming.reset();
            while (!registrations.isEmpty()) {
                ServerGamePacketListenerImpl listener = registrations.removeFirst();
                if (listener.player.connection == listener && listener.isAcceptingMessages()) connected(listener.player);
            }
            if (processor.overflow.getAndSet(false)) for (Session session : sessions.values()) fail(session, SharedControlPayload.Reason.RESYNC_REQUIRED);
            for (int i = 0; i < 16; i++) { Completion completion = processor.completions.poll(); if (completion == null) break; completion(completion); }
            for (UUID token : processor.outgoing.expire(now())) { Session session = session(token); if (session != null) fail(session, SharedControlPayload.Reason.RESYNC_REQUIRED); }
            if (++ticks % 20 == 0) processor.expire();
            while (!capacityDiagnostics.isEmpty() && sends.take()) {
                ServerPlayer player = capacityDiagnostics.removeFirst();
                if (!player.connection.isAcceptingMessages() || !ServerPlayNetworking.canSend(player, SharedControlPayload.TYPE)) continue;
                long bytes = 8L * SharedTransfers.FRAME_BYTES;
                TransportBudget.Reservation release = flight.reserveOnce(bytes);
                if (release == null) continue;
                try { player.connection.send(ServerPlayNetworking.createClientboundPacket(
                    new SharedControlPayload(SharedControlPayload.Reason.CAPACITY)), future -> release.run()); }
                catch (RuntimeException failure) { release.run(); }
            }
            for (Session session : sessions.values()) {
                if (!compatible(session)) continue;
                if (session.waiting && processor.ready != null) hello(session, SharedProtocol.VERSION);
                if (session.control != null && sends.take()) {
                    SharedControlPayload.Reason reason = session.control; session.control = null; send(session, new SharedControlPayload(reason));
                }
                if (session.capable && !session.discovery.isEmpty() && sends.take()) {
                    UUID request = session.discovery.removeFirst();
                    // At most128 bounded names: <=42KiB, one frame, within the same per-tick budget.
                    processor.service.publishDiscovery(session.actor, names -> {
                        if (!compatible(session)) return;
                        byte[] bytes = SharedProtocol.encode(new SharedMessage.Projects(request, SharedEvents.Status.OK, names));
                        send(session, new SharedPayload(new SharedTransfers.Fragment(UUID.randomUUID(), 0, 1, bytes.length, bytes)));
                    });
                }
            }
            while (sends.take()) {
                var frame = processor.outgoing.poll(now()); if (frame == null) break;
                Session session = session(frame.connection());
                if (session == null || !compatible(session) || !session.capable) continue;
                if (frame.scope() == null) send(session, new SharedPayload(frame.fragment()));
                else if (frame.scope().equals(session.scope)) processor.service.publishIfAuthorized(session.actor, frame.scope(), () -> {
                    if (compatible(session) && session.capable && frame.scope().equals(session.scope)) send(session, new SharedPayload(frame.fragment()));
                });
            }
        }
        void stop() { processor.stop(); sessions.clear(); capacityDiagnostics.clear(); registrations.clear(); }
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
}
