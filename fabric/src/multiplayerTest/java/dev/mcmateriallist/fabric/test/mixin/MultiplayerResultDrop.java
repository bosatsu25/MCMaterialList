package dev.mcmateriallist.fabric.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.fabric.test.MultiplayerFaults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Simulates a response lost after the authority saved it: the decoded ACK never reaches production handling. */
@Mixin(value = SharedClientNetworking.class, remap = false)
abstract class MultiplayerResultDrop {
    private static final Logger LOG = LoggerFactory.getLogger("mcmateriallist-multiplayer-test");
    @WrapMethod(method = "decoded")
    private static void received(@Coerce Object connection, SharedMessage message, Operation<Void> original) {
        if (message instanceof SharedMessage.ProjectDelta delta) MultiplayerFaults.delta(delta.delta().scope(), delta.delta().projectRevision());
        if (message instanceof SharedMessage.CommandResult result && MultiplayerFaults.shouldDrop(result.result().operationId())) {
            LOG.info("Multiplayer trace dropped {}", summary(message)); return;
        }
        original.call(connection, message);
        // Evidence for failures: what arrived and the production state it left, never row contents.
        var state = SharedClientNetworking.state();
        LOG.info("Multiplayer trace {} -> status={} cursor={}", summary(message), state.status(), state.cursor());
    }
    private static String summary(SharedMessage message) {
        return switch (message) {
            case SharedMessage.Snapshot value -> "Snapshot request=" + value.requestId() + " status=" + value.status()
                + (value.snapshot() == null ? "" : " revision=" + value.snapshot().projectRevision() + " manifest=" + value.snapshot().manifest().version());
            case SharedMessage.ProjectDelta value -> "Delta revision=" + value.delta().projectRevision() + " manifest=" + value.delta().manifestVersion();
            case SharedMessage.ProjectInvalidated value -> "Invalidated revision=" + value.invalidated().projectRevision()
                + " manifest=" + value.invalidated().manifestVersion();
            case SharedMessage.CommandResult value -> "Result operation=" + value.result().operationId() + " status=" + value.result().status()
                + " revision=" + value.result().projectRevision();
            default -> message.getClass().getSimpleName();
        };
    }
}
