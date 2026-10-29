package dev.mcmateriallist.fabric.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.fabric.test.MultiplayerFaults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Simulates a response lost after the authority saved it: the decoded ACK never reaches production handling. */
@Mixin(value = SharedClientNetworking.class, remap = false)
abstract class MultiplayerResultDrop {
    @WrapMethod(method = "decoded")
    private static void received(@Coerce Object connection, SharedMessage message, Operation<Void> original) {
        if (message instanceof SharedMessage.ProjectDelta delta) MultiplayerFaults.delta(delta.delta().scope(), delta.delta().projectRevision());
        if (message instanceof SharedMessage.CommandResult result && MultiplayerFaults.shouldDrop(result.result().operationId())) return;
        original.call(connection, message);
    }
}
