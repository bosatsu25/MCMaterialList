package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.material.*;
import dev.mcmateriallist.fabric.client.region.*;
import dev.mcmateriallist.fabric.client.shared.*;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import dev.mcmateriallist.fabric.test.mixin.SharedUiAccess;
import fi.dy.masa.litematica.gui.*;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import static dev.mcmateriallist.fabric.test.SharedUiClientTest.*;

/** Deterministic regression interleavings using real services, native callbacks and socket events. */
final class SharedUiRaceChecks {
    private SharedUiRaceChecks() {}
    static boolean enabled(String name) {
        String selected = System.getProperty("mcmateriallist.sharedUiRegression", "all");
        return selected.equals("all") || selected.equals(name);
    }
    static void publication(ClientGameTestContext context, TestDedicatedServerContext server, GuiMaterialList screen,
            MaterialWorkSession work, MaterialTaskId stone) throws Exception {
        var old = context.computeOnClient(client -> work.shared().snapshot());
        var gate = new CompletableFuture<Void>(); SharedUiDelays.cache = gate; SharedUiDelays.cacheHeld = false;
        var confirmed = new AtomicBoolean();
        SharedUiDelays.publication = result -> {
            if (!result.ok() || SharedUiDelays.command == null || result.value().operations().stream().noneMatch(operation ->
                operation.command().operationId().equals(SharedUiDelays.command.operationId()) && operation.confirmation() != null)) return;
            SharedUiDelays.publication = null;
            check(!work.writable() && work.pending(), "Journal confirmation reopened native editing before confirmed cache publication");
            check(work.shared().snapshot().equals(old), "Fixture did not hold the actual old native cache");
            work.shared().command(stone, new TaskCommand.SetDone(true));
            confirmed.set(true);
        };
        try {
            click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
            context.waitFor(client -> confirmed.get() && SharedUiDelays.cacheHeld, 600);
            int submissions = SharedUiDelays.submissions;
            context.runOnClient(client -> {
                check(!((SharedUiAccess.Button) rowButton(screen, stone, MaterialDoneButton.class)).enabled(), "Native Done enabled during cache hold");
                work.shared().command(stone, new TaskCommand.SetDone(true));
            });
            context.waitTicks(2); check(SharedUiDelays.submissions == submissions, "Direct command escaped confirmed presentation barrier");
        } finally {
            SharedUiDelays.publication = null; SharedUiDelays.cache = null;
            context.runOnClient(client -> gate.complete(null));
        }
        context.waitFor(client -> work.writable() && work.state(stone).done(), 600);
        click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
        context.waitFor(client -> work.writable() && !work.state(stone).done(), 600);
        check(SharedUiDelays.command.intent() instanceof SharedCommand.SetDone done && !done.done(), "Next native action reused a stale Done intent");

        // The wire applies a genuine delta while the original join snapshot is waiting to persist.
        action(context, "Share"); action(context, "Use local work"); context.waitFor(client -> !work.shared().selected() && !work.pending(), 600);
        var selection = new CompletableFuture<Void>(); var cache = new CompletableFuture<Void>();
        SharedUiDelays.selection = selection; SharedUiDelays.selectionHeld = false;
        SharedUiDelays.cache = cache; SharedUiDelays.cacheHeld = false;
        var validated = new AtomicBoolean();
        try {
            context.runOnClient(client -> work.shared().join(old.scope()));
            context.waitFor(client -> SharedUiDelays.selectionHeld, 600);
            var actor = context.computeOnClient(client -> client.player.getUUID());
            var snapshot = context.computeOnClient(client -> SharedClientNetworking.state().snapshot());
            var service = server.computeOnServer(SharedServerNetworking::service);
            var outcome = service.command(actor, new SharedCommand(snapshot.scope(), stone, UUID.randomUUID(),
                snapshot.rows().get(stone).rowVersion(), snapshot.manifest().version(), new SharedCommand.SetNote("Selection delta")))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
            check(outcome.result().status() == SharedEvents.Status.OK, "Fixture server delta was not committed");
            server.runOnServer(instance -> SharedServerNetworking.publishEvents(instance, outcome.events()));
            context.waitFor(client -> SharedClientNetworking.state().snapshot().rows().get(stone).note().equals("Selection delta"), 600);
            SharedUiDelays.validated = () -> {
                if (!work.shared().selected()) return;
                SharedUiDelays.validated = null;
                check(!work.writable() && work.pending(), "Old joined snapshot became writable before arriving delta cache publication");
                validated.set(true);
            };
            context.runOnClient(client -> selection.complete(null));
            context.waitFor(client -> validated.get() && SharedUiDelays.cacheHeld, 600);
            context.runOnClient(client -> check(!work.state(stone).note().equals("Selection delta") && !work.writable(), "Selection cache hold was bypassed"));
        } finally {
            SharedUiDelays.validated = null; SharedUiDelays.selection = null; SharedUiDelays.cache = null;
            context.runOnClient(client -> { selection.complete(null); cache.complete(null); });
        }
        context.waitFor(client -> work.writable() && work.state(stone).note().equals("Selection delta"), 600); back(context);
        digestProvenance(context, server, screen, work, stone);
    }
    private static void digestProvenance(ClientGameTestContext context, TestDedicatedServerContext server,
            GuiMaterialList screen, MaterialWorkSession work, MaterialTaskId stone) throws Exception {
        var service = server.computeOnServer(SharedServerNetworking::service);
        var actor = context.computeOnClient(client -> client.player.getUUID());
        for (boolean sameRow : new boolean[]{true, false}) {
            var before = context.computeOnClient(client -> work.shared().snapshot());
            int submissions = SharedUiDelays.submissions;
            var gate = new CompletableFuture<Void>(); SharedUiDelays.digest = gate; SharedUiDelays.digestHeld = false;
            try {
                click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
                context.waitFor(client -> SharedUiDelays.digestHeld, 600);
                MaterialTaskId changed = sameRow ? stone : new MaterialTaskId("minecraft:glass");
                SharedCommand.Intent intent = sameRow ? new SharedCommand.SetDone(true) : new SharedCommand.SetNote("Unrelated row delta");
                var outcome = service.command(actor, new SharedCommand(before.scope(), changed, UUID.randomUUID(),
                    before.rows().get(changed).rowVersion(), before.manifest().version(), intent)).toCompletableFuture().get(10, TimeUnit.SECONDS);
                check(outcome.result().status() == SharedEvents.Status.OK, "Fixture concurrent delta did not commit");
                server.runOnServer(instance -> SharedServerNetworking.publishEvents(instance, outcome.events()));
                context.waitFor(client -> work.shared().snapshot().projectRevision() == outcome.result().projectRevision(), 600);
            } finally {
                SharedUiDelays.digest = null;
                context.runOnClient(client -> gate.complete(null));
            }
            context.waitFor(client -> !work.pending() && work.writable(), 600);
            if (sameRow) {
                check(SharedUiDelays.submissions == submissions, "Old clicked intent was reinterpreted against a changed target row");
                context.runOnClient(client -> check(work.shared().feedback().equals(SharedWorkBackend.text("state_changed")), "Changed target did not receive an explicit retry reason"));
            } else {
                context.waitFor(client -> work.state(stone).done(), 600);
                check(SharedUiDelays.submissions == submissions + 1 && SharedUiDelays.command.expectedRowVersion() == before.rows().get(stone).rowVersion(),
                    "Unrelated row change replaced row-version semantics with a global revision conflict");
            }
            // Both genuine paths leave this row done; return to the original fixture state using native input.
            click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
            context.waitFor(client -> work.writable() && !work.state(stone).done(), 600);
        }
    }
    static void restoredRouting(ClientGameTestContext context, GuiMaterialList originalScreen, SchematicPlacement placement,
            Path config, SharedClientStore store, LocalPlacementId id) throws Exception {
        Path directory = config.resolve("placements").resolve(placement.getHashId().toString());
        byte[] materials = Files.readAllBytes(directory.resolve("materials.json"));
        byte[] regions = Files.readAllBytes(directory.resolve("regions.json"));
        var binding = store.load(id).value();
        var localMaterials = MCMaterialListClient.work().load(id, DatasetKind.MATERIALS).get(10, TimeUnit.SECONDS).dataset();
        var localRegions = MCMaterialListClient.work().load(id, DatasetKind.REGIONS).get(10, TimeUnit.SECONDS).dataset();
        var previous = context.computeOnClient(client -> MCMaterialListClient.shared());
        var restored = context.computeOnClient(client -> new SharedWorkController(config));
        var loaded = new CompletableFuture<Void>(); SharedUiDelays.load = loaded; SharedUiDelays.loadHeld = false;
        SharedUiDelays.materialRefreshes = 0; SharedUiDelays.regionRefreshes = 0; SharedUiDelays.observeLocalRefresh = true;
        try {
            context.runOnClient(client -> SharedUiAccess.Client.shared(restored));
            context.setScreen(() -> new GuiMaterialList(placement.getMaterialList()));
            var screen = context.computeOnClient(client -> (GuiMaterialList) client.gui.screen());
            var work = context.computeOnClient(client -> ((MaterialWorkScreen) screen).mcmateriallist$session());
            context.waitFor(client -> SharedUiDelays.loadHeld && work.dataset() != null, 600);
            context.runOnClient(client -> placement.toggleSubRegionEnabled("Second region", screen));
            action(context, "Refresh"); context.waitFor(client -> work.ready() && screen.getMaterialList().getMaterialsAll().size() == 1, 600);
            context.waitTicks(2);
            context.runOnClient(client -> work.refresh());
            var afterMaterials = MCMaterialListClient.work().load(id, DatasetKind.MATERIALS).get(10, TimeUnit.SECONDS).dataset();
            check(localMaterials.equals(afterMaterials) && Arrays.equals(materials, Files.readAllBytes(directory.resolve("materials.json"))),
                "Restored shared load routed partial recount/Refresh into preserved local materials");
            context.runOnClient(client -> check(!((SharedUiAccess.Button) button(screen, "Refresh work")).enabled(), "Material Refresh enabled before restored sharing selection resolved"));

            context.setScreen(() -> new GuiPlacementConfiguration(placement));
            var regionScreen = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
            var regionWork = context.computeOnClient(client -> ((RegionWorkScreen) regionScreen).mcmateriallist$session());
            context.waitFor(client -> regionWork.dataset() != null, 600);
            context.runOnClient(client -> {
                check(((SharedUiAccess.Backend) (Object) work.shared()).controller() == restored
                    && ((SharedUiAccess.Backend) (Object) regionWork.shared()).controller() == restored, "Fixture did not attach both views to the restored controller");
                check(!((SharedUiAccess.Button) button(regionScreen, "Refresh work")).enabled(), "Region Refresh enabled before restored sharing selection resolved");
                regionWork.refresh();
            });
            var afterRegions = MCMaterialListClient.work().load(id, DatasetKind.REGIONS).get(10, TimeUnit.SECONDS).dataset();
            check(localRegions.equals(afterRegions) && Arrays.equals(regions, Files.readAllBytes(directory.resolve("regions.json"))), "Restored loading changed preserved local regions");
            check(SharedUiDelays.materialRefreshes == 0 && SharedUiDelays.regionRefreshes == 0, "Unresolved shared source invoked local reconciliation");
            context.runOnClient(client -> check(loaded.complete(null), "Restored-load test gate was already settled"));
            try { context.waitFor(client -> !work.shared().loading() && !regionWork.shared().loading(), 600); }
            catch (AssertionError failure) {
                throw new AssertionError("Restored load did not publish: " + context.computeOnClient(client ->
                    "materialLoading=" + work.shared().loading() + ", regionLoading=" + regionWork.shared().loading()
                    + ", closed=" + ((SharedUiAccess.Controller) (Object) restored).closed() + ", gateDone=" + loaded.isDone()), failure);
            }
            context.runOnClient(client -> check(work.shared().reference().equals(binding.materials().shared())
                && regionWork.shared().reference().equals(binding.regions().shared()), "Restored independent bindings changed while loading"));
        } finally {
            SharedUiDelays.observeLocalRefresh = false; SharedUiDelays.load = null; loaded.complete(null);
            context.runOnClient(client -> {
                restored.shutdown(); SharedUiAccess.Client.shared(previous);
                if (!placement.getRelativeSubRegionPlacement("Second region").isEnabled()) placement.toggleSubRegionEnabled("Second region", originalScreen);
            });
        }
        context.setScreen(() -> originalScreen); action(context, "Refresh");
        context.waitFor(client -> originalScreen.getMaterialList().getMaterialsAll().size() == 2
            && ((MaterialWorkScreen) originalScreen).mcmateriallist$session().ready(), 600);
        action(context, "Refresh work"); context.waitFor(client -> ((MaterialWorkScreen) originalScreen).mcmateriallist$session().writable(), 600);
    }
    static void falseAdmission(ClientGameTestContext context, MaterialWorkSession work, SharedClientStore store,
            Path config, LocalPlacementId id) throws Exception {
        var before = store.load(id).value(); var snapshot = context.computeOnClient(client -> work.shared().snapshot());
        Path file = config.resolve("shared-client").resolve("placement-" + id + ".json");
        byte[] bytes = Files.readAllBytes(file);
        int submissions = SharedUiDelays.submissions;
        // Actual store lock contention makes durable admission return false. No fabricated server outcome.
        try (var channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"), StandardOpenOption.WRITE);
             var held = channel.lock()) {
            check(held.isValid(), "Fixture did not hold the actual shared save lock");
            context.runOnClient(client -> work.shared().command(new MaterialTaskId("minecraft:stone"), new TaskCommand.SetNote("Refused admission")));
            context.waitFor(client -> SharedUiDelays.submissions > submissions && SharedUiDelays.submitted.toCompletableFuture().isDone()
                && !work.pending(), 600);
            check(!SharedUiDelays.submitted.toCompletableFuture().join(), "Locked durable admission was sent");
            check(before.equals(store.load(id).value()) && Arrays.equals(bytes, Files.readAllBytes(file)), "Refused admission rewrote original journal/bindings/cache");
            context.runOnClient(client -> check(work.shared().snapshot().equals(snapshot), "Refused admission optimistically changed native presentation"));
        }
        context.waitTicks(2);
        context.runOnClient(client -> check(work.writable(), "False durable admission left the controller busy or permanently poisoned storage"));
    }
    static void closedAdmission(ClientGameTestContext context, TestDedicatedServerContext server) throws Exception {
        var connection = server.connect(); boolean closed = false;
        var gate = new CompletableFuture<Void>();
        try {
            connection.getClientLevel().waitForChunksRender();
            context.waitFor(client -> SharedClientNetworking.state().capabilities() != null, 600);
            var placement = context.computeOnClient(client -> fixture(client.player.blockPosition().above(14)));
            var materials = context.computeOnClient(client -> placement.getMaterialList());
            context.waitFor(client -> materials.getMaterialsAll().size() == 2
                && dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter.capture(placement, materials).status()
                    == dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter.Status.OK, 600);
            context.setScreen(() -> new GuiMaterialList(materials));
            var screen = context.computeOnClient(client -> (GuiMaterialList) client.gui.screen());
            var work = context.computeOnClient(client -> ((MaterialWorkScreen) screen).mcmateriallist$session());
            context.waitFor(client -> !work.pending(), 600); action(context, "Track materials"); context.waitFor(client -> work.writable(), 600);
            Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist");
            var id = context.computeOnClient(client -> dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter.captureRegions(placement).snapshot().placementId());
            Path local = config.resolve("placements").resolve(placement.getHashId().toString()).resolve("materials.json");
            byte[] localBytes = Files.readAllBytes(local);
            action(context, "Share"); create(context, "Closed admission project"); context.waitFor(client -> work.shared().selected() && work.writable(), 600);
            back(context); back(context);
            SharedUiDelays.admission = gate; SharedUiDelays.admitted = false;
            click(context, context.computeOnClient(client -> rowButton(screen, new MaterialTaskId("minecraft:stone"), MaterialDoneButton.class)));
            context.waitFor(client -> SharedUiDelays.admitted, 600);
            var store = new SharedClientStore(config);
            var original = store.load(id).value().operations().stream().filter(operation -> operation.confirmation() == null).findFirst().orElseThrow();
            connection.close(); closed = true;
            context.waitFor(client -> SharedClientNetworking.state().status() == SharedClientState.Status.DISCONNECTED, 600);
            context.runOnClient(client -> check(gate.complete(null), "Closed-admission test gate was already settled"));
            SharedUiDelays.admission = null;
            try { context.waitFor(client -> SharedUiDelays.submitted.toCompletableFuture().isDone() && !work.shared().pending(), 600); }
            catch (AssertionError failure) {
                throw new AssertionError("Closed admission did not settle: " + context.computeOnClient(client ->
                    "submittedDone=" + SharedUiDelays.submitted.toCompletableFuture().isDone()
                    + ", submittedValue=" + (SharedUiDelays.submitted.toCompletableFuture().isDone()
                        ? SharedUiDelays.submitted.toCompletableFuture().join() : null)
                    + ", pending=" + work.shared().pending() + ", loading=" + work.shared().loading()
                    + ", selected=" + work.shared().selected() + ", feedback=" + work.shared().feedback()
                    + ", transport=" + SharedClientNetworking.state().status()
                    + ", actorPresent=" + (client.player != null)
                    + ", controllerClosed=" + ((SharedUiAccess.Controller) (Object)
                        ((SharedUiAccess.Backend) (Object) work.shared()).controller()).closed()), failure);
            }
            check(!SharedUiDelays.submitted.toCompletableFuture().join(), "Closed connection sent a delayed command");
            check(store.load(id).value().operations().contains(original), "Closure cancelled or remapped the original unknown command");
            context.runOnClient(client -> work.shared().local());
            context.waitFor(client -> !work.shared().selected() && !work.shared().pending(), 600);
            check(store.load(id).value().operations().contains(original) && Arrays.equals(localBytes, Files.readAllBytes(local)),
                "Local selection after closed admission cancelled unknown work or changed local bytes");
        } finally {
            SharedUiDelays.admission = null; gate.complete(null); if (!closed) connection.close();
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
