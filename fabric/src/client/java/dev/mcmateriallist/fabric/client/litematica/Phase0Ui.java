package dev.mcmateriallist.fabric.client.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;

/** Experimental footer only; upstream lists and row actions are untouched. */
public final class Phase0Ui {
    public static final int FOOTER_HEIGHT = 22;

    private Phase0Ui() {}

    public static void addMaterialButton(GuiBase screen) {
        screen.addButton(button(screen, 58), (button, mouseButton) ->
            screen.addMessage(MessageType.INFO, "MCMaterialList Phase 0"));
    }

    public static void addPlacementButton(GuiBase screen, SchematicPlacement placement) {
        screen.addButton(button(screen, 44), (button, mouseButton) -> {
            try {
                PlacementIdentity.get(placement, DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
                screen.addMessage(MessageType.INFO, "MCMaterialList Phase 0");
            } catch (IllegalStateException exception) {
                // No UUIDs, filenames or paths in the feedback or logs.
                screen.addMessage(MessageType.ERROR, "MCMaterialList Phase 0: " + exception.getMessage());
            }
        });
    }

    private static ButtonGeneric button(GuiBase screen, int bottomOffset) {
        return new ButtonGeneric(12, screen.getScreenHeight() - bottomOffset, -1, 20, "MCMaterialList");
    }
}
