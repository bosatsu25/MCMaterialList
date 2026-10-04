package dev.mcmateriallist.fabric.client;

import net.fabricmc.loader.api.FabricLoader;

/** Check metadata without loading any class from the optional client mods. */
public final class Compatibility {
    private Compatibility() {}

    public static boolean isSupported() {
        return hasVersion("litematica", "0.28.3") && hasVersion("malilib", "0.29.2");
    }

    private static boolean hasVersion(String id, String version) {
        return FabricLoader.getInstance().getModContainer(id)
            .map(mod -> version.equals(mod.getMetadata().getVersion().getFriendlyString()))
            .orElse(false);
    }
}
