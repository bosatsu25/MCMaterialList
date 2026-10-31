package dev.mcmateriallist.fabric.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationResult;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.ProjectRole;
import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.shared.transport.TickAllowance;
import dev.mcmateriallist.core.stock.LocationReport;
import dev.mcmateriallist.core.stock.StockAdmission;
import dev.mcmateriallist.core.stock.StockGrantStore;
import dev.mcmateriallist.core.stock.StockGrants;
import dev.mcmateriallist.core.stock.StockLimits;
import dev.mcmateriallist.core.stock.StockPolicy;
import dev.mcmateriallist.core.stock.StockScan;
import dev.mcmateriallist.core.stock.StockScheduler;
import dev.mcmateriallist.core.stock.protocol.StockMessage;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle-owned Check Materials authority on the stock channel. Everything but grant-file I/O runs on the server thread: requests
 * are admitted (one scan per player, cooldown, server capacity), scans are stepped by a bounded StockScheduler from the server
 * tick, and reports stream at most FRAMES_PER_TICK frames per player and tick, followed by Done. The actor is always the
 * connection's player. Grant files are read and written on one owned background thread; live access checks only consult the
 * in-memory cache. Nothing here references work, completion, counts or .litematic files.
 */
public final class StockServerNetworking {
    public static final int INCOMING_PER_TICK = 4, GRANT_JOBS = 16, COMPLETIONS_PER_TICK = 64;
    private static final Logger LOG = LoggerFactory.getLogger("mcmateriallist");
    private static final Map<MinecraftServer, Runtime> RUNTIMES = new ConcurrentHashMap<>();
    private StockServerNetworking() {}

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> RUNTIMES.put(server, new Runtime(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { Runtime runtime = RUNTIMES.get(server); if (runtime != null) runtime.stop(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RUNTIMES.remove(server));
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> { Runtime runtime = RUNTIMES.get(server); if (runtime != null) runtime.disconnected(listener); });
        ServerPlayNetworking.registerGlobalReceiver(StockPayload.TYPE, (payload, context) -> {
            Runtime runtime = RUNTIMES.get(context.server());
            if (runtime != null) runtime.received(context.player(), payload.message());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { Runtime runtime = RUNTIMES.get(server); if (runtime != null) runtime.tick(); });
    }
    /** Server thread: tick-wide maxima of the scheduler since start (measurement; only the container count is a gate). */
    public static StockScheduler.Stats stats(MinecraftServer server) {
        Runtime runtime = RUNTIMES.get(server); return runtime == null ? new StockScheduler.Stats(0, 0) : runtime.scheduler.stats();
    }
    /** Server thread: scans currently admitted. */
    public static int running(MinecraftServer server) { Runtime runtime = RUNTIMES.get(server); return runtime == null ? 0 : runtime.admission.running(); }

    private static final class Active {
        final UUID actor;
        final ServerGamePacketListenerImpl listener;
        final StockMessage.ScanRequest request;
        StockScan scan;
        Active(ServerPlayer player, StockMessage.ScanRequest request) { actor = player.getUUID(); listener = player.connection; this.request = request; }
    }

    private static final class Runtime {
        final MinecraftServer server;
        final StockGrantStore store;
        final ServerContainerProbe probe;
        final StockAdmission admission = new StockAdmission(Clock.systemUTC());
        final StockScheduler scheduler = new StockScheduler();
        final ThreadPoolExecutor io;
        final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
        /** Server thread only: committed grant registries by projectId, and projects whose file is being read. */
        final Map<UUID, StockGrants> grants = new HashMap<>();
        final Set<UUID> loading = new HashSet<>();
        final Map<UUID, Active> scans = new LinkedHashMap<>();
        final Map<UUID, TickAllowance> incoming = new HashMap<>();
        int grantJobs;
        volatile boolean open = true;

        Runtime(MinecraftServer server) {
            this.server = server;
            store = new StockGrantStore(server.getWorldPath(LevelResource.ROOT));
            probe = new ServerContainerProbe(server);
            io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2 * GRANT_JOBS), runnable -> {
                Thread thread = new Thread(runnable, "mcmateriallist-stock-grants"); thread.setDaemon(false); return thread;
            });
        }

        void received(ServerPlayer player, StockMessage message) {
            if (!open || player == null || player.connection == null) return;
            if (!incoming.computeIfAbsent(player.getUUID(), ignored -> new TickAllowance(INCOMING_PER_TICK)).take()) {
                if (message instanceof StockMessage.ScanRequest request) send(player, new StockMessage.Refused(request.requestId(), StockMessage.RefusalReason.BUSY));
                return;
            }
            switch (message) {
                case StockMessage.ScanRequest request -> scan(player, request);
                case StockMessage.Cancel cancel -> {
                    Active active = scans.get(player.getUUID());
                    if (active != null && active.listener == player.connection && active.request.requestId().equals(cancel.requestId())) {
                        scans.remove(active.actor); cancel(active, true); admission.finish(active.actor);
                    }
                }
                case StockMessage.GrantRequest request -> grant(player, request.requestId(), request.scope(), request.dimension(), request.positions(), false);
                case StockMessage.RevokeRequest request -> grant(player, request.requestId(), request.scope(), request.dimension(), request.positions(), true);
                // Server-to-client messages are never accepted from a client.
                default -> { }
            }
        }

        void scan(ServerPlayer player, StockMessage.ScanRequest request) {
            UUID actor = player.getUUID();
            StockAdmission.Verdict verdict = admission.admit(actor);
            if (verdict != StockAdmission.Verdict.ADMITTED) {
                send(player, new StockMessage.Refused(request.requestId(), verdict == StockAdmission.Verdict.COOLDOWN
                    ? StockMessage.RefusalReason.COOLDOWN : StockMessage.RefusalReason.CAPACITY));
                return;
            }
            // A new request replaces the running one; the admission slot stays held by this player.
            Active previous = scans.remove(actor);
            if (previous != null) cancel(previous, true);
            Active active = new Active(player, request);
            scans.put(actor, active);
            ProjectScope binding = request.binding();
            if (binding != null && !grants.containsKey(binding.projectId()) && !admin(actor)) {
                if (!load(binding.projectId())) {
                    scans.remove(actor); admission.finish(actor);
                    send(player, new StockMessage.Refused(request.requestId(), StockMessage.RefusalReason.BUSY));
                }
                return;  // starts when the grant file has been read off-thread
            }
            start(active);
        }
        void start(Active active) {
            StockScan.Access access = StockPolicy.live(() -> open && admin(active.actor), active.actor, active.request.binding(), this::role, grants::get);
            active.scan = new StockScan(active.request.dimension(), active.request.positions(), probe, access);
            scheduler.add(active.scan);
        }
        boolean admin(UUID actor) {
            ServerPlayer current = server.getPlayerList().getPlayer(actor);
            return current != null && StockAuthority.serverAdmin(server, current);
        }
        Optional<ProjectRole> role(UUID actor, ProjectScope scope) {
            try { return SharedServerNetworking.service(server).committedSnapshot(actor, scope).map(snapshot -> snapshot.role(actor)); }
            catch (IllegalStateException unavailable) { return Optional.empty(); }
        }

        /** Reads one project's grant file off-thread; false when the I/O queue is full. */
        boolean load(UUID projectId) {
            if (!loading.add(projectId)) return true;
            try {
                io.execute(() -> {
                    LocationResult<StockGrants> result = store.load(projectId);
                    completions.add(() -> loaded(projectId, result));
                });
                return true;
            } catch (RejectedExecutionException full) { loading.remove(projectId); return false; }
        }
        void loaded(UUID projectId, LocationResult<StockGrants> result) {
            loading.remove(projectId);
            // A damaged or unreadable file grants nothing and is not cached, so the next request reads it again.
            if (result.ok()) grants.put(projectId, result.value());
            else if (result.status() == StoreStatus.MISSING) grants.put(projectId, StockGrants.empty(projectId));
            for (Active active : List.copyOf(scans.values()))
                if (active.scan == null && active.request.binding() != null && active.request.binding().projectId().equals(projectId)) start(active);
        }

        void grant(ServerPlayer player, UUID requestId, ProjectScope scope, String dimension, List<ContainerPosition> positions, boolean revoke) {
            UUID actor = player.getUUID();
            if (!StockPolicy.mayGrant(StockAuthority.serverAdmin(server, player), role(actor, scope).orElse(null))) {
                send(player, new StockMessage.GrantReply(requestId, StockMessage.GrantStatus.UNAUTHORIZED, dimension, List.of())); return;
            }
            if (grantJobs >= GRANT_JOBS) { send(player, new StockMessage.GrantReply(requestId, StockMessage.GrantStatus.BUSY, dimension, List.of())); return; }
            UUID projectId = scope.projectId();
            long now = System.currentTimeMillis();
            ServerGamePacketListenerImpl listener = player.connection;
            try {
                io.execute(() -> {
                    LocationResult<StockGrants> result = positions.isEmpty() ? store.load(projectId)
                        : store.update(projectId, current -> revoke ? current.revoke(dimension, positions) : current.grant(dimension, positions, actor, now));
                    completions.add(() -> granted(actor, listener, requestId, projectId, dimension, positions.isEmpty(), result));
                });
                grantJobs++;
            } catch (RejectedExecutionException full) {
                send(player, new StockMessage.GrantReply(requestId, StockMessage.GrantStatus.BUSY, dimension, List.of()));
            }
        }
        void granted(UUID actor, ServerGamePacketListenerImpl listener, UUID requestId, UUID projectId, String dimension, boolean listing,
                LocationResult<StockGrants> result) {
            grantJobs--;
            StockGrants value = result.ok() ? result.value() : listing && result.status() == StoreStatus.MISSING ? StockGrants.empty(projectId) : null;
            if (value != null) grants.put(projectId, value);
            StockMessage.GrantStatus status = value != null ? StockMessage.GrantStatus.OK : switch (result.status()) {
                case BUSY -> StockMessage.GrantStatus.BUSY;
                case CONFLICT -> StockMessage.GrantStatus.CONFLICT;
                case TOO_LARGE -> StockMessage.GrantStatus.CAPACITY;
                default -> StockMessage.GrantStatus.FAILED;
            };
            ServerPlayer player = server.getPlayerList().getPlayer(actor);
            if (player != null && player.connection == listener)
                send(player, new StockMessage.GrantReply(requestId, status, dimension, value == null ? List.of() : value.in(dimension)));
            LOG.info("Stock grant {} status={} grants={}", listing ? "list" : "change", status, value == null ? -1 : value.grants().size());
        }

        void tick() {
            if (!open) return;
            incoming.values().forEach(TickAllowance::reset);
            for (int i = 0; i < COMPLETIONS_PER_TICK; i++) { Runnable completion = completions.poll(); if (completion == null) break; completion.run(); }
            scheduler.tick();
            for (Active active : new ArrayList<>(scans.values())) {
                if (active.scan == null) continue;
                ServerPlayer player = server.getPlayerList().getPlayer(active.actor);
                if (player == null || player.connection != active.listener || !active.listener.isAcceptingMessages()
                    || !ServerPlayNetworking.canSend(player, StockPayload.TYPE)) {
                    scans.remove(active.actor); cancel(active, false); admission.finish(active.actor); continue;
                }
                int frames = 0;
                while (frames < StockLimits.FRAMES_PER_TICK) {
                    LocationReport report = active.scan.poll();
                    if (report == null) break;
                    send(player, new StockMessage.Report(active.request.requestId(), report)); frames++;
                }
                if (frames < StockLimits.FRAMES_PER_TICK && active.scan.finished()) {
                    scans.remove(active.actor); admission.finish(active.actor);
                    send(player, done(active, StockMessage.Outcome.COMPLETE));
                }
            }
        }
        StockMessage.Done done(Active active, StockMessage.Outcome outcome) {
            StockScan.Summary summary = active.scan == null ? new StockScan.Summary(0, 0, 0, 0, true) : active.scan.summary();
            LOG.info("Stock scan {} reports={} checked={} positions={} maxContainersPerTick={} maxNanosPerTick={}", outcome, summary.reports(),
                summary.checked(), active.request.positions().size(), summary.maxContainersPerTick(), summary.maxNanosPerTick());
            return new StockMessage.Done(active.request.requestId(), outcome, summary.reports(), summary.checked(), summary.maxContainersPerTick(), summary.maxNanosPerTick());
        }
        /** Pending and undrained positions are never reported; the client keeps them unchecked. */
        void cancel(Active active, boolean notify) {
            if (active.scan != null) { active.scan.cancel(); scheduler.remove(active.scan); }
            if (!notify) return;
            ServerPlayer player = server.getPlayerList().getPlayer(active.actor);
            if (player != null && player.connection == active.listener) send(player, done(active, StockMessage.Outcome.CANCELLED));
        }
        void disconnected(ServerGamePacketListenerImpl listener) {
            for (Active active : new ArrayList<>(scans.values())) if (active.listener == listener) {
                scans.remove(active.actor); cancel(active, false); admission.finish(active.actor);
            }
            if (listener.player != null) incoming.remove(listener.player.getUUID());
        }
        void send(ServerPlayer player, StockMessage message) {
            if (!open || !player.connection.isAcceptingMessages() || !ServerPlayNetworking.canSend(player, StockPayload.TYPE)) return;
            try { ServerPlayNetworking.send(player, new StockPayload(message)); }
            catch (RuntimeException failure) { LOG.warn("Stock frame not sent: {}", failure.toString()); }
        }
        void stop() {
            open = false;
            for (Active active : scans.values()) if (active.scan != null) { active.scan.cancel(); scheduler.remove(active.scan); }
            scans.clear(); incoming.clear(); completions.clear(); grants.clear(); loading.clear();
            io.shutdown();
            // Accepted grant writes finish off the server thread; nothing waits for them here.
            Thread drain = new Thread(() -> {
                try { while (!io.awaitTermination(1, TimeUnit.SECONDS)) { /* bounded accepted grant jobs */ } }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "mcmateriallist-stock-drain");
            drain.setDaemon(false); drain.start();
        }
    }
}
