package dev.mcmateriallist.core;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 5A measurement output. Every line starts with {@value #MARKER} so CI logs and JUnit XML keep them; the Gradle core
 * test task also echoes these lines to the console. Measured values are environment-sensitive observations, never targets:
 * tests only assert hard bounds (limits, no corruption, bounded refusal) and generous ceilings.
 */
public final class Perf5A {
    public static final String MARKER = "PERF5A";
    private static final AtomicBoolean ENVIRONMENT = new AtomicBoolean();
    private Perf5A() {}

    public record Stats(int samples, long minNanos, long p50Nanos, long p95Nanos, long maxNanos, long meanNanos) {
        public String format() {
            return "n=" + samples + " minMs=" + ms(minNanos) + " p50Ms=" + ms(p50Nanos) + " p95Ms=" + ms(p95Nanos) + " maxMs=" + ms(maxNanos) + " meanMs=" + ms(meanNanos);
        }
        public static Stats of(long[] nanos) {
            if (nanos.length == 0) return new Stats(0, 0, 0, 0, 0, 0);
            long[] sorted = nanos.clone(); Arrays.sort(sorted);
            long sum = 0; for (long value : sorted) sum += value;
            return new Stats(sorted.length, sorted[0], sorted[(sorted.length - 1) / 2], sorted[(int) Math.ceil(sorted.length * 0.95) - 1],
                sorted[sorted.length - 1], sum / sorted.length);
        }
    }
    @FunctionalInterface public interface Body { void run() throws Exception; }

    /** Runs body warmup times unmeasured, then samples times, and returns per-run wall-clock statistics. */
    public static Stats measure(int warmup, int samples, Body body) throws Exception {
        for (int i = 0; i < warmup; i++) body.run();
        long[] nanos = new long[samples];
        for (int i = 0; i < samples; i++) { long start = System.nanoTime(); body.run(); nanos[i] = System.nanoTime() - start; }
        return Stats.of(nanos);
    }
    /** Prints "PERF5A name key=value ..." once; the first call also records the JVM/hardware environment. */
    public static void record(String name, Object... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("key/value pairs expected");
        if (ENVIRONMENT.compareAndSet(false, true)) {
            Runtime runtime = Runtime.getRuntime();
            System.out.println(MARKER + " environment java=" + System.getProperty("java.version") + " vm=" + System.getProperty("java.vm.name").replace(' ', '_')
                + " os=" + System.getProperty("os.name").replace(' ', '_') + "/" + System.getProperty("os.arch") + " cpus=" + runtime.availableProcessors()
                + " maxHeapMiB=" + runtime.maxMemory() / (1024 * 1024) + " tmp=" + System.getProperty("java.io.tmpdir"));
        }
        StringBuilder line = new StringBuilder(MARKER).append(' ').append(name);
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            line.append(' ').append(keyValues[i]).append('=').append(value instanceof Stats stats ? "{" + stats.format() + "}" : String.valueOf(value));
        }
        System.out.println(line);
    }
    public static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0); }
    public static String kib(long bytes) { return String.format(Locale.ROOT, "%.1f", bytes / 1024.0); }
}
