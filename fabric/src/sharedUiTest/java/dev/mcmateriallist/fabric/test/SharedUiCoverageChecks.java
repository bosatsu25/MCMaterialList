package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.material.*;
import dev.mcmateriallist.fabric.client.region.*;
import dev.mcmateriallist.fabric.client.shared.*;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import dev.mcmateriallist.fabric.test.mixin.SharedUiAccess;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static dev.mcmateriallist.fabric.test.SharedUiClientTest.*;

/**
 * Issue #34 (Phase 5G) on its own fixtures and connection, after the Issue #11 checks:
 * <ul>
 * <li>Unreadable sharing metadata (a damaged shared-client/placement-*.json) blocks original region editing in the native
 * screen and states the storage reason; nothing is written over the damaged bytes or the original regions.json.</li>
 * <li>The sharing view limit of 16 open placements (SharedWorkController.attach): with every entry held by a live screen the
 * next placement gets none, so its original region editing is blocked with the capacity reason; once idle entries can be
 * collected one is evicted and the same placement becomes editable.</li>
 * <li>AC-12: a server-side save failure of a shared edit is shown to the client as "Server save failed" and changes nothing;
 * the retry is saved.</li>
 * <li>A refused unshare save (the client store cannot replace its previous-good backup) leaves the regions shared, unchanged
 * on disk, and shows a failure; once the obstacle is gone the same two-step Unshare succeeds. This is the client side of any
 * refused detached-copy save, including a copy beyond the save limit.</li>
 * </ul>
 * Failures are induced only through real filesystem state (a damaged file, or a directory where a backup file belongs) or the
 * real controller limit; no store outcome is fabricated. A fresh SharedWorkController is installed for the capacity and metadata
 * checks, as SharedUiRaceChecks does, and the production one is restored afterwards.
 */
final class SharedUiCoverageChecks {
    private static final Logger LOG = LoggerFactory.getLogger("mcmateriallist-shared-ui-test");
    private SharedUiCoverageChecks() {}

    static void run(ClientGameTestContext context, TestDedicatedServerContext server) throws Exception {
        Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist");
        var store = new SharedClientStore(config);
        var connection = server.connect();
        try {
            connection.getClientLevel().waitForChunksRender();
            context.waitFor(client -> SharedClientNetworking.state().capabilities() != null, 600);
            capacityAndMetadata(context, config);
            saveFailures(context, server, config, store);
        } finally { connection.close(); }
    }

    private static void capacityAndMetadata(ClientGameTestContext context, Path config) throws Exception {
        BlockPos origin = context.computeOnClient(client -> client.player.blockPosition().above(24));
        var damaged = context.computeOnClient(client -> fixture(origin));
        var limited = context.computeOnClient(client -> fixture(origin.offset(8, 0, 0)));
        // Original local region work exists for both, created through the production controller.
        track(context, damaged); track(context, limited);
        LocalPlacementId damagedId = context.computeOnClient(client -> PlacementWorkAdapter.captureRegions(damaged).snapshot().placementId());
        Path sharing = config.resolve("shared-client").resolve("placement-" + damagedId + ".json");
        check(!Files.exists(sharing), "Tracking local regions wrote sharing metadata");
        Files.createDirectories(sharing.getParent());
        byte[] garbage = "{\"schema\":3,\"payload\":".getBytes(StandardCharsets.UTF_8);
        Files.write(sharing, garbage);
        Path damagedRegions = regionsFile(config, damaged), limitedRegions = regionsFile(config, limited);
        byte[] damagedBytes = Files.readAllBytes(damagedRegions), limitedBytes = Files.readAllBytes(limitedRegions);

        var previous = context.computeOnClient(client -> MCMaterialListClient.shared());
        var fresh = context.computeOnClient(client -> new SharedWorkController(config));
        List<SharedWorkBackend> held = new ArrayList<>();
        try {
            context.runOnClient(client -> SharedUiAccess.Client.shared(fresh));

            // (12) Damaged sharing metadata: a detached copy could be hidden in it, so the original regions are not edited.
            context.setScreen(() -> new GuiPlacementConfiguration(damaged));
            var damagedScreen = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
            var damagedWork = context.computeOnClient(client -> ((RegionWorkScreen) damagedScreen).mcmateriallist$session());
            context.waitFor(client -> !damagedWork.shared().loading() && damagedWork.status() == StoreStatus.OK && damagedWork.id("First region") != null, 600);
            String damagedKey = context.computeOnClient(client -> damagedWork.shared().reasonKey());
            context.runOnClient(client -> {
                damagedScreen.initGui();
                var shared = damagedWork.shared();
                check(((SharedUiAccess.Backend) (Object) shared).controller() == fresh, "Damaged-metadata fixture did not use the fresh controller");
                check(shared.metadataBlocked() && shared.reasonKey().startsWith("storage.") && !shared.reasonKey().equals("storage.MISSING"),
                    "Damaged sharing metadata did not block original regions: " + shared.reasonKey());
                check(!damagedWork.writable() && !damagedWork.refreshEnabled(), "Original regions stayed editable behind damaged sharing metadata");
                check(damagedWork.stateReason().equals(RegionWorkSession.text("metadata_blocked", shared.feedback()))
                    && damagedWork.feedback().equals(shared.feedback()), "The blocked state does not show the storage reason: " + damagedWork.stateReason());
                check(!((SharedUiAccess.Button) regionButton(damagedScreen, "First region", RegionDoneButton.class)).enabled(),
                    "Region Done was enabled behind damaged sharing metadata");
                var refresh = button(damagedScreen, "Refresh work");
                check(!((SharedUiAccess.Button) refresh).enabled() && refresh.getHoverStrings().size() > 1, "Disabled Refresh work does not explain why");
            });
            click(context, context.computeOnClient(client -> regionButton(damagedScreen, "First region", RegionDoneButton.class)));
            context.waitTicks(20);
            context.runOnClient(client -> check(!damagedWork.state(damagedWork.id("First region")).done(), "A blocked Done changed the original regions"));
            check(Arrays.equals(damagedBytes, Files.readAllBytes(damagedRegions)), "Damaged sharing metadata let regions.json change");
            check(Arrays.equals(garbage, Files.readAllBytes(sharing)), "The damaged sharing metadata was overwritten");
            capture(context, damagedScreen, "issue34-metadata-blocked");
            LOG.info("Shared native UI unreadable sharing metadata PASS reason={}", damagedKey);

            // (11) Sixteen entries, each held by a live backend: nothing is evictable, so the next placement gets no entry.
            int attached = 0;
            SharedWorkBackend refused = null;
            for (int i = 0; i < 17 && refused == null; i++) {
                var backend = context.computeOnClient(client -> new SharedWorkBackend(fresh, LocalPlacementId.create(), DatasetKind.REGIONS,
                    () -> null, () -> null, () -> true));
                if (context.computeOnClient(client -> backend.metadataBlocked())) refused = backend;
                else { held.add(backend); attached++; context.waitFor(client -> !backend.loading(), 200); }
            }
            var full = refused;
            check(full != null && attached == 15, "The sharing view did not stop at 16 open placements: attached=" + attached);
            check("placement_capacity".equals(context.computeOnClient(client -> full.reasonKey())), "The refused placement does not report the view limit");
            context.setScreen(() -> new GuiPlacementConfiguration(limited));
            var limitedScreen = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
            var limitedWork = context.computeOnClient(client -> ((RegionWorkScreen) limitedScreen).mcmateriallist$session());
            context.waitFor(client -> limitedWork.status() == StoreStatus.OK && limitedWork.id("Second region") != null, 600);
            context.runOnClient(client -> {
                limitedScreen.initGui();
                var shared = limitedWork.shared();
                check(shared.metadataBlocked() && "placement_capacity".equals(shared.reasonKey()), "A placement beyond the view limit got sharing metadata: " + shared.reasonKey());
                check(!limitedWork.writable() && !limitedWork.refreshEnabled(), "Original regions were editable without sharing metadata");
                check(limitedWork.stateReason().equals(RegionWorkSession.text("metadata_blocked", SharedWorkBackend.text("placement_capacity"))),
                    "The view limit is not stated: " + limitedWork.stateReason());
                check(!((SharedUiAccess.Button) regionButton(limitedScreen, "Second region", RegionDoneButton.class)).enabled(),
                    "Region Done was enabled beyond the view limit");
            });
            click(context, context.computeOnClient(client -> regionButton(limitedScreen, "Second region", RegionDoneButton.class)));
            context.waitTicks(20);
            check(Arrays.equals(limitedBytes, Files.readAllBytes(limitedRegions)), "A placement beyond the view limit changed regions.json");
            capture(context, limitedScreen, "issue34-placement-capacity");

            // Idle entries (closed screens, nothing to replay, restore or hide) are evicted once their screens are collected.
            held.clear();
            RegionWorkSession resumed = null;
            GuiPlacementConfiguration resumedScreen = null;
            for (int attempt = 0; attempt < 60 && resumed == null; attempt++) {
                System.gc(); context.waitTicks(10);
                context.setScreen(() -> new GuiPlacementConfiguration(limited));
                var candidateScreen = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
                var candidate = context.computeOnClient(client -> ((RegionWorkScreen) candidateScreen).mcmateriallist$session());
                if (!context.computeOnClient(client -> candidate.shared().metadataBlocked())) { resumed = candidate; resumedScreen = candidateScreen; }
            }
            check(resumed != null, "No idle sharing entry was evicted for the next placement");
            var work = resumed; var workScreen = resumedScreen;
            context.waitFor(client -> !work.shared().loading() && work.writable() && work.id("Second region") != null, 600);
            context.runOnClient(client -> workScreen.initGui());
            click(context, context.computeOnClient(client -> regionButton(workScreen, "Second region", RegionDoneButton.class)));
            context.waitFor(client -> work.writable() && work.state(work.id("Second region")).done(), 600);
            check(!Arrays.equals(limitedBytes, Files.readAllBytes(limitedRegions)), "The evicted slot did not let original region work save");
            java.lang.ref.Reference.reachabilityFence(damagedScreen);
            LOG.info("Shared native UI placement capacity PASS attached={}", attached);
        } finally {
            context.setScreen(() -> null);
            context.runOnClient(client -> { fresh.shutdown(); SharedUiAccess.Client.shared(previous); });
            Files.deleteIfExists(sharing);
            java.lang.ref.Reference.reachabilityFence(held);
        }
    }

    private static void saveFailures(ClientGameTestContext context, TestDedicatedServerContext server, Path config, SharedClientStore store) throws Exception {
        var placement = context.computeOnClient(client -> fixture(client.player.blockPosition().above(40)));
        context.waitFor(client -> NativeSharedCapture.capture(placement, null, false).ok(), 600);
        var materials = context.computeOnClient(client -> placement.getMaterialList());
        context.waitFor(client -> materials.getMaterialsAll().size() == 2
            && PlacementWorkAdapter.capture(placement, materials).status() == PlacementWorkAdapter.Status.OK, 600);
        context.setScreen(() -> new GuiMaterialList(materials));
        var screen = context.computeOnClient(client -> (GuiMaterialList) client.gui.screen());
        var work = context.computeOnClient(client -> ((MaterialWorkScreen) screen).mcmateriallist$session());
        context.waitFor(client -> !work.pending(), 600); action(context, "Track materials"); context.waitFor(client -> work.writable(), 600);
        action(context, "Share"); create(context, "Coverage save failure project");
        context.waitFor(client -> work.shared().selected() && work.writable(), 600);
        back(context); back(context);
        context.waitForScreen(GuiMaterialList.class);

        // AC-12: the server cannot replace the project's previous-good backup, so its save fails; the client says so.
        UUID actor = context.computeOnClient(client -> client.player.getUUID());
        var scope = context.computeOnClient(client -> work.shared().reference().scope());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var before = service.join(actor, scope).toCompletableFuture().get(10, TimeUnit.SECONDS).value();
        Path worldRoot = server.computeOnServer(mc -> mc.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        Path serverBackup = worldRoot.resolve("mcmateriallist-shared").resolve("project-" + scope.projectId() + ".json.bak");
        var stone = new MaterialTaskId("minecraft:stone");
        byte[] keptServer = Files.exists(serverBackup, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(serverBackup) : null;
        Files.deleteIfExists(serverBackup); Files.createDirectory(serverBackup);
        try {
            click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
            try { context.waitFor(client -> !work.pending() && work.shared().feedback().equals(SharedWorkBackend.text("result.SAVE_FAILED")), 600); }
            catch (AssertionError failure) {
                throw new AssertionError("Server save failure was not presented: " + context.computeOnClient(client -> work.shared().feedback()), failure);
            }
            context.runOnClient(client -> check(!work.state(stone).done(), "A failed server save showed the row as done"));
            check(before.equals(service.join(actor, scope).toCompletableFuture().get(10, TimeUnit.SECONDS).value()), "A failed server save changed the authority");
            capture(context, screen, "issue34-server-save-failed");
        } finally {
            Files.deleteIfExists(serverBackup);
            if (keptServer != null) Files.write(serverBackup, keptServer);
        }
        context.waitFor(client -> work.writable(), 600);
        click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
        context.waitFor(client -> work.writable() && work.state(stone).done(), 600);
        check(service.join(actor, scope).toCompletableFuture().get(10, TimeUnit.SECONDS).value().rows().get(stone).done(), "The retry after a failed server save was not saved");
        LOG.info("Shared native UI server save failure PASS");

        // A refused unshare save: the client store cannot replace its previous-good backup, so nothing is detached.
        context.setScreen(() -> new GuiPlacementConfiguration(placement));
        var regionScreen = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
        var regions = context.computeOnClient(client -> ((RegionWorkScreen) regionScreen).mcmateriallist$session());
        context.waitFor(client -> !regions.pending(), 600);
        action(context, "Track regions"); context.waitFor(client -> regions.writable(), 600);
        action(context, "Share"); create(context, "Coverage region project");
        context.waitFor(client -> regions.shared().selected() && regions.writable(), 600);
        back(context); back(context);
        for (int tick = 0; tick < 2400 && !context.computeOnClient(client -> regions.shared().canUnshare()); tick++) {
            if (tick % 300 == 299) context.runOnClient(client -> { if (!regions.pending()) regions.shared().refresh(); });
            context.waitTick();
        }
        check(context.computeOnClient(client -> regions.shared().canUnshare()),
            "Fresh shared regions never allowed Unshare: " + context.computeOnClient(client -> regions.shared().unshareFeedback()));
        LocalPlacementId id = context.computeOnClient(client -> PlacementWorkAdapter.captureRegions(placement).snapshot().placementId());
        Path primary = config.resolve("shared-client").resolve("placement-" + id + ".json");
        Path clientBackup = primary.resolveSibling(primary.getFileName() + ".bak");
        action(context, "Share"); context.waitForScreen(SharedWorkScreen.class);
        action(context, "Unshare regions...");
        context.runOnClient(client -> check(hasButton((fi.dy.masa.malilib.gui.GuiBase) client.gui.screen(), "Confirm unshare"), "Unshare confirmation did not arm"));
        var archived = store.load(id).value();
        byte[] primaryBytes = Files.readAllBytes(primary);
        byte[] keptClient = Files.exists(clientBackup, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(clientBackup) : null;
        Files.deleteIfExists(clientBackup); Files.createDirectory(clientBackup);
        String shown;
        try {
            action(context, "Confirm unshare");
            context.waitFor(client -> !regions.shared().pending(), 600);
            context.waitTicks(5);
            var after = store.load(id);
            check(after.ok() && after.value().regions().detached() == null && Objects.equals(archived.regions().shared(), after.value().regions().shared())
                && Arrays.equals(primaryBytes, Files.readAllBytes(primary)), "A refused unshare save changed the sharing settings");
            shown = context.computeOnClient(client -> regions.shared().feedback());
            context.runOnClient(client -> check(!regions.detached() && regions.shared().selected(), "A refused unshare save detached the regions"));
            check(!shown.equals(SharedWorkBackend.text("detached")) && !shown.equals(SharedWorkBackend.text("shared")),
                "A refused unshare save showed no failure: " + shown);
            capture(context, context.computeOnClient(client -> (fi.dy.masa.malilib.gui.GuiBase) client.gui.screen()), "issue34-unshare-refused");
        } finally {
            Files.deleteIfExists(clientBackup);
            if (keptClient != null) Files.write(clientBackup, keptClient);
        }
        context.waitFor(client -> regions.shared().canUnshare(), 600);
        context.waitForScreen(SharedWorkScreen.class);
        context.runOnClient(client -> ((SharedWorkScreen) client.gui.screen()).initGui());
        action(context, "Unshare regions..."); action(context, "Confirm unshare");
        context.waitFor(client -> regions.detached() && !regions.pending(), 600);
        back(context);
        LOG.info("Shared native UI refused unshare save PASS feedback={}", shown);

        // Leave no shared binding of ours behind for the next connection's reconnect restoration.
        context.setScreen(() -> screen); action(context, "Share"); action(context, "Use local work");
        context.waitFor(client -> !work.shared().selected() && !work.pending(), 600);
        back(context);
    }

    private static void track(ClientGameTestContext context, SchematicPlacement placement) {
        context.waitFor(client -> PlacementWorkAdapter.captureRegions(placement).status() == PlacementWorkAdapter.Status.OK, 600);
        context.setScreen(() -> new GuiPlacementConfiguration(placement));
        var screen = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
        var regions = context.computeOnClient(client -> ((RegionWorkScreen) screen).mcmateriallist$session());
        context.waitFor(client -> !regions.pending(), 600);
        action(context, "Track regions");
        context.waitFor(client -> regions.writable() && regions.status() == StoreStatus.OK, 600);
    }
    private static Path regionsFile(Path config, SchematicPlacement placement) {
        return config.resolve("placements").resolve(placement.getHashId().toString()).resolve("regions.json");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
