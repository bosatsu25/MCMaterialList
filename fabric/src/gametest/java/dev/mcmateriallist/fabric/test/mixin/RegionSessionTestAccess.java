package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.fabric.client.region.RegionWorkSession;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = RegionWorkSession.class, remap = false)
public interface RegionSessionTestAccess { @Invoker("await") <T> void phase2Await(CompletableFuture<T> future, Consumer<T> receiver); }
