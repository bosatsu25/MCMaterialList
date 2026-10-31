package dev.mcmateriallist.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.LoggerFactory;

/** Common entrypoint: deliberately no reference to client or adapter classes. */
public final class MCMaterialList implements ModInitializer {
    @Override
    public void onInitialize() {
        dev.mcmateriallist.fabric.shared.SharedPayload.register();
        dev.mcmateriallist.fabric.shared.SharedControlPayload.register();
        dev.mcmateriallist.fabric.shared.SharedServerNetworking.initialize();
        dev.mcmateriallist.fabric.stock.StockPayload.register();
        dev.mcmateriallist.fabric.stock.StockServerNetworking.initialize();
        LoggerFactory.getLogger("mcmateriallist").info("MCMaterialList Phase 0 common initialized");
    }
}
