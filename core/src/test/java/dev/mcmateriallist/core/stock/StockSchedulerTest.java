package dev.mcmateriallist.core.stock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockSchedulerTest {
    /** Each step advances the fake clock by its own cost. */
    private static final class Job implements StockScheduler.Job {
        final String name; final long cost; final long[] clock; final List<String> log; int left, maxContainers; long maxNanos;
        Job(String name, int steps, long cost, long[] clock, List<String> log) { this.name = name; left = steps; this.cost = cost; this.clock = clock; this.log = log; }
        @Override public boolean done() { return left == 0; }
        @Override public void step() { left--; clock[0] += cost; log.add(name); }
        @Override public void served(int containers, long nanos) { maxContainers = Math.max(maxContainers, containers); maxNanos = Math.max(maxNanos, nanos); }
    }

    @Test void containerBudgetIsRoundRobinAcrossScans() {
        long[] clock = {0}; List<String> log = new ArrayList<>();
        var scheduler = new StockScheduler(8, 1_000_000, () -> clock[0]);
        Job a = new Job("a", 10, 10, clock, log), b = new Job("b", 3, 10, clock, log);
        scheduler.add(a); scheduler.add(b);
        assertThrows(IllegalArgumentException.class, () -> scheduler.add(a));
        assertEquals(8, scheduler.tick());
        assertEquals(List.of("a", "b", "a", "b", "a", "b", "a", "a"), log);
        assertEquals(5, scheduler.tick()); assertEquals(0, scheduler.size()); assertEquals(0, scheduler.tick());
        assertEquals(new StockScheduler.Stats(8, 80), scheduler.stats());
        assertEquals(8, a.maxContainers); assertEquals(8, b.maxContainers); assertEquals(80, a.maxNanos);
    }
    @Test void timeBudgetIsCheckedBetweenContainersAndTheFirstAlwaysRuns() {
        long[] clock = {5_000}; List<String> log = new ArrayList<>();
        var scheduler = new StockScheduler(8, 1_000_000, () -> clock[0]);
        Job slow = new Job("slow", 4, 400_000, clock, log); scheduler.add(slow);
        assertEquals(3, scheduler.tick());   // 0, 0.4, 0.8 ms started; 1.2 ms elapsed stops the fourth
        assertEquals(new StockScheduler.Stats(3, 1_200_000), scheduler.stats());
        Job huge = new Job("huge", 2, 5_000_000, clock, log); scheduler.add(huge);
        assertEquals(2, scheduler.tick());   // slow's last at 0.4 ms leaves room to start huge, which overruns: measured, not cut
        assertEquals(5_400_000, scheduler.stats().maxNanos()); assertEquals(3, scheduler.stats().maxContainers());
        assertEquals(List.of("slow", "slow", "slow", "slow", "huge"), log);
        assertEquals(1, scheduler.tick()); assertEquals(0, scheduler.size());
    }
    @Test void stockScansNeverExceedEightContainersPerTick() {
        var world = new FakeProbe(); List<dev.mcmateriallist.core.location.ContainerPosition> positions = new ArrayList<>();
        for (int i = 0; i < StockLimits.MAX_POSITIONS; i++) { world.block(i, 64, 0, "minecraft:barrel", Map.of("minecraft:stone", 1L));
            positions.add(new dev.mcmateriallist.core.location.ContainerPosition(i, 64, 0, "minecraft:barrel")); }
        long[] clock = {0}; var scheduler = new StockScheduler(StockLimits.CONTAINERS_PER_TICK, StockLimits.NANOS_PER_TICK, () -> clock[0]++);
        var scan = new StockScan("minecraft:overworld", positions, world, (d, p) -> true); scheduler.add(scan);
        int ticks = 0, reads = 0;
        while (scheduler.size() > 0) { assertTrue(scheduler.tick() <= 8); ticks++; for (LocationReport r; (r = scan.poll()) != null; ) reads++; }
        assertEquals(64, ticks); assertEquals(512, reads);
        assertEquals(8, scan.summary().maxContainersPerTick()); assertEquals(512, scan.summary().checked());
        assertThrows(IllegalArgumentException.class, () -> new StockScheduler(0, 1, () -> 0));
    }
}
