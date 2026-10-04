package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.litematica.Phase0Ui;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "fi.dy.masa.litematica.gui.GuiPlacementConfiguration", remap = false)
public abstract class PlacementConfigurationGuiMixin {
    @Shadow @Final public SchematicPlacement placement;

    @Inject(method = "getBrowserHeight()I", at = @At("RETURN"), cancellable = true, require = 0)
    private void reserveFooter(CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(Math.max(0, callback.getReturnValue() - Phase0Ui.FOOTER_HEIGHT));
    }

    @Inject(method = "initGui()V", at = @At("TAIL"), require = 0)
    private void addPhase0Button(CallbackInfo callback) {
        Phase0Ui.addPlacementButton((GuiBase) (Object) this, placement);
    }
}
