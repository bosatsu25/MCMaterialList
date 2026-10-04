package dev.mcmateriallist.fabric.test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import dev.mcmateriallist.core.shared.SharedProjectService;
import dev.mcmateriallist.core.persistence.StoreStatus;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/** Test-only entrypoint; not included in the distributable JAR. */
public final class ServerIsolationSmoke implements ModInitializer {
    private CompletableFuture<SharedProjectService.Ready> ready;
    private int ticks;
    private boolean finished;
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var loader = FabricLoader.getInstance();
            if (loader.isModLoaded("litematica") || loader.isModLoaded("malilib")) {
                throw new AssertionError("Dedicated-server smoke classpath contains client mods");
            }
            if (!loader.isModLoaded("mcmateriallist")) {
                throw new AssertionError("Dedicated server did not load MCMaterialList");
            }
            LoggerFactory.getLogger("mcmateriallist-smoke").info("Phase 0 dedicated server isolation PASS");
            ready = SharedServerNetworking.service(server).ready().toCompletableFuture();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ready == null || finished) return;
            if (++ticks > 200) throw new AssertionError("Isolated shared authority readiness timed out");
            if (!ready.isDone()) return;
            var result = ready.getNow(null);
            if (result == null || result.status() != StoreStatus.OK || result.worldNamespace() == null)
                throw new AssertionError("Isolated shared authority did not initialize safely");
            finished = true;
            LoggerFactory.getLogger("mcmateriallist-smoke").info("Shared authority isolated asynchronous readiness PASS");
            server.halt(false);
        });
    }
}
