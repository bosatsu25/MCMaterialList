package dev.mcmateriallist.fabric.test.mixin;
import dev.mcmateriallist.core.shared.SharedProjectService;
import dev.mcmateriallist.fabric.test.TransportLifecycleProbe;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(targets = "dev.mcmateriallist.fabric.shared.SharedServerNetworking$Processor", remap = false)
abstract class SharedProcessorObservation {
    @Shadow @Final private SharedProjectService service;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void created(SharedProjectService value, CallbackInfo callback) {
        if (!((Object) this instanceof SharedProcessorControl control)) throw new AssertionError("Test processor bridge missing");
        TransportLifecycleProbe.register(value, control);
    }
    @Inject(method = "stop", at = @At("HEAD"))
    private void stopped(CallbackInfo callback) { TransportLifecycleProbe.unregister(service); }
    @WrapMethod(method = "completion")
    private void completed(@Coerce Object completion, Operation<Void> original) {
        if (completion instanceof SharedCompletionIdentity identity
            && TransportLifecycleProbe.hold(identity.initiatingToken(), () -> original.call(completion))) return;
        original.call(completion);
    }
}
