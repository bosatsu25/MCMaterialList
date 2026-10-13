package dev.mcmateriallist.fabric.test;

import java.util.concurrent.CompletableFuture;

/** Deterministic test-only gates; never included in the application archive. */
public final class SharedUiDelays {
    public static volatile CompletableFuture<Void> admission;
    public static volatile CompletableFuture<Void> result;
    public static volatile boolean admitted, executed;
    public static volatile CompletableFuture<Void> cache, selection, load, digest;
    public static volatile boolean cacheHeld, selectionHeld, loadHeld, digestHeld, observeLocalRefresh;
    public static volatile int materialRefreshes, regionRefreshes, submissions;
    public static volatile java.util.function.Consumer<dev.mcmateriallist.core.shared.client.SharedClientStore.Result> publication;
    public static volatile Runnable validated;
    public static volatile java.util.concurrent.CompletionStage<Boolean> submitted;
    public static volatile dev.mcmateriallist.core.shared.SharedCommand command;
    public static volatile boolean holdCount;
    public static volatile Runnable publishCount;
    private SharedUiDelays() {}
}
