package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerKind;
import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.core.location.WorldIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockLedgerTest {
    private static final String STONE = "minecraft:stone";
    private final Instant start = Instant.parse("2026-10-30T12:00:00Z");
    private final Instant[] now = {start};
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now[0]; }
    };
    private final WorldIdentity world = new WorldIdentity(UUID.randomUUID());
    // Index 0 chest, 1 chest, 2 hopper, 3 barrel, 4 chest (the other half of index 0).
    private final LocationProfile profile = LocationProfile.create(world, "minecraft:overworld", "taichi_ms")
        .withContainer(new ContainerPosition(0, 64, 0, "minecraft:chest")).withContainer(new ContainerPosition(1, 64, 5, "minecraft:chest"))
        .withContainer(new ContainerPosition(2, 64, 0, "minecraft:hopper")).withContainer(new ContainerPosition(3, 64, 0, "minecraft:barrel"))
        .withContainer(new ContainerPosition(4, 64, 0, "minecraft:chest"));

    @Test void aggregationSeparatesMachinesAndNeverCountsUncheckedAsZero() {
        var ledger = new StockLedger(clock); ledger.begin(profile);
        assertTrue(ledger.accept(checked(0, STONE, 10))); assertTrue(ledger.accept(LocationReport.empty(1, LocationStatus.UNLOADED, ContainerKind.STORAGE)));
        assertTrue(ledger.accept(new LocationReport(2, LocationStatus.CHECKED, ContainerKind.MACHINE, Map.of(STONE, 5L))));
        var snapshot = ledger.snapshot(world, profile).orElseThrow();
        assertEquals(10, snapshot.confirmed(STONE)); assertEquals(5, snapshot.machine(STONE)); assertEquals(0, snapshot.confirmed("minecraft:dirt"));
        // Two checked of five; the unloaded and the two unreported locations are unchecked, not zero.
        assertEquals(new StockSnapshot.Coverage(2, 5), snapshot.coverage()); assertEquals(3, snapshot.unchecked()); assertFalse(snapshot.complete());
        ledger.accept(LocationReport.empty(3, LocationStatus.UNREADABLE, ContainerKind.STORAGE)); ledger.accept(LocationReport.empty(4, LocationStatus.DUPLICATE, ContainerKind.STORAGE));
        now[0] = start.plusSeconds(3); ledger.complete();
        snapshot = ledger.snapshot(world, profile).orElseThrow();
        assertEquals(new StockSnapshot.Coverage(2, 4), snapshot.coverage()); assertEquals(2, snapshot.unchecked()); assertEquals(start.plusSeconds(3), snapshot.completedAt());
        assertEquals(StockSnapshot.Previous.NONE, snapshot.previous(STONE));
        // Reports that do not fit the scan are ignored.
        assertFalse(ledger.accept(checked(1, STONE, 1)));
    }
    @Test void reportsAreValidatedAgainstTheScan() {
        var ledger = new StockLedger(clock);
        assertFalse(ledger.accept(checked(0, STONE, 1)));
        ledger.begin(profile);
        assertFalse(ledger.accept(checked(2, STONE, 1)));   // a hopper is never STORAGE
        assertFalse(ledger.accept(checked(9, STONE, 1)));
        assertTrue(ledger.accept(checked(0, STONE, 1))); assertFalse(ledger.accept(checked(0, STONE, 2)));
        assertThrows(IllegalArgumentException.class, () -> new LocationReport(0, LocationStatus.UNLOADED, ContainerKind.STORAGE, Map.of(STONE, 1L)));
        assertThrows(IllegalArgumentException.class, () -> new LocationReport(0, LocationStatus.CHECKED, ContainerKind.STORAGE, Map.of(STONE, -1L)));
        assertThrows(IllegalArgumentException.class, () -> new LocationReport(0, LocationStatus.CHECKED, ContainerKind.STORAGE, Map.of("Stone", 1L)));
        assertThrows(IllegalArgumentException.class, () -> new LocationReport(StockLimits.MAX_POSITIONS, LocationStatus.UNLOADED, ContainerKind.STORAGE, Map.of()));
    }
    @Test void aLaterScanThatCannotReachALocationShowsItsPreviousValue() {
        var ledger = new StockLedger(clock); ledger.begin(profile);
        ledger.accept(checked(0, STONE, 10)); ledger.accept(checked(1, STONE, 7)); ledger.complete();
        now[0] = start.plusSeconds(60); ledger.begin(profile);
        // Mid-scan, unreported locations already show their previous value.
        assertEquals(new StockSnapshot.Previous(17, 2, start), ledger.snapshot(world, profile).orElseThrow().previous(STONE));
        now[0] = start.plusSeconds(61);
        ledger.accept(LocationReport.empty(0, LocationStatus.UNLOADED, ContainerKind.STORAGE)); ledger.accept(checked(1, STONE, 8)); ledger.complete();
        var snapshot = ledger.snapshot(world, profile).orElseThrow();
        assertEquals(8, snapshot.confirmed(STONE)); assertEquals(new StockSnapshot.Previous(10, 1, start), snapshot.previous(STONE));
        assertEquals(1, snapshot.previousLocations()); assertEquals(4, snapshot.unchecked());
        // A duplicate's old value is folded into another index and is never shown again.
        now[0] = start.plusSeconds(70); ledger.begin(profile); ledger.accept(LocationReport.empty(0, LocationStatus.DUPLICATE, ContainerKind.STORAGE)); ledger.complete();
        assertEquals(new StockSnapshot.Previous(8, 1, start.plusSeconds(61)), ledger.snapshot(world, profile).orElseThrow().previous(STONE));
    }
    @Test void staleAfterFiveMinutesOrAGenerationChange() {
        var ledger = new StockLedger(clock); ledger.begin(profile); ledger.accept(checked(0, STONE, 10)); ledger.complete();
        now[0] = start.plus(StockLimits.FRESH);
        assertEquals(10, ledger.snapshot(world, profile).orElseThrow().confirmed(STONE));
        now[0] = start.plus(StockLimits.FRESH).plusMillis(1);
        var aged = ledger.snapshot(world, profile).orElseThrow();
        assertEquals(0, aged.confirmed(STONE)); assertEquals(new StockSnapshot.Previous(10, 1, start), aged.previous(STONE));
        assertEquals(new StockSnapshot.Coverage(0, 5), aged.coverage());
        // A new generation re-indexes by position: the reading follows its container, a removed container's reading is gone.
        now[0] = start.plusSeconds(1);
        var edited = profile.withContainer(new ContainerPosition(-5, 64, 0, "minecraft:barrel"));
        var moved = ledger.snapshot(world, edited).orElseThrow();
        assertEquals(edited.generation(), moved.profileGeneration()); assertEquals(0, moved.confirmed(STONE));
        assertEquals(10, moved.previous().get(1).counts().get(STONE)); assertTrue(moved.reports().isEmpty());
        assertTrue(moved.stale(now[0], profile.generation()) && !moved.stale(now[0], edited.generation()));
        ledger.begin(edited.withoutContainer(0, 64, 0)); assertEquals(StockSnapshot.Previous.NONE, ledger.snapshot(world, edited.withoutContainer(0, 64, 0)).orElseThrow().previous(STONE));
        assertTrue(Duration.between(start, now[0]).compareTo(StockLimits.FRESH) < 0);
    }
    @Test void anotherWorldOrProfileDiscardsEverything() {
        var ledger = new StockLedger(clock); ledger.begin(profile); ledger.accept(checked(0, STONE, 10)); ledger.complete();
        assertTrue(ledger.snapshot(new WorldIdentity(UUID.randomUUID()), profile).isEmpty());
        assertTrue(ledger.snapshot(world, profile).isEmpty());
        ledger.begin(profile); ledger.accept(checked(0, STONE, 10)); ledger.complete();
        var other = LocationProfile.create(world, "minecraft:overworld", "other");
        assertTrue(ledger.snapshot(world, other).isEmpty()); assertTrue(ledger.snapshot(world, profile).isEmpty());
        ledger.begin(profile); assertEquals(StockSnapshot.Previous.NONE, ledger.snapshot(world, profile).orElseThrow().previous(STONE));
    }
    @Test void observedIsNeverCheckedAndCarriesItsOwnTime() {
        var ledger = new StockLedger(clock); ledger.begin(profile);
        Instant opened = start.minusSeconds(30);
        assertTrue(ledger.accept(new LocationReport(1, LocationStatus.OBSERVED, ContainerKind.STORAGE, Map.of(STONE, 4L)), opened));
        ledger.accept(LocationReport.empty(0, LocationStatus.OTHER_DIMENSION, ContainerKind.STORAGE)); ledger.complete();
        var snapshot = ledger.snapshot(world, profile).orElseThrow();
        assertEquals(0, snapshot.confirmed(STONE)); assertEquals(new StockSnapshot.Previous(4, 1, opened), snapshot.previous(STONE));
        assertEquals(new StockSnapshot.Coverage(0, 5), snapshot.coverage());
        assertEquals(LocationStatus.OBSERVED, snapshot.report(1).orElseThrow().status());
    }

    private static LocationReport checked(int index, String item, long count) { return new LocationReport(index, LocationStatus.CHECKED, ContainerKind.STORAGE, Map.of(item, count)); }

    @Test void aCheckedMachineAloneLeavesStorageUncheckedAndMachineReadingsStaySeparate() {
        var ledger = new StockLedger(clock); ledger.begin(profile);
        // Only the hopper was read: warehouse stock is unknown, not 0.
        assertTrue(ledger.accept(new LocationReport(2, LocationStatus.CHECKED, ContainerKind.MACHINE, Map.of(STONE, 5L))));
        var snapshot = ledger.snapshot(world, profile).orElseThrow();
        assertEquals(new StockSnapshot.Coverage(1, 5), snapshot.coverage()); assertEquals(new StockSnapshot.Coverage(0, 4), snapshot.storageCoverage());
        ledger.complete();
        // A later scan that cannot reach the hopper keeps its reading as a previous machine count, never as storage.
        now[0] = start.plusSeconds(60); ledger.begin(profile);
        assertTrue(ledger.accept(LocationReport.empty(2, LocationStatus.UNLOADED, ContainerKind.MACHINE)));
        snapshot = ledger.snapshot(world, profile).orElseThrow();
        assertEquals(5, snapshot.previousMachine(STONE).count()); assertEquals(1, snapshot.previousMachine(STONE).locations());
        assertEquals(StockSnapshot.Previous.NONE, snapshot.previous(STONE)); assertEquals(0, snapshot.machine(STONE));
    }

    @Test void aReadingOfOneHalfSupersedesAKeptDoubleChestReading() {
        // Index 0 and 4 are the two halves; the double chest was observed as 15 under index 0.
        var ledger = new StockLedger(clock); ledger.begin(profile);
        var a = profile.positions().get(0); var b = profile.positions().get(4);
        assertTrue(ledger.accept(new LocationReport(0, LocationStatus.OBSERVED, ContainerKind.STORAGE, Map.of(STONE, 15L)), start, List.of(a, b)));
        assertTrue(ledger.accept(LocationReport.empty(4, LocationStatus.DUPLICATE, ContainerKind.STORAGE)));
        ledger.complete();
        // A was broken; the remaining chest B now holds 5. The old combined 15 must not be counted next to it.
        now[0] = start.plusSeconds(30); ledger.begin(profile);
        assertTrue(ledger.accept(new LocationReport(4, LocationStatus.OBSERVED, ContainerKind.STORAGE, Map.of(STONE, 5L)), now[0], List.of(b)));
        ledger.complete();
        var snapshot = ledger.snapshot(world, profile).orElseThrow();
        assertEquals(5, snapshot.previous(STONE).count()); assertEquals(1, snapshot.previous(STONE).locations());
    }

    @Test void aChangedLocationDropsItsKeptReading() {
        // Server path: the double chest was checked as 15 under index 0; then that half was broken and the server reports CHANGED.
        var ledger = new StockLedger(clock); ledger.begin(profile);
        assertTrue(ledger.accept(checked(0, STONE, 15))); assertTrue(ledger.accept(LocationReport.empty(4, LocationStatus.DUPLICATE, ContainerKind.STORAGE)));
        ledger.complete();
        now[0] = start.plusSeconds(30); ledger.begin(profile);
        assertTrue(ledger.accept(LocationReport.empty(0, LocationStatus.CHANGED, ContainerKind.STORAGE))); assertTrue(ledger.accept(checked(4, STONE, 5)));
        ledger.complete();
        var snapshot = ledger.snapshot(world, profile).orElseThrow();
        assertEquals(5, snapshot.confirmed(STONE)); assertEquals(StockSnapshot.Previous.NONE, snapshot.previous(STONE));
    }
}
