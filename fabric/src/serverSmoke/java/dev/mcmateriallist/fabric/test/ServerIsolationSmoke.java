package dev.mcmateriallist.fabric.test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/** Test-only entrypoint; not included in the distributable JAR. */
public final class ServerIsolationSmoke implements ModInitializer {
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
            server.halt(false);
        });
    }
}
