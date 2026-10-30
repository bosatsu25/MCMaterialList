package dev.mcmateriallist.fabric.client.stock;

import dev.mcmateriallist.core.stock.protocol.StockMessage;
import dev.mcmateriallist.fabric.stock.StockPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Client end of the stock channel; client thread only. The capability is exactly "the server registered mcmateriallist:stock_v1"
 * (ClientPlayNetworking.canSend). Replies are routed by requestId to the listener that sent the request; nothing survives a
 * connection change, and every world change discards all Check Materials state.
 */
public final class StockClientNetworking {
    public static final int LISTENERS = 16;
    private static ClientPacketListener connection;
    private static final Map<UUID, Consumer<StockMessage>> listeners = new LinkedHashMap<>();
    private StockClientNetworking() {}

    public static void initialize() {
        ClientPlayConnectionEvents.INIT.register((listener, client) ->
            // Capture the actual listener, so a frame of an old connection never reaches a replacement.
            ClientPlayNetworking.registerReceiver(StockPayload.TYPE, (payload, context) -> {
                if (listener == connection && listener == context.client().getConnection()) dispatch(payload.message());
            }));
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> { connection = listener; listeners.clear(); StockClient.discard(); });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            if (connection == listener) { connection = null; listeners.clear(); StockClient.discard(); }
        });
    }
    /** The server answers Check Materials (singleplayer included); without it only the client fallback is available. */
    public static boolean available() {
        Minecraft client = Minecraft.getInstance();
        return client.isSameThread() && connection != null && connection == client.getConnection() && ClientPlayNetworking.canSend(StockPayload.TYPE);
    }
    /**
     * Sends one request built for a fresh requestId; every reply with that id goes to listener until a terminal reply (Done,
     * Refused or GrantReply) arrives. Returns null when the server lacks the channel or too many requests are outstanding.
     */
    public static UUID send(Function<UUID, StockMessage> request, Consumer<StockMessage> listener) {
        if (!available() || listeners.size() >= LISTENERS) return null;
        UUID id = UUID.randomUUID();
        StockMessage message = request.apply(id);
        if (!id.equals(message.requestId())) throw new IllegalArgumentException("Request does not carry its requestId");
        listeners.put(id, listener);
        try { ClientPlayNetworking.send(new StockPayload(message)); }
        catch (RuntimeException failure) { listeners.remove(id); return null; }
        return id;
    }
    /** A Cancel carries the scan's requestId; the scan's own listener still receives the final Done. */
    public static void cancel(UUID requestId) {
        if (requestId == null || !available()) return;
        try { ClientPlayNetworking.send(new StockPayload(new StockMessage.Cancel(requestId))); } catch (RuntimeException ignored) { /* the connection is closing */ }
    }
    /** The listener of a request that is no longer awaited; later replies are dropped. */
    public static void forget(UUID requestId) { if (requestId != null) listeners.remove(requestId); }
    private static void dispatch(StockMessage message) {
        Consumer<StockMessage> listener = listeners.get(message.requestId());
        if (listener == null) return;
        if (message instanceof StockMessage.Done || message instanceof StockMessage.Refused || message instanceof StockMessage.GrantReply)
            listeners.remove(message.requestId());
        listener.accept(message);
    }
}
