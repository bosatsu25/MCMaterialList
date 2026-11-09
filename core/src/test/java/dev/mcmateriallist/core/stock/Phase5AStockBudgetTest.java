package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.Perf5A;
import dev.mcmateriallist.core.location.ContainerPosition;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5A item 2: Check Materials scans under the default tick budget (8 containers, 1 ms checked between containers) and the
 * R4 admission limits for 20 players. The probe reads a realistic slot array per container (27 slots, or 54 for a double
 * chest), so these timings cover the core scan/report path, not Minecraft chunk or block-entity access.
 */
class Phase5AStockBudgetTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String[] ITEMS = new String[200];
    static { for (int i = 0; i < ITEMS.length; i++) ITEMS[i] = String.format(Locale.ROOT, "minecraft:item_%03d", i); }

    /** Every container holds a fixed slot array; read() merges it like ServerContainerProbe does for a vanilla inventory. */
    static final class SlotProbe implements ContainerProbe {
        final Map<List<Integer>, String[]> slots = new HashMap<>();
        final Map<List<Integer>, List<Integer>> partners = new HashMap<>();
        @Override public Shape shape(String dimension, ContainerPosition registered) {
            var here = List.of(registered.x(), registered.y(), registered.z());
            var other = partners.get(here);
            return other == null ? Present.single(registered.blockId())
                : new Present(registered.blockId(), new ContainerPosition(other.get(0), other.get(1), other.get(2), registered.blockId()), true);
        }
        @Override public Reading read(String dimension, List<ContainerPosition> halves) {
            Map<String, Long> counts = new HashMap<>();
            for (ContainerPosition half : halves)
                for (String item : slots.getOrDefault(List.of(half.x(), half.y(), half.z()), new String[0])) if (item != null) counts.merge(item, 64L, Long::sum);
            return new Contents(counts);
        }
    }
    /** 512 barrels with 27 slots drawn from 200 item ids, three quarters filled. */
    static List<ContainerPosition> barrels(SlotProbe probe, int x0, Random random) {
        List<ContainerPosition> positions = new ArrayList<>();
        for (int i = 0; i < StockLimits.MAX_POSITIONS; i++) {
            String[] slots = new String[27];
            for (int s = 0; s < slots.length; s++) if (random.nextInt(4) != 0) slots[s] = ITEMS[random.nextInt(ITEMS.length)];
            probe.slots.put(List.of(x0 + i % 16, 64 + i / 256, i / 16 % 16), slots);
            positions.add(new ContainerPosition(x0 + i % 16, 64 + i / 256, i / 16 % 16, "minecraft:barrel"));
        }
        return positions;
    }
    /** 256 double chests (both halves registered), every slot a distinct item: the most work one container step can do. */
    static List<ContainerPosition> doubleChests(SlotProbe probe, int x0) {
        List<ContainerPosition> positions = new ArrayList<>();
        for (int i = 0; i < StockLimits.MAX_POSITIONS / 2; i++) {
            int x = x0 + (i % 8) * 2, y = 64 + i / 64, z = i / 8 % 8;
            String[] a = new String[27], b = new String[27];
            for (int s = 0; s < 27; s++) { a[s] = ITEMS[(i + s) % ITEMS.length] + "_a" + s; b[s] = ITEMS[(i + s) % ITEMS.length] + "_b" + s; }
            probe.slots.put(List.of(x, y, z), a); probe.slots.put(List.of(x + 1, y, z), b);
            probe.partners.put(List.of(x, y, z), List.of(x + 1, y, z)); probe.partners.put(List.of(x + 1, y, z), List.of(x, y, z));
            positions.add(new ContainerPosition(x, y, z, "minecraft:chest")); positions.add(new ContainerPosition(x + 1, y, z, "minecraft:chest"));
        }
        return positions;
    }
    record Run(int ticks, int reports, int checked, int maxContainers, Perf5A.Stats tickStats, StockScheduler.StepStats steps) {}
    static Run drive(List<StockScan> scans) {
        var scheduler = new StockScheduler();
        scans.forEach(scheduler::add);
        List<Long> nanos = new ArrayList<>(); int ticks = 0, reports = 0, checked = 0, maxContainers = 0;
        while (scheduler.size() > 0 || scans.stream().anyMatch(scan -> !scan.finished())) {
            long start = System.nanoTime(); int used = scheduler.tick(); long elapsed = System.nanoTime() - start;
            if (used > 0) nanos.add(elapsed);
            maxContainers = Math.max(maxContainers, used); ticks++;
            for (StockScan scan : scans)
                for (int frame = 0; frame < StockLimits.FRAMES_PER_TICK; frame++) {
                    LocationReport report = scan.poll(); if (report == null) break;
                    reports++; if (report.status() == LocationStatus.CHECKED) checked++;
                }
            assertTrue(ticks < 100_000, "scan did not finish");
        }
        return new Run(ticks, reports, checked, maxContainers, Perf5A.Stats.of(nanos.stream().mapToLong(Long::longValue).toArray()), scheduler.stepStats());
    }
    static void record(String name, Run run) {
        Perf5A.record(name, "ticks", run.ticks(), "reports", run.reports(), "checked", run.checked(), "maxContainersPerTick", run.maxContainers(),
            "tick", run.tickStats(), "maxStepMs", Perf5A.ms(run.steps().maxStepNanos()), "overBudgetTicks", run.steps().overBudgetTicks(),
            "busyTicks", run.steps().ticks(), "budgetMs", Perf5A.ms(StockLimits.NANOS_PER_TICK));
    }

    @Test void fourConcurrentMaximalScansStayWithinTheContainerBudget() {
        Random random = new Random(12);
        for (String phase : List.of("cold", "warm")) {
            List<StockScan> scans = new ArrayList<>();
            for (int s = 0; s < StockLimits.CONCURRENT_SCANS; s++) {
                var probe = new SlotProbe();
                scans.add(new StockScan(DIMENSION, barrels(probe, s * 100, random), probe, (dimension, position) -> true));
            }
            Run run = drive(scans);
            record("stock-scan-4x512-barrels-" + phase, run);
            assertEquals(4 * 512, run.reports()); assertEquals(4 * 512, run.checked());
            assertTrue(run.maxContainers() <= StockLimits.CONTAINERS_PER_TICK, "Container budget exceeded: " + run.maxContainers());
            // 2048 containers at 8 per tick: the container count, not time, bounds the tick count on any host fast enough to read 8 per ms.
            assertTrue(run.ticks() >= 4 * 512 / StockLimits.CONTAINERS_PER_TICK);
            assertTrue(run.tickStats().p50Nanos() < 50_000_000L, "A median scan tick used a whole server tick: " + run.tickStats().format());
        }
        var probe = new SlotProbe();
        Run worst = drive(List.of(new StockScan(DIMENSION, doubleChests(probe, 0), probe, (dimension, position) -> true)));
        record("stock-scan-256-double-chests-54-distinct", worst);
        assertEquals(512, worst.reports()); assertEquals(256, worst.checked());
        assertTrue(worst.maxContainers() <= StockLimits.CONTAINERS_PER_TICK);
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** 20 players each start one scan at the same moment and re-click once a second until admitted. */
    @Test void twentyPlayersAreServedFourAtATimeWithCooldown() {
        for (int positions : List.of(64, StockLimits.MAX_POSITIONS)) {
            var clock = new MutableClock(); var admission = new StockAdmission(clock); var scheduler = new StockScheduler();
            Map<UUID, StockScan> running = new HashMap<>(); Map<UUID, Integer> admittedAt = new HashMap<>(), finishedAt = new HashMap<>();
            List<UUID> players = new ArrayList<>(); for (int i = 0; i < 20; i++) players.add(new UUID(0x5A, i));
            Random random = new Random(positions);
            int tick = 0, capacityRefusals = 0, maxRunning = 0;
            while (finishedAt.size() < players.size()) {
                if (tick % 20 == 0) for (UUID player : players) {
                    if (admittedAt.containsKey(player)) continue;
                    var verdict = admission.admit(player);
                    if (verdict == StockAdmission.Verdict.CAPACITY) { capacityRefusals++; continue; }
                    assertEquals(StockAdmission.Verdict.ADMITTED, verdict);
                    var probe = new SlotProbe();
                    var scan = new StockScan(DIMENSION, barrels(probe, 0, random).subList(0, positions), probe, (dimension, position) -> true);
                    running.put(player, scan); scheduler.add(scan); admittedAt.put(player, tick);
                    assertEquals(StockAdmission.Verdict.COOLDOWN, admission.admit(player), "A second start inside the cooldown must be refused");
                }
                maxRunning = Math.max(maxRunning, admission.running());
                assertTrue(scheduler.tick() <= StockLimits.CONTAINERS_PER_TICK);
                for (var entry : new ArrayList<>(running.entrySet())) {
                    for (int frame = 0; frame < StockLimits.FRAMES_PER_TICK && entry.getValue().poll() != null; frame++) {}
                    if (entry.getValue().finished()) { admission.finish(entry.getKey()); running.remove(entry.getKey()); finishedAt.put(entry.getKey(), tick); }
                }
                tick++; clock.now = clock.now.plusMillis(50);
                assertTrue(tick < 1_000_000, "admission never drained");
            }
            assertTrue(maxRunning <= StockLimits.CONCURRENT_SCANS, "More than four concurrent scans: " + maxRunning);
            int[] waits = players.stream().mapToInt(admittedAt::get).sorted().toArray();
            int[] done = players.stream().mapToInt(finishedAt::get).sorted().toArray();
            Perf5A.record("stock-admission-20-players", "positions", positions, "concurrent", StockLimits.CONCURRENT_SCANS,
                "cooldownS", StockLimits.COOLDOWN.toSeconds(), "capacityRefusals", capacityRefusals,
                "firstFinishS", seconds(done[0]), "lastAdmitS", seconds(waits[waits.length - 1]), "lastFinishS", seconds(done[done.length - 1]),
                "medianFinishS", seconds(done[done.length / 2]));
        }
    }
    private static String seconds(int ticks) { return String.format(Locale.ROOT, "%.2f", ticks / 20.0); }
}
