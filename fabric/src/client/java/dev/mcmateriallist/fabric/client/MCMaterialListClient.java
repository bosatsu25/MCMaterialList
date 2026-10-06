package dev.mcmateriallist.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import dev.mcmateriallist.core.persistence.LocalWorkService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

public final class MCMaterialListClient implements ClientModInitializer {
    private static LocalWorkService work;
    private static dev.mcmateriallist.fabric.client.shared.SharedWorkController shared;
    public static LocalWorkService work() { return work; }
    public static dev.mcmateriallist.fabric.client.shared.SharedWorkController shared() { return shared; }
    @Override
    public void onInitializeClient() {
        dev.mcmateriallist.fabric.client.shared.SharedClientNetworking.initialize();
        var logger = LoggerFactory.getLogger("mcmateriallist");
        if (Compatibility.isSupported()) {
            work = new LocalWorkService(FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist"));
            shared = new dev.mcmateriallist.fabric.client.shared.SharedWorkController(FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist"));
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
                if (shared != null) { shared.shutdown(); shared = null; }
                LocalWorkService current = work; work = null;
                if (current != null) current.close();
            });
            logger.info("MCMaterialList Phase 0 client adapter enabled");
        } else {
            logger.warn("MCMaterialList Phase 0 adapter disabled: requires Litematica 0.28.3 and MaLiLib 0.29.2");
        }
    }
}
