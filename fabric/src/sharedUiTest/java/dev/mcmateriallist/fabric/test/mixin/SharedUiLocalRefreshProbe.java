package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.persistence.*;
import dev.mcmateriallist.fabric.test.SharedUiDelays;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LocalWorkService.class, remap = false)
public abstract class SharedUiLocalRefreshProbe {
    @Inject(method = "refreshMaterials", at = @At("HEAD"))
    private void materials(CallbackInfoReturnable<CompletableFuture<MaterialRefreshResult>> callback) {
        if (SharedUiDelays.observeLocalRefresh) SharedUiDelays.materialRefreshes++;
    }
    @Inject(method = "refreshRegions", at = @At("HEAD"))
    private void regions(CallbackInfoReturnable<CompletableFuture<RegionRefreshResult>> callback) {
        if (SharedUiDelays.observeLocalRefresh) SharedUiDelays.regionRefreshes++;
    }
}
