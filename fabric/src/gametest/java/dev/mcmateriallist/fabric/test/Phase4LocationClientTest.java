package dev.mcmateriallist.fabric.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmateriallist.core.location.*;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.location.*;
import dev.mcmateriallist.fabric.client.material.MaterialWorkScreen;
import dev.mcmateriallist.fabric.client.material.MaterialWorkSession;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.test.mixin.ButtonTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiBaseTestAccess;
import dev.mcmateriallist.fabric.test.mixin.LocationNameTestAccess;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/**
 * Issue #9: Location Files through the real material-list button and screens, looked-at registration validated at click
 * time against real placed blocks, cross-dimension refusal, UUID-only paths, and fresh-process/cross-world persistence.
 */
public final class Phase4LocationClientTest implements FabricClientGameTest {
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    private static final String UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern STORED = Pattern.compile("(" + UUID_TEXT + "|current)\\.json(\\.bak|\\.lock)?");
    private static final String RENAMED = "taichi_ms 倉庫";

    @Override public void runTest(ClientGameTestContext context) {
        Path root = Path.of(System.getProperty("mcmateriallist.phase0.fixture")).resolve("phase4");
        try {
            Files.createDirectories(root);
            if (Boolean.getBoolean("mcmateriallist.phase0.restore")) restore(context, root); else run(context, root);
        } catch (Exception failure) { throw new AssertionError("Phase 4A Location File fixture failed", failure); }
        finally { FixtureWorlds.drainSources(); }
    }
    private static void run(ClientGameTestContext context, Path root) throws Exception {
        // The restore process must never pass on evidence left by an earlier, possibly failed, run.
        for (String stale : List.of("expected-location.json", "profile.bin", "current.bin")) Files.deleteIfExists(root.resolve(stale));
        try (var world = FixtureWorlds.create(context, "Phase 4A")) {
            world.getClientLevel().waitForChunksRender();
            context.waitFor(mc -> SharedClientNetworking.state().capabilities() != null, 600);
            var server = world.getServer();
            Consumer<String> command = server::runCommand;
            BlockPos base = context.computeOnClient(mc -> mc.player.blockPosition());
            WorldIdentity identity = context.computeOnClient(mc -> ((LocationWorld.Available) LocationWorld.resolve()).world());
            // Terrain-independent fixture strip: stone below, air above, each container standable for a straight-down look.
            command.accept(fill(base.offset(2, -1, -2), base.offset(21, -1, 2), "minecraft:stone"));
            command.accept(fill(base.offset(2, 0, -2), base.offset(21, 8, 2), "minecraft:air"));
            BlockPos chest = base.offset(3, 0, 0), left = base.offset(6, 0, 0), right = base.offset(7, 0, 0), barrel = base.offset(10, 0, 0),
                hopper = base.offset(13, 0, 0), stone = base.offset(16, 0, 0), ender = base.offset(19, 0, 0);
            command.accept(set(chest, "minecraft:chest[facing=north]"));
            // Facing north, a left half connects east: one double chest at x and x + 1.
            command.accept(set(left, "minecraft:chest[facing=north,type=left]"));
            command.accept(set(right, "minecraft:chest[facing=north,type=right]"));
            command.accept(set(barrel, "minecraft:barrel"));
            command.accept(set(hopper, "minecraft:hopper"));
            command.accept(set(stone, "minecraft:stone"));
            command.accept(set(ender, "minecraft:ender_chest"));
            context.waitFor(mc -> mc.level.getBlockState(right).is(Blocks.CHEST) && mc.level.getBlockState(ender).is(Blocks.ENDER_CHEST)
                && mc.level.getBlockState(hopper).is(Blocks.HOPPER) && mc.level.getBlockState(barrel).is(Blocks.BARREL), 200);

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
            String none = LocationSession.text("button", LocationSession.text("none"));
            context.runOnClient(mc -> {
                check(work.location().label().equals(none), "Material list did not show an empty Location File: " + work.location().label());
                hover(screen, none); UiParityEvidence.controls(screen);
            });

            click(context, context.computeOnClient(mc -> hover(screen, none)));
            context.waitForScreen(LocationFileScreen.class);
            var files = context.computeOnClient(mc -> (LocationFileScreen) mc.gui.screen());
            context.waitFor(mc -> !files.pending() && files.entries() != null);
            check(files.entries().isEmpty() && files.selected() == null, "Fresh world listed Location Files");
            create(context, files, "taichi_ms", 1);
            create(context, files, "../evil", 2);
            check(files.selected().displayName().equals("../evil"), "Creation did not make the new Location File current");
            verifyPaths();
            String taichi = LocationSession.text("row", "taichi_ms", OVERWORLD, 0);
            click(context, context.computeOnClient(mc -> hover(files, taichi)));
            context.waitFor(mc -> !files.pending() && files.selected() != null && files.selected().displayName().equals("taichi_ms"));
            UiParityEvidence.capture(context, files, "phase4-location-files");
            click(context, context.computeOnClient(mc -> hover(files, LocationSession.text("edit"))));
            context.waitForScreen(LocationProfileEditScreen.class);
            var edit = context.computeOnClient(mc -> (LocationProfileEditScreen) mc.gui.screen());
            context.waitFor(mc -> !edit.pending() && edit.profile() != null);
            context.runOnClient(mc -> check(!((ButtonTestAccess) hover(edit, LocationSession.text("bind"))).phase2Enabled(), "A local list offered a shared project binding"));

            register(context, command, edit, OVERWORLD, chest, 90, "outcome.CHANGED", "", 1);
            register(context, command, edit, OVERWORLD, right, 90, "outcome.CHANGED", "", 2);
            check(edit.profile().container(left.getX(), left.getY(), left.getZ()).isPresent() && edit.profile().container(right.getX(), right.getY(), right.getZ()).isEmpty(),
                "Double chest was not stored once at its smaller position");
            register(context, command, edit, OVERWORLD, left, 90, "reject.duplicate", "", 2);
            register(context, command, edit, OVERWORLD, stone, 90, "reject.unsupported_block", "minecraft:stone", 2);
            register(context, command, edit, OVERWORLD, barrel, 90, "outcome.CHANGED", "", 3);
            register(context, command, edit, OVERWORLD, ender, 90, "reject.unsupported_block", "minecraft:ender_chest", 3);
            register(context, command, edit, OVERWORLD, hopper, 90, "outcome.CHANGED", "", 4);
            register(context, command, edit, OVERWORLD, hopper, -90, "reject.not_block", "", 4);
            check(edit.profile().positions().stream().map(ContainerPosition::blockId).toList()
                .equals(List.of("minecraft:chest", "minecraft:chest", "minecraft:barrel", "minecraft:hopper")), "Registered container identities differ");

            String removeHopper = LocationSession.text("remove_hover", LocationSession.text("position", hopper.getX(), hopper.getY(), hopper.getZ(), "minecraft:hopper"));
            click(context, context.computeOnClient(mc -> hover(edit, removeHopper)));
            context.waitFor(mc -> !edit.pending() && edit.profile().positions().size() == 3 && "outcome.CHANGED".equals(edit.feedbackKey()));
            check(edit.profile().container(hopper.getX(), hopper.getY(), hopper.getZ()).isEmpty(), "Remove kept the hopper");

            click(context, context.computeOnClient(mc -> hover(edit, LocationSession.text("rename"))));
            context.waitForScreen(LocationNameScreen.class);
            var form = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
            clickField(context, context.computeOnClient(mc -> ((LocationNameTestAccess) form).phase4Input()));
            context.getInput().pressKey(GLFW.GLFW_KEY_END);
            for (int i = 0; i < "taichi_ms".length(); i++) context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
            context.getInput().typeChars(RENAMED);
            click(context, context.computeOnClient(mc -> hover(form, LocationSession.text("rename_confirm"))));
            context.waitFor(mc -> mc.gui.screen() == edit && !edit.pending() && edit.profile().displayName().equals(RENAMED));
            UiParityEvidence.capture(context, edit, "phase4-location-edit");

            // Same world, another dimension: registration into the overworld file is refused before any write.
            context.runOnClient(mc -> GuiBase.openGui(null));
            command.accept("execute in " + NETHER + " run tp @a 0.5 129 0.5 0 90");
            context.waitFor(mc -> LocationWorld.resolve() instanceof LocationWorld.Available now && now.dimension().equals(NETHER), 600);
            world.getClientLevel().waitForChunksRender();
            BlockPos netherChest = new BlockPos(2, 128, 0);
            command.accept("execute in " + NETHER + " run setblock 2 128 0 minecraft:chest");
            long generation = edit.profile().generation();
            register(context, command, edit, NETHER, netherChest, 90, "reject.other_dimension", OVERWORLD, 3);
            check(edit.profile().generation() == generation, "Cross-dimension registration changed the profile");
            context.runOnClient(mc -> GuiBase.openGui(null));
            command.accept("execute in " + OVERWORLD + " run tp @a " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5) + " 0 0");
            context.waitFor(mc -> LocationWorld.resolve() instanceof LocationWorld.Available now && now.dimension().equals(OVERWORLD), 600);
            world.getClientLevel().waitForChunksRender();

            context.setScreen(() -> edit);
            context.waitFor(mc -> mc.gui.screen() == edit && !edit.pending());
            click(context, context.computeOnClient(mc -> hover(edit, LocationSession.text("back"))));
            context.waitFor(mc -> mc.gui.screen() == files && !files.pending() && files.entries().size() == 2
                && files.selected() != null && files.selected().displayName().equals(RENAMED));
            click(context, context.computeOnClient(mc -> hover(files, LocationSession.text("back"))));
            String renamed = LocationSession.text("button", RENAMED);
            context.waitFor(mc -> mc.gui.screen() == screen && !work.pending() && work.location().label().equals(renamed));
            context.runOnClient(mc -> { hover(screen, renamed); UiParityEvidence.controls(screen); });
            UiParityEvidence.capture(context, screen, "phase4-material-location-button");
            check(Arrays.equals(materialBytes, Files.readAllBytes(materialsFile)), "Location File actions changed materials.json");
            check(Arrays.equals(schematicBytes, Files.readAllBytes(placement.getSchematicFile())), "Location File actions changed .litematic bytes");

            // A fresh session of the same world reads the remembered profile without writing.
            var profile = edit.profile();
            Path directory = locationRoot().resolve("worlds").resolve(identity.toString());
            Path profileFile = directory.resolve("profiles").resolve(profile.id() + ".json"), currentFile = directory.resolve("current.json");
            byte[] profileBytes = Files.readAllBytes(profileFile), currentBytes = Files.readAllBytes(currentFile);
            context.setScreen(() -> new GuiMaterialList(materials));
            var reopened = context.computeOnClient(mc -> ((MaterialWorkScreen) mc.gui.screen()).mcmateriallist$session());
            context.waitFor(mc -> !reopened.pending() && reopened.location().label().equals(renamed));
            check(reopened.location().profile().equals(profile), "Reopened list did not read the same current Location File");
            check(Arrays.equals(profileBytes, Files.readAllBytes(profileFile)) && Arrays.equals(currentBytes, Files.readAllBytes(currentFile)), "Reading a Location File rewrote it");

            JsonObject expected = new JsonObject();
            expected.addProperty("world", identity.toString()); expected.addProperty("profile", profile.id().toString());
            expected.addProperty("name", profile.displayName()); expected.addProperty("generation", profile.generation());
            JsonArray positions = new JsonArray(); describe(profile).forEach(positions::add); expected.add("positions", positions);
            Files.writeString(root.resolve("expected-location.json"), expected.toString());
            Files.write(root.resolve("profile.bin"), profileBytes); Files.write(root.resolve("current.bin"), currentBytes);
            context.runOnClient(mc -> GuiBase.openGui(null));
        }
        LoggerFactory.getLogger("mcmateriallist").info("Phase 4A Location File assertions PASS");
    }
    /** Fresh process: the durable files are unchanged, readable, still current, and invisible from another world. */
    private static void restore(ClientGameTestContext context, Path root) throws Exception {
        var expected = JsonParser.parseString(Files.readString(root.resolve("expected-location.json"))).getAsJsonObject();
        var world = new WorldIdentity(UUID.fromString(expected.get("world").getAsString()));
        var id = LocationProfileId.parse(expected.get("profile").getAsString());
        Path directory = locationRoot().resolve("worlds").resolve(world.toString());
        Path profileFile = directory.resolve("profiles").resolve(id + ".json"), currentFile = directory.resolve("current.json");
        byte[] profileBytes = Files.readAllBytes(root.resolve("profile.bin")), currentBytes = Files.readAllBytes(root.resolve("current.bin"));
        check(Arrays.equals(profileBytes, Files.readAllBytes(profileFile)) && Arrays.equals(currentBytes, Files.readAllBytes(currentFile)), "Restart changed Location File bytes");
        var service = MCMaterialListClient.locations();
        var current = service.current(world).get(10, TimeUnit.SECONDS);
        check(current.selection().ok() && id.equals(current.selection().value().profile()) && current.profile() != null && current.profile().ok(),
            "Fresh process lost the remembered current Location File");
        var profile = current.profile().value();
        List<String> positions = expected.getAsJsonArray("positions").asList().stream().map(element -> element.getAsString()).toList();
        check(profile.displayName().equals(expected.get("name").getAsString()) && profile.generation() == expected.get("generation").getAsLong()
            && describe(profile).equals(positions) && profile.positions().size() == 3, "Fresh process restored different Location File contents");
        var listed = service.list(world).get(10, TimeUnit.SECONDS);
        check(listed.ok() && listed.value().size() == 2 && listed.value().stream().allMatch(entry -> entry.status() == StoreStatus.OK), "Fresh process listing differs");
        try (var other = FixtureWorlds.create(context, "Phase 4A restore")) {
            other.getClientLevel().waitForChunksRender();
            context.waitFor(mc -> SharedClientNetworking.state().capabilities() != null, 600);
            check(context.computeOnClient(mc -> LocationWorld.resolve() instanceof LocationWorld.Available now && !now.world().equals(world)),
                "A different world resolved to the first world's identity");
            var session = context.computeOnClient(mc -> { var value = new LocationSession(() -> {}); value.ensure(); return value; });
            String none = LocationSession.text("button", LocationSession.text("none"));
            context.waitFor(mc -> !session.pending() && session.label().equals(none));
            var files = context.computeOnClient(mc -> new LocationFileScreen(null, session, null));
            context.setScreen(() -> files);
            context.waitFor(mc -> !files.pending() && files.entries() != null);
            check(files.entries().isEmpty() && files.selected() == null, "Another world's Location Files are visible");
            UiParityEvidence.capture(context, files, "phase4-location-other-world");
            context.runOnClient(mc -> GuiBase.openGui(null));
        }
        check(Arrays.equals(profileBytes, Files.readAllBytes(profileFile)) && Arrays.equals(currentBytes, Files.readAllBytes(currentFile)), "Reading after restart rewrote Location Files");
        verifyPaths();
        LoggerFactory.getLogger("mcmateriallist").info("Phase 4A Location File restore PASS");
    }
    private static void create(ClientGameTestContext context, LocationFileScreen files, String name, int expected) {
        click(context, context.computeOnClient(mc -> hover(files, LocationSession.text("new"))));
        context.waitForScreen(LocationNameScreen.class);
        var form = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
        clickField(context, context.computeOnClient(mc -> ((LocationNameTestAccess) form).phase4Input()));
        context.getInput().typeChars(name);
        click(context, context.computeOnClient(mc -> hover(form, LocationSession.text("create_confirm"))));
        context.waitFor(mc -> mc.gui.screen() == files && !files.pending() && files.entries() != null && files.entries().size() == expected
            && files.selected() != null && files.selected().displayName().equals(name));
    }
    /** Looks at the block from directly above (pitch 90) or at the sky (-90), then uses the real Add control. */
    private static void register(ClientGameTestContext context, Consumer<String> command, LocationProfileEditScreen edit, String dimension, BlockPos block,
            int pitch, String key, String detail, int count) {
        context.runOnClient(mc -> GuiBase.openGui(null));
        command.accept("execute in " + dimension + " run tp @a " + (block.getX() + 0.5) + " " + (block.getY() + 1) + " " + (block.getZ() + 0.5) + " 0 " + pitch);
        context.waitFor(mc -> LocationWorld.resolve() instanceof LocationWorld.Available now && now.dimension().equals(dimension)
            && (pitch < 0 ? mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS
                : mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block)), 200);
        context.setScreen(() -> edit);
        context.waitFor(mc -> mc.gui.screen() == edit && !edit.pending());
        click(context, context.computeOnClient(mc -> hover(edit, LocationSession.text("add"))));
        context.waitFor(mc -> !edit.pending() && key.equals(edit.feedbackKey()) && edit.feedback().contains(detail) && edit.profile().positions().size() == count);
    }
    /** Display names (including "../evil") never reach the filesystem: only canonical UUID and fixed names exist. */
    private static void verifyPaths() throws java.io.IOException {
        Path locations = locationRoot();
        try (var walk = Files.walk(locations)) {
            for (Path path : walk.toList()) {
                if (path.equals(locations)) continue;
                String name = path.getFileName().toString();
                check(Files.isDirectory(path) ? name.equals("worlds") || name.equals("profiles") || name.matches(UUID_TEXT) : STORED.matcher(name).matches(),
                    "Unexpected Location File path: " + locations.relativize(path));
            }
        }
        try (var walk = Files.walk(locations.getParent())) {
            check(walk.noneMatch(path -> path.getFileName().toString().contains("evil")), "A display name became a path component");
        }
    }
    private static List<String> describe(LocationProfile profile) {
        return profile.positions().stream().map(p -> p.x() + "," + p.y() + "," + p.z() + "," + p.blockId()).toList();
    }
    private static Path locationRoot() { return FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist").resolve("location-files"); }
    private static String fill(BlockPos from, BlockPos to, String block) {
        return "fill " + from.getX() + " " + from.getY() + " " + from.getZ() + " " + to.getX() + " " + to.getY() + " " + to.getZ() + " " + block;
    }
    private static String set(BlockPos at, String block) { return "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + block; }
    private static ButtonBase hover(GuiBase screen, String label) {
        return ((GuiBaseTestAccess) screen).phase0Buttons().stream().filter(button -> button.getHoverStrings().contains(label)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing control: " + label));
    }
    private static void click(ClientGameTestContext context, WidgetBase button) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void clickField(ClientGameTestContext context, fi.dy.masa.malilib.gui.GuiTextFieldMultiLine field) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(field.getX() + 8.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (field.getY() + 8.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static SchematicPlacement fixture(Path root, BlockPos origin) throws java.io.IOException {
        var directory = Files.createTempDirectory(root, "schematic-");
        var selection = new AreaSelection();
        selection.addSubRegionBox(new Box(BlockPos.ZERO, BlockPos.ZERO, "Location"), false);
        var schematic = LitematicaSchematic.createEmptySchematic(selection, "Phase4Test");
        schematic.getSubRegionContainer("Location").set(0, 0, 0, Blocks.STONE.defaultBlockState());
        if (!schematic.writeToFile(directory, "fixture", false)) throw new AssertionError("Phase 4A schematic write failed");
        var placement = SchematicPlacement.createFor(LitematicaSchematic.createFromFile(directory, "fixture.litematic"), origin, "Phase 4A location", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }
}
