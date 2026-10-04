package dev.mcmateriallist.fabric.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.fabric.client.litematica.PlacementIdentity;
import dev.mcmateriallist.fabric.test.mixin.ButtonTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiBaseTestAccess;
import dev.mcmateriallist.fabric.test.mixin.MessageRendererTestAccess;
import dev.mcmateriallist.fabric.test.mixin.MessageTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiListTestAccess;
import dev.mcmateriallist.fabric.test.mixin.ListTestAccess;
import dev.mcmateriallist.fabric.test.mixin.WidgetContainerTestAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.gui.GuiSubRegionConfiguration;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

/** Uses real upstream placements, JSON, widgets, rendering and mouse input. */
public final class Phase0ClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            if (Boolean.getBoolean("mcmateriallist.phase0.restore")) {
                verifyRestart(context);
                return;
            }
            SchematicPlacement placement = context.computeOnClient(mc -> createFixture(mc.player.blockPosition()));
            context.runOnClient(mc -> verifyIdentity(placement));
            context.getInput().resizeWindow(1280, 720);
            for (int scale : new int[]{1, 2, 3, 4}) {
                context.runOnClient(mc -> { mc.options.guiScale().set(scale); mc.resizeGui(); });
                verifyPlacementUi(context, placement, scale);
                verifyMaterialUi(context, placement, scale);
            }
            context.setScreen(() -> null);
            context.runOnClient(mc -> saveRestartFixture(placement));
        } catch (IOException exception) {
            throw new AssertionError("Phase 0 fixture I/O failed", exception);
        }
    }

    private static SchematicPlacement createFixture(BlockPos origin) throws IOException {
        Path root = fixtureRoot();
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "schematic-");
        AreaSelection selection = new AreaSelection();
        selection.setName("Phase 0 fixture");
        selection.addSubRegionBox(new Box(BlockPos.ZERO, new BlockPos(1, 1, 1), "Region 1"), false);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(selection, "Phase0Test");
        schematic.getSubRegionContainer("Region 1").set(0, 0, 0, Blocks.STONE.defaultBlockState());
        check(schematic.writeToFile(directory, "fixture", false), "Could not create fixture schematic");
        LitematicaSchematic loaded = LitematicaSchematic.createFromFile(directory, "fixture.litematic");
        check(loaded != null, "Could not load fixture schematic");
        SchematicPlacement placement = SchematicPlacement.createFor(loaded, origin, "Fixture", true, false);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }

    private static void verifyIdentity(SchematicPlacement placement) throws IOException {
        LocalPlacementId first = PlacementIdentity.get(placement, List.of(placement));
        check(first.equals(PlacementIdentity.get(placement, List.of(placement))), "Reopening changed identity");
        byte[] before = Files.readAllBytes(placement.getSchematicFile());
        SchematicPlacement duplicate = SchematicPlacement.createFor(placement.getSchematic(), placement.getOrigin(), placement.getName(), false, false);
        check(!first.equals(PlacementIdentity.get(duplicate, List.of(placement, duplicate))), "Duplicate creation reused identity");
        SchematicPlacement restored = SchematicPlacement.fromJson(placement.toJson());
        check(restored != null, "JSON restoration failed");
        check(first.equals(PlacementIdentity.get(restored, List.of(restored))), "Serialization lost identity");
        boolean rejected = false;
        try { PlacementIdentity.get(restored, List.of(placement, restored)); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Copied placement UUID silently aliased another placement");
        placement.setName("Renamed fixture");
        placement.setOrigin(placement.getOrigin().offset(2, 0, 2), message -> {});
        check(first.equals(PlacementIdentity.get(placement, List.of(placement))), "Rename/movement changed identity");
        AreaSelection transientSelection = new AreaSelection();
        transientSelection.addSubRegionBox(new Box(BlockPos.ZERO, BlockPos.ZERO, "Transient"), false);
        SchematicPlacement transientPlacement = SchematicPlacement.createTemporary(LitematicaSchematic.createEmptySchematic(transientSelection, "Phase0Test"), BlockPos.ZERO);
        rejected = false;
        try { PlacementIdentity.get(transientPlacement, List.of(transientPlacement)); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Unsaved schematic claimed persistent identity");
        check(Arrays.equals(before, Files.readAllBytes(placement.getSchematicFile())), "Identity operations modified .litematic");
    }

    private static void verifyPlacementUi(ClientGameTestContext context, SchematicPlacement placement, int scale) {
        context.setScreen(() -> new GuiPlacementConfiguration(placement));
        context.waitTick();
        GuiBase screen = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
        ButtonBase button = context.computeOnClient(mc -> findButton(screen, "MCMaterialList"));
        context.runOnClient(mc -> checkLayout(screen, button));
        context.takeScreenshot("placement-scale-" + scale);
        click(context, button);
        context.runOnClient(mc -> checkPhase0Message(screen));
        context.takeScreenshot("placement-click-scale-" + scale);
        // Major existing screen operations: toggles reinitialize the GUI.
        boolean enabled = placement.isEnabled();
        ButtonBase toggle = context.computeOnClient(mc -> findButtonContaining(screen, "Placement:"));
        click(context, toggle);
        context.runOnClient(mc -> check(placement.isEnabled() != enabled, "Placement toggle did not work"));
        click(context, context.computeOnClient(mc -> findButtonContaining(screen, "Placement:")));
        context.runOnClient(mc -> {
            check(placement.isEnabled() == enabled, "Placement toggle could not restore state");
            checkLayout(screen, findButton(screen, "MCMaterialList"));
        });
        click(context, context.computeOnClient(mc -> findButtonContaining(screen, "All OFF")));
        context.runOnClient(mc -> check(!placement.getRelativeSubRegionPlacement("Region 1").isEnabled(), "All OFF failed"));
        click(context, context.computeOnClient(mc -> findButtonContaining(screen, "All ON")));
        context.runOnClient(mc -> check(placement.getRelativeSubRegionPlacement("Region 1").isEnabled(), "All ON failed"));
        click(context, context.computeOnClient(mc -> findRowButton(screen, "Configure")));
        context.waitForScreen(GuiSubRegionConfiguration.class);
    }

    private static void verifyMaterialUi(ClientGameTestContext context, SchematicPlacement placement, int scale) {
        context.setScreen(() -> null);
        context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld().getBlockState(placement.getOrigin()).is(Blocks.STONE));
        MaterialListPlacement materials = context.computeOnClient(mc -> new MaterialListPlacement(placement, true));
        context.waitFor(mc -> materials.getCountTotal() == 1);
        context.setScreen(() -> new GuiMaterialList(materials));
        context.waitTick();
        GuiBase screen = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
        ButtonBase button = context.computeOnClient(mc -> findButton(screen, "MCMaterialList"));
        context.runOnClient(mc -> checkLayout(screen, button));
        context.takeScreenshot("materials-scale-" + scale);
        click(context, button);
        context.runOnClient(mc -> checkPhase0Message(screen));
        boolean hidden = materials.getHideAvailable();
        click(context, context.computeOnClient(mc -> findButtonContaining(screen, "Hide available:")));
        context.runOnClient(mc -> {
            check(materials.getHideAvailable() != hidden, "Hide available toggle did not work");
            checkLayout(screen, findButton(screen, "MCMaterialList"));
            check(findButton(screen, "Refresh") != null, "Refresh disappeared");
            check(findButton(screen, "Clear ignored") != null, "Clear ignored disappeared");
        });
        click(context, context.computeOnClient(mc -> findRowButton(screen, "Ignore")));
        context.takeScreenshot("materials-ignore-scale-" + scale);
        context.runOnClient(mc -> check(materials.getMaterialsFiltered(false).isEmpty(), "Ignore did not hide the material"));
        click(context, context.computeOnClient(mc -> findButton(screen, "Clear ignored")));
        context.runOnClient(mc -> {
            check(materials.getMaterialsFiltered(false).size() == 1, "Clear ignored did not restore the material");
            materials.setMaterialListEntries(List.of());
        });
        click(context, context.computeOnClient(mc -> findButton(screen, "Refresh")));
        context.waitFor(mc -> materials.getCountTotal() == 1);
        context.runOnClient(mc -> checkLayout(screen, findButton(screen, "MCMaterialList")));
    }

    private static ButtonBase findButton(GuiBase screen, String text) {
        List<ButtonBase> matches = ((GuiBaseTestAccess) screen).phase0Buttons().stream()
            .filter(button -> text.equals(buttonText(button))).toList();
        check(matches.size() == 1, "Expected exactly one button: " + text);
        return matches.getFirst();
    }

    private static ButtonBase findButtonContaining(GuiBase screen, String text) {
        return ((GuiBaseTestAccess) screen).phase0Buttons().stream()
            .filter(button -> buttonText(button).contains(text)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing existing button: " + text));
    }

    private static void checkLayout(GuiBase screen, ButtonBase added) {
        check(added.getX() >= 0 && added.getY() >= 0 && added.getX() + added.getWidth() <= screen.getScreenWidth()
            && added.getY() + added.getHeight() <= screen.getScreenHeight(), "Added button outside screen");
        for (ButtonBase button : ((GuiBaseTestAccess) screen).phase0Buttons()) {
            if (button != added) check(!overlaps(added, button), "Added button overlaps an existing button");
        }
        for (WidgetBase widget : ((GuiBaseTestAccess) screen).phase0Widgets()) {
            check(!overlaps(added, widget), "Added button overlaps an existing widget/label");
        }
        ListTestAccess list = (ListTestAccess) ((GuiListTestAccess) screen).phase0List();
        check(list.phase0Y() + list.phase0Height() <= added.getY(), "List extends into the reserved footer");
    }

    private static ButtonBase findRowButton(GuiBase screen, String text) {
        ListTestAccess list = (ListTestAccess) ((GuiListTestAccess) screen).phase0List();
        return list.phase0Rows().stream()
            .filter(row -> row.getEntry() != null) // Header widgets can contain non-rendered buttons.
            .flatMap(row -> ((WidgetContainerTestAccess) row).phase0Children().stream())
            .filter(widget -> widget instanceof ButtonBase)
            .map(widget -> (ButtonBase) widget)
            .filter(button -> text.equals(buttonText(button)))
            .findFirst().orElseThrow(() -> new AssertionError("Missing existing row button: " + text));
    }

    private static String buttonText(ButtonBase button) {
        return ChatFormatting.stripFormatting(((ButtonTestAccess) button).phase0Text());
    }

    private static boolean overlaps(WidgetBase a, WidgetBase b) {
        return a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
            && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
    }

    private static void click(ClientGameTestContext context, ButtonBase button) {
        double[] cursor = context.computeOnClient(mc -> new double[]{
            (button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
            (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.waitTick();
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private static void checkPhase0Message(GuiBase screen) {
        var renderer = ((GuiBaseTestAccess) screen).phase0MessageRenderer();
        boolean found = ((MessageRendererTestAccess) renderer).phase0Messages().stream()
            .flatMap(message -> ((MessageTestAccess) message).phase0Lines().stream())
            .anyMatch(line -> line.contains("MCMaterialList Phase 0"));
        check(found, "Mouse click produced no Phase 0 feedback");
    }

    private static Path fixtureRoot() {
        return Path.of(System.getProperty("mcmateriallist.phase0.fixture")).toAbsolutePath().normalize();
    }

    private static void saveRestartFixture(SchematicPlacement placement) throws IOException {
        JsonObject saved = DataManager.getSchematicPlacementManager().toJson();
        saved.addProperty("expected_id", PlacementIdentity.get(placement, List.of(placement)).toString());
        // Only test-owned files, never user schematics; replacement permits repeated verification runs.
        Files.writeString(fixtureRoot().resolve("restart.json"), saved.toString());
        Files.write(fixtureRoot().resolve("schematic-baseline.bin"), Files.readAllBytes(placement.getSchematicFile()));
    }

    private static void verifyRestart(ClientGameTestContext context) throws IOException {
        JsonObject saved = JsonParser.parseString(Files.readString(fixtureRoot().resolve("restart.json"))).getAsJsonObject();
        LocalPlacementId expected = LocalPlacementId.parse(saved.get("expected_id").getAsString());
        context.runOnClient(mc -> {
            var manager = DataManager.getSchematicPlacementManager();
            manager.loadFromJson(saved);
            check(manager.getAllSchematicsPlacements().size() == 1, "Restart restored the wrong number of placements");
            SchematicPlacement restored = manager.getAllSchematicsPlacements().getFirst();
            check(expected.equals(PlacementIdentity.get(restored, manager.getAllSchematicsPlacements())), "New Minecraft process lost placement identity");
            check(Arrays.equals(Files.readAllBytes(fixtureRoot().resolve("schematic-baseline.bin")), Files.readAllBytes(restored.getSchematicFile())), "Restart changed .litematic");
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
