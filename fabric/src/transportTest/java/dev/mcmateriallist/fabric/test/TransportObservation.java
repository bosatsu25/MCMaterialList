package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.fabric.shared.*;
import dev.mcmateriallist.core.shared.SharedEvents;
import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;

/** Test-only bounded boolean event-order observations, never identities or payload contents. */
public final class TransportObservation implements ClientModInitializer {
    private static final List<String> EVENTS = new ArrayList<>();
    private static final Map<UUID, SharedEvents.CommandResult> RESULTS = new LinkedHashMap<>();
    private static UUID delayedOperation;
    private static Runnable delayedCallback;
    public static synchronized void delay(UUID operation) {
        if (delayedOperation != null || delayedCallback != null) throw new AssertionError("Test delay already occupied");
        delayedOperation = operation;
    }
    public static synchronized boolean hold(UUID operation, Runnable callback) {
        if (!operation.equals(delayedOperation)) return false;
        if (delayedCallback != null) throw new AssertionError("Duplicate delayed callback");
        delayedCallback = callback; return true;
    }
    public static synchronized boolean held() { return delayedCallback != null; }
    public static void release() {
        Runnable callback;
        synchronized (TransportObservation.class) {
            callback = delayedCallback; delayedCallback = null; delayedOperation = null;
        }
        if (callback == null) throw new AssertionError("Test delay missing incoming callback");
        callback.run();
    }
    public static synchronized void result(SharedEvents.CommandResult result) {
        if (RESULTS.size() < 64) RESULTS.put(result.operationId(), result);
    }
    public static synchronized SharedEvents.CommandResult result(UUID operation) { return RESULTS.get(operation); }
    private static synchronized void record(String observation) { if (EVENTS.size() < 24) EVENTS.add(observation); }
    public static synchronized void reset() { EVENTS.clear(); RESULTS.clear(); delayedOperation = null; delayedCallback = null; }
    public static synchronized void report() {
        var logger = org.slf4j.LoggerFactory.getLogger("mcmateriallist-transport-test");
        for (String event : EVENTS) logger.info("Shared test registration boundary: {}", event);
    }
    @Override public void onInitializeClient() {
        ClientPlayConnectionEvents.INIT.register((listener, client) -> record("client INIT"));
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> record("client JOIN sendable=" + ClientPlayNetworking.canSend(SharedPayload.TYPE)));
        ServerboundPlayChannelEvents.REGISTER.register((listener, sender, client, channels) -> record("client REGISTER sendable=" + ClientPlayNetworking.canSend(SharedPayload.TYPE)));
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> record("server JOIN payload="
            + ServerPlayNetworking.canSend(listener.player, SharedPayload.TYPE) + " control=" + ServerPlayNetworking.canSend(listener.player, SharedControlPayload.TYPE)));
        ClientboundPlayChannelEvents.REGISTER.register((listener, sender, server, channels) -> record("server REGISTER declared payload="
            + channels.contains(SharedPayload.TYPE.id()) + " control=" + channels.contains(SharedControlPayload.TYPE.id())));
    }
}
