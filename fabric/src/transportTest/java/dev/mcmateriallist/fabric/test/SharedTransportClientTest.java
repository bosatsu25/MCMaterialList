package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.fabric.shared.SharedPayload;
import dev.mcmateriallist.fabric.shared.SharedControlPayload;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.core.shared.SharedLimits;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import dev.mcmateriallist.core.shared.protocol.SharedTransfers;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Test-only actual Minecraft registry buffer and socket harness. */
public final class SharedTransportClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                var frame = new SharedTransfers.Fragment(UUID.randomUUID(), 0, 1, SharedLimits.FRAGMENT_BYTES,
                    new byte[SharedLimits.FRAGMENT_BYTES]);
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), client.level.registryAccess());
                try {
                    SharedPayload.CODEC.encode(buffer, new SharedPayload(frame));
                    check(buffer.readableBytes() == 61_480, "Near-max frame lost bytes at Fabric codec boundary");
                    check(Arrays.equals(frame.bytes(), SharedPayload.CODEC.decode(buffer).fragment().bytes()), "Frame roundtrip changed bytes");
                } finally { buffer.release(); }
                var invalid = new RegistryFriendlyByteBuf(Unpooled.buffer(), client.level.registryAccess());
                try {
                    invalid.writeInt(Integer.MAX_VALUE);
                    boolean rejected = false;
                    try { SharedPayload.CODEC.decode(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
                    check(rejected, "Oversized length was accepted before allocation");
                } finally { invalid.release(); }
                var diagnostic = new RegistryFriendlyByteBuf(Unpooled.buffer(), client.level.registryAccess());
                try {
                    SharedControlPayload.CODEC.encode(diagnostic, new SharedControlPayload(SharedControlPayload.Reason.CAPACITY));
                    check(diagnostic.readableBytes() == 1, "Content-free diagnostic carried extra bytes");
                    check(SharedControlPayload.CODEC.decode(diagnostic).reason() == SharedControlPayload.Reason.CAPACITY,
                        "Diagnostic roundtrip changed reason");
                    diagnostic.writeByte(255);
                    boolean rejected = false;
                    try { SharedControlPayload.CODEC.decode(diagnostic); } catch (IllegalArgumentException expected) { rejected = true; }
                    check(rejected, "Unknown diagnostic enum accepted");
                    diagnostic.clear(); diagnostic.writeByte(0); diagnostic.writeByte(0); rejected = false;
                    try { SharedControlPayload.CODEC.decode(diagnostic); } catch (IllegalArgumentException expected) { rejected = true; }
                    check(rejected, "Diagnostic trailing bytes accepted");
                    diagnostic.clear(); rejected = false;
                    try { SharedControlPayload.CODEC.decode(diagnostic); } catch (IllegalArgumentException expected) { rejected = true; }
                    check(rejected, "Truncated diagnostic accepted");
                } finally { diagnostic.release(); }
            });
        }
        Properties properties = new Properties();
        // Fixed test-owned relative child only; Fabric deletes level-name before creation.
        properties.setProperty("level-name", "shared-transport-owned-world");
        properties.setProperty("online-mode", "false");
        properties.setProperty("server-ip", "localhost");
        properties.setProperty("server-port", Integer.toString(availableLoopbackPort()));
        TransportObservation.reset();
        try (var server = context.worldBuilder().createServer(properties); var connection = connectBounded(context, server)) {
            connection.getClientLevel().waitForChunksRender();
            try { context.waitFor(client -> SharedClientNetworking.state().capabilities() != null); }
            catch (AssertionError failure) {
                TransportObservation.report();
                boolean ready = server.computeOnServer(mc -> SharedServerNetworking.service(mc).ready().toCompletableFuture().isDone());
                org.slf4j.LoggerFactory.getLogger("mcmateriallist-transport-test").info("Shared test service ready={} client state={}",
                    ready, context.computeOnClient(client -> SharedClientNetworking.state().status()));
                throw failure;
            }
            context.runOnClient(client -> check(SharedClientNetworking.state().capabilities().protocolVersion() == 1,
                "Actual registered socket handshake did not negotiate v1"));
            verifySocket(context, server);
        }
        org.slf4j.LoggerFactory.getLogger("mcmateriallist-transport-test").info("Shared transport all assertions PASS");
    }
    private static net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection connectBounded(
            ClientGameTestContext context, net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext server) {
        var active = new java.util.concurrent.atomic.AtomicBoolean();
        long started = System.nanoTime();
        context.runOnClient(client -> net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (active.get() && (mc.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen
                    || System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(30)))
                throw new AssertionError("Controlled socket fixture failed to connect within deadline");
        }));
        active.set(true);
        try { return server.connect(); } finally { active.set(false); }
    }
    private static int availableLoopbackPort() {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException failure) { throw new AssertionError("Could not reserve test socket port", failure); }
    }
    private static SharedManifest.Definition definition() {
        Map<MaterialTaskId, Long> materials = new HashMap<>();
        for (int i = 0; i < 700; i++) materials.put(new MaterialTaskId("fixture:material_" + i + "_" + "a".repeat(85)), 1L);
        return new SharedManifest.Definition("a".repeat(64), new SharedManifest.Baseline("minecraft:overworld", new Vector3(0, 0, 0),
            SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), materials,
            List.of(new RegionDescriptor("exact", new Vector3(0, 0, 0), new Vector3(-2, 3, 4))));
    }
    private static void verifySocket(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext server) {
        context.runOnClient(client -> sendRaw(new SharedMessage.Hello(SharedProtocol.VERSION + 1)));
        context.waitFor(client -> SharedClientNetworking.state().status()
            == dev.mcmateriallist.core.shared.transport.SharedClientState.Status.UNSUPPORTED, 600);
        context.runOnClient(client -> {
            check(SharedClientNetworking.diagnostic() == SharedControlPayload.Reason.UNSUPPORTED,
                "Unsupported peer did not receive registered content-free diagnostic");
            check(SharedClientNetworking.state().capabilities() == null, "Unsupported peer retained usable capabilities");
            SharedClientNetworking.negotiate();
        });
        context.waitFor(client -> SharedClientNetworking.state().capabilities() != null, 600);
        UUID actor = context.computeOnClient(client -> client.player.getUUID());
        SharedManifest.Definition definition = definition();
        check(SharedProtocol.encode(new SharedMessage.CreateProject(UUID.randomUUID(),
            SharedClientNetworking.state().capabilities().worldNamespace(), "wire fixture", definition)).length > 61_440,
            "Fixture did not exercise full-size registered C2S frame");
        UUID create = context.computeOnClient(client -> SharedClientNetworking.create("wire fixture", definition));
        context.waitFor(client -> SharedClientNetworking.reply(create) != null, 600);
        ProjectSnapshot snapshot = context.computeOnClient(client -> SharedClientNetworking.reply(create).snapshot());
        check(snapshot != null && snapshot.creator().equals(actor), "Create actor did not come from socket connection");
        check(snapshot.rows().size() == 701, "Near-max C2S/S2C delivery lost definitions or independent region list");
        check(SharedProtocol.encode(new SharedMessage.Snapshot(create, SharedEvents.Status.OK, snapshot)).length > 61_440,
            "Fixture did not exercise full-size registered S2C frame");
        var row = definition.materials().keySet().iterator().next();
        var command = new SharedCommand(snapshot.scope(), row, UUID.randomUUID(), 0, 0, new SharedCommand.SetNote("wire confirmed"));
        var refusal = context.computeOnClient(client -> SharedClientNetworking.submit(command, (admission, ignored) -> CompletableFuture.completedFuture(false)));
        context.waitFor(client -> refusal.toCompletableFuture().isDone());
        check(!refusal.toCompletableFuture().join(), "Journal refusal sent an unsafe edit");
        check(SharedClientNetworking.result(command.operationId()) == null, "Refused edit received an ACK");
        SharedClientNetworking.DurableAdmission journal = (admission, value) -> CompletableFuture.supplyAsync(() -> {
            check(admission.actor().equals(actor) && admission.scope().equals(value.scope()), "Journal admission lost original actor/scope");
            // Test-only generated controlled path, actual force before admission.
            Path path = Path.of("journal-" + value.operationId() + ".test");
            try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(SharedProtocol.encode(new SharedMessage.TaskCommand(value)));
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true); return true;
            } catch (java.io.IOException failure) { throw new CompletionException(failure); }
        });
        context.runOnClient(client -> SharedClientNetworking.submit(command, journal));
        context.waitFor(client -> SharedClientNetworking.result(command.operationId()) != null && SharedClientNetworking.state().cursor() == 1, 600);
        context.runOnClient(client -> {
            var result = SharedClientNetworking.result(command.operationId());
            check(result.status() == SharedEvents.Status.OK && result.row().rowVersion() == 1, "Actual socket mutation/result ordering failed");
            check("wire confirmed".equals(SharedClientNetworking.state().snapshot().rows().get(row).note()), "Delta omitted saved row");
        });
        var service = server.computeOnServer(SharedServerNetworking::service);
        var persisted = await(service.join(actor, snapshot.scope()));
        check(persisted.value().projectRevision() == 1 && "wire confirmed".equals(persisted.value().rows().get(row).note()),
            "ACK preceded authoritative durable mutation");
        UUID discovery = context.computeOnClient(client -> SharedClientNetworking.discover());
        context.waitFor(client -> SharedClientNetworking.state().projects().size() == 1, 600);
        check(discovery != null, "Discovery lacked correlation");
        ProjectScope wrong = new ProjectScope(snapshot.scope().worldNamespace(), snapshot.scope().projectId(), UUID.randomUUID(), snapshot.scope().regionListId());
        UUID wrongJoin = context.computeOnClient(client -> SharedClientNetworking.join(wrong));
        context.waitFor(client -> SharedClientNetworking.reply(wrongJoin) != null, 600);
        context.runOnClient(client -> check(SharedClientNetworking.reply(wrongJoin).snapshot() == null,
            "Distinct list scope exposed private contents"));
        UUID join = context.computeOnClient(client -> SharedClientNetworking.join(snapshot.scope()));
        context.waitFor(client -> SharedClientNetworking.reply(join) != null && SharedClientNetworking.state().synchronizedState(), 600);
        UUID admin = context.computeOnClient(client -> SharedClientNetworking.updateManifest(snapshot.scope(), 1, 0,
            new SharedManifest.Definition("b".repeat(64), definition.baseline(), definition.materials(), definition.regions())));
        context.waitFor(client -> SharedClientNetworking.reply(admin) != null, 600);
        context.runOnClient(client -> check(!SharedClientNetworking.state().synchronizedState()
            && SharedClientNetworking.reply(admin).snapshot().manifest().version() == 1,
            "Manifest invalidation failed to block writes pending explicit join"));
        UUID fresh = context.computeOnClient(client -> SharedClientNetworking.join(snapshot.scope()));
        context.waitFor(client -> SharedClientNetworking.reply(fresh) != null && SharedClientNetworking.state().synchronizedState(), 600);
        var late = new SharedCommand(snapshot.scope(), row, UUID.randomUUID(), 1, 1, new SharedCommand.SetNote("late confirmed"));
        var originalAdmission = new java.util.concurrent.atomic.AtomicReference<SharedClientNetworking.AdmissionContext>();
        context.runOnClient(client -> {
            TransportObservation.delay(late.operationId());
            SharedClientNetworking.submit(late, (admission, value) -> {
                originalAdmission.set(admission); return journal.persist(admission, value);
            });
        });
        context.waitFor(client -> TransportObservation.held() && SharedClientNetworking.state().cursor() == 3, 600);
        check(originalAdmission.get() != null, "Late result lost original journal context");
        var delayed = new SharedCommand(snapshot.scope(), row, UUID.randomUUID(), 2, 1, new SharedCommand.SetNote("delayed durable"));
        CompletableFuture<Boolean> delayedGate = new CompletableFuture<>();
        var delayedSend = context.computeOnClient(client -> SharedClientNetworking.submit(delayed, (admission, value) -> {
            check(value.equals(delayed) && admission.actor().equals(actor) && admission.scope().equals(snapshot.scope()),
                "Delayed journal lost exact original actor/command/scope");
            return journal.persist(admission, value).thenCompose(durable -> durable ? delayedGate : CompletableFuture.completedFuture(false));
        }));
        // Test setup uses synthetic creator exclusively via core API; transport still authenticates the connected Viewer.
        UUID syntheticCreator = UUID.randomUUID();
        var other = await(service.create(syntheticCreator, snapshot.scope().worldNamespace(), "viewer fixture", definition)).value();
        await(service.setViewer(syntheticCreator, other.scope(), 0, actor, true));
        UUID firstViewerJoin = context.computeOnClient(client -> SharedClientNetworking.join(other.scope()));
        context.waitFor(client -> SharedClientNetworking.reply(firstViewerJoin) != null && SharedClientNetworking.state().synchronizedState(), 600);
        context.runOnClient(client -> {
            TransportObservation.release();
            check(SharedClientNetworking.result(late.operationId()) == null,
                "Late original ACK exposed private row in another selected binding");
            var confirmed = SharedClientNetworking.result(originalAdmission.get(), late.operationId());
            check(confirmed != null && confirmed.status() == SharedEvents.Status.OK && confirmed.scope().equals(snapshot.scope())
                && confirmed.row().taskId().equals(row) && confirmed.row().rowVersion() == 2,
                "Original journal context lost legitimate late authoritative ACK");
            var original = originalAdmission.get();
            check(SharedClientNetworking.result(new SharedClientNetworking.AdmissionContext(UUID.randomUUID(),
                original.connectionId(), original.scope()), late.operationId()) == null,
                "Replacement actor read original journal outcome");
            check(SharedClientNetworking.result(new SharedClientNetworking.AdmissionContext(original.actor(),
                UUID.randomUUID(), original.scope()), late.operationId()) == null,
                "Replacement connection read original journal outcome");
            check(SharedClientNetworking.result(new SharedClientNetworking.AdmissionContext(original.actor(),
                original.connectionId(), other.scope()), late.operationId()) == null,
                "Different journal scope read original outcome");
            check(SharedClientNetworking.state().snapshot().scope().equals(other.scope())
                && SharedClientNetworking.state().snapshot().rows().get(row).rowVersion() == 0,
                "Late ACK adopted into another binding");
        });
        delayedGate.complete(true);
        context.waitFor(client -> delayedSend.toCompletableFuture().isDone(), 600);
        check(!delayedSend.toCompletableFuture().join(), "Delayed command crossed its original scope after journal admission");
        context.runOnClient(client -> check(!SharedClientNetworking.unconfirmedOperations().contains(delayed),
            "Different selected binding exposed old uncertain command contents"));
        UUID originalScope = context.computeOnClient(client -> SharedClientNetworking.join(snapshot.scope()));
        context.waitFor(client -> SharedClientNetworking.reply(originalScope) != null && SharedClientNetworking.state().synchronizedState(), 600);
        context.runOnClient(client -> check(SharedClientNetworking.unconfirmedOperations().contains(delayed),
            "Original durable unsent command was treated as cancelled at scope change"));
        check(await(service.join(actor, snapshot.scope())).value().projectRevision() == 3,
            "Delayed durable command mutated original authority after scope change");
        UUID viewerJoin = context.computeOnClient(client -> SharedClientNetworking.join(other.scope()));
        context.waitFor(client -> SharedClientNetworking.reply(viewerJoin) != null && SharedClientNetworking.state().synchronizedState(), 600);
        var denied = new SharedCommand(other.scope(), row, UUID.randomUUID(), 0, 0, new SharedCommand.Assign(syntheticCreator));
        // Test-owned raw wire bypass exercises authenticated server denial; no production actor override.
        context.runOnClient(client -> sendRaw(new SharedMessage.TaskCommand(denied)));
        context.waitFor(client -> TransportObservation.result(denied.operationId()) != null, 600);
        context.runOnClient(client -> check(TransportObservation.result(denied.operationId()).status() == SharedEvents.Status.DENIED
            && TransportObservation.result(denied.operationId()).row() == null,
            "Actual authenticated Viewer socket command lacked content-free DENIED result"));
        check(await(service.join(syntheticCreator, other.scope())).value().projectRevision() == 1,
            "Assignment target was trusted as actor");
        var removal = await(service.setViewer(syntheticCreator, other.scope(), 1, actor, false));
        // Core setup creates the actual admin outcome; transport's publication API emits its removal event.
        server.runOnServer(mc -> SharedServerNetworking.publishEvents(mc, removal.events()));
        context.waitFor(client -> SharedClientNetworking.state().status() == dev.mcmateriallist.core.shared.transport.SharedClientState.Status.REVOKED, 600);
        context.runOnClient(client -> {
            check(SharedClientNetworking.state().snapshot() == null, "Revoked connection retained private rows");
            check(SharedClientNetworking.reply(viewerJoin) == null, "Revoked connection exposed private rows through old correlated reply");
        });
        org.slf4j.LoggerFactory.getLogger("mcmateriallist-transport-test").info("Shared transport registered near-max C2S/S2C socket, authority and revocation PASS");
    }
    private static void sendRaw(SharedMessage message) {
        for (var fragment : SharedTransfers.split(UUID.randomUUID(), SharedProtocol.encode(message)))
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new SharedPayload(fragment));
    }
    private static <T> T await(CompletionStage<T> stage) {
        try { return stage.toCompletableFuture().get(20, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError("Test setup authority failed", failure); }
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
