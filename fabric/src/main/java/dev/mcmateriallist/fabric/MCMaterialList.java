package dev.mcmateriallist.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.LoggerFactory;

/** Common entrypoint: deliberately no reference to client or adapter classes. */
public final class MCMaterialList implements ModInitializer {
    @Override
    public void onInitialize() {
        LoggerFactory.getLogger("mcmateriallist").info("MCMaterialList Phase 0 common initialized");
    }
}
