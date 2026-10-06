package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.work.MaterialReadiness;
import fi.dy.masa.litematica.materials.MaterialListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaterialListBase.class, remap = false)
public abstract class MaterialListReadinessMixin implements MaterialReadiness, dev.mcmateriallist.fabric.client.shared.SharedCountingProvenance {
    @Unique private boolean mcmateriallist$ready;
    @Unique private dev.mcmateriallist.fabric.client.shared.NativeSharedCapture.CountingStamp mcmateriallist$countStarted;
    @Unique private dev.mcmateriallist.fabric.client.shared.NativeSharedCapture.CountingStamp mcmateriallist$countPublished;
    @Override public void mcmateriallist$beginSharedCount(dev.mcmateriallist.fabric.client.shared.NativeSharedCapture.CountingStamp stamp) {
        mcmateriallist$countStarted = stamp; mcmateriallist$countPublished = null;
    }
    @Override public dev.mcmateriallist.fabric.client.shared.NativeSharedCapture.CountingStamp mcmateriallist$sharedCount() { return mcmateriallist$countPublished; }
    @Override public boolean mcmateriallist$ready() { return mcmateriallist$ready; }
    @Override public void mcmateriallist$invalidate() { mcmateriallist$ready = false; }
    @Inject(method = "setMaterialListEntries", at = @At("TAIL"), require = 0)
    private void published(CallbackInfo callback) {
        mcmateriallist$ready = true;
        mcmateriallist$countPublished = dev.mcmateriallist.fabric.client.shared.NativeSharedCapture.current(mcmateriallist$countStarted) ? mcmateriallist$countStarted : null;
        mcmateriallist$countStarted = null;
    }
}
