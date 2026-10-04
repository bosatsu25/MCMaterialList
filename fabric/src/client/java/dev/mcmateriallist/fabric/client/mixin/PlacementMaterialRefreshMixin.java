package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.work.MaterialReadiness;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaterialListPlacement.class, remap = false)
public abstract class PlacementMaterialRefreshMixin {
    @Inject(method = "reCreateMaterialList", at = @At("HEAD"), require = 0)
    private void countingStarted(CallbackInfo callback) { ((MaterialReadiness) this).mcmateriallist$invalidate(); }
}
