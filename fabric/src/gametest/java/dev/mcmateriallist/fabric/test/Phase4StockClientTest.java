package dev.mcmateriallist.fabric.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmateriallist.core.location.*;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.stock.LocationReport;
import dev.mcmateriallist.core.stock.LocationStatus;
import dev.mcmateriallist.core.stock.StockLimits;
import dev.mcmateriallist.core.stock.StockSnapshot;
import dev.mcmateriallist.core.stock.protocol.StockMessage;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.location.LocationWorld;
import dev.mcmateriallist.fabric.client.material.MaterialWorkScreen;
import dev.mcmateriallist.fabric.client.material.MaterialWorkSession;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.fabric.client.stock.StockClient;
import dev.mcmateriallist.fabric.client.stock.StockClientNetworking;
import dev.mcmateriallist.fabric.client.stock.StockDetailScreen;
import dev.mcmateriallist.fabric.client.stock.StockSession;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.stock.StockServerNetworking;
import dev.mcmateriallist.fabric.test.mixin.ButtonTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiBaseTestAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/**
 * Issue #10, singleplayer: Check Materials through the real material-list button against the integrated server's real blocks.
 * The singleplayer owner is a server admin (R1), so every authorized path is the server path; non-admin and grant paths are
 * covered by the multiplayer harness. Asserts statuses, deduplication, machine separation, the unloaded and loot-table guards,
 * "previous" after leaving the dimension, the per-tick container budget with 512 barrels, and that work bytes and the
 * Total/Missing/Available columns never change. The fresh restore process asserts that nothing about inventories was persisted.
 */
public final class Phase4StockClientTest implements FabricClientGameTest {
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether", COBBLE = "minecraft:cobblestone";
    private static final String EVIDENCE = "stock-evidence.json";

    @Override public void runTest(ClientGameTestContext context) {
        Path root = Path.of(System.getProperty("mcmateriallist.phase0.fixture")).resolve("phase4b");
        try {
            Files.createDirectories(root);
            if (Boolean.getBoolean("mcmateriallist.phase0.restore")) restore(root); else run(context, root);
        } catch (Exception failure) { throw new AssertionError("Phase 4B Check Materials fixture failed", failure); }
        finally { FixtureWorlds.drainSources(); }
    }

    private static void run(ClientGameTestContext context, Path root) throws Exception {
        // The restore process must never pass on evidence left by an earlier, possibly failed, run.
        Files.deleteIfExists(root.resolve(EVIDENCE));
        var log = LoggerFactory.getLogger("mcmateriallist");
        JsonObject evidence = new JsonObject();
        try (var world = FixtureWorlds.create(context, "Phase 4B")) {
            world.getClientLevel().waitForChunksRender();
            context.waitFor(mc -> SharedClientNetworking.state().capabilities() != null, 600);
            TestServerContext server = world.getServer();
            Consumer<String> command = server::runCommand;
            check(context.computeOnClient(mc -> StockClientNetworking.available()), "The integrated server does not answer Check Materials");
            BlockPos start = context.computeOnClient(mc -> mc.player.blockPosition());
            // Away from the spawn area, so leaving the dimension really unloads the fixture chunks.
            BlockPos base = new BlockPos(start.getX() + 800, start.getY(), start.getZ());
            String arrive = "tp @a " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5) + " 0 0";
            // The fixture chunks must be loaded before fill can change them; then stand on the fixture strip again.
            command.accept(arrive);
            context.waitFor(mc -> mc.player.blockPosition().getX() == base.getX(), 200);
            world.getClientLevel().waitForChunksRender();
            // The client position moves first; fill fails with "That position is not loaded" until the server has both corners.
            BlockPos low = base.offset(-2, -1, -6), high = base.offset(24, 8, 6);
            for (int i = 0; i < 1200 && !server.computeOnServer(mc -> mc.overworld().hasChunkAt(low) && mc.overworld().hasChunkAt(high)); i++) context.waitTick();
            check(server.computeOnServer(mc -> mc.overworld().hasChunkAt(low) && mc.overworld().hasChunkAt(high)), "Fixture chunks never loaded on the server");
            command.accept(fill(base.offset(-2, -1, -6), base.offset(24, -1, 6), "minecraft:stone"));
            command.accept(fill(base.offset(-2, 0, -6), base.offset(24, 8, 6), "minecraft:air"));
            command.accept(arrive);
            context.waitFor(mc -> mc.level.getBlockState(base.offset(24, -1, 6)).is(Blocks.STONE), 200);
            BlockPos chest = base.offset(3, 0, 0), left = base.offset(6, 0, 0), right = base.offset(7, 0, 0), barrel = base.offset(10, 0, 0),
                hopper = base.offset(13, 0, 0), loot = base.offset(16, 0, 0), far = base.offset(4000, 0, 0);
            command.accept(set(chest, "minecraft:chest[facing=north]"));
            // Facing north, a left half connects east: one double chest at x and x + 1.
            command.accept(set(left, "minecraft:chest[facing=north,type=left]"));
            command.accept(set(right, "minecraft:chest[facing=north,type=right]"));
            command.accept(set(barrel, "minecraft:barrel"));
            command.accept(set(hopper, "minecraft:hopper"));
            command.accept(set(loot, "minecraft:chest[facing=north]{LootTable:\"minecraft:chests/simple_dungeon\"}"));
            command.accept(item(chest, 0, "minecraft:cobblestone 10"));
            command.accept(item(left, 0, "minecraft:cobblestone 30"));
            command.accept(item(right, 0, "minecraft:cobblestone 40"));
            command.accept(item(barrel, 0, "minecraft:cobblestone 20"));
            // Depth 0: the shulker box item counts as itself; its 64 cobblestone are not stock.
            command.accept(item(barrel, 1, "minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:\"minecraft:cobblestone\",count:64}}]] 1"));
            command.accept(item(hopper, 0, "minecraft:cobblestone 5"));
            context.waitFor(mc -> mc.level.getBlockState(right).is(Blocks.CHEST) && mc.level.getBlockState(loot).is(Blocks.CHEST)
                && mc.level.getBlockState(hopper).is(Blocks.HOPPER) && mc.level.getBlockState(barrel).is(Blocks.BARREL), 200);
            check(server.computeOnServer(mc -> mc.overworld().getBlockEntity(loot) instanceof RandomizableContainer container && container.getLootTable() != null),
                "Loot-table fixture has no loot table");
            check(!server.computeOnServer(mc -> mc.overworld().hasChunkAt(far)), "The far fixture position is loaded");

            SchematicPlacement placement = context.computeOnClient(mc -> fixture(root, base.offset(-6, 6, 0)));
            context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld().getBlockState(placement.getOrigin()).is(Blocks.STONE));
            var materials = context.computeOnClient(mc -> new MaterialListPlacement(placement, true));
            context.waitFor(mc -> materials.getMaterialsAll().size() == 1 && PlacementWorkAdapter.capture(placement, materials).status() == PlacementWorkAdapter.Status.OK);
            byte[] schematicBytes = Files.readAllBytes(placement.getSchematicFile());
            context.getInput().resizeWindow(1920, 1080);
            context.runOnClient(mc -> { mc.options.guiScale().set(3); mc.resizeGui(); });
            context.setScreen(() -> new GuiMaterialList(materials));
            GuiMaterialList screen = context.computeOnClient(mc -> (GuiMaterialList) mc.gui.screen());
            MaterialWorkSession work = context.computeOnClient(mc -> ((MaterialWorkScreen) screen).mcmateriallist$session());
            context.waitFor(mc -> !work.pending() && work.status() == StoreStatus.MISSING);
            click(context, context.computeOnClient(mc -> hover(screen, MaterialWorkSession.text("track"))));
            context.waitFor(mc -> !work.pending() && work.status() == StoreStatus.OK);
            Path materialsFile = FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist/placements").resolve(placement.getHashId().toString()).resolve("materials.json");
            byte[] materialBytes = Files.readAllBytes(materialsFile);
            List<String> columns = context.computeOnClient(mc -> columns(materials));
            String checkLabel = StockSession.text("button");
            context.runOnClient(mc -> {
                check(!((ButtonTestAccess) hover(screen, checkLabel)).phase2Enabled(), "Check Materials was enabled without a Location File");
                check(work.stock().footer() == null && work.stock().info(COBBLE).isEmpty(), "A check was shown before one ran");
                UiParityEvidence.controls(screen);
            });

            // The Location File, through the real service; the right half is added too, so it must fold into the left one.
            WorldIdentity identity = context.computeOnClient(mc -> ((LocationWorld.Available) LocationWorld.resolve()).world());
            var service = MCMaterialListClient.locations();
            LocationProfile profile = service.create(identity, OVERWORLD, "stock fixture").get(10, TimeUnit.SECONDS).storage().value();
            check(service.select(identity, profile.id()).get(10, TimeUnit.SECONDS).selection().ok(), "Selecting the stock Location File failed");
            for (BlockPos pos : List.of(chest, left, right, barrel, hopper, loot, far)) {
                String block = pos.equals(hopper) ? "minecraft:hopper" : pos.equals(barrel) ? "minecraft:barrel" : "minecraft:chest";
                var update = service.addContainer(identity, profile.id(), profile.generation(), OVERWORLD, new ContainerPosition(pos.getX(), pos.getY(), pos.getZ(), block))
                    .get(10, TimeUnit.SECONDS);
                check(update.outcome() == LocationOutcome.CHANGED, "Adding a stock fixture position failed: " + update.outcome());
                profile = update.storage().value();
            }
            LocationProfile registered = profile;
            context.runOnClient(mc -> work.location().reload());
            context.waitFor(mc -> !work.pending() && registered.equals(work.location().profile())
                && ((ButtonTestAccess) hover(screen, checkLabel)).phase2Enabled(), 200);

            click(context, context.computeOnClient(mc -> hover(screen, checkLabel)));
            context.waitFor(mc -> StockClient.done() != null && !StockClient.scanning() && work.stock().snapshot().map(StockSnapshot::complete).orElse(false), 600);
            StockSnapshot first = context.computeOnClient(mc -> work.stock().snapshot().orElseThrow());
            StockMessage.Done done = context.computeOnClient(mc -> StockClient.done());
            check(StockClient.mode() == StockClient.Mode.SERVER && done.outcome() == StockMessage.Outcome.COMPLETE, "Check Materials did not use the server path");
            status(first, registered, chest, LocationStatus.CHECKED);
            status(first, registered, left, LocationStatus.CHECKED);
            status(first, registered, right, LocationStatus.DUPLICATE);
            status(first, registered, barrel, LocationStatus.CHECKED);
            status(first, registered, hopper, LocationStatus.CHECKED);
            status(first, registered, loot, LocationStatus.UNREADABLE);
            status(first, registered, far, LocationStatus.UNLOADED);
            var chestReport = report(first, registered, chest); var doubleReport = report(first, registered, left);
            var barrelReport = report(first, registered, barrel); var hopperReport = report(first, registered, hopper);
            check(chestReport.count(COBBLE) == 10, "Single chest count differs: " + chestReport.counts());
            check(doubleReport.count(COBBLE) == 70, "Double chest was not counted once with both halves: " + doubleReport.counts());
            check(barrelReport.count(COBBLE) == 20 && barrelReport.count("minecraft:shulker_box") == 1, "Barrel or depth-0 shulker counting differs: " + barrelReport.counts());
            check(hopperReport.kind() == ContainerKind.MACHINE && hopperReport.count(COBBLE) == 5, "Hopper was not reported as MACHINE");
            check(first.confirmed(COBBLE) == 100 && first.machine(COBBLE) == 5, "Storage and machine totals differ: " + first.confirmed(COBBLE) + "/" + first.machine(COBBLE));
            check(first.coverage().checked() == 4 && first.coverage().total() == 6 && first.unchecked() == 2, "Coverage differs: " + first.coverage());
            check(done.maxContainersPerTick() >= 1 && done.maxContainersPerTick() <= StockLimits.CONTAINERS_PER_TICK, "Container budget exceeded: " + done.maxContainersPerTick());
            check(!server.computeOnServer(mc -> mc.overworld().hasChunkAt(far)), "Checking loaded the far chunk");
            check(server.computeOnServer(mc -> mc.overworld().getBlockEntity(loot) instanceof RandomizableContainer container && container.getLootTable() != null),
                "Checking generated the loot table");
            context.runOnClient(mc -> {
                String info = work.stock().info(COBBLE);
                check(info.contains(StockSession.text("info.stock", 100)) && info.contains(StockSession.text("info.machines", 5)), "Show Info stock segment differs: " + info);
                check(work.stock().tooltip(COBBLE).contains(StockSession.text("tooltip.unchecked", 2)), "Tooltip does not state unchecked locations");
                check(work.stock().footer().text().contains(StockSession.text("footer.unchecked", 2)), "Footer does not state unchecked locations");
                check(columns.equals(columns(materials)), "Check Materials changed Total/Missing/Available");
                UiParityEvidence.controls(screen);
            });
            UiParityEvidence.capture(context, screen, "phase4b-material-stock");
            click(context, context.computeOnClient(mc -> hover(screen, StockSession.text("details"))));
            context.waitForScreen(StockDetailScreen.class);
            UiParityEvidence.capture(context, context.computeOnClient(mc -> (GuiBase) mc.gui.screen()), "phase4b-stock-details");
            context.setScreen(() -> screen);
            log.info("Phase 4B Check Materials statuses PASS checked={} duplicate=1 machine=1 unloaded=1 unreadable=1", first.coverage().checked());

            // Leave the dimension: the overworld unloads, a re-check reports UNLOADED, and the earlier values become "previous".
            context.runOnClient(mc -> GuiBase.openGui(null));
            command.accept("execute in " + NETHER + " run tp @a 0.5 129 0.5 0 90");
            context.waitFor(mc -> LocationWorld.resolve() instanceof LocationWorld.Available now && now.dimension().equals(NETHER), 600);
            for (int i = 0; i < 2400 && server.computeOnServer(mc -> mc.overworld().hasChunkAt(chest)); i++) context.waitTick();
            check(!server.computeOnServer(mc -> mc.overworld().hasChunkAt(chest)), "Overworld fixture stayed loaded after leaving it");
            context.waitTicks(120);  // the per-player cooldown
            context.setScreen(() -> screen);
            context.waitFor(mc -> mc.gui.screen() == screen && !work.pending());
            click(context, context.computeOnClient(mc -> hover(screen, checkLabel)));
            context.waitFor(mc -> !StockClient.scanning() && StockClient.done() != null && StockClient.done() != done, 600);
            StockSnapshot second = context.computeOnClient(mc -> work.stock().snapshot().orElseThrow());
            status(second, registered, chest, LocationStatus.UNLOADED);
            status(second, registered, left, LocationStatus.UNLOADED);
            status(second, registered, right, LocationStatus.UNLOADED);
            check(second.confirmed(COBBLE) == 0 && second.coverage().checked() == 0, "An unloaded location was counted");
            check(second.previous(COBBLE).count() == 100 && second.previous(COBBLE).locations() == 3, "Earlier readings were not kept as previous: " + second.previous(COBBLE));
            context.runOnClient(mc -> {
                String info = work.stock().info(COBBLE);
                check(info.contains(StockSession.text("info.unchecked")) && info.contains(StockSession.text("info.previous", 100)), "Show Info does not mark previous: " + info);
                check(columns.equals(columns(materials)), "Re-checking changed Total/Missing/Available");
            });
            UiParityEvidence.capture(context, screen, "phase4b-material-previous");
            log.info("Phase 4B Check Materials previous PASS previous={}", second.previous(COBBLE).count());

            // 512 registered barrels: the scheduler serves at most 8 containers per tick; nanoseconds are a measurement only.
            context.runOnClient(mc -> GuiBase.openGui(null));
            command.accept("execute in " + OVERWORLD + " run " + arrive);
            context.waitFor(mc -> LocationWorld.resolve() instanceof LocationWorld.Available now && now.dimension().equals(OVERWORLD), 600);
            world.getClientLevel().waitForChunksRender();
            int x0 = (base.getX() & ~15) + 4, z0 = (base.getZ() & ~15) + 4, y0 = base.getY() + 10;
            command.accept(fill(new BlockPos(x0, y0, z0), new BlockPos(x0 + 7, y0 + 7, z0 + 7), "minecraft:barrel"));
            context.waitFor(mc -> mc.level.getBlockState(new BlockPos(x0 + 7, y0 + 7, z0 + 7)).is(Blocks.BARREL), 200);
            List<ContainerPosition> barrels = new ArrayList<>();
            for (int dx = 0; dx < 8; dx++) for (int dy = 0; dy < 8; dy++) for (int dz = 0; dz < 8; dz++)
                barrels.add(new ContainerPosition(x0 + dx, y0 + dy, z0 + dz, "minecraft:barrel"));
            context.waitTicks(120);
            var replies = new ConcurrentLinkedQueue<StockMessage>();
            var request = context.computeOnClient(mc -> StockClientNetworking.send(id -> new StockMessage.ScanRequest(id, LocationProfileId.create(), 0, null, OVERWORLD, barrels), replies::add));
            check(request != null, "The 512-barrel request was not sent");
            for (int i = 0; i < 1200 && replies.stream().noneMatch(m -> m instanceof StockMessage.Done || m instanceof StockMessage.Refused); i++) context.waitTick();
            var budget = replies.stream().filter(m -> m instanceof StockMessage.Done).map(m -> (StockMessage.Done) m).findFirst()
                .orElseThrow(() -> new AssertionError("512-barrel check did not finish: " + replies.size()));
            long reports = replies.stream().filter(m -> m instanceof StockMessage.Report).count();
            var stats = server.computeOnServer(StockServerNetworking::stats);
            check(budget.reports() == 512 && reports == 512 && budget.checked() == 512, "512-barrel check did not read every barrel: " + budget);
            check(budget.maxContainersPerTick() <= StockLimits.CONTAINERS_PER_TICK && stats.maxContainers() <= StockLimits.CONTAINERS_PER_TICK,
                "Per-tick container budget exceeded: " + budget.maxContainersPerTick() + "/" + stats.maxContainers());
            log.info("Phase 4B Check Materials budget PASS positions=512 maxContainersPerTick={} maxNanosPerTick={} (measured, not gated)",
                budget.maxContainersPerTick(), budget.maxNanosPerTick());
            evidence.addProperty("maxContainersPerTick", budget.maxContainersPerTick());
            evidence.addProperty("maxNanosPerTick", budget.maxNanosPerTick());

            context.setScreen(() -> screen);
            context.waitFor(mc -> mc.gui.screen() == screen && !work.pending());
            context.runOnClient(mc -> check(columns.equals(columns(materials)), "Check Materials changed Total/Missing/Available"));
            check(Arrays.equals(materialBytes, Files.readAllBytes(materialsFile)), "Check Materials changed materials.json");
            check(Arrays.equals(schematicBytes, Files.readAllBytes(placement.getSchematicFile())), "Check Materials changed .litematic bytes");
            noStockFiles();
            context.runOnClient(mc -> GuiBase.openGui(null));
        }
        // Leaving the world discards every result.
        context.waitFor(mc -> StockClient.snapshot(null).isEmpty() && StockClient.known() == null, 200);
        evidence.addProperty("pass", true);
        Files.writeString(root.resolve(EVIDENCE), evidence.toString());
        log.info("Phase 4B Check Materials assertions PASS");
    }

    /** Fresh process: the first process passed, and nothing about inventories reached the client configuration. */
    private static void restore(Path root) throws Exception {
        var evidence = JsonParser.parseString(Files.readString(root.resolve(EVIDENCE))).getAsJsonObject();
        check(evidence.get("pass").getAsBoolean(), "The first process did not pass Check Materials");
        noStockFiles();
        LoggerFactory.getLogger("mcmateriallist").info("Phase 4B Check Materials restore PASS maxContainersPerTick={} maxNanosPerTick={}",
            evidence.get("maxContainersPerTick").getAsInt(), evidence.get("maxNanosPerTick").getAsLong());
    }
    /** Results are memory only: no client file mentions stock. */
    private static void noStockFiles() throws java.io.IOException {
        Path config = FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist");
        if (!Files.exists(config)) return;
        try (var walk = Files.walk(config)) {
            check(walk.noneMatch(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains("stock")), "Check Materials wrote client files");
        }
    }
    private static List<String> columns(MaterialListPlacement materials) {
        return materials.getMaterialsAll().stream().map(row -> row.getCountTotal() + "/" + row.getCountMissing() + "/" + row.getCountAvailable()).toList();
    }
    private static int index(LocationProfile profile, BlockPos pos) {
        for (int i = 0; i < profile.positions().size(); i++) if (profile.positions().get(i).at(pos.getX(), pos.getY(), pos.getZ())) return i;
        throw new AssertionError("Position not registered: " + pos);
    }
    private static LocationReport report(StockSnapshot snapshot, LocationProfile profile, BlockPos pos) {
        return snapshot.report(index(profile, pos)).orElseThrow(() -> new AssertionError("No report for " + pos));
    }
    private static void status(StockSnapshot snapshot, LocationProfile profile, BlockPos pos, LocationStatus expected) {
        var actual = report(snapshot, profile, pos);
        check(actual.status() == expected, "Location " + pos + " is " + actual.status() + ", expected " + expected);
        if (!expected.read()) check(actual.counts().isEmpty(), "An unread location carried counts");
    }
    private static String fill(BlockPos from, BlockPos to, String block) {
        return "fill " + from.getX() + " " + from.getY() + " " + from.getZ() + " " + to.getX() + " " + to.getY() + " " + to.getZ() + " " + block;
    }
    private static String set(BlockPos at, String block) { return "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + block; }
    private static String item(BlockPos at, int slot, String stack) {
        return "item replace block " + at.getX() + " " + at.getY() + " " + at.getZ() + " container." + slot + " with " + stack;
    }
    private static ButtonBase hover(GuiBase screen, String label) {
        return ((GuiBaseTestAccess) screen).phase0Buttons().stream().filter(button -> button.getHoverStrings().contains(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing control: " + label));
    }
    private static void click(ClientGameTestContext context, WidgetBase button) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static SchematicPlacement fixture(Path root, BlockPos origin) throws java.io.IOException {
        var directory = Files.createTempDirectory(root, "schematic-");
        var selection = new AreaSelection();
        selection.addSubRegionBox(new Box(BlockPos.ZERO, BlockPos.ZERO, "Stock"), false);
        var schematic = LitematicaSchematic.createEmptySchematic(selection, "Phase4bTest");
        schematic.getSubRegionContainer("Stock").set(0, 0, 0, Blocks.STONE.defaultBlockState());
        if (!schematic.writeToFile(directory, "fixture", false)) throw new AssertionError("Phase 4B schematic write failed");
        var placement = SchematicPlacement.createFor(LitematicaSchematic.createFromFile(directory, "fixture.litematic"), origin, "Phase 4B stock", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }
}
