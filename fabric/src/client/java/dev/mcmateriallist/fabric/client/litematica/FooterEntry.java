package dev.mcmateriallist.fabric.client.litematica;

import dev.mcmateriallist.fabric.client.material.MaterialWorkScreen;
import dev.mcmateriallist.fabric.client.region.RegionWorkScreen;
import dev.mcmateriallist.fabric.client.work.LocalWorkDiagnostic;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.client.work.WorkHubScreen;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;

/**
 * The {@code MCMaterialList} footer button of the native material list and placement configuration screens (Issue #22).
 * An ordinary click opens the MCMaterialList screen for that list. The Phase 1 local work diagnostic, which creates and
 * refreshes both local datasets, is a developer tool: it runs only on Shift-click while the JVM was started with
 * {@code -Dmcmateriallist.diagnostics=true}. Without that flag Shift-click behaves like an ordinary click and writes nothing.
 * Upstream lists and row actions are untouched.
 */
public final class FooterEntry {
    /** The explicit developer flag; read at click time. */
    public static final String DIAGNOSTICS_PROPERTY = "mcmateriallist.diagnostics";

    private FooterEntry() {}

    public static boolean diagnosticsEnabled() { return Boolean.getBoolean(DIAGNOSTICS_PROPERTY); }

    public static void addMaterialButton(GuiBase screen) {
        screen.addButton(button(screen, 58), (button, mouseButton) -> {
            if (diagnosticsEnabled() && GuiBase.isShiftDown() && screen instanceof GuiMaterialList materialScreen) {
                var materials = materialScreen.getMaterialList();
                LocalWorkDiagnostic.refresh(screen, button, PlacementWorkAdapter.owner(materials), materials);
            } else if (screen instanceof MaterialWorkScreen work) {
                GuiBase.openGui(WorkHubScreen.materials(screen, work.mcmateriallist$session()));
            }
        });
    }

    public static void addPlacementButton(GuiBase screen, SchematicPlacement placement) {
        screen.addButton(button(screen, 44), (button, mouseButton) -> {
            if (diagnosticsEnabled() && GuiBase.isShiftDown()) {
                LocalWorkDiagnostic.refresh(screen, button, placement, placement.getMaterialList());
            } else if (screen instanceof RegionWorkScreen work) {
                // An unsaved or duplicated placement is explained on the screen; no UUIDs, file names or paths are shown.
                GuiBase.openGui(WorkHubScreen.regions(screen, work.mcmateriallist$session()));
            }
        });
    }

    private static ButtonGeneric button(GuiBase screen, int bottomOffset) {
        var button = new ButtonGeneric(12, screen.getScreenHeight() - bottomOffset, -1, 20, "MCMaterialList");
        if (diagnosticsEnabled()) button.setHoverStrings(WorkHubScreen.text("open_hover"), WorkHubScreen.text("diagnostic_hover"));
        else button.setHoverStrings(WorkHubScreen.text("open_hover"));
        return button;
    }
}
