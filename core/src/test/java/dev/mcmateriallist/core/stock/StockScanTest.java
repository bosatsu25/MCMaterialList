package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerKind;
import dev.mcmateriallist.core.location.ContainerPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static dev.mcmateriallist.core.stock.FakeProbe.key;
import static org.junit.jupiter.api.Assertions.*;

class StockScanTest {
    private static final String OVERWORLD = "minecraft:overworld", CHEST = "minecraft:chest";
    private static final StockScan.Access ALL = (dimension, position) -> true;

    @Test void joinedSinglesAreReadOnceAndTheLaterIndexIsDuplicate() {
        var world = new FakeProbe().block(0, 64, 0, CHEST, Map.of("minecraft:stone", 10L)).block(1, 64, 0, CHEST, Map.of("minecraft:stone", 5L, "minecraft:dirt", 1L));
        world.join(key(0, 64, 0), key(1, 64, 0));
        var reports = run(new StockScan(OVERWORLD, List.of(chest(0, 64, 0), chest(1, 64, 0)), world, ALL));
        assertEquals(LocationStatus.CHECKED, reports.get(0).status()); assertEquals(Map.of("minecraft:stone", 15L, "minecraft:dirt", 1L), reports.get(0).counts());
        assertEquals(LocationStatus.DUPLICATE, reports.get(1).status()); assertTrue(reports.get(1).counts().isEmpty());
        assertEquals(1, world.calls.stream().filter(c -> c.startsWith("read")).count());
    }
    @Test void reRegisteredOtherHalfIsDuplicateWhicheverHalfComesFirst() {
        var world = new FakeProbe().block(4, 64, -2, CHEST, Map.of("minecraft:stone", 3L)).block(5, 64, -2, CHEST, Map.of("minecraft:stone", 4L));
        world.join(key(4, 64, -2), key(5, 64, -2));
        // Profiles sort positions, but the request order is what decides which index reads.
        var reports = run(new StockScan(OVERWORLD, List.of(chest(5, 64, -2), chest(4, 64, -2)), world, ALL));
        assertEquals(7L, reports.get(0).count("minecraft:stone")); assertEquals(LocationStatus.DUPLICATE, reports.get(1).status());
    }
    @Test void splitChestReadsOnlyTheRegisteredHalf() {
        var world = new FakeProbe().block(0, 64, 0, CHEST, Map.of("minecraft:stone", 10L)).block(1, 64, 0, CHEST, Map.of("minecraft:stone", 5L));
        var reports = run(new StockScan(OVERWORLD, List.of(chest(0, 64, 0)), world, ALL));
        assertEquals(Map.of("minecraft:stone", 10L), reports.get(0).counts());
        assertEquals(List.of("shape [0, 64, 0]", "read [[0, 64, 0]]"), world.calls);
        // Both halves registered and later split: two separate units.
        var both = run(new StockScan(OVERWORLD, List.of(chest(0, 64, 0), chest(1, 64, 0)), world, ALL));
        assertEquals(10L, both.get(0).count("minecraft:stone")); assertEquals(5L, both.get(1).count("minecraft:stone"));
    }
    @Test void unloadedChunkOrPartnerIsUnloadedAndNeverHalfAChest() {
        var world = new FakeProbe().block(0, 64, 0, CHEST, Map.of("minecraft:stone", 10L)).block(1, 64, 0, CHEST, Map.of("minecraft:stone", 5L))
            .block(900, 64, 0, CHEST, Map.of("minecraft:stone", 1L));
        world.join(key(0, 64, 0), key(1, 64, 0)); world.unloaded.add(key(1, 64, 0)); world.unloaded.add(key(900, 64, 0));
        var reports = run(new StockScan(OVERWORLD, List.of(chest(0, 64, 0), chest(1, 64, 0), chest(900, 64, 0)), world, ALL));
        assertEquals(List.of(LocationStatus.UNLOADED, LocationStatus.UNLOADED, LocationStatus.UNLOADED), statuses(reports));
        assertTrue(world.calls.stream().noneMatch(c -> c.startsWith("read")));
    }
    @Test void changedBlockUnsupportedIdAndUnreadableContents() {
        var world = new FakeProbe().block(0, 64, 0, "minecraft:barrel", Map.of()).block(2, 64, 0, CHEST, Map.of()).block(3, 64, 0, "minecraft:hopper", Map.of("minecraft:stone", 2L));
        world.unreadable.add(key(2, 64, 0));
        var reports = run(new StockScan(OVERWORLD, List.of(chest(0, 64, 0), chest(1, 64, 0), new ContainerPosition(9, 64, 0, "minecraft:stone"), chest(2, 64, 0),
            new ContainerPosition(3, 64, 0, "minecraft:hopper")), world, ALL));
        assertEquals(List.of(LocationStatus.CHANGED, LocationStatus.CHANGED, LocationStatus.CHANGED, LocationStatus.UNREADABLE, LocationStatus.CHECKED), statuses(reports));
        assertEquals(ContainerKind.MACHINE, reports.get(4).kind());
        assertFalse(world.calls.contains("shape [9, 64, 0]"));
    }
    @Test void tooManyItemsOrAFrameThatCannotFitIsUnreadable() {
        Map<String, Long> many = new HashMap<>(), long_ = new HashMap<>();
        for (int i = 0; i <= StockLimits.MAX_ITEMS; i++) many.put("minecraft:item_" + i, 1L);
        for (int i = 0; i < 300; i++) long_.put("minecraft:" + "x".repeat(200) + i, 1L);
        var world = new FakeProbe().block(0, 64, 0, CHEST, many).block(1, 64, 0, CHEST, long_).block(2, 64, 0, CHEST, Map.of("minecraft:stone", -1L))
            .block(3, 64, 0, CHEST, Map.of());
        var reports = run(new StockScan(OVERWORLD, List.of(chest(0, 64, 0), chest(1, 64, 0), chest(2, 64, 0), chest(3, 64, 0)), world, ALL));
        assertEquals(List.of(LocationStatus.UNREADABLE, LocationStatus.UNREADABLE, LocationStatus.UNREADABLE, LocationStatus.CHECKED), statuses(reports));
        // An empty container is a real zero, which only CHECKED can say.
        assertTrue(reports.get(3).counts().isEmpty());
    }
    @Test void repeatedRequestPositionsCollapseWithoutAProbeCall() {
        var world = new FakeProbe().block(0, 64, 0, CHEST, Map.of("minecraft:stone", 1L));
        var reports = run(new StockScan(OVERWORLD, List.of(chest(0, 64, 0), chest(0, 64, 0), chest(0, 64, 0)), world, ALL));
        assertEquals(List.of(LocationStatus.CHECKED, LocationStatus.DUPLICATE, LocationStatus.DUPLICATE), statuses(reports));
        assertEquals(1, world.calls.stream().filter(c -> c.startsWith("shape")).count());
        assertThrows(IllegalArgumentException.class, () -> new StockScan(OVERWORLD, java.util.Collections.nCopies(StockLimits.MAX_POSITIONS + 1, chest(0, 64, 0)), world, ALL));
        assertThrows(IllegalArgumentException.class, () -> new StockScan("Overworld", List.of(), world, ALL));
    }
    @Test void unauthorizedPositionsAreNeverProbedAndRevocationAppliesBeforeTheNextContainer() {
        var world = new FakeProbe().block(0, 64, 0, CHEST, Map.of("minecraft:stone", 1L)).block(1, 64, 5, CHEST, Map.of("minecraft:stone", 2L))
            .block(2, 64, 9, CHEST, Map.of("minecraft:stone", 3L));
        AtomicBoolean granted = new AtomicBoolean(true);
        StockScan.Access access = (dimension, p) -> p.x() != 1 && granted.get();
        var scan = new StockScan(OVERWORLD, List.of(chest(0, 64, 0), chest(1, 64, 5), chest(2, 64, 9)), world, access);
        // Planning already answered the unauthorized index, before any world access.
        assertEquals(LocationStatus.UNAUTHORIZED, scan.poll().status()); assertTrue(world.calls.isEmpty());
        scan.step(); assertEquals(LocationStatus.CHECKED, scan.poll().status());
        granted.set(false); scan.step();
        LocationReport revoked = scan.poll(); assertEquals(2, revoked.index()); assertEquals(LocationStatus.UNAUTHORIZED, revoked.status());
        assertEquals(List.of("shape [0, 64, 0]", "read [[0, 64, 0]]"), world.calls);
        assertTrue(scan.finished()); assertEquals(new StockScan.Summary(3, 1, 0, 0, false), scan.summary());
    }
    @Test void cancelStopsBeforeTheNextContainer() {
        var world = new FakeProbe().block(0, 64, 0, CHEST, Map.of()).block(5, 64, 0, CHEST, Map.of());
        var scan = new StockScan(OVERWORLD, List.of(chest(0, 64, 0), chest(5, 64, 0)), world, ALL);
        scan.step(); scan.cancel(); scan.step();
        assertNull(scan.poll()); assertTrue(scan.finished()); assertTrue(scan.summary().cancelled());
        assertEquals(1, world.calls.stream().filter(c -> c.startsWith("shape")).count());
    }

    static ContainerPosition chest(int x, int y, int z) { return new ContainerPosition(x, y, z, CHEST); }
    private static List<LocationReport> run(StockScan scan) {
        while (!scan.done()) scan.step();
        List<LocationReport> reports = new ArrayList<>(); for (LocationReport r; (r = scan.poll()) != null; ) reports.add(r);
        reports.sort(java.util.Comparator.comparingInt(LocationReport::index)); return reports;
    }
    private static List<LocationStatus> statuses(List<LocationReport> reports) { return reports.stream().map(LocationReport::status).toList(); }
}
