package dev.mcmateriallist.fabric.test.mixin;

import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Identifies the real subscription-start callback without treating it as a committed reply. */
@Mixin(targets = "dev.mcmateriallist.fabric.shared.SharedServerNetworking$Started", remap = false)
public interface SharedStartedIdentity {
    @Accessor("token") UUID initiatingToken();
}
