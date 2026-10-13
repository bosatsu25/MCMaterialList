package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.shared.client.SharedClientStore;
import dev.mcmateriallist.fabric.client.shared.SharedWorkController;
import dev.mcmateriallist.fabric.test.SharedUiDelays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SharedWorkController.class, remap = false)
public abstract class SharedUiPublicationProbe {
    @Inject(method = "validate", at = @At("RETURN"))
    private void validated(CallbackInfo callback) {
        var probe = SharedUiDelays.validated; if (probe != null) probe.run();
    }
    @Inject(method = "publish", at = @At("RETURN"))
    private static void published(@Coerce Object entry, SharedClientStore.Result result, Throwable failure, CallbackInfo callback) {
        var probe = SharedUiDelays.publication;
        if (probe != null && failure == null && result != null) probe.accept(result);
    }
}
