package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.fabric.test.mixin.GuiBaseTestAccess;
import dev.mcmateriallist.fabric.test.mixin.MessageRendererTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiListTestAccess;
import dev.mcmateriallist.fabric.test.mixin.ListTestAccess;
import dev.mcmateriallist.fabric.test.mixin.InGameMessagesTestAccess;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

/** UI-only evidence: dynamic world backgrounds are never compared. */
final class UiParityEvidence {
    private UiParityEvidence() {}
    static void capture(ClientGameTestContext context, GuiBase screen, String name) {
        context.runOnClient(mc -> {
            ((MessageRendererTestAccess) ((GuiBaseTestAccess) screen).phase0MessageRenderer()).phase0Messages().clear();
            ((MessageRendererTestAccess) InGameMessagesTestAccess.phase2Messages()).phase0Messages().clear();
            mc.gui.hud.getChat().clearMessages(true);
        });
        context.getInput().setCursorPos(0, 0);
        context.waitTick(); context.waitTick();
        context.takeScreenshot(name);
    }
    static void controls(GuiBase screen) {
        var controls = ((GuiBaseTestAccess) screen).phase0Buttons();
        var list = (ListTestAccess) ((GuiListTestAccess) screen).phase0List();
        var labels = new java.util.HashSet<String>();
        for (String key : java.util.List.of("track", "refresh", "recover", "confirm_recovery")) labels.add(workText(screen, key));
        for (String key : java.util.List.of("show_info", "hide_done")) for (String value : java.util.List.of("ON", "OFF")) labels.add(workText(screen, key, value));
        int archived;
        boolean recovery;
        if (screen instanceof dev.mcmateriallist.fabric.client.material.MaterialWorkScreen material) {
            var work = material.mcmateriallist$session();
            archived = work.dataset() == null ? 0 : work.dataset().archived().size();
            recovery = work.status() == dev.mcmateriallist.core.persistence.StoreStatus.RECOVERY_REQUIRED;
        } else {
            var work = ((dev.mcmateriallist.fabric.client.region.RegionWorkScreen) screen).mcmateriallist$session();
            archived = work.dataset() == null ? 0 : work.dataset().archived().size();
            recovery = work.status() == dev.mcmateriallist.core.persistence.StoreStatus.RECOVERY_REQUIRED;
        }
        labels.add(workText(screen, "archived", archived));
        var added = controls.stream().filter(control -> control.getHoverStrings().stream().anyMatch(labels::contains)
            || "MCMaterialList".equals(((dev.mcmateriallist.fabric.test.mixin.ButtonTestAccess) control).phase0Text())).toList();
        require(added.size() == 4 + (recovery || archived > 0 ? 1 : 0), "Added work/diagnostic controls not identified by complete localized labels");
        for (int i = 0; i < controls.size(); i++) {
            var control = controls.get(i);
            if (!added.contains(control)) continue;
            require(control.getX() >= 0 && control.getY() >= 0 && control.getX() + control.getWidth() <= screen.getScreenWidth()
                && control.getY() + control.getHeight() <= screen.getScreenHeight(), "Screen control outside GUI bounds");
            require(!(control.getX() < list.phase2X() + list.phase2Width() && control.getX() + control.getWidth() > list.phase2X()
                && control.getY() < list.phase0Y() + list.phase0Height() && control.getY() + control.getHeight() > list.phase0Y() + 4),
                "Screen control overlaps native list/header: control=" + control.getX() + "," + control.getY() + "," + control.getWidth() + "," + control.getHeight()
                    + "; list=" + list.phase2X() + "," + list.phase0Y() + "," + list.phase2Width() + "," + list.phase0Height());
            for (int j = 0; j < controls.size(); j++) {
                if (i == j) continue;
                var other = controls.get(j);
                require(!overlaps(control, other), "Screen controls overlap: " + control.getX() + "," + control.getY() + "," + control.getWidth() + "," + control.getHeight()
                    + " with " + other.getX() + "," + other.getY() + "," + other.getWidth() + "," + other.getHeight());
            }
        }
    }
    private static String workText(GuiBase screen, String key, Object... args) {
        return screen instanceof dev.mcmateriallist.fabric.client.material.MaterialWorkScreen
            ? dev.mcmateriallist.fabric.client.material.MaterialWorkSession.text(key, args)
            : dev.mcmateriallist.fabric.client.region.RegionWorkSession.text(key, args);
    }
    static boolean overlaps(WidgetBase a, WidgetBase b) {
        return a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
            && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY();
    }
    static void scroll(ClientGameTestContext context, GuiBase screen, double amount) {
        double[] point = context.computeOnClient(mc -> {
            var row = ((ListTestAccess) ((GuiListTestAccess) screen).phase0List()).phase0Rows().stream().filter(candidate -> candidate.getEntry() != null).findFirst().orElseThrow();
            return new double[]{(row.getX() + row.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (row.getY() + 10.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(point[0], point[1]); context.waitTick();
        // MaLiLib moves a fixed step per wheel event, regardless of its magnitude.
        for (int i = 0; i < 64; i++) {
            boolean reached = context.computeOnClient(mc -> {
                var bar = ((GuiListTestAccess) screen).phase0List().getScrollbar();
                return bar.getValue() == (amount < 0 ? bar.getMaxValue() : 0);
            });
            if (reached) return;
            context.getInput().scroll(amount < 0 ? -1 : 1); context.waitTick();
        }
        throw new AssertionError("Real wheel events did not reach list boundary");
    }
    static void recreation(ClientGameTestContext context, GuiBase screen, boolean selectable) {
        double[] wheelPoint = context.computeOnClient(mc -> {
            var row = ((ListTestAccess) ((GuiListTestAccess) screen).phase0List()).phase0Rows().stream()
                .filter(candidate -> candidate.getEntry() != null).findFirst().orElseThrow();
            return new double[]{(row.getX() + 60.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
                (row.getY() + 10.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(wheelPoint[0], wheelPoint[1]); context.waitTick();
        context.getInput().scroll(-1); context.waitTick();
        var original = context.computeOnClient(mc -> ((GuiListTestAccess) screen).phase0List());
        int scroll = context.computeOnClient(mc -> original.getScrollbar().getValue());
        require(scroll > 0, "Recreation fixture requires genuine nonzero wheel scroll");
        var target = context.computeOnClient(mc -> ((ListTestAccess) original).phase0Rows().stream()
            .filter(candidate -> candidate.getEntry() != null).skip(1).findFirst().orElseThrow());
        Object entry = context.computeOnClient(mc -> target.getEntry());
        double[] clickPoint = context.computeOnClient(mc -> new double[]{(target.getX() + 60.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
            (target.getY() + 10.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(clickPoint[0], clickPoint[1]); context.waitTick();
        context.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT); context.waitTick();
        int selectionIndex = context.computeOnClient(mc -> ((ListTestAccess) original).phase2SelectionIndex());
        String selectedRegion = context.computeOnClient(mc -> screen instanceof fi.dy.masa.litematica.gui.GuiPlacementConfiguration regions
            ? regions.getSchematicPlacement().getSelectedSubRegionName() : null);
        context.runOnClient(mc -> require(selectable ? original.getLastSelectedEntry() == entry && selectionIndex >= 0
            : original.getLastSelectedEntry() == null && selectionIndex == -1,
            "Real row hit did not retain native selectability"));
        if (selectable) require(selectedRegion != null, "Real region selection did not update its native placement owner");
        var previous = original;
        for (int scale : new int[]{4, 3}) {
            context.runOnClient(mc -> { mc.options.guiScale().set(scale); mc.resizeGui(); }); context.waitTick();
            var current = context.computeOnClient(mc -> ((GuiListTestAccess) screen).phase0List());
            require(current != previous, "Scale change did not recreate the immutable-position native list");
            context.runOnClient(mc -> {
                require(current.getScrollbar().getValue() == scroll, "Recreation lost nonzero native scrollbar position");
                require(selectable ? current.getLastSelectedEntry() == entry && ((ListTestAccess) current).phase2SelectionIndex() == selectionIndex
                    : current.getLastSelectedEntry() == null && ((ListTestAccess) current).phase2SelectionIndex() == -1,
                    "Recreation lost native single selection or invented material selection");
                require(current.getSelectedEntries().isEmpty(), "Recreation changed native single-select semantics");
                if (screen instanceof fi.dy.masa.litematica.gui.GuiPlacementConfiguration regions)
                    require(java.util.Objects.equals(selectedRegion, regions.getSchematicPlacement().getSelectedSubRegionName()), "Recreation toggled the owning placement selection");
                controls(screen);
            });
            previous = current;
        }
        scroll(context, screen, 100);
    }
    static void language(ClientGameTestContext context, GuiBase screen, String language) {
        var reload = context.computeOnClient(mc -> { mc.getLanguageManager().setSelected(language); return mc.reloadResourcePacks(); });
        context.waitFor(mc -> reload.isDone()); reload.join();
        context.setScreen(() -> screen);
        context.waitFor(mc -> mc.gui.overlay() == null && mc.gui.screen() == screen);
    }
    static void color(ClientGameTestContext context, String name, int x, int y, int width, int height, int expected) throws java.io.IOException {
        Path screenshot;
        try (var files = Files.list(FabricLoader.getInstance().getGameDir().resolve("screenshots"))) {
            screenshot = files.filter(file -> file.getFileName().toString().endsWith(name + ".png")).findFirst().orElseThrow();
        }
        var image = javax.imageio.ImageIO.read(screenshot.toFile());
        double[] scale = context.computeOnClient(mc -> new double[]{image.getWidth() / (double) mc.getWindow().getGuiScaledWidth(), image.getHeight() / (double) mc.getWindow().getGuiScaledHeight()});
        boolean found = false;
        for (int px = (int) (x * scale[0]); px < (x + width) * scale[0]; px++)
            for (int py = (int) (y * scale[1]); py < (y + height) * scale[1]; py++)
                if ((image.getRGB(px, py) & 0xFFFFFF) == expected) found = true;
        require(found, "Expected UI color absent from captured control/footer");
    }
    static void notice(ClientGameTestContext context, String name, WidgetBase control) throws java.io.IOException {
        var icon = fi.dy.masa.litematica.gui.Icons.NOTICE_EXCLAMATION_11;
        var sprite = context.computeOnClient(mc -> {
            try (var input = mc.getResourceManager().getResource(icon.getTexture()).orElseThrow().open()) {
                return javax.imageio.ImageIO.read(input);
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        // Require an opaque native sprite color in its exact control rectangle.
        int expected = 0;
        boolean found = false;
        for (int y = 0; y < icon.getHeight() && !found; y++) for (int x = 0; x < icon.getWidth() && !found; x++) {
            int pixel = sprite.getRGB(icon.getU() + x, icon.getV() + y);
            if ((pixel >>> 24) == 255 && (pixel & 0xFFFFFF) != 0) { expected = pixel & 0xFFFFFF; found = true; }
        }
        require(found, "Native notice sprite has no opaque pixels");
        color(context, name, control.getX(), control.getY(), control.getWidth(), control.getHeight(), expected);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
