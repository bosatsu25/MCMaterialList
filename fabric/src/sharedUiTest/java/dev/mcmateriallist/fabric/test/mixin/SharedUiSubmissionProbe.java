package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.shared.SharedCommand;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.fabric.test.SharedUiDelays;
import java.util.concurrent.CompletionStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SharedClientNetworking.class, remap = false)
public abstract class SharedUiSubmissionProbe {
    @Inject(method = "submit", at = @At("RETURN"))
    private static void submitted(SharedCommand command, SharedClientNetworking.DurableAdmission admission,
            CallbackInfoReturnable<CompletionStage<Boolean>> callback) {
        SharedUiDelays.command = command; SharedUiDelays.submitted = callback.getReturnValue(); SharedUiDelays.submissions++;
    }
}
