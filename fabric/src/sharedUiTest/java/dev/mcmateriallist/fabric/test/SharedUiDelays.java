package dev.mcmateriallist.fabric.test;

import java.util.concurrent.CompletableFuture;

/** Deterministic test-only gates; never included in the application archive. */
public final class SharedUiDelays {
    public static volatile CompletableFuture<Void> admission;
    public static volatile CompletableFuture<Void> result;
    public static volatile boolean admitted, executed;
    private SharedUiDelays() {}
}
