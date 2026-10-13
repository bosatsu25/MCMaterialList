package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.fabric.test.SharedUiDelays;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds the real count's native publication, without substituting count data. */
@Mixin(value = MaterialListBase.class, remap = false)
public abstract class SharedUiCountBarrier {
    @Inject(method = "setMaterialListEntries", at = @At("HEAD"), cancellable = true)
    private void hold(List<MaterialListEntry> entries, CallbackInfo callback) {
        if (!SharedUiDelays.holdCount) return;
        SharedUiDelays.holdCount = false;
        SharedUiDelays.publishCount = () -> ((MaterialListBase) (Object) this).setMaterialListEntries(entries);
        callback.cancel();
    }
}
