package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.shared.client.SchematicSourceDigest;
import dev.mcmateriallist.fabric.test.SharedUiDelays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Holds only the owned background digest worker, after an actual read-only hash. */
@Mixin(value = SchematicSourceDigest.class, remap = false)
public abstract class SharedUiDigestBarrier {
    @Inject(method = "read", at = @At("RETURN"))
    private static void read(CallbackInfoReturnable<SchematicSourceDigest.Result> callback) {
        var gate = SharedUiDelays.digest;
        if (gate != null) { SharedUiDelays.digest = null; SharedUiDelays.digestHeld = true; gate.join(); }
    }
}
