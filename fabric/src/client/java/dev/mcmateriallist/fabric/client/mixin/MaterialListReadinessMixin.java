package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.work.MaterialReadiness;
import fi.dy.masa.litematica.materials.MaterialListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaterialListBase.class, remap = false)
public abstract class MaterialListReadinessMixin implements MaterialReadiness {
    @Unique private boolean mcmateriallist$ready;
    @Override public boolean mcmateriallist$ready() { return mcmateriallist$ready; }
    @Override public void mcmateriallist$invalidate() { mcmateriallist$ready = false; }
    @Inject(method = "setMaterialListEntries", at = @At("TAIL"), require = 0)
    private void published(CallbackInfo callback) { mcmateriallist$ready = true; }
}
