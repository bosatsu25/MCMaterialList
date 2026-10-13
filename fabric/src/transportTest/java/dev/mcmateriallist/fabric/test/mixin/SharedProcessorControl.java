package dev.mcmateriallist.fabric.test.mixin;
import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;

@Mixin(targets = "dev.mcmateriallist.fabric.shared.SharedServerNetworking$Processor", remap = false)
public interface SharedProcessorControl {
    @Invoker("work") boolean wireWork(Runnable job);
    @Invoker("dispatch") void dispatchSynthetic(UUID token, UUID actor, SharedMessage message);
    @Accessor("live") Set<UUID> liveTokens();
    @Accessor("capable") Set<UUID> capableTokens();
    @Accessor("subscriptions") Map<UUID, ProjectScope> subscriptions();
}
