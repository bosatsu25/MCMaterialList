package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.shared.protocol.SharedTransfers;
import dev.mcmateriallist.core.shared.transport.TransportBudget;
import dev.mcmateriallist.fabric.test.AdminSnapshotGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(targets = "dev.mcmateriallist.fabric.client.shared.SharedClientNetworking$Connection", remap = false)
abstract class SharedClientFrameObservation implements AdminSnapshotGate.WireControl {
    @Override @Invoker("work") public abstract boolean wireWork(Runnable job);
    @Override @Accessor("incomingBudget") public abstract TransportBudget inboundBudget();
    @WrapMethod(method = "receive")
    private void received(SharedTransfers.Fragment fragment, Operation<Void> original) {
        if (AdminSnapshotGate.frame(this, fragment, () -> original.call(fragment))) return;
        original.call(fragment);
    }
}
