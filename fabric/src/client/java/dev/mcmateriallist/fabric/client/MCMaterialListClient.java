package dev.mcmateriallist.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.LoggerFactory;

public final class MCMaterialListClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        var logger = LoggerFactory.getLogger("mcmateriallist");
        if (Compatibility.isSupported()) {
            logger.info("MCMaterialList Phase 0 client adapter enabled");
        } else {
            logger.warn("MCMaterialList Phase 0 adapter disabled: requires Litematica 0.28.3 and MaLiLib 0.29.2");
        }
    }
}
