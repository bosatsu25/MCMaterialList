package dev.mcmateriallist.fabric.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mcmateriallist.core.shared.protocol.SharedTransfers;
import dev.mcmateriallist.fabric.test.LegacyPeerFixture;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "dev.mcmateriallist.fabric.shared.SharedServerNetworking$Runtime", remap = false)
abstract class SharedLegacyPeerWire {
    @WrapMethod(method = "received")
    private void oldPeer(ServerPlayer player, SharedTransfers.Fragment fragment, Operation<Void> original) {
        if (LegacyPeerFixture.receive(player)) return;
        original.call(player, fragment);
    }
}
