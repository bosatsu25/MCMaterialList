package dev.mcmateriallist.fabric.test.mixin;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = {"dev.mcmateriallist.fabric.shared.SharedServerNetworking$Outcome",
    "dev.mcmateriallist.fabric.shared.SharedServerNetworking$SnapshotReply"}, remap = false)
public interface SharedCompletionIdentity {
    @Accessor("token") UUID initiatingToken();
}
