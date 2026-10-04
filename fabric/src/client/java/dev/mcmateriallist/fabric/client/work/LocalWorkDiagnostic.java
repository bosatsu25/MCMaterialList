package dev.mcmateriallist.fabric.client.work;

import dev.mcmateriallist.core.persistence.RefreshResult;
import dev.mcmateriallist.core.persistence.StoreResult;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;

/** Temporary Phase 1 diagnostic; deliberately no final task editing controls. */
public final class LocalWorkDiagnostic {
    private LocalWorkDiagnostic() {}
    public static void refresh(GuiBase screen, ButtonBase button, SchematicPlacement placement, MaterialListBase materials) {
        var captured = PlacementWorkAdapter.capture(placement, materials);
        var service = MCMaterialListClient.work();
        if (captured.status() != PlacementWorkAdapter.Status.OK || service == null) {
            screen.addMessage(MessageType.ERROR, "MCMaterialList Phase 1: " + captured.status()); return;
        }
        button.setEnabled(false);
        service.refresh(captured.snapshot()).whenComplete((result, error) -> screen.mc.execute(() -> {
            button.setEnabled(true);
            if (screen.mc.gui.screen() != screen) return;
            if (error != null) screen.addMessage(MessageType.ERROR, "MCMaterialList Phase 1: local operation failed");
            else screen.addMessage(MessageType.INFO, summary(result));
        }));
    }
    private static String summary(RefreshResult result) {
        return "MCMaterialList Phase 1: materials " + progress(result.materials()) + "; regions " + progress(result.regions());
    }
    private static String progress(StoreResult result) {
        if (!result.ok()) return result.status().name();
        var progress = result.dataset().progress();
        return progress.completed() + "/" + progress.total() + " (" + progress.percentage() + "%)";
    }
}
