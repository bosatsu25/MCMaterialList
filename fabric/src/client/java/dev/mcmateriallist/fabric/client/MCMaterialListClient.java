package dev.mcmateriallist.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import dev.mcmateriallist.core.persistence.LocalWorkService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

public final class MCMaterialListClient implements ClientModInitializer {
    private static LocalWorkService work;
    public static LocalWorkService work() { return work; }
    @Override
    public void onInitializeClient() {
        var logger = LoggerFactory.getLogger("mcmateriallist");
        if (Compatibility.isSupported()) {
            work = new LocalWorkService(FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist"));
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
                LocalWorkService current = work; work = null;
                if (current != null) current.close();
            });
            logger.info("MCMaterialList Phase 0 client adapter enabled");
        } else {
            logger.warn("MCMaterialList Phase 0 adapter disabled: requires Litematica 0.28.3 and MaLiLib 0.29.2");
        }
    }
}
