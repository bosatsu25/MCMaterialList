package dev.mcmateriallist.fabric.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmateriallist.core.location.*;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.stock.LocationReport;
import dev.mcmateriallist.core.stock.LocationStatus;
import dev.mcmateriallist.core.stock.StockGrantStore;
import dev.mcmateriallist.core.stock.StockSnapshot;
import dev.mcmateriallist.core.stock.protocol.StockMessage;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.location.LocationFileScreen;
import dev.mcmateriallist.fabric.client.location.LocationProfileEditScreen;
import dev.mcmateriallist.fabric.client.location.LocationSession;
import dev.mcmateriallist.fabric.client.location.LocationWorld;
import dev.mcmateriallist.fabric.client.location.LookedAtContainer;
import dev.mcmateriallist.fabric.client.material.MaterialWorkScreen;
import dev.mcmateriallist.fabric.client.material.MaterialWorkSession;
import dev.mcmateriallist.fabric.client.shared.NativeSharedCapture;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.fabric.client.stock.StockClient;
import dev.mcmateriallist.fabric.client.stock.StockClientNetworking;
import dev.mcmateriallist.fabric.client.stock.StockDetailScreen;
import dev.mcmateriallist.fabric.client.stock.StockObservations;
import dev.mcmateriallist.fabric.client.stock.StockSession;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.stock.StockServerNetworking;
import dev.mcmateriallist.fabric.test.mixin.ButtonTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiBaseTestAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue #34 (Phase 5G): real-client coverage of Phase 4 paths that previously had none, on one singleplayer fixture.
 * <ul>
 * <li>Location File (#9): placed shulker box, dropper and dispenser registered through the real Add control; registration of
 * a block whose chunk is no longer loaded on the client is refused; Bind / Unbind to the list's own shared project.</li>
 * <li>Check Materials (#10): the client fallback when the server lacks the stock channel (OBSERVED after opening the chest);
 * placed shulker box (storage), dropper and dispenser (machines) read by the server; CHANGED after the block was replaced; the
 * Grant / Revoke / Grant all controls of the details screen with the grant file on the integrated server; a damaged grant file
 * failing closed, its recovery from the previous-good backup, and the recovered registry served again; Cancel midway.</li>
 * </ul>
 * The singleplayer owner is a server admin and the project's creator its Administrator, so every grant path is authorized.
 * The fresh restore process only confirms that the first process wrote its evidence.
 */
public final class Phase5GCoverageClientTest implements FabricClientGameTest {
    private static final String OVERWORLD = "minecraft:overworld", COBBLE = "minecraft:cobblestone", EVIDENCE = "phase5g-evidence.json";
    private static final Logger LOG = LoggerFactory.getLogger("mcmateriallist");

    @Override public void runTest(ClientGameTestContext context) {
        Path root = Path.of(System.getProperty("mcmateriallist.phase0.fixture")).resolve("phase5g");
        try {
            Files.createDirectories(root);
            if (Boolean.getBoolean("mcmateriallist.phase0.restore")) restore(root); else run(context, root);
        } catch (Exception failure) { throw new AssertionError("Phase 5G coverage fixture failed", failure); }
        finally { Phase5GFaults.stockChannelAbsent = false; FixtureWorlds.drainSources(); }
    }

    private static void run(ClientGameTestContext context, Path root) throws Exception {
        // The restore process must never pass on evidence left by an earlier, possibly failed, run.
        Files.deleteIfExists(root.resolve(EVIDENCE));
        // Earlier tests leave idle shared-work entries behind closed screens; let those screens be collected so the sharing
        // view limit (16 open placements) cannot refuse this list.
        for (int i = 0; i < 3; i++) { System.gc(); context.waitTicks(5); }
        JsonObject evidence = new JsonObject();
        try (var world = FixtureWorlds.create(context, "Phase 5G")) {
            world.getClientLevel().waitForChunksRender();
            context.waitFor(mc -> SharedClientNetworking.state().capabilities() != null, 600);
            TestServerContext server = world.getServer();
            Consumer<String> command = server::runCommand;
            check(context.computeOnClient(mc -> StockClientNetworking.available()), "The integrated server does not answer Check Materials");
            BlockPos start = context.computeOnClient(mc -> mc.player.blockPosition());
            BlockPos base = new BlockPos(start.getX() + 800, start.getY(), start.getZ());
            String arrive = tp(base, 0);
            command.accept(arrive);
            context.waitFor(mc -> mc.player.blockPosition().getX() == base.getX(), 200);
            world.getClientLevel().waitForChunksRender();
            BlockPos low = base.offset(-2, -1, -6), high = base.offset(24, 14, 6);
            serverChunks(context, server, low, high);
            command.accept(fill(low, base.offset(24, -1, 6), "minecraft:stone"));
            command.accept(fill(base.offset(-2, 0, -6), high, "minecraft:air"));
            command.accept(arrive);
            context.waitFor(mc -> mc.level.getBlockState(base.offset(24, -1, 6)).is(Blocks.STONE), 200);
            BlockPos chest = base.offset(3, 0, 0), shulker = base.offset(6, 0, 0), dropper = base.offset(9, 0, 0), dispenser = base.offset(12, 0, 0),
                replaced = base.offset(15, 0, 0), unloaded = base.offset(18, 0, 0);
            command.accept(set(chest, "minecraft:chest[facing=north]"));
            command.accept(set(shulker, "minecraft:shulker_box"));
            command.accept(set(dropper, "minecraft:dropper"));
            command.accept(set(dispenser, "minecraft:dispenser"));
            command.accept(set(replaced, "minecraft:chest[facing=north]"));
            command.accept(set(unloaded, "minecraft:chest[facing=north]"));
            command.accept(item(chest, 0, COBBLE + " 12"));
            command.accept(item(shulker, 0, COBBLE + " 7"));
            command.accept(item(dropper, 0, COBBLE + " 3"));
            command.accept(item(dispenser, 0, COBBLE + " 2"));
            command.accept(item(replaced, 0, COBBLE + " 5"));
            context.waitFor(mc -> id(mc, chest).equals("minecraft:chest") && id(mc, shulker).equals("minecraft:shulker_box")
                && id(mc, dropper).equals("minecraft:dropper") && id(mc, dispenser).equals("minecraft:dispenser")
                && id(mc, replaced).equals("minecraft:chest") && id(mc, unloaded).equals("minecraft:chest"), 200);

            // A material list that can be shared: the schematic lives under the schematics base directory and is counted natively.
            SchematicPlacement placement = context.computeOnClient(mc -> fixture(base.offset(-6, 6, 0)));
            context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld().getBlockState(placement.getOrigin()).is(Blocks.STONE), 600);
            context.waitFor(mc -> NativeSharedCapture.capture(placement, null, false).ok(), 600);
            var materials = context.computeOnClient(mc -> placement.getMaterialList());
            context.waitFor(mc -> materials.getMaterialsAll().size() == 1
                && PlacementWorkAdapter.capture(placement, materials).status() == PlacementWorkAdapter.Status.OK, 600);
            context.getInput().resizeWindow(1920, 1080);
            context.runOnClient(mc -> { mc.options.guiScale().set(3); mc.resizeGui(); });
            context.setScreen(() -> new GuiMaterialList(materials));
            GuiMaterialList screen = context.computeOnClient(mc -> (GuiMaterialList) mc.gui.screen());
            MaterialWorkSession work = context.computeOnClient(mc -> ((MaterialWorkScreen) screen).mcmateriallist$session());
            context.waitFor(mc -> !work.pending() && work.status() == StoreStatus.MISSING, 600);
            click(context, context.computeOnClient(mc -> hover(screen, MaterialWorkSession.text("track"))));
            context.waitFor(mc -> !work.pending() && work.status() == StoreStatus.OK, 600);
            // The list's own shared project; the New project form itself is covered by the shared native UI run.
            context.waitFor(mc -> NativeSharedCapture.capture(placement, materials, true).ok(), 600);
            context.runOnClient(mc -> work.shared().create("Phase 5G project"));
            try { context.waitFor(mc -> work.shared().selected() && work.writable(), 1200); }
            catch (AssertionError failure) {
                throw new AssertionError("Phase 5G list was not shared: " + context.computeOnClient(mc -> work.shared().feedback()), failure);
            }
            ProjectScope scope = context.computeOnClient(mc -> work.shared().reference().scope());

            // The current Location File, created through the real service; its containers are added through the real screen.
            WorldIdentity identity = context.computeOnClient(mc -> ((LocationWorld.Available) LocationWorld.resolve()).world());
            check(identity.namespace().equals(scope.worldNamespace()), "Location world and shared project world differ");
            var service = MCMaterialListClient.locations();
            LocationProfile created = service.create(identity, OVERWORLD, "phase 5g").get(10, TimeUnit.SECONDS).storage().value();
            check(service.select(identity, created.id()).get(10, TimeUnit.SECONDS).selection().ok(), "Selecting the Phase 5G Location File failed");
            context.runOnClient(mc -> work.location().reload());
            context.waitFor(mc -> !work.pending() && created.equals(work.location().profile()), 200);
            String locationLabel = context.computeOnClient(mc -> work.location().label());
            context.waitFor(mc -> present(screen, locationLabel), 200);
            click(context, context.computeOnClient(mc -> hover(screen, locationLabel)));
            context.waitForScreen(LocationFileScreen.class);
            var files = context.computeOnClient(mc -> (LocationFileScreen) mc.gui.screen());
            context.waitFor(mc -> !files.pending() && files.entries() != null && files.selected() != null, 200);
            click(context, context.computeOnClient(mc -> hover(files, LocationSession.text("edit"))));
            context.waitForScreen(LocationProfileEditScreen.class);
            var edit = context.computeOnClient(mc -> (LocationProfileEditScreen) mc.gui.screen());
            context.waitFor(mc -> !edit.pending() && edit.profile() != null, 200);

            // (3) Placed shulker box, dropper and dispenser are registered by looking at them and using Add.
            register(context, command, edit, shulker, 1);
            register(context, command, edit, dropper, 2);
            register(context, command, edit, dispenser, 3);
            register(context, command, edit, chest, 4);
            register(context, command, edit, replaced, 5);
            check(edit.profile().positions().stream().map(ContainerPosition::blockId).sorted().toList()
                .equals(List.of("minecraft:chest", "minecraft:chest", "minecraft:dispenser", "minecraft:dropper", "minecraft:shulker_box")),
                "Registered container identities differ: " + edit.profile().positions());
            LOG.info("Phase 5G location shulker dropper dispenser PASS");

            // (2) A look taken while the chest was loaded, used after its chunk unloaded on the client: refused, nothing written.
            context.runOnClient(mc -> GuiBase.openGui(null));
            command.accept(tp(unloaded.above(), 90));
            context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(unloaded), 200);
            HitResult look = context.computeOnClient(mc -> mc.hitResult);
            command.accept(tp(base.offset(4000, 0, 0), 90));
            for (int i = 0; i < 2400 && context.computeOnClient(mc -> LookedAtContainer.loaded(mc.level, unloaded)); i++) context.waitTick();
            String chunkState = context.computeOnClient(mc -> "player=" + mc.player.blockPosition() + " cache=" + LookedAtContainer.loaded(mc.level, unloaded)
                + " levelHasChunkAt=" + mc.level.hasChunkAt(unloaded) + " cachedChunks=" + mc.level.getChunkSource().getLoadedChunksCount());
            LOG.info("Phase 5G client chunk state after leaving: {}", chunkState);
            check(!context.computeOnClient(mc -> LookedAtContainer.loaded(mc.level, unloaded)), "The chest chunk stayed loaded on the client: " + chunkState);
            long generation = edit.profile().generation();
            context.setScreen(() -> edit);
            context.waitFor(mc -> mc.gui.screen() == edit && !edit.pending(), 200);
            clickLooking(context, context.computeOnClient(mc -> hover(edit, LocationSession.text("add"))), look);
            context.waitFor(mc -> !edit.pending() && "reject.not_loaded".equals(edit.feedbackKey()), 200);
            check(edit.profile().generation() == generation && edit.profile().positions().size() == 5
                && edit.profile().container(unloaded.getX(), unloaded.getY(), unloaded.getZ()).isEmpty(), "Unloaded-chunk registration changed the profile");
            check(service.load(identity, created.id()).get(10, TimeUnit.SECONDS).value().generation() == generation, "Unloaded-chunk refusal wrote the profile");
            LOG.info("Phase 5G location unloaded refusal PASS");
            context.runOnClient(mc -> GuiBase.openGui(null));
            command.accept(arrive);
            context.waitFor(mc -> mc.player.blockPosition().getX() == base.getX(), 600);
            world.getClientLevel().waitForChunksRender();
            context.waitFor(mc -> mc.level.hasChunkAt(chest) && id(mc, chest).equals("minecraft:chest"), 600);

            // (1) Bind to this list's shared project, Unbind, and bind again (the grant controls below need the binding).
            context.setScreen(() -> edit);
            context.waitFor(mc -> mc.gui.screen() == edit && !edit.pending(), 200);
            context.runOnClient(mc -> check(((ButtonTestAccess) (Object) hover(edit, LocationSession.text("bind"))).phase2Enabled(),
                "Bind was not offered for the list's own shared project"));
            click(context, context.computeOnClient(mc -> hover(edit, LocationSession.text("bind"))));
            context.waitFor(mc -> !edit.pending() && "outcome.CHANGED".equals(edit.feedbackKey()) && scope.equals(edit.profile().binding()), 200);
            check(scope.equals(service.load(identity, created.id()).get(10, TimeUnit.SECONDS).value().binding()), "Bind was not durable");
            UiParityEvidence.capture(context, edit, "phase5g-location-bound");
            click(context, context.computeOnClient(mc -> hover(edit, LocationSession.text("unbind"))));
            context.waitFor(mc -> !edit.pending() && "outcome.CHANGED".equals(edit.feedbackKey()) && edit.profile().binding() == null, 200);
            check(service.load(identity, created.id()).get(10, TimeUnit.SECONDS).value().binding() == null, "Unbind was not durable");
            click(context, context.computeOnClient(mc -> hover(edit, LocationSession.text("bind"))));
            context.waitFor(mc -> !edit.pending() && scope.equals(edit.profile().binding()), 200);
            LOG.info("Phase 5G location bind unbind PASS");
            LocationProfile bound = edit.profile();
            ContainerPosition chestAt = registered(bound, chest), shulkerAt = registered(bound, shulker), dropperAt = registered(bound, dropper),
                dispenserAt = registered(bound, dispenser), replacedAt = registered(bound, replaced);

            context.setScreen(() -> screen);
            context.runOnClient(mc -> work.location().reload());
            context.waitFor(mc -> mc.gui.screen() == screen && !work.pending() && bound.equals(work.location().profile()), 200);
            String checkLabel = StockSession.text("button");

            // (10) A server without the stock channel: the client fallback, then OBSERVED once the player opened the chest.
            Phase5GFaults.stockChannelAbsent = true;
            try {
                context.waitFor(mc -> !StockClientNetworking.available() && enabled(screen, checkLabel), 200);
                click(context, context.computeOnClient(mc -> hover(screen, checkLabel)));
                context.waitFor(mc -> !StockClient.scanning() && StockClient.mode() == StockClient.Mode.FALLBACK && work.stock().snapshot().isPresent(), 200);
                context.runOnClient(mc -> {
                    var none = work.stock().snapshot().orElseThrow();
                    check(StockClient.refusal() == null && none.coverage().checked() == 0 && none.report(index(none, chestAt)).isEmpty(),
                        "Fallback without the channel reported a location before anything was observed");
                    check(work.stock().footer() != null && work.stock().footer().text().contains(StockSession.text("fallback")), "Fallback footer lacks the limited scope");
                });
                context.runOnClient(mc -> GuiBase.openGui(null));
                command.accept(tp(chest.above(), 90));
                context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(chest), 200);
                context.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, (BlockHitResult) mc.hitResult));
                context.waitFor(mc -> mc.player.containerMenu != mc.player.inventoryMenu && StockObservations.of(bound, chestAt) != null
                    && StockObservations.of(bound, chestAt).counts().getOrDefault(COBBLE, 0L) == 12, 200);
                context.runOnClient(mc -> mc.player.closeContainer());
                context.waitFor(mc -> mc.player.containerMenu == mc.player.inventoryMenu, 200);
                context.setScreen(() -> screen);
                context.waitFor(mc -> mc.gui.screen() == screen && !work.pending() && enabled(screen, checkLabel), 200);
                click(context, context.computeOnClient(mc -> hover(screen, checkLabel)));
                context.waitFor(mc -> !StockClient.scanning() && StockClient.observed() == 1, 200);
                context.runOnClient(mc -> {
                    var observed = work.stock().snapshot().orElseThrow();
                    var report = observed.report(index(observed, chestAt)).orElseThrow(() -> new AssertionError("Opened chest was not observed"));
                    check(StockClient.mode() == StockClient.Mode.FALLBACK && report.status() == LocationStatus.OBSERVED && report.count(COBBLE) == 12,
                        "Fallback did not show the opened chest as OBSERVED: " + report);
                    check(observed.coverage().checked() == 0 && observed.confirmed(COBBLE) == 0, "An observation was counted as CHECKED");
                    check(work.stock().footer().text().contains(StockSession.text("fallback")), "Fallback footer lacks the limited scope after observing");
                });
            } finally { Phase5GFaults.stockChannelAbsent = false; }
            UiParityEvidence.capture(context, screen, "phase5g-stock-fallback");
            LOG.info("Phase 5G stock fallback without channel PASS");

            // (8)(9) The server reads the placed shulker box as storage and the dropper and dispenser as machines; the replaced chest is CHANGED.
            command.accept(set(replaced, "minecraft:stone"));
            context.waitFor(mc -> id(mc, replaced).equals("minecraft:stone"), 200);
            context.waitTicks(120);  // the per-player cooldown
            context.waitFor(mc -> StockClientNetworking.available() && enabled(screen, checkLabel), 200);
            click(context, context.computeOnClient(mc -> hover(screen, checkLabel)));
            context.waitFor(mc -> StockClient.done() != null && !StockClient.scanning() && work.stock().snapshot().map(StockSnapshot::complete).orElse(false), 600);
            StockSnapshot checked = context.computeOnClient(mc -> work.stock().snapshot().orElseThrow());
            check(StockClient.mode() == StockClient.Mode.SERVER && context.computeOnClient(mc -> StockClient.done()).outcome() == StockMessage.Outcome.COMPLETE,
                "Check Materials did not use the server path");
            var chestReport = report(checked, chestAt, LocationStatus.CHECKED);
            var shulkerReport = report(checked, shulkerAt, LocationStatus.CHECKED);
            var dropperReport = report(checked, dropperAt, LocationStatus.CHECKED);
            var dispenserReport = report(checked, dispenserAt, LocationStatus.CHECKED);
            var replacedReport = report(checked, replacedAt, LocationStatus.CHANGED);
            check(chestReport.kind() == ContainerKind.STORAGE && chestReport.count(COBBLE) == 12, "Chest reading differs: " + chestReport);
            check(shulkerReport.kind() == ContainerKind.STORAGE && shulkerReport.count(COBBLE) == 7, "Placed shulker box was not read as storage: " + shulkerReport);
            check(dropperReport.kind() == ContainerKind.MACHINE && dropperReport.count(COBBLE) == 3, "Dropper was not read as a machine: " + dropperReport);
            check(dispenserReport.kind() == ContainerKind.MACHINE && dispenserReport.count(COBBLE) == 2, "Dispenser was not read as a machine: " + dispenserReport);
            check(replacedReport.counts().isEmpty(), "A changed block carried counts");
            check(checked.confirmed(COBBLE) == 19 && checked.machine(COBBLE) == 5, "Storage and machine totals differ: " + checked.confirmed(COBBLE) + "/" + checked.machine(COBBLE));
            check(checked.coverage().checked() == 4 && checked.coverage().total() == 5, "Coverage differs: " + checked.coverage());
            context.waitFor(mc -> present(screen, StockSession.text("details")) && present(screen, work.stock().label()), 200);
            context.runOnClient(mc -> {
                String info = work.stock().info(COBBLE);
                check(info.contains(StockSession.text("info.stock", 19)) && info.contains(StockSession.text("info.machines", 5)), "Show Info stock segment differs: " + info);
            });
            UiParityEvidence.capture(context, screen, "phase5g-stock-containers");
            LOG.info("Phase 5G stock containers and CHANGED PASS");

            // (5) Grant, Revoke and Grant all in the details screen, confirmed in the integrated server's grant file.
            Path worldRoot = server.computeOnServer(mc -> mc.getWorldPath(LevelResource.ROOT));
            var grantStore = new StockGrantStore(worldRoot);
            Path grantFile = worldRoot.resolve("mcmateriallist-stock").resolve("grants").resolve(scope.projectId() + ".json");
            StockDetailScreen details = openDetails(context, screen);
            context.waitFor(mc -> !details.pending() && details.grantStatus() == StockMessage.GrantStatus.OK && details.grants() != null, 600);
            check(details.grants().isEmpty(), "A fresh project already had grants: " + details.grants());
            String grantChest = StockSession.text("grant_hover", describe(chestAt)), revokeChest = StockSession.text("revoke_hover", describe(chestAt));
            context.waitFor(mc -> present(details, grantChest), 200);
            UiParityEvidence.capture(context, details, "phase5g-stock-details-admin");
            click(context, context.computeOnClient(mc -> hover(details, grantChest)));
            context.waitFor(mc -> !details.pending() && details.grants() != null && granted(details, chestAt) && present(details, revokeChest), 600);
            check(grantStore.load(scope.projectId()).ok() && grantStore.load(scope.projectId()).value().grants(OVERWORLD, chestAt), "Grant was not saved by the server");
            click(context, context.computeOnClient(mc -> hover(details, revokeChest)));
            context.waitFor(mc -> !details.pending() && details.grants() != null && !granted(details, chestAt) && present(details, grantChest), 600);
            check(grantStore.load(scope.projectId()).ok() && !grantStore.load(scope.projectId()).value().grants(OVERWORLD, chestAt), "Revoke was not saved by the server");
            click(context, context.computeOnClient(mc -> hover(details, StockSession.text("grant_all"))));
            context.waitFor(mc -> !details.pending() && details.grants() != null && bound.positions().stream().allMatch(position -> granted(details, position)), 600);
            var all = grantStore.load(scope.projectId());
            check(all.ok() && bound.positions().stream().allMatch(position -> all.value().grants(OVERWORLD, position)), "Grant all was not saved by the server");
            UiParityEvidence.capture(context, details, "phase5g-stock-details-granted");
            LOG.info("Phase 5G stock grant revoke buttons PASS");

            // (6) A damaged grant file fails closed (no grant controls, damaged bytes kept); recovery restores the previous-good
            // backup (the state after Revoke) and the details screen manages grants again.
            check(Files.exists(grantFile) && Files.exists(grantFile.resolveSibling(grantFile.getFileName() + ".bak")), "Grant file or its backup is missing");
            byte[] damaged = "{\"damaged\":".getBytes(StandardCharsets.UTF_8);
            Files.write(grantFile, damaged);
            StockDetailScreen failed = openDetails(context, screen);
            context.waitFor(mc -> !failed.pending() && failed.grantStatus() == StockMessage.GrantStatus.FAILED, 600);
            context.runOnClient(mc -> check(!present(failed, StockSession.text("grant_all")) && bound.positions().stream().noneMatch(position ->
                present(failed, StockSession.text("grant_hover", describe(position))) || present(failed, StockSession.text("revoke_hover", describe(position)))),
                "A damaged grant file still offered grant controls"));
            UiParityEvidence.capture(context, failed, "phase5g-stock-details-damaged");
            check(Arrays.equals(damaged, Files.readAllBytes(grantFile)), "Listing a damaged grant file rewrote it");
            check(grantStore.load(scope.projectId()).status() == StoreStatus.RECOVERY_REQUIRED, "Damaged grant file with a backup is not RECOVERY_REQUIRED");
            var recovered = grantStore.recover(scope.projectId());
            check(recovered.ok() && recovered.value().grants().isEmpty(), "Recovery did not restore the previous-good grants: " + recovered.status());
            try (var listing = Files.list(grantFile.getParent())) {
                check(listing.anyMatch(path -> path.getFileName().toString().startsWith("grants-damaged-") && contentEquals(path, damaged)),
                    "Recovery did not preserve the damaged grant bytes");
            }
            StockDetailScreen restored = openDetails(context, screen);
            context.waitFor(mc -> !restored.pending() && restored.grantStatus() == StockMessage.GrantStatus.OK && restored.grants() != null, 600);
            check(restored.grants().isEmpty(), "Recovered registry differs from the backup: " + restored.grants());
            context.waitFor(mc -> present(restored, grantChest), 200);
            LOG.info("Phase 5G stock grant file recovery PASS");

            // (7) Cancel midway: 256 registered barrels stream at most 4 reports per tick, so the check runs for at least 64 ticks.
            context.runOnClient(mc -> GuiBase.openGui(null));
            int x0 = (base.getX() & ~15) + 4, z0 = (base.getZ() & ~15) + 4, y0 = base.getY() + 10;
            command.accept(fill(new BlockPos(x0, y0, z0), new BlockPos(x0 + 7, y0 + 3, z0 + 7), "minecraft:barrel"));
            context.waitFor(mc -> id(mc, new BlockPos(x0 + 7, y0 + 3, z0 + 7)).equals("minecraft:barrel"), 200);
            LocationProfile many = service.create(identity, OVERWORLD, "phase 5g cancel").get(10, TimeUnit.SECONDS).storage().value();
            for (int dx = 0; dx < 8; dx++) for (int dy = 0; dy < 4; dy++) for (int dz = 0; dz < 8; dz++) {
                var update = service.addContainer(identity, many.id(), many.generation(), OVERWORLD,
                    new ContainerPosition(x0 + dx, y0 + dy, z0 + dz, "minecraft:barrel")).get(10, TimeUnit.SECONDS);
                check(update.outcome() == LocationOutcome.CHANGED, "Adding a cancel fixture barrel failed: " + update.outcome());
                many = update.storage().value();
            }
            LocationProfile barrels = many;
            check(service.select(identity, barrels.id()).get(10, TimeUnit.SECONDS).selection().ok(), "Selecting the cancel fixture failed");
            context.setScreen(() -> screen);
            context.runOnClient(mc -> work.location().reload());
            context.waitFor(mc -> mc.gui.screen() == screen && !work.pending() && barrels.equals(work.location().profile()), 200);
            context.waitTicks(120);  // the per-player cooldown
            context.waitFor(mc -> enabled(screen, checkLabel), 200);
            click(context, context.computeOnClient(mc -> hover(screen, checkLabel)));
            context.waitFor(mc -> StockClient.scanning(), 100);
            String sample = StockSession.text("checking", 7, 9), prefix = sample.substring(0, sample.indexOf('7'));
            context.waitFor(mc -> cancelControl(screen, prefix) != null, 100);
            check(context.computeOnClient(mc -> StockClient.scanning() && StockClient.total() == 256), "The 256-barrel check was not running before Cancel");
            click(context, context.computeOnClient(mc -> cancelControl(screen, prefix)));
            context.waitFor(mc -> !StockClient.scanning(), 200);
            StockSnapshot cut = context.computeOnClient(mc -> work.stock().snapshot().orElseThrow());
            check(context.computeOnClient(mc -> StockClient.done()) == null, "A cancelled check reported Done");
            check(cut.reports().size() < 256 && cut.coverage().checked() < 256 && cut.unchecked() > 0, "Cancel did not stop the check midway: " + cut.coverage());
            for (int i = 0; i < 400 && server.computeOnServer(StockServerNetworking::running) != 0; i++) context.waitTick();
            check(server.computeOnServer(StockServerNetworking::running) == 0, "The server kept the cancelled scan running");
            context.waitFor(mc -> present(screen, checkLabel), 200);
            context.runOnClient(mc -> check(work.stock().footer() != null && work.stock().footer().text().contains(StockSession.text("footer.unchecked", cut.unchecked())),
                "Footer does not state the cancelled check's unchecked locations"));
            UiParityEvidence.capture(context, screen, "phase5g-stock-cancelled");
            LOG.info("Phase 5G stock cancel midway PASS reports={} unchecked={}", cut.reports().size(), cut.unchecked());
            evidence.addProperty("cancelledReports", cut.reports().size());
            context.runOnClient(mc -> GuiBase.openGui(null));
        }
        evidence.addProperty("pass", true);
        Files.writeString(root.resolve(EVIDENCE), evidence.toString());
        LOG.info("Phase 5G coverage assertions PASS");
    }

    /** Fresh process: the first process passed every Phase 5G assertion. */
    private static void restore(Path root) throws Exception {
        var evidence = JsonParser.parseString(Files.readString(root.resolve(EVIDENCE))).getAsJsonObject();
        check(evidence.get("pass").getAsBoolean(), "The first process did not pass the Phase 5G coverage");
        LOG.info("Phase 5G coverage restore PASS cancelledReports={}", evidence.get("cancelledReports").getAsInt());
    }

    /** Looks at the block from directly above, then uses the real Add control; each must be accepted. */
    private static void register(ClientGameTestContext context, Consumer<String> command, LocationProfileEditScreen edit, BlockPos block, int count) {
        context.runOnClient(mc -> GuiBase.openGui(null));
        command.accept(tp(block.above(), 90));
        context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block), 200);
        context.setScreen(() -> edit);
        context.waitFor(mc -> mc.gui.screen() == edit && !edit.pending(), 200);
        click(context, context.computeOnClient(mc -> hover(edit, LocationSession.text("add"))));
        try { context.waitFor(mc -> !edit.pending() && "outcome.CHANGED".equals(edit.feedbackKey()) && edit.profile().positions().size() == count, 200); }
        catch (AssertionError failure) { throw new AssertionError("Registering " + block + " failed: " + context.computeOnClient(mc -> edit.feedback()), failure); }
    }
    private static StockDetailScreen openDetails(ClientGameTestContext context, GuiMaterialList screen) {
        context.setScreen(() -> screen);
        context.waitFor(mc -> mc.gui.screen() == screen && present(screen, StockSession.text("details")), 200);
        click(context, context.computeOnClient(mc -> hover(screen, StockSession.text("details"))));
        context.waitForScreen(StockDetailScreen.class);
        return context.computeOnClient(mc -> (StockDetailScreen) mc.gui.screen());
    }
    private static ButtonBase cancelControl(GuiBase screen, String prefix) {
        return ((GuiBaseTestAccess) (Object) screen).phase0Buttons().stream()
            .filter(button -> !button.getHoverStrings().isEmpty() && button.getHoverStrings().getFirst().startsWith(prefix)).findFirst().orElse(null);
    }
    private static boolean granted(StockDetailScreen details, ContainerPosition position) {
        return details.grants().stream().anyMatch(grant -> grant.at(OVERWORLD, position));
    }
    private static ContainerPosition registered(LocationProfile profile, BlockPos pos) {
        return profile.container(pos.getX(), pos.getY(), pos.getZ()).orElseThrow(() -> new AssertionError("Position not registered: " + pos));
    }
    private static int index(StockSnapshot snapshot, ContainerPosition position) {
        for (int i = 0; i < snapshot.positions().size(); i++) if (snapshot.positions().get(i).at(position.x(), position.y(), position.z())) return i;
        throw new AssertionError("Position not in the check: " + position);
    }
    private static LocationReport report(StockSnapshot snapshot, ContainerPosition position, LocationStatus expected) {
        var report = snapshot.report(index(snapshot, position)).orElseThrow(() -> new AssertionError("No report for " + position));
        check(report.status() == expected, "Location " + position + " is " + report.status() + ", expected " + expected);
        return report;
    }
    private static String describe(ContainerPosition position) {
        return StockSession.text("position", position.x(), position.y(), position.z(), position.blockId());
    }
    private static boolean contentEquals(Path path, byte[] expected) {
        try { return Arrays.equals(expected, Files.readAllBytes(path)); }
        catch (java.io.IOException failure) { return false; }
    }
    private static String id(Minecraft mc, BlockPos pos) { return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString(); }
    /** The client position moves first; fill fails with "That position is not loaded" until the server has both corners. */
    private static void serverChunks(ClientGameTestContext context, TestServerContext server, BlockPos low, BlockPos high) {
        for (int i = 0; i < 1200 && !server.computeOnServer(mc -> mc.overworld().hasChunkAt(low) && mc.overworld().hasChunkAt(high)); i++) context.waitTick();
        check(server.computeOnServer(mc -> mc.overworld().hasChunkAt(low) && mc.overworld().hasChunkAt(high)), "Fixture chunks never loaded on the server");
    }
    private static String tp(BlockPos at, int pitch) {
        return "execute in " + OVERWORLD + " run tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 0 " + pitch;
    }
    private static String fill(BlockPos from, BlockPos to, String block) {
        return "fill " + from.getX() + " " + from.getY() + " " + from.getZ() + " " + to.getX() + " " + to.getY() + " " + to.getZ() + " " + block;
    }
    private static String set(BlockPos at, String block) { return "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + block; }
    private static String item(BlockPos at, int slot, String stack) {
        return "item replace block " + at.getX() + " " + at.getY() + " " + at.getZ() + " container." + slot + " with " + stack;
    }
    private static boolean present(GuiBase screen, String label) {
        return ((GuiBaseTestAccess) (Object) screen).phase0Buttons().stream().anyMatch(button -> button.getHoverStrings().contains(label));
    }
    private static boolean enabled(GuiBase screen, String label) {
        return ((GuiBaseTestAccess) (Object) screen).phase0Buttons().stream()
            .anyMatch(button -> button.getHoverStrings().contains(label) && ((ButtonTestAccess) (Object) button).phase2Enabled());
    }
    private static ButtonBase hover(GuiBase screen, String label) {
        return ((GuiBaseTestAccess) (Object) screen).phase0Buttons().stream().filter(button -> button.getHoverStrings().contains(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing control: " + label));
    }
    private static void click(ClientGameTestContext context, WidgetBase button) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    /**
     * The game never produces a block hit result for an unloaded block, because reach is far shorter than the loaded radius. The
     * look is therefore one the game itself produced while the chest was loaded; it is put back immediately before the press,
     * with no client tick in between, so the Add control validates exactly that look after the chunk unloaded.
     */
    private static void clickLooking(ClientGameTestContext context, WidgetBase button, HitResult look) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick();
        context.runOnClient(mc -> mc.hitResult = look);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    /** Under the schematics base directory, so the native source proof and digest used by sharing are available. */
    private static SchematicPlacement fixture(BlockPos origin) throws java.io.IOException {
        Path base = DataManager.getSchematicsBaseDirectory(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "phase5g-fixture-");
        var selection = new AreaSelection();
        selection.addSubRegionBox(new Box(BlockPos.ZERO, BlockPos.ZERO, "Phase 5G"), false);
        var schematic = LitematicaSchematic.createEmptySchematic(selection, "Phase5GTest");
        schematic.getSubRegionContainer("Phase 5G").set(0, 0, 0, Blocks.STONE.defaultBlockState());
        if (!schematic.writeToFile(directory, "fixture", false)) throw new AssertionError("Phase 5G schematic write failed");
        var placement = SchematicPlacement.createFor(LitematicaSchematic.createFromFile(directory, "fixture.litematic"), origin, "Phase 5G coverage", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }
}
