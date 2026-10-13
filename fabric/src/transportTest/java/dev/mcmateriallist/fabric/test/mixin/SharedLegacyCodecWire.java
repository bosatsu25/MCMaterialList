package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.fabric.shared.SharedPayload;
import dev.mcmateriallist.fabric.test.LegacyPeerFixture;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.mcmateriallist.fabric.shared.SharedPayload$1", remap = false)
abstract class SharedLegacyCodecWire {
    @Inject(method = "encode(Lnet/minecraft/network/RegistryFriendlyByteBuf;Ldev/mcmateriallist/fabric/shared/SharedPayload;)V",
        at = @At("HEAD"), remap = false)
    private void before(RegistryFriendlyByteBuf buffer, SharedPayload payload, CallbackInfo callback) {
        LegacyPeerFixture.encoding(buffer);
    }
    @Inject(method = "encode(Lnet/minecraft/network/RegistryFriendlyByteBuf;Ldev/mcmateriallist/fabric/shared/SharedPayload;)V",
        at = @At("TAIL"), remap = false)
    private void after(RegistryFriendlyByteBuf buffer, SharedPayload payload, CallbackInfo callback) {
        LegacyPeerFixture.encoded(buffer);
    }
}
