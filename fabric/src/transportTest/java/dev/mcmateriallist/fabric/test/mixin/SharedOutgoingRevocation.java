package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.shared.transport.BoundedOutgoing;
import dev.mcmateriallist.fabric.test.QueuedRevocationGate;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(value = BoundedOutgoing.class, remap = false)
abstract class SharedOutgoingRevocation {
    @WrapMethod(method = "poll")
    private BoundedOutgoing.Frame poll(long now, Operation<BoundedOutgoing.Frame> original) {
        var outgoing = (BoundedOutgoing) (Object) this;
        if (QueuedRevocationGate.block(outgoing)) return null;
        var frame = original.call(now);
        QueuedRevocationGate.observed(outgoing, frame);
        return frame;
    }
}
