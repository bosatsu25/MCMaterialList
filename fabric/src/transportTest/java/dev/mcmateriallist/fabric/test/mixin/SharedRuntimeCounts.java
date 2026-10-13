package dev.mcmateriallist.fabric.test.mixin;
import dev.mcmateriallist.core.shared.transport.TransportBudget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "dev.mcmateriallist.fabric.shared.SharedServerNetworking$Runtime", remap = false)
public interface SharedRuntimeCounts {
    @Accessor("connections") TransportBudget connectionBudget();
}
