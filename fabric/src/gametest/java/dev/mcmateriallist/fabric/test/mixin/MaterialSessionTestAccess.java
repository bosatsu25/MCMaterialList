package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.fabric.client.material.MaterialWorkSession;
import dev.mcmateriallist.core.persistence.StoreResult;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = MaterialWorkSession.class, remap = false)
public interface MaterialSessionTestAccess {
    @Invoker("await") void phase2Await(CompletableFuture<StoreResult> future, Consumer<StoreResult> receiver);
}
