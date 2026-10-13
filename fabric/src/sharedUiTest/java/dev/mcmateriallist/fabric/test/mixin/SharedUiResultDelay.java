package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.fabric.test.SharedUiDelays;
import java.util.concurrent.CompletionStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SharedProjectService.class, remap = false)
public abstract class SharedUiResultDelay {
    @Inject(method = "command", at = @At("RETURN"), cancellable = true)
    private void hold(CallbackInfoReturnable<CompletionStage<SharedEvents.Outcome>> callback) {
        var gate = SharedUiDelays.result;
        if (gate != null) callback.setReturnValue(callback.getReturnValue().thenCompose(result -> {
            SharedUiDelays.executed = true; return gate.thenApply(ignored -> result);
        }));
    }
}
