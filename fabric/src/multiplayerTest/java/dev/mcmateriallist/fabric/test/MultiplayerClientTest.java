package dev.mcmateriallist.fabric.test;

import static dev.mcmateriallist.core.shared.SharedEvents.Status.*;

import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import dev.mcmateriallist.core.shared.protocol.SharedProtocol;
import dev.mcmateriallist.core.shared.protocol.SharedTransfers;
import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.fabric.shared.SharedPayload;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue #8: one of two real client processes (role alpha or beta, distinct offline accounts) against one separate
 * dedicated-server process that has neither Litematica nor MaLiLib. The Gradle harness launches all three, restarts
 * the server once on request and checks every PASS marker. Steps rendezvous through files in a coordination directory;
 * every assertion reads the actual socket-delivered state of this client or a fresh authoritative snapshot.
 * Test-only; excluded from the application JAR. Synthetic accounts and data only.
 */
public final class MultiplayerClientTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("mcmateriallist-multiplayer-test");
    private static final Duration STEP = Duration.ofSeconds(120), BOOT = Duration.ofSeconds(360), PEER = Duration.ofSeconds(600);
    /** The durable client journal has its own core/UI coverage; this harness exercises the transport and authority. */
    private static final SharedClientNetworking.DurableAdmission ADMIT = (admission, command) -> CompletableFuture.completedFuture(true);
    private static final MaterialTaskId M0 = material(0), M1 = material(1), M2 = material(2), M3 = material(3);
    private final String role = System.getProperty("mcmateriallist.multiplayer.role", "");
    private final Path coordination = Path.of(System.getProperty("mcmateriallist.multiplayer.dir", "."));
    private ClientGameTestContext context;
    private UUID actor;

    private record Sent(SharedCommand command, SharedEvents.CommandResult result) {}

    @Override public void runTest(ClientGameTestContext context) {
        this.context = context;
        try {
            check(role.equals("alpha") || role.equals("beta"), "Unknown multiplayer role: " + role);
            if (role.equals("alpha")) alpha(); else beta();
            mark(role + "-finished", "");
            LOG.info("Multiplayer {} all assertions PASS", role);
        } catch (RuntimeException | Error failure) {
            try { mark("failed-" + role, String.valueOf(failure)); } catch (RuntimeException ignored) { /* best effort */ }
            LOG.error("Multiplayer {} failed", role, failure);
            throw failure;
        }
    }

    /** Administrator: creates the projects and drives membership, definition and restart steps. */
    private void alpha() {
        connect(1);
        mark("alpha-uuid", actor.toString());
        // AC-14 isolation fixture: a second project beta never belongs to.
        ProjectSnapshot isolated = create("isolated fixture", definition('c'));
        mark("isolated-scope", encode(isolated.scope()));
        ProjectSnapshot created = create("multiplayer fixture", definition('a'));
        ProjectScope scope = created.scope();
        mark("scope", encode(scope));
        UUID beta = UUID.fromString(awaitFile("beta-uuid"));
        membership(scope, beta, ProjectRole.MEMBER);
        mark("member-granted", "");
        awaitFile("beta-joined");

        // AC-17: two clients synchronize assignment, completion and notes through the dedicated server.
        Sent done = submit(M0, new SharedCommand.SetDone(true));
        check(done.result().status() == OK && done.result().row().done() && actor.equals(done.result().row().assignee()),
            "Alpha completion was not confirmed: " + done.result());
        mark("alpha-done-m0", Long.toString(done.result().projectRevision()));
        awaitFile("beta-noted");
        until("beta note delta", client -> {
            var row = row(M1); return row != null && "beta note".equals(row.note()) && beta.equals(row.assignee());
        });

        race(beta);

        // AC-13: authority changes while beta is offline.
        awaitFile("beta-offline");
        Sent offline = submit(M0, new SharedCommand.SetNote("while beta offline"));
        check(offline.result().status() == OK, "Alpha offline-period edit failed: " + offline.result());
        mark("alpha-offline-edit", Long.toString(offline.result().projectRevision()));
        awaitFile("beta-reconnected");

        // AC-17: a definition change invalidates every subscriber until it re-fetches.
        var current = snapshot();
        UUID update = context.computeOnClient(client -> SharedClientNetworking.updateManifest(scope, current.projectRevision(),
            current.manifest().version(), definition('b')));
        var updated = reply(update);
        check(updated.status() == OK && updated.snapshot().manifest().version() == current.manifest().version() + 1,
            "Definition update failed: " + updated.status());
        settleInvalidation();
        var refreshed = join(scope);
        check(refreshed.manifest().version() == current.manifest().version() + 1
            && "while beta offline".equals(refreshed.rows().get(M0).note()), "Definition update lost confirmed work");
        mark("alpha-manifest", Long.toString(refreshed.manifest().version()));
        awaitFile("beta-refetched");

        // AC-14: a Viewer's direct edit is refused by the authority and changes nothing.
        membership(scope, beta, ProjectRole.VIEWER);
        mark("alpha-viewer", "");
        awaitFile("beta-viewer-denied");
        check("beta note 2".equals(join(scope).rows().get(M1).note()), "Viewer edit changed authoritative work");

        // Restart: a fresh dedicated-server process serves the same durable work and the same retained outcomes.
        String before = fingerprint(join(scope));
        mark("before-restart", before);
        awaitFile("beta-before-restart");
        mark("restart", "requested by alpha");
        reconnectAfterRestart(2);
        check(before.equals(fingerprint(join(scope))), "Server restart changed authoritative work");
        var again = replay(done.command());
        check(again.equals(done.result()), "Replay after restart did not return the original outcome: " + again);
        check(before.equals(fingerprint(snapshot())), "Replay after restart executed the operation again");
        mark("alpha-restarted", "");
        awaitFile("beta-restarted");

        // AC-17: content delivery to a removed participant stops.
        membership(scope, beta, null);
        mark("alpha-revoked", "");
        awaitFile("beta-revocation-observed");
        Sent after = submit(M0, new SharedCommand.SetNote("after revocation"));
        check(after.result().status() == OK, "Post-revocation edit failed: " + after.result());
        mark("alpha-after-revocation", Long.toString(after.result().projectRevision()));
        awaitFile("beta-final");

        // AC-14: the project beta never belonged to was not rewritten by its forged command.
        var untouched = join(isolated.scope());
        check(untouched.projectRevision() == 0 && untouched.rows().values().stream()
            .noneMatch(row -> row.done() || row.assignee() != null || !row.note().isEmpty()), "Isolated project was modified");
        disconnect();
    }

    /** Member, later Viewer, finally removed: exercises delivery, lost responses, reconnect and refusal. */
    private void beta() {
        connect(1);
        mark("beta-uuid", actor.toString());
        UUID alpha = UUID.fromString(awaitFile("alpha-uuid"));
        awaitFile("member-granted");
        ProjectScope scope = decode(awaitFile("scope")), isolated = decode(awaitFile("isolated-scope"));
        context.runOnClient(client -> SharedClientNetworking.discover());
        until("discovery", client -> SharedClientNetworking.state().projects().stream().anyMatch(project -> project.scope().equals(scope)));
        check(context.computeOnClient(client -> SharedClientNetworking.state().projects().stream()
            .noneMatch(project -> project.scope().equals(isolated))), "Discovery exposed a project beta cannot read");
        // AC-14: no read access to another project, and a forged raw command cannot rewrite it.
        var denied = joinReply(isolated);
        check(denied.status() == DENIED && denied.snapshot() == null, "Non-participant read another project: " + denied.status());
        var forged = new SharedCommand(isolated, M0, UUID.randomUUID(), 0, 0, new SharedCommand.SetDone(true));
        context.runOnClient(client -> sendRaw(new SharedMessage.TaskCommand(forged)));
        // The refused join leaves no readable subscription; the authority refuses the forged command and withholds
        // its content-free answer. Alpha's final check proves the other project stayed untouched.
        settleInvalidation(); settleInvalidation();
        var joined = join(scope);
        check(joined.role(actor) == ProjectRole.MEMBER, "Beta did not join as Member: " + joined.role(actor));
        mark("beta-joined", "");

        long alphaRevision = Long.parseLong(awaitFile("alpha-done-m0"));
        until("alpha completion delta", client -> {
            var row = row(M0); return row != null && row.done() && alpha.equals(row.assignee()) && alpha.equals(row.completedBy());
        });
        check(cursor() >= alphaRevision, "Beta cursor did not reach alpha's confirmed revision");
        check(submit(M1, new SharedCommand.Claim()).result().status() == OK, "Beta claim failed");
        check(submit(M1, new SharedCommand.SetNote("beta note")).result().status() == OK, "Beta note failed");
        mark("beta-noted", "");

        race(alpha);
        lostAckOnSameConnection(scope);

        // AC-12/13: the ACK is lost and the connection closes; the replacement connection retrieves authority and
        // settles the original by exact replay without executing it twice.
        var note = command(M1, new SharedCommand.SetNote("beta note 2"));
        MultiplayerFaults.dropResult(note.operationId());
        admit(note);
        until("own note delta despite dropped ACK", client -> { var row = row(M1); return row != null && "beta note 2".equals(row.note()); });
        until("note ACK dropped", client -> MultiplayerFaults.dropped(note.operationId()));
        long noted = cursor();
        disconnect();
        // Only after the socket is closed, so alpha's edit cannot reach beta live.
        mark("beta-offline", "");
        long offlineRevision = Long.parseLong(awaitFile("alpha-offline-edit"));
        connect(1);
        var resynced = join(scope);
        check(resynced.projectRevision() >= offlineRevision && "while beta offline".equals(resynced.rows().get(M0).note()),
            "Reconnect did not retrieve authority changed while offline");
        var settled = replay(note);
        check(settled.status() == OK && settled.projectRevision() == noted && "beta note 2".equals(settled.row().note()),
            "Replay on the replacement connection did not return the original outcome: " + settled);
        check(cursor() == resynced.projectRevision(), "Replay after reconnect executed the operation again");
        mark("beta-reconnected", "");

        long manifest = Long.parseLong(awaitFile("alpha-manifest"));
        until("definition invalidation", client -> SharedClientNetworking.state().status() == SharedClientState.Status.RESYNC_REQUIRED);
        var refetched = join(scope);
        check(refetched.manifest().version() == manifest && "beta note 2".equals(refetched.rows().get(M1).note()),
            "Re-fetched definition or work did not match the authority");
        mark("beta-refetched", "");

        awaitFile("alpha-viewer");
        until("membership invalidation", client -> SharedClientNetworking.state().status() == SharedClientState.Status.RESYNC_REQUIRED);
        var viewer = join(scope);
        check(viewer.role(actor) == ProjectRole.VIEWER, "Beta was not demoted to Viewer");
        var edit = command(M1, new SharedCommand.SetNote("viewer edit"));
        var gated = context.computeOnClient(client -> SharedClientNetworking.submit(edit, ADMIT)).toCompletableFuture();
        until("client admission gate", client -> gated.isDone());
        check(!gated.join(), "Client admitted a Viewer edit");
        var refused = replay(edit);
        check(refused.status() == DENIED && refused.row() == null, "Authority accepted a Viewer edit: " + refused);
        check("beta note 2".equals(snapshot().rows().get(M1).note()), "Viewer refusal changed presentation");
        mark("beta-viewer-denied", "");

        String before = awaitFile("before-restart");
        check(before.equals(fingerprint(join(scope))), "Beta view diverged from alpha before restart");
        mark("beta-before-restart", "");
        reconnectAfterRestart(2);
        check(before.equals(fingerprint(join(scope))), "Beta view after restart differs from the persisted authority");
        mark("beta-restarted", "");

        awaitFile("alpha-revoked");
        until("access revoked", client -> SharedClientNetworking.state().status() == SharedClientState.Status.REVOKED);
        check(context.computeOnClient(client -> SharedClientNetworking.state().snapshot() == null), "Revoked participant retained private rows");
        mark("beta-revocation-observed", "");
        long afterRevocation = Long.parseLong(awaitFile("alpha-after-revocation"));
        long quiet = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() - quiet < 0) pause();
        check(MultiplayerFaults.latestDelta(scope) < afterRevocation, "A delta reached the removed participant after revocation");
        check(context.computeOnClient(client -> SharedClientNetworking.state().status() == SharedClientState.Status.REVOKED
            && SharedClientNetworking.state().snapshot() == null), "Content reached a revoked participant");
        var after = joinReply(scope);
        check(after.status() == DENIED && after.snapshot() == null, "Removed participant could still join: " + after.status());
        mark("beta-final", "");
        disconnect();
    }

    /** AC-11: both claims are sent unconditionally at a shared barrier, so the authority decides the race. */
    private void race(UUID other) {
        mark("race-ready-" + role, "");
        awaitFile("race-ready-" + peer());
        var current = snapshot();
        // Fixed expected version 0 and the gate-free replay path guarantee both commands reach the authority.
        var claim = new SharedCommand(current.scope(), M2, UUID.randomUUID(), 0, current.manifest().version(), new SharedCommand.Claim());
        var mine = replay(claim).status();
        mark("race-" + role, mine.name());
        var theirs = SharedEvents.Status.valueOf(awaitFile("race-" + peer()));
        check((mine == OK) != (theirs == OK), "Concurrent claims did not have exactly one winner: " + mine + "/" + theirs);
        check(mine == OK || mine == DENIED || mine == CONFLICT, "Unexpected concurrent claim outcome: " + mine);
        UUID winner = mine == OK ? actor : other;
        until("confirmed assignee on every client", client -> {
            var row = row(M2); return row != null && winner.equals(row.assignee()) && row.rowVersion() == 1;
        });
        LOG.info("Multiplayer {} concurrent claim {} winner={}", role, mine, winner.equals(actor) ? role : peer());
    }

    /** AC-12: a response lost after the authority saved it; replay returns the original and duplicates never execute. */
    private void lostAckOnSameConnection(ProjectScope scope) {
        var done = command(M3, new SharedCommand.SetDone(true));
        MultiplayerFaults.dropResult(done.operationId());
        admit(done);
        until("own completion delta despite dropped ACK", client -> { var row = row(M3); return row != null && row.done() && actor.equals(row.assignee()); });
        until("completion ACK dropped", client -> MultiplayerFaults.dropped(done.operationId()));
        check(context.computeOnClient(client -> SharedClientNetworking.result(done.operationId()) == null), "Dropped ACK reached presentation");
        long executed = cursor(); var row = snapshot().rows().get(M3);
        join(scope);
        var settled = replay(done);
        check(settled.status() == OK && settled.projectRevision() == executed && row.equals(settled.row()),
            "Replay did not return the original outcome: " + settled);
        check(cursor() == executed && row.equals(snapshot().rows().get(M3)), "Duplicate operation executed twice");
        var stale = new SharedCommand(scope, M3, UUID.randomUUID(), row.rowVersion() - 1, snapshot().manifest().version(), new SharedCommand.SetDone(false));
        var conflict = replay(stale);
        check(conflict.status() == CONFLICT && row.equals(conflict.row()), "Stale command was not rejected against the latest row: " + conflict);
        check(cursor() == executed && snapshot().rows().get(M3).done(), "Stale command changed authority");
    }

    private ProjectSnapshot create(String name, SharedManifest.Definition definition) {
        UUID request = context.computeOnClient(client -> SharedClientNetworking.create(name, definition));
        var reply = reply(request);
        check(reply.status() == OK && reply.snapshot() != null, "Create failed: " + reply.status());
        until("created project synchronized", client -> synchronizedOn(reply.snapshot().scope()));
        return reply.snapshot();
    }
    private void membership(ProjectScope scope, UUID target, ProjectRole role) {
        var current = snapshot();
        check(current.scope().equals(scope), "Membership change requires the selected scope");
        UUID request = context.computeOnClient(client -> SharedClientNetworking.setMembership(scope, current.projectRevision(), target, role));
        var reply = reply(request);
        check(reply.status() == OK && Objects.equals(reply.snapshot().role(target), role), "Membership change failed: " + reply.status());
        settleInvalidation();
        join(scope);
    }
    /** An administration reply precedes its invalidation event; let that event land before re-joining. */
    private void settleInvalidation() {
        long settle = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() - settle < 0) pause();
    }
    private ProjectSnapshot join(ProjectScope scope) {
        for (int attempt = 1; ; attempt++) {
            UUID request = context.computeOnClient(client -> SharedClientNetworking.join(scope));
            var reply = replyWithin(request, Duration.ofSeconds(15));
            if (reply == null) {
                // A diagnostic that cleared this transport can discard the queued request; ask again.
                check(attempt < 5, "Join reply never arrived: " + diagnostics());
                continue;
            }
            check(reply.status() == OK, "Join refused: " + reply.status());
            long settle = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!context.computeOnClient(client -> synchronizedOn(scope)) && System.nanoTime() - settle < 0) pause();
            // A newer invalidation in flight can fence an older snapshot; re-join against the newer revision.
            if (context.computeOnClient(client -> synchronizedOn(scope))) return snapshot();
            check(attempt < 5, "Join did not synchronize: " + diagnostics());
        }
    }
    private SharedMessage.Snapshot joinReply(ProjectScope scope) {
        return reply(context.computeOnClient(client -> SharedClientNetworking.join(scope)));
    }
    private SharedMessage.Snapshot replyWithin(UUID request, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (context.computeOnClient(client -> SharedClientNetworking.reply(request)) == null) {
            peerHealthy();
            if (System.nanoTime() - deadline > 0) return null;
            pause();
        }
        return context.computeOnClient(client -> SharedClientNetworking.reply(request));
    }
    private SharedMessage.Snapshot reply(UUID request) {
        until("correlated reply", client -> SharedClientNetworking.reply(request) != null);
        return context.computeOnClient(client -> SharedClientNetworking.reply(request));
    }
    private SharedCommand command(TaskId task, SharedCommand.Intent intent) {
        var snapshot = snapshot();
        return new SharedCommand(snapshot.scope(), task, UUID.randomUUID(), snapshot.rows().get(task).rowVersion(), snapshot.manifest().version(), intent);
    }
    private void admit(SharedCommand command) {
        var sent = context.computeOnClient(client -> SharedClientNetworking.submit(command, ADMIT)).toCompletableFuture();
        until("command admission", client -> sent.isDone());
        check(sent.join(), "Command was not admitted: " + command.intent());
    }
    private Sent submit(TaskId task, SharedCommand.Intent intent) {
        var command = command(task, intent);
        admit(command);
        until("command result", client -> SharedClientNetworking.result(command.operationId()) != null);
        return new Sent(command, context.computeOnClient(client -> SharedClientNetworking.result(command.operationId())));
    }
    /** Exact resend through the production replay path; it applies no client-side permission or version gate. */
    private SharedEvents.CommandResult replay(SharedCommand command) {
        check(context.computeOnClient(client -> SharedClientNetworking.replay(command, actor)), "Replay was not sent: " + diagnostics());
        until("replay result", client -> SharedClientNetworking.replayed(actor, command.scope(), command.operationId()) != null);
        return context.computeOnClient(client -> SharedClientNetworking.replayed(actor, command.scope(), command.operationId()));
    }

    private void connect(int boot) {
        awaitFile("server-ready", value -> value.equals(Integer.toString(boot)), BOOT);
        String address = "localhost:" + read("port");
        context.setScreen(TitleScreen::new);
        context.runOnClient(client -> ConnectScreen.startConnecting(client.gui.screen(), client, ServerAddress.parseString(address),
            new ServerData("MCMaterialList multiplayer", address, ServerData.Type.OTHER), false, null));
        until("world join", BOOT, client -> client.level != null && client.player != null && client.gui.screen() == null);
        actor = context.computeOnClient(client -> client.player.getUUID());
        until("shared capabilities", client -> SharedClientNetworking.state().capabilities() != null);
        LOG.info("Multiplayer {} connected boot={} actor={}", role, boot, actor);
    }
    private void disconnect() {
        context.runOnClient(client -> {
            if (client.level == null) return;
            client.level.disconnect(Component.literal("Disconnecting"));
            client.disconnectWithSavingScreen();
        });
        until("disconnected", client -> client.level == null);
        context.waitTicks(2);
        context.setScreen(TitleScreen::new);
    }
    private void reconnectAfterRestart(int boot) {
        until("server-initiated disconnect", BOOT, client -> client.level == null);
        context.waitTicks(2);
        context.setScreen(TitleScreen::new);
        connect(boot);
    }

    private void until(String what, Predicate<Minecraft> condition) { until(what, STEP, condition); }
    private void until(String what, Duration timeout, Predicate<Minecraft> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!context.computeOnClient(client -> condition.test(client))) {
            peerHealthy();
            if (System.nanoTime() - deadline > 0) throw new AssertionError(role + " timed out waiting for " + what + "; " + diagnostics());
            pause();
        }
    }
    private void pause() {
        context.waitTick();
        try { Thread.sleep(10); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("Interrupted", interrupted); }
    }
    private String diagnostics() {
        return context.computeOnClient(client -> {
            var state = SharedClientNetworking.state();
            return "status=" + state.status() + " scope=" + state.scope() + " cursor=" + state.cursor()
                + " connected=" + (client.level != null) + " screen=" + (client.gui.screen() == null ? null : client.gui.screen().getClass().getSimpleName());
        });
    }
    private ProjectSnapshot snapshot() {
        var snapshot = context.computeOnClient(client -> SharedClientNetworking.state().snapshot());
        check(snapshot != null, "No synchronized snapshot: " + diagnostics());
        return snapshot;
    }
    private long cursor() { return context.computeOnClient(client -> SharedClientNetworking.state().cursor()); }
    private static TaskState row(TaskId task) {
        var snapshot = SharedClientNetworking.state().snapshot();
        return snapshot == null ? null : snapshot.rows().get(task);
    }
    private static boolean synchronizedOn(ProjectScope scope) {
        var state = SharedClientNetworking.state();
        return state.synchronizedState() && scope.equals(state.scope()) && state.snapshot() != null;
    }
    private static void sendRaw(SharedMessage message) {
        for (var fragment : SharedTransfers.split(UUID.randomUUID(), SharedProtocol.encode(message)))
            ClientPlayNetworking.send(new SharedPayload(fragment));
    }

    private String peer() { return role.equals("alpha") ? "beta" : "alpha"; }
    private void peerHealthy() {
        Path failure = coordination.resolve("failed-" + peer());
        if (Files.exists(failure)) throw new AssertionError(role + " stopped because " + peer() + " failed: " + read("failed-" + peer()));
    }
    private String awaitFile(String name) { return awaitFile(name, value -> true, PEER); }
    private String awaitFile(String name, Predicate<String> accepted, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Path file = coordination.resolve(name);
            if (Files.exists(file)) { String value = read(name); if (accepted.test(value)) return value; }
            peerHealthy();
            if (System.nanoTime() - deadline > 0) throw new AssertionError(role + " timed out waiting for " + name + "; " + diagnostics());
            pause();
        }
    }
    private String read(String name) {
        try { return Files.readString(coordination.resolve(name), StandardCharsets.UTF_8).trim(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private void mark(String name, String value) {
        try {
            Path temporary = coordination.resolve(name + "." + role + ".tmp");
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, coordination.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        LOG.info("Multiplayer {} reached {}", role, name);
    }

    private static MaterialTaskId material(int index) { return new MaterialTaskId("fixture:m" + index); }
    private static SharedManifest.Definition definition(char digest) {
        Map<MaterialTaskId, Long> materials = new HashMap<>();
        for (int i = 0; i < 6; i++) materials.put(material(i), 1L + i);
        return new SharedManifest.Definition(String.valueOf(digest).repeat(64), new SharedManifest.Baseline("minecraft:overworld",
            new Vector3(0, 0, 0), SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), materials,
            List.of(new RegionDescriptor("multiplayer", new Vector3(0, 0, 0), new Vector3(2, 2, 2))));
    }
    /** Comparable across processes: revision, manifest, roles, rows and reconfirmation markers. */
    private static String fingerprint(ProjectSnapshot snapshot) {
        return snapshot.projectRevision() + "|" + snapshot.manifest().version() + "|" + new TreeMap<>(snapshot.members()) + "|"
            + snapshot.rows().values().stream().map(Object::toString).sorted().collect(Collectors.joining(";")) + "|"
            + snapshot.needsReconfirmation().stream().map(Object::toString).sorted().collect(Collectors.joining(";"));
    }
    private static String encode(ProjectScope scope) {
        return scope.worldNamespace() + "," + scope.projectId() + "," + scope.materialListId() + "," + scope.regionListId();
    }
    private static ProjectScope decode(String value) {
        String[] parts = value.split(",");
        check(parts.length == 4, "Malformed scope fixture");
        return new ProjectScope(UUID.fromString(parts[0]), UUID.fromString(parts[1]), UUID.fromString(parts[2]), UUID.fromString(parts[3]));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
