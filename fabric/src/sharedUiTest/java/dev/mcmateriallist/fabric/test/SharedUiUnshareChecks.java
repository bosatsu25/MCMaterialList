package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.DatasetJsonCodec;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.material.MaterialWorkScreen;
import dev.mcmateriallist.fabric.client.region.*;
import dev.mcmateriallist.fabric.client.shared.*;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import static dev.mcmateriallist.fabric.test.SharedUiClientTest.*;

/**
 * Issue #11 Unshare regions through the native Shared work screen, on its own fixture and connections: a held result
 * keeps Unshare refused across a real disconnect and reconnect until the replay settles; the two-step confirmation is
 * cancelled by a cursor change; Unshare and a local Done on the detached copy send nothing and change nothing upstream;
 * Rejoin shows the server rows and writes the export. Geometry is checked while detached at GUI scales 1-4, narrow and
 * Japanese (the shared "Unshare regions..." row is covered by the main geometry pass).
 */
final class SharedUiUnshareChecks {
    private SharedUiUnshareChecks() {}
    static void run(ClientGameTestContext context, TestDedicatedServerContext server) throws Exception {
        Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist");
        var store = new SharedClientStore(config);
        var service = server.computeOnServer(SharedServerNetworking::service);
        var connection = server.connect(); boolean open = true;
        var gate = new CompletableFuture<Void>();
        try {
            connection.getClientLevel().waitForChunksRender();
            context.waitFor(client -> SharedClientNetworking.state().capabilities() != null, 600);
            var original = context.computeOnClient(client -> fixture(client.player.blockPosition().above(20)));
            context.waitFor(client -> NativeSharedCapture.capture(original, null, false).ok(), 600);
            var materials = context.computeOnClient(client -> original.getMaterialList());
            context.waitFor(client -> materials.getMaterialsAll().size() == 2
                && PlacementWorkAdapter.capture(original, materials).status() == PlacementWorkAdapter.Status.OK, 600);
            UUID actor = context.computeOnClient(client -> client.player.getUUID());
            UUID hash = context.computeOnClient(client -> original.getHashId());
            var id = context.computeOnClient(client -> PlacementWorkAdapter.captureRegions(original).snapshot().placementId());
            Path regionsFile = config.resolve("placements").resolve(hash.toString()).resolve("regions.json");

            // Materials stay shared in their own project throughout.
            context.setScreen(() -> new GuiMaterialList(materials));
            var materialScreen = context.computeOnClient(client -> (GuiMaterialList) client.gui.screen());
            var work = context.computeOnClient(client -> ((MaterialWorkScreen) materialScreen).mcmateriallist$session());
            context.waitFor(client -> !work.pending(), 600); action(context, "Track materials"); context.waitFor(client -> work.writable(), 600);
            action(context, "Share"); create(context, "Unshare material project"); context.waitFor(client -> work.shared().selected() && work.writable(), 600);
            back(context); back(context);

            // Original local region work exists before sharing; its bytes must never change.
            context.setScreen(() -> new GuiPlacementConfiguration(original));
            var screen = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
            var regions = context.computeOnClient(client -> ((RegionWorkScreen) screen).mcmateriallist$session());
            context.waitFor(client -> !regions.pending(), 600);
            action(context, "Track regions"); context.waitFor(client -> regions.writable(), 600);
            click(context, context.computeOnClient(client -> regionButton(screen, "Second region", RegionDoneButton.class)));
            context.waitFor(client -> regions.writable() && regions.state(regions.id("Second region")).done(), 600);
            byte[] regionsBytes = Files.readAllBytes(regionsFile);
            String flags = context.computeOnClient(client -> flags(original));
            action(context, "Share"); context.waitForScreen(SharedWorkScreen.class);
            context.runOnClient(client -> check(hasButton((GuiBase) client.gui.screen(), "Unshare regions...")
                && !((dev.mcmateriallist.fabric.test.mixin.SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Unshare regions...")).enabled()
                && regions.shared().unshareFeedback().equals(SharedWorkBackend.text("unshare.not_shared")),
                "Unshare was offered for unshared local regions"));
            create(context, "Unshare region project");
            context.waitFor(client -> regions.shared().selected() && regions.writable(), 600);
            back(context); back(context);
            var scope = context.computeOnClient(client -> regions.shared().reference().scope());
            var first = context.computeOnClient(client -> regions.id("First region"));
            var second = context.computeOnClient(client -> regions.id("Second region"));
            var materialBinding = store.load(id).value().materials();
            check(materialBinding.shared() != null && !materialBinding.shared().scope().equals(scope), "Fixture did not share materials separately");

            // A held authoritative result: the journal entry stays unresolved and Unshare stays refused.
            SharedUiDelays.result = gate; SharedUiDelays.executed = false;
            click(context, context.computeOnClient(client -> regionButton(screen, "First region", RegionDoneButton.class)));
            context.waitFor(client -> SharedUiDelays.executed && !regions.pending(), 600);
            check(unresolved(store, id) == 1, "Held region result did not leave exactly one unresolved original");
            String waiting = context.computeOnClient(client -> SharedWorkBackend.text("unshare.unresolved_operations", 1));
            action(context, "Share"); context.waitForScreen(SharedWorkScreen.class);
            context.runOnClient(client -> check(!regions.shared().canUnshare() && waiting.equals(regions.shared().unshareFeedback())
                && !((dev.mcmateriallist.fabric.test.mixin.SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Unshare regions...")).enabled(),
                "Held region result did not keep Unshare disabled"));
            capture(context, context.computeOnClient(client -> (GuiBase) client.gui.screen()), "issue11-unshare-blocked");
            back(context);

            // A real disconnect never resolves the original.
            connection.close(); open = false;
            context.waitFor(client -> SharedClientNetworking.state().status() == SharedClientState.Status.DISCONNECTED, 600);
            context.runOnClient(client -> check(!regions.shared().canUnshare() && waiting.equals(regions.shared().unshareFeedback()),
                "Disconnect resolved or hid the unresolved region original"));
            check(unresolved(store, id) == 1 && store.load(id).value().regions().shared() != null, "Disconnect changed the region journal or binding");
            context.runOnClient(client -> check(gate.complete(null), "Unshare result gate was already settled"));
            SharedUiDelays.result = null;

            connection = server.connect(); open = true;
            connection.getClientLevel().waitForChunksRender();
            context.waitFor(client -> SharedClientNetworking.state().capabilities() != null, 600);
            context.waitTicks(20);
            var placement = context.computeOnClient(client -> registered(original, hash));
            context.waitFor(client -> NativeSharedCapture.capture(placement, null, false).ok(), 600);
            context.setScreen(() -> new GuiPlacementConfiguration(placement));
            var after = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
            var resumed = context.computeOnClient(client -> ((RegionWorkScreen) after).mcmateriallist$session());
            boolean early = context.computeOnClient(client -> resumed.shared().canUnshare());
            check(!early || unresolved(store, id) == 0, "Reconnect alone allowed unshare before the replay settled");
            // Reconnect restoration rejoins the last scope and replays the original; Join / synchronize is the explicit fallback.
            for (int tick = 0; tick < 2400 && !context.computeOnClient(client -> resumed.shared().canUnshare()); tick++) {
                if (tick % 300 == 299) context.runOnClient(client -> { if (!resumed.pending()) resumed.shared().refresh(); });
                context.waitTick();
            }
            check(context.computeOnClient(client -> resumed.shared().canUnshare()) && unresolved(store, id) == 0,
                "Replay did not settle the held region original: " + context.computeOnClient(client -> resumed.shared().unshareFeedback()));
            context.runOnClient(client -> check(resumed.state(first).done(), "Replayed region result is not in the shared view"));

            // Two-step confirmation; any cursor change cancels it.
            action(context, "Share"); context.waitForScreen(SharedWorkScreen.class);
            action(context, "Unshare regions...");
            context.runOnClient(client -> check(hasButton((GuiBase) client.gui.screen(), "Confirm unshare"), "Unshare confirmation did not arm"));
            var armed = context.computeOnClient(client -> resumed.shared().snapshot());
            var changed = service.command(actor, new SharedCommand(scope, second, UUID.randomUUID(), armed.rows().get(second).rowVersion(),
                armed.manifest().version(), new SharedCommand.SetNote("Unshare cursor fixture"))).toCompletableFuture().get(10, TimeUnit.SECONDS);
            check(changed.result().status() == SharedEvents.Status.OK, "Unshare cursor fixture command did not commit");
            server.runOnServer(serverInstance -> SharedServerNetworking.publishEvents(serverInstance, changed.events()));
            context.waitFor(client -> resumed.shared().snapshot() != null
                && resumed.shared().snapshot().projectRevision() == changed.result().projectRevision() && resumed.shared().canUnshare(), 600);
            context.runOnClient(client -> {
                ((SharedWorkScreen) client.gui.screen()).initGui();
                check(hasButton((GuiBase) client.gui.screen(), "Unshare regions...") && !hasButton((GuiBase) client.gui.screen(), "Confirm unshare"),
                    "Unshare confirmation remained armed after a cursor change");
            });
            check(store.load(id).value().regions().shared() != null, "First Unshare click detached the regions");

            var before = store.load(id).value();
            var serverBefore = service.join(actor, scope).toCompletableFuture().get(10, TimeUnit.SECONDS).value();
            int submissions = SharedUiDelays.submissions;
            action(context, "Unshare regions..."); action(context, "Confirm unshare");
            context.waitFor(client -> resumed.detached() && !resumed.pending(), 600);
            var detached = store.load(id).value();
            var copy = detached.regions().detached();
            check(copy != null && detached.regions().shared() == null && copy.from().equals(scope)
                && !copy.dataset().id().equals(before.regions().localDataset())
                && copy.dataset().states().equals(Map.of(first, serverBefore.rows().get(first), second, serverBefore.rows().get(second))),
                "Unshare did not copy the confirmed server rows into a fresh detached dataset");
            check(detached.materials().equals(before.materials()) && detached.operations().equals(before.operations())
                && detached.materials().equals(materialBinding), "Unshare changed the material binding or the journal");
            check(serverBefore.equals(service.join(actor, scope).toCompletableFuture().get(10, TimeUnit.SECONDS).value())
                && SharedUiDelays.submissions == submissions, "Unshare sent a command or changed the server");
            check(Arrays.equals(regionsBytes, Files.readAllBytes(regionsFile)), "Unshare rewrote the original local regions.json");
            context.runOnClient(client -> {
                check(flags.equals(flags(placement)), "Unshare changed Placement ON/OFF or sub-region flags");
                check(resumed.feedback().equals(SharedWorkBackend.text("detached")) && resumed.shared().feedback().equals(SharedWorkBackend.text("detached")),
                    "Detached local copy status missing");
                ((SharedWorkScreen) client.gui.screen()).initGui();
                check(hasButton((GuiBase) client.gui.screen(), "Rejoin shared regions...") && !hasButton((GuiBase) client.gui.screen(), "Unshare regions...")
                    && resumed.shared().canRejoin(), "Detached regions did not offer Rejoin shared regions");
            });
            capture(context, context.computeOnClient(client -> (GuiBase) client.gui.screen()), "issue11-detached-management");
            back(context);

            // A local Done changes only the copy and sends nothing.
            context.runOnClient(client -> after.initGui());
            click(context, context.computeOnClient(client -> regionButton(after, "Second region", RegionDoneButton.class)));
            context.waitFor(client -> !resumed.pending() && resumed.state(second).done(), 600);
            var edited = store.load(id).value();
            check(edited.regions().detached().dataset().states().get(second).done() && edited.regions().detached().dataset().generation() == 1
                && edited.materials().equals(materialBinding) && edited.operations().equals(before.operations()),
                "Local Done did not change exactly the detached copy");
            check(serverBefore.equals(service.join(actor, scope).toCompletableFuture().get(10, TimeUnit.SECONDS).value())
                && SharedUiDelays.submissions == submissions, "Local Done on the detached copy reached the server");
            check(Arrays.equals(regionsBytes, Files.readAllBytes(regionsFile)), "Detached Done rewrote the original local regions.json");
            capture(context, after, "issue11-detached-regions");

            detachedGeometry(context, after);

            // Rejoin: explicit two-step join of the original scope; server rows return and the copy is exported.
            var exported = edited.regions().detached().dataset();
            context.setScreen(() -> after); action(context, "Share"); context.waitForScreen(SharedWorkScreen.class);
            action(context, "Rejoin shared regions...");
            context.runOnClient(client -> check(hasButton((GuiBase) client.gui.screen(), "Confirm rejoin") && resumed.detached(),
                "Rejoin confirmation did not arm, or the first click rejoined"));
            action(context, "Confirm rejoin");
            context.waitFor(client -> resumed.shared().selected() && resumed.writable(), 600);
            var authority = service.join(actor, scope).toCompletableFuture().get(10, TimeUnit.SECONDS).value();
            context.runOnClient(client -> check(authority.equals(resumed.shared().snapshot()) && !resumed.state(second).done()
                && "Unshare cursor fixture".equals(resumed.state(second).note()), "Rejoin did not show the server rows"));
            var rejoined = store.load(id).value();
            check(rejoined.regions().detached() == null && rejoined.regions().cache().snapshot().equals(authority)
                && rejoined.materials().equals(materialBinding), "Rejoin did not install the server snapshot alone");
            Path export = context.computeOnClient(client -> resumed.shared().detachedExport(exported));
            check(Files.exists(export) && export.startsWith(config.toAbsolutePath().normalize().resolve("shared-client").resolve("detached"))
                && exported.equals(new DatasetJsonCodec().decode(Files.readString(export, StandardCharsets.UTF_8))),
                "Rejoin export does not hold the detached copy");
            check(Arrays.equals(regionsBytes, Files.readAllBytes(regionsFile)), "Rejoin rewrote the original local regions.json");
            context.runOnClient(client -> check(flags.equals(flags(placement)), "Rejoin changed Placement ON/OFF or sub-region flags"));
            // Leave no shared region binding behind, so the next connection's reconnect restoration has nothing of ours to rejoin.
            action(context, "Use local work");
            context.waitFor(client -> !resumed.shared().selected() && !resumed.pending(), 600);
            check(Arrays.equals(regionsBytes, Files.readAllBytes(regionsFile)), "Use local work after rejoin rewrote regions.json");
            back(context);
            org.slf4j.LoggerFactory.getLogger("mcmateriallist-shared-ui-test").info("Shared native UI unshare PASS");
        } finally {
            SharedUiDelays.result = null; gate.complete(null);
            if (open) connection.close();
        }
    }
    /** The same saved placement after reconnect: Litematica's restored copy, or the original object registered again. */
    private static SchematicPlacement registered(SchematicPlacement original, UUID hash) {
        var active = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
        for (var candidate : active) if (hash.equals(candidate.getHashId())) return candidate;
        DataManager.getSchematicPlacementManager().addSchematicPlacement(original, false);
        return original;
    }
    private static String flags(SchematicPlacement placement) {
        return placement.isEnabled() + ":" + placement.getAllSubRegionsPlacements().stream()
            .map(region -> region.getName() + "=" + region.isEnabled()).sorted().toList();
    }
    private static long unresolved(SharedClientStore store, LocalPlacementId id) {
        return store.load(id).value().operations().stream()
            .filter(operation -> operation.kind() == DatasetKind.REGIONS && operation.confirmation() == null).count();
    }
    /** The detached "Rejoin shared regions..." row at GUI scales 1-4, the narrow layout and in Japanese. */
    private static void detachedGeometry(ClientGameTestContext context, GuiPlacementConfiguration screen) throws Exception {
        for (int scale : new int[]{1, 2, 3, 4}) {
            context.runOnClient(client -> { client.options.guiScale().set(scale); client.resizeGui(); });
            context.setScreen(() -> screen); context.waitTick(); context.runOnClient(client -> geometry(screen));
            action(context, "Share"); context.waitForScreen(SharedWorkScreen.class);
            context.runOnClient(client -> {
                check(hasButton((GuiBase) client.gui.screen(), "Rejoin shared regions..."), "Rejoin row missing at GUI scale " + scale);
                geometry((GuiBase) client.gui.screen());
            });
            capture(context, context.computeOnClient(client -> (GuiBase) client.gui.screen()), "issue11-detached-scale-" + scale);
            back(context);
        }
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(client -> { client.options.guiScale().set(4); client.resizeGui(); });
        context.setScreen(() -> screen); context.waitTick(); context.runOnClient(client -> geometry(screen));
        action(context, "Share"); context.waitForScreen(SharedWorkScreen.class);
        context.runOnClient(client -> geometry((GuiBase) client.gui.screen()));
        capture(context, context.computeOnClient(client -> (GuiBase) client.gui.screen()), "issue11-detached-narrow");
        back(context);
        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
        var japanese = context.computeOnClient(client -> { client.getLanguageManager().setSelected("ja_jp"); return client.reloadResourcePacks(); });
        context.waitFor(client -> japanese.isDone(), 600); japanese.join();
        context.setScreen(() -> screen); context.waitFor(client -> client.gui.overlay() == null); context.runOnClient(client -> geometry(screen));
        action(context, "共有"); context.waitForScreen(SharedWorkScreen.class);
        context.runOnClient(client -> {
            check(hasButton((GuiBase) client.gui.screen(), "共有領域に再参加..."), "Japanese Rejoin row missing");
            geometry((GuiBase) client.gui.screen());
        });
        capture(context, context.computeOnClient(client -> (GuiBase) client.gui.screen()), "issue11-detached-ja");
        action(context, "戻る");
        var english = context.computeOnClient(client -> { client.getLanguageManager().setSelected("en_us"); return client.reloadResourcePacks(); });
        context.waitFor(client -> english.isDone(), 600); english.join();
        context.setScreen(() -> screen); context.waitFor(client -> client.gui.overlay() == null);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
