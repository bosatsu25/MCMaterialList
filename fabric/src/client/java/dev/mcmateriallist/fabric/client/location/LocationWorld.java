package dev.mcmateriallist.fabric.client.location;

import dev.mcmateriallist.core.location.LocationLimits;
import dev.mcmateriallist.core.location.WorldIdentity;
import dev.mcmateriallist.core.shared.protocol.SharedProtocol;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import net.minecraft.client.Minecraft;

/**
 * Client-thread world context, resolved again for every action. Only the namespace negotiated with a mod-enabled
 * server (singleplayer included) identifies a world; there is no server-address or local fallback identity.
 */
public final class LocationWorld {
    public enum Reason { DISABLED, NO_WORLD, NEGOTIATING, UNSUPPORTED, DISCONNECTED, UNAVAILABLE }
    public sealed interface Resolution permits Available, Unavailable {}
    /** connection is the exact client listener whose negotiation supplied the namespace. */
    public record Available(WorldIdentity world, String dimension, Object connection) implements Resolution {
        /** Still the same listener and world; the dimension may have changed. */
        public boolean current() { return resolve() instanceof Available now && now.connection == connection && now.world.equals(world); }
    }
    public record Unavailable(Reason reason) implements Resolution {}
    private LocationWorld() {}

    public static Resolution resolve() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Location Files require the client thread");
        if (MCMaterialListClient.locations() == null) return new Unavailable(Reason.DISABLED);
        var connection = mc.getConnection();
        if (connection == null || mc.level == null || mc.player == null) return new Unavailable(Reason.NO_WORLD);
        var state = SharedClientNetworking.state(); var capabilities = state.capabilities();
        // Negotiating/unsupported/unavailable/disconnected all clear the capabilities, so none can supply a stale namespace.
        if (capabilities == null) return new Unavailable(switch (state.status()) {
            case NEGOTIATING -> Reason.NEGOTIATING; case UNSUPPORTED -> Reason.UNSUPPORTED; case DISCONNECTED -> Reason.DISCONNECTED; default -> Reason.UNAVAILABLE;
        });
        if (capabilities.protocolVersion() != SharedProtocol.VERSION) return new Unavailable(Reason.UNSUPPORTED);
        String dimension = mc.level.dimension().identifier().toString();
        if (!LocationLimits.namespacedId(dimension)) return new Unavailable(Reason.NO_WORLD);
        return new Available(new WorldIdentity(capabilities.worldNamespace()), dimension, connection);
    }
    /** Same world and listener, or the same unavailable reason. The dimension is deliberately ignored. */
    public static boolean same(Resolution a, Resolution b) {
        if (a instanceof Available x && b instanceof Available y) return x.connection == y.connection && x.world.equals(y.world);
        return a instanceof Unavailable x && b instanceof Unavailable y && x.reason == y.reason;
    }
}
