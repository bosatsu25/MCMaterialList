package dev.mcmateriallist.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import dev.mcmateriallist.core.location.LocationProfileService;
import dev.mcmateriallist.core.persistence.LocalWorkService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

public final class MCMaterialListClient implements ClientModInitializer {
    private static LocalWorkService work;
    private static dev.mcmateriallist.fabric.client.shared.SharedWorkController shared;
    private static LocationProfileService locations;
    public static LocalWorkService work() { return work; }
    /** Location Files share the local work configuration root; null while the adapter is disabled or stopping. */
    public static LocationProfileService locations() { return locations; }
    public static dev.mcmateriallist.fabric.client.shared.SharedWorkController shared() { return shared; }
    @Override
    public void onInitializeClient() {
        dev.mcmateriallist.fabric.client.shared.SharedClientNetworking.initialize();
        var logger = LoggerFactory.getLogger("mcmateriallist");
        if (Compatibility.isSupported()) {
            work = new LocalWorkService(FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist"));
            locations = new LocationProfileService(FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist"));
            shared = new dev.mcmateriallist.fabric.client.shared.SharedWorkController(FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist"));
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
                dev.mcmateriallist.fabric.client.shared.SharedSourceLoading.shutdown();
                if (shared != null) { shared.shutdown(); shared = null; }
                LocationProfileService location = locations; locations = null;
                LocalWorkService current = work; work = null;
                // A stalled location write must not prevent the local work queue from draining.
                try { if (location != null) location.close(); }
                finally { if (current != null) current.close(); }
            });
            logger.info("MCMaterialList Phase 0 client adapter enabled");
        } else {
            logger.warn("MCMaterialList Phase 0 adapter disabled: requires Litematica 0.28.3 and MaLiLib 0.29.2");
        }
    }
}
