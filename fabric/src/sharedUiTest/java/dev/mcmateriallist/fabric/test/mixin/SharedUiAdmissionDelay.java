package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.fabric.test.SharedUiDelays;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SharedClientService.class, remap = false)
public abstract class SharedUiAdmissionDelay {
    @Inject(method = "admit", at = @At("RETURN"), cancellable = true)
    private void hold(CallbackInfoReturnable<CompletableFuture<SharedClientStore.Result>> callback) {
        var gate = SharedUiDelays.admission;
        if (gate != null) callback.setReturnValue(callback.getReturnValue().thenCompose(result -> {
            SharedUiDelays.admitted = true; return gate.thenApply(ignored -> result);
        }));
    }
}
