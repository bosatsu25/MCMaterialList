package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.fabric.client.material.MaterialDoneButton;
import dev.mcmateriallist.fabric.client.material.MaterialWorkSession;
import dev.mcmateriallist.fabric.client.region.RegionWorkScreen;
import dev.mcmateriallist.fabric.client.region.RegionWorkSession;
import dev.mcmateriallist.fabric.client.shared.SharedWorkBackend;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.test.mixin.ButtonTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiBaseTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiListTestAccess;
import dev.mcmateriallist.fabric.test.mixin.ListTestAccess;
import dev.mcmateriallist.fabric.test.mixin.WidgetContainerTestAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;

/**
 * Issue #14: upstream behaviour this build tolerates or depends on, pinned with real widgets, input and placements.
 * See docs/compatibility.md. The region checks run on the Phase 2B fixture right after Track regions, the material check on
 * the Phase 2A fixture right after Track materials.
 */
final class UpstreamBoundaryEvidence {
    private UpstreamBoundaryEvidence() {}

    /**
     * Litematica 0.28.3 GuiPlacementConfiguration.createButton adds the TOGGLE_RENDERING button inside its switch, and the
     * case's break reaches the common tail that adds a second one: two native buttons with identical bounds sharing one
     * listener. MaLiLib's GuiBase.onMouseClicked stops at the first button that handles a click, so one real click toggles
     * rendering exactly once. This pins that boundary; a changed upstream (one button, or a double toggle) fails here.
     */
    static void renderingDuplicate(ClientGameTestContext context, GuiPlacementConfiguration screen, SchematicPlacement placement, RegionWorkSession work) {
        String label = fi.dy.masa.malilib.util.StringUtils.translate("litematica.gui.button.schematic_placement.abbr.rendering");
        var before = work.dataset();
        boolean enabled = context.computeOnClient(mc -> placement.isRenderingEnabled());
        for (int round = 0; round < 2; round++) {
            ButtonBase target = context.computeOnClient(mc -> {
                var rendering = rendering(screen, label);
                check(rendering.size() == 2, "Pinned upstream Rendering duplicate changed: native Rendering controls=" + rendering.size());
                var first = rendering.get(0); var second = rendering.get(1);
                check(first.getX() == second.getX() && first.getY() == second.getY() && first.getWidth() == second.getWidth()
                    && first.getHeight() == second.getHeight(), "Native Rendering duplicates no longer share bounds: "
                    + first.getX() + "," + first.getY() + "," + first.getWidth() + "," + first.getHeight() + " / "
                    + second.getX() + "," + second.getY() + "," + second.getWidth() + "," + second.getHeight());
                return first;
            });
            final boolean expected = round == 0 ? !enabled : enabled;
            click(context, target);
            context.runOnClient(mc -> check(placement.isRenderingEnabled() == expected, "One real Rendering click did not toggle rendering exactly once"));
        }
        check(work.dataset().equals(before), "Native Rendering toggles changed region work");
    }

    /**
     * A copy of a saved placement (same upstream hash_code UUID) registered beside its original: neither keeps a persistent
     * identity, both screens refuse work and say why, the copy's Refresh control is disabled and writes nothing, and
     * removing the copy restores the original unchanged.
     */
    static void duplicatePlacement(ClientGameTestContext context, GuiPlacementConfiguration screen, SchematicPlacement placement, RegionWorkSession work, Path primary) {
        byte[] stored = read(primary);
        var before = work.dataset();
        var copy = registerCopy(context, placement);
        try {
            context.waitTick();
            context.runOnClient(mc -> {
                check(PlacementWorkAdapter.captureRegions(placement).status() == PlacementWorkAdapter.Status.UNSAVED_OR_DUPLICATE
                    && PlacementWorkAdapter.captureRegions(copy).status() == PlacementWorkAdapter.Status.UNSAVED_OR_DUPLICATE,
                    "A duplicate hash_code kept a persistent identity");
                check(!work.ready() && !work.writable() && work.feedback().equals(RegionWorkSession.text("waiting")),
                    "Original region screen stayed writable, or did not say why, beside a duplicate");
                check(!((ButtonTestAccess) UiParityEvidence.workControl(screen, "refresh")).phase2Enabled(), "Original Refresh stayed enabled beside a duplicate");
            });
            context.setScreen(() -> new GuiPlacementConfiguration(copy));
            var copyScreen = context.computeOnClient(mc -> (GuiPlacementConfiguration) mc.gui.screen());
            var copyWork = context.computeOnClient(mc -> ((RegionWorkScreen) copyScreen).mcmateriallist$session());
            String unavailable = SharedWorkBackend.text("placement_unavailable");
            context.waitTick();
            context.runOnClient(mc -> check(copyWork.status() == StoreStatus.INVALID_DEFINITION && !copyWork.ready() && !copyWork.writable()
                && copyWork.feedback().equals(unavailable), "Duplicate region screen claimed work or hid its reason"));
            var refresh = context.computeOnClient(mc -> UiParityEvidence.workControl(copyScreen, "refresh"));
            context.runOnClient(mc -> check(!((ButtonTestAccess) refresh).phase2Enabled(), "Duplicate region Refresh stayed enabled"));
            click(context, refresh);
            context.runOnClient(mc -> check(copyWork.status() == StoreStatus.INVALID_DEFINITION && !copyWork.pending(), "Duplicate region Refresh started work"));
        } finally {
            context.runOnClient(mc -> DataManager.getSchematicPlacementManager().removeSchematicPlacement(copy));
            context.setScreen(() -> screen);
        }
        context.waitTick();
        context.runOnClient(mc -> check(PlacementWorkAdapter.captureRegions(placement).status() == PlacementWorkAdapter.Status.OK
            && work.ready() && work.writable() && work.dataset().equals(before), "Removing the duplicate did not restore the original placement's work"));
        check(Arrays.equals(stored, read(primary)), "Duplicate placement screens wrote region work");
    }

    /**
     * The material screen is opened before the copy appears: it must stop being writable, disable Done, say why (not blame
     * storage or the material calculation), ignore a real Done click, write nothing, and recover when the copy is removed.
     */
    static void duplicateMaterialPlacement(ClientGameTestContext context, GuiMaterialList screen, SchematicPlacement placement, MaterialWorkSession work, Path primary) {
        byte[] stored = read(primary);
        var before = work.dataset();
        String unavailable = SharedWorkBackend.text("placement_unavailable");
        var copy = registerCopy(context, placement);
        try {
            context.waitTick();
            context.runOnClient(mc -> check(!work.ready() && !work.writable() && work.feedback().equals(unavailable),
                "Material screen stayed writable, or did not say why, beside a duplicate"));
            var done = context.computeOnClient(mc -> firstDone(screen));
            context.runOnClient(mc -> check(!((ButtonTestAccess) done).phase2Enabled(), "Material Done stayed enabled beside a duplicate"));
            click(context, done);
            context.waitTick();
            context.runOnClient(mc -> check(!work.pending() && work.dataset().equals(before), "Done on a duplicated placement changed material work"));
        } finally {
            context.runOnClient(mc -> DataManager.getSchematicPlacementManager().removeSchematicPlacement(copy));
        }
        context.waitTick();
        context.runOnClient(mc -> check(work.ready() && work.writable() && work.dataset().equals(before), "Removing the duplicate did not restore material work"));
        check(Arrays.equals(stored, read(primary)), "Duplicate placement wrote material work");
    }

    /** A copy with the same upstream hash_code UUID, registered beside its original as a copied placement would be. */
    private static SchematicPlacement registerCopy(ClientGameTestContext context, SchematicPlacement placement) {
        return context.computeOnClient(mc -> {
            var restored = SchematicPlacement.fromJson(placement.toJson());
            check(restored != null && restored.getHashId().equals(placement.getHashId()), "Copied placement did not keep the upstream hash_code");
            DataManager.getSchematicPlacementManager().addSchematicPlacement(restored, false);
            return restored;
        });
    }
    private static ButtonBase firstDone(GuiMaterialList screen) {
        return ((ListTestAccess) ((GuiListTestAccess) screen).phase0List()).phase0Rows().stream()
            .filter(row -> row.getEntry() != null)
            .flatMap(row -> ((WidgetContainerTestAccess) row).phase0Children().stream())
            .filter(widget -> widget instanceof MaterialDoneButton).map(widget -> (ButtonBase) widget)
            .findFirst().orElseThrow(() -> new AssertionError("No visible material Done control"));
    }
    private static List<ButtonBase> rendering(GuiPlacementConfiguration screen, String label) {
        return ((GuiBaseTestAccess) screen).phase0Buttons().stream()
            .filter(button -> label.equals(ChatFormatting.stripFormatting(((ButtonTestAccess) button).phase0Text()))).toList();
    }
    private static void click(ClientGameTestContext context, ButtonBase button) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static byte[] read(Path file) {
        try { return Files.readAllBytes(file); } catch (java.io.IOException failure) { throw new AssertionError("Region work read failed", failure); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
