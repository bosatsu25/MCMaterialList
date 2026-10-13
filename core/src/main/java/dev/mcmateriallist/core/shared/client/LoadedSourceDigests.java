package dev.mcmateriallist.core.shared.client;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Observes the native reader's exact stream. Never hashes or waits for queue space on its thread. */
public final class LoadedSourceDigests implements AutoCloseable {
    public static final int ACTIVE = 4, CHUNK_BYTES = 64 * 1024, CHUNKS = 8;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(ACTIVE * (CHUNKS + 1)), task -> new Thread(task, "mcmateriallist-loaded-source-hash"));
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
        task -> new Thread(task, "mcmateriallist-loaded-source-deadline"));
    private final Set<Observation> active = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity = new Semaphore(ACTIVE);
    private final Semaphore chunks = new Semaphore(ACTIVE * CHUNKS);
    private volatile boolean closed;
    private final long timeoutMillis;

    public LoadedSourceDigests() { this(30_000); }
    LoadedSourceDigests(long timeoutMillis) { this.timeoutMillis = timeoutMillis; deadlines.setRemoveOnCancelPolicy(true); }
    public Observation observe(InputStream input) {
        java.util.Objects.requireNonNull(input);
        if (closed || !capacity.tryAcquire()) return null;
        var observation = new Observation(input); active.add(observation);
        try { observation.deadline = deadlines.schedule(observation::invalidate, timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException stopped) { observation.invalidate(); }
        return observation;
    }
    @Override public void close() {
        closed = true;
        for (var observation : active) observation.invalidate();
        deadlines.shutdownNow(); worker.shutdown();
    }
    public boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        return deadlines.awaitTermination(timeout, unit)
            && worker.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            && active.isEmpty();
    }
    /** Fixture/owner drain without disabling subsequent native loads. Never call from a native reader. */
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        synchronized (active) {
            while (!active.isEmpty()) {
                long remaining = end - System.nanoTime(); if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(active, remaining);
            }
        }
        long remaining = Math.max(0, end - System.nanoTime());
        if (worker.isShutdown()) return worker.awaitTermination(remaining, TimeUnit.NANOSECONDS) && active.isEmpty();
        try { worker.submit(() -> {}).get(remaining, TimeUnit.NANOSECONDS); return active.isEmpty(); }
        catch (RejectedExecutionException | ExecutionException | TimeoutException unavailable) { return false; }
    }

    public final class Observation extends InputStream {
        private volatile InputStream input;
        private byte[] buffer = new byte[CHUNK_BYTES];
        private int used;
        private long consumed;
        private final Semaphore queued = new Semaphore(CHUNKS);
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final AtomicBoolean invalid = new AtomicBoolean(), released = new AtomicBoolean();
        private volatile boolean nativeClosed;
        private volatile ScheduledFuture<?> deadline;
        private MessageDigest digest; // Worker owned, never retained in a completed proof.
        private Observation(InputStream input) { this.input = java.util.Objects.requireNonNull(input); }
        public CompletableFuture<String> digest() { return result; }
        public void invalidate() {
            invalid.set(true); result.complete(null);
            if (nativeClosed) closeTransferred();
        }
        @Override public int read() throws IOException {
            int value = readable().read();
            if (value >= 0) { byte[] one = {(byte) value}; observed(one, 0, 1); }
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = readable().read(bytes, offset, length);
            if (count > 0) observed(bytes, offset, count);
            return count;
        }
        private InputStream readable() throws IOException {
            if (nativeClosed || input == null) throw new IOException("Source stream is closed");
            return input;
        }
        @Override public int available() throws IOException { return readable().available(); }
        @Override public long skip(long count) throws IOException {
            // Skipped bytes are still part of the file identity.
            byte[] bytes = new byte[(int) Math.min(CHUNK_BYTES, Math.max(1, count))];
            long skipped = 0; int read;
            while (skipped < count && (read = read(bytes, 0, (int) Math.min(bytes.length, count - skipped))) > 0) skipped += read;
            return skipped;
        }
        private void observed(byte[] bytes, int offset, int count) {
            consumed += count;
            if (consumed > SchematicSourceDigest.MAX_SOURCE_BYTES) invalidate();
            if (invalid.get()) { buffer = null; return; }
            while (count > 0) {
                int part = Math.min(count, CHUNK_BYTES - used);
                System.arraycopy(bytes, offset, buffer, used, part); used += part; offset += part; count -= part;
                if (used == CHUNK_BYTES) {
                    enqueue(buffer); used = 0;
                    if (invalid.get()) { buffer = null; return; }
                    buffer = new byte[CHUNK_BYTES];
                }
            }
        }
        private void enqueue(byte[] bytes) {
            if (!queued.tryAcquire()) { invalidate(); return; }
            if (!chunks.tryAcquire()) { queued.release(); Arrays.fill(bytes, (byte) 0); invalidate(); return; }
            try {
                worker.execute(() -> {
                    try { if (!invalid.get()) hasher().update(bytes); }
                    finally { if (invalid.get()) digest = null; Arrays.fill(bytes, (byte) 0); queued.release(); chunks.release(); }
                });
            } catch (RejectedExecutionException stopped) { Arrays.fill(bytes, (byte) 0); queued.release(); chunks.release(); invalidate(); }
        }
        private MessageDigest hasher() {
            if (digest == null) try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
            return digest;
        }
        @Override public void close() throws IOException {
            if (nativeClosed) return;
            if (!invalid.get() && used > 0) enqueue(Arrays.copyOf(buffer, used));
            buffer = null; used = 0; nativeClosed = true;
            if (invalid.get()) { closeTransferred(); return; }
            try { worker.execute(this::finish); }
            catch (RejectedExecutionException stopped) { invalidate(); }
        }
        private void finish() {
            byte[] tail = new byte[CHUNK_BYTES];
            String value = null;
            try {
                InputStream owned = input;
                if (invalid.get() || owned == null) return;
                int count;
                while (!invalid.get() && (count = owned.read(tail)) != -1) {
                    consumed += count;
                    if (consumed > SchematicSourceDigest.MAX_SOURCE_BYTES) { invalidate(); return; }
                    hasher().update(tail, 0, count);
                }
                if (!invalid.get()) value = HexFormat.of().formatHex(hasher().digest());
            } catch (IOException failure) { invalidate(); }
            finally { Arrays.fill(tail, (byte) 0); digest = null; closeTransferred(value); }
        }
        private void closeTransferred() { closeTransferred(null); }
        private synchronized void closeTransferred(String value) {
            if (!nativeClosed) return; // The upstream parser still owns this stream.
            InputStream owned = input; input = null;
            try { if (owned != null) owned.close(); }
            catch (IOException failure) { invalid.set(true); }
            finally {
                result.complete(invalid.get() ? null : value);
                var task = deadline; if (task != null) task.cancel(false);
                if (released.compareAndSet(false, true)) {
                    synchronized (active) { active.remove(this); capacity.release(); active.notifyAll(); }
                }
            }
        }
    }
}
