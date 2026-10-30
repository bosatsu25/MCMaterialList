package dev.mcmateriallist.core.stock;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Owning server-tick thread only. Serves jobs round-robin, one container per step, until the container budget is spent or the
 * nano budget has elapsed between containers; the first container of a tick always runs, so every tick makes progress.
 */
public final class StockScheduler {
    public interface Job {
        boolean done();
        /** Exactly one container. */
        void step();
        /** Tick-wide totals of a tick in which this job was served. */
        default void served(int containers, long nanos) {}
    }
    public record Stats(int maxContainers, long maxNanos) {}
    private final int containers;
    private final long nanos;
    private final LongSupplier clock;
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private int maxContainers;
    private long maxNanos;

    public StockScheduler() { this(StockLimits.CONTAINERS_PER_TICK, StockLimits.NANOS_PER_TICK, System::nanoTime); }
    public StockScheduler(int containers, long nanos, LongSupplier clock) {
        if (containers < 1 || nanos < 1) throw new IllegalArgumentException("Invalid tick budget");
        this.containers = containers; this.nanos = nanos; this.clock = Objects.requireNonNull(clock, "clock");
    }
    public void add(Job job) { if (jobs.contains(Objects.requireNonNull(job))) throw new IllegalArgumentException("Job already scheduled"); jobs.add(job); }
    public boolean remove(Job job) { return jobs.remove(job); }
    public int size() { return jobs.size(); }
    /** Returns the containers stepped this tick. Finished jobs leave the queue. */
    public int tick() {
        if (jobs.isEmpty()) return 0;
        long start = clock.getAsLong(); int used = 0; Set<Job> served = new HashSet<>();
        while (used < containers && !jobs.isEmpty()) {
            if (used > 0 && clock.getAsLong() - start >= nanos) break;
            Job job = jobs.poll();
            if (job.done()) continue;
            job.step(); used++; served.add(job);
            if (!job.done()) jobs.add(job);
        }
        long elapsed = Math.max(0, clock.getAsLong() - start);
        maxContainers = Math.max(maxContainers, used); maxNanos = Math.max(maxNanos, elapsed);
        for (Job job : served) job.served(used, elapsed);
        return used;
    }
    public Stats stats() { return new Stats(maxContainers, maxNanos); }
}
