package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.fabric.test.TransportObservation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Observes the real decoded socket callback before production pending-operation filtering. */
@Mixin(value = SharedClientNetworking.class, remap = false)
abstract class SharedResultObservation {
    @WrapMethod(method = "decoded")
    private static void received(@Coerce Object connection, SharedMessage message, Operation<Void> original) {
        if (message instanceof SharedMessage.CommandResult result) {
            TransportObservation.result(result.result());
            if (TransportObservation.hold(result.result().operationId(), () -> original.call(connection, message))) return;
        }
        original.call(connection, message);
    }
}
