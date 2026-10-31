package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerBlocks;
import dev.mcmateriallist.core.location.ContainerKind;
import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationProfileId;
import dev.mcmateriallist.core.location.WorldIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable view of one scan against the profile generation it was requested for. positions are that generation's positions,
 * so report indices stay meaningful. A missing report is a location not reported yet: unchecked, never zero.
 * previous holds the latest earlier CHECKED or OBSERVED reading for locations that are not CHECKED (nor DUPLICATE) here.
 * completedAt is null while the scan runs.
 */
public record StockSnapshot(LocationProfileId profileId, long profileGeneration, WorldIdentity world, List<ContainerPosition> positions,
    Instant startedAt, Instant completedAt, List<LocationReport> reports, Map<Integer, StockReading> previous) {
    public record Coverage(int checked, int total) {
        public double ratio() { return total == 0 ? 0 : (double) checked / total; }
    }
    /** Storage count from earlier readings, with the oldest contributing time; oldest is null when nothing contributes. */
    public record Previous(long count, int locations, Instant oldest) {
        public static final Previous NONE = new Previous(0, 0, null);
    }
    public StockSnapshot {
        Objects.requireNonNull(profileId, "profileId"); Objects.requireNonNull(world, "world"); Objects.requireNonNull(startedAt, "startedAt");
        positions = List.copyOf(positions); previous = Map.copyOf(previous);
        if (profileGeneration < 0 || positions.size() > StockLimits.MAX_POSITIONS) throw new IllegalArgumentException("Invalid stock snapshot");
        if (completedAt != null && completedAt.isBefore(startedAt)) throw new IllegalArgumentException("Completed before started");
        List<LocationReport> sorted = new ArrayList<>(reports); sorted.sort(Comparator.comparingInt(LocationReport::index));
        for (int i = 0; i < sorted.size(); i++) {
            LocationReport report = sorted.get(i);
            if (report.index() >= positions.size() || i > 0 && sorted.get(i - 1).index() == report.index()) throw new IllegalArgumentException("Invalid report index");
            if (report.kind() != kindOf(positions.get(report.index()))) throw new IllegalArgumentException("Report kind differs from the registered block");
        }
        reports = List.copyOf(sorted);
        for (var entry : previous.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= positions.size() || entry.getValue().kind() != kindOf(positions.get(index))) throw new IllegalArgumentException("Invalid previous index");
            LocationStatus now = status(reports, index);
            if (now == LocationStatus.CHECKED || now == LocationStatus.DUPLICATE) throw new IllegalArgumentException("Previous shadows a current reading");
        }
    }
    public Optional<LocationReport> report(int index) { return reports.stream().filter(r -> r.index() == index).findFirst(); }
    public boolean complete() { return completedAt != null; }
    /** CHECKED storage only. */
    public long confirmed(String itemId) { return checked(itemId, ContainerKind.STORAGE); }
    /** CHECKED hoppers, droppers and dispensers; never part of confirmed(). */
    public long machine(String itemId) { return checked(itemId, ContainerKind.MACHINE); }
    /** Earlier storage readings of locations that are unchecked now, including this snapshot's OBSERVED locations. */
    public Previous previous(String itemId) { return previous(itemId, ContainerKind.STORAGE); }
    /** The same for hoppers, droppers and dispensers; never part of previous(). */
    public Previous previousMachine(String itemId) { return previous(itemId, ContainerKind.MACHINE); }
    private Previous previous(String itemId, ContainerKind kind) {
        long count = 0; int locations = 0; Instant oldest = null;
        for (StockReading reading : previous.values()) {
            if (reading.kind() != kind || !reading.counts().containsKey(itemId)) continue;
            count = saturated(count, reading.counts().get(itemId)); locations++;
            if (oldest == null || reading.at().isBefore(oldest)) oldest = reading.at();
        }
        return locations == 0 ? Previous.NONE : new Previous(count, locations, oldest);
    }
    /** Checked locations over distinct locations; a DUPLICATE is folded into another index and is not a location of its own. */
    public Coverage coverage() {
        int checked = 0, duplicates = 0;
        for (LocationReport report : reports) {
            if (report.status() == LocationStatus.CHECKED) checked++;
            else if (report.status() == LocationStatus.DUPLICATE) duplicates++;
        }
        return new Coverage(checked, positions.size() - duplicates);
    }
    /** Like coverage(), over storage locations only: whether any warehouse stock was actually read. */
    public Coverage storageCoverage() {
        int checked = 0, total = 0;
        for (int i = 0; i < positions.size(); i++) {
            if (kindOf(positions.get(i)) != ContainerKind.STORAGE) continue;
            LocationStatus status = status(reports, i);
            if (status == LocationStatus.DUPLICATE) continue;
            total++; if (status == LocationStatus.CHECKED) checked++;
        }
        return new Coverage(checked, total);
    }
    /** Distinct locations without a CHECKED report, including ones not reported yet. */
    public int unchecked() { Coverage coverage = coverage(); return coverage.total() - coverage.checked(); }
    public int previousLocations() { return previous.size(); }
    /** Stale once older than five minutes from its start, or when the profile generation has moved on. */
    public boolean stale(Instant now, long currentGeneration) {
        return currentGeneration != profileGeneration || Duration.between(startedAt, now).compareTo(StockLimits.FRESH) > 0;
    }
    private long checked(String itemId, ContainerKind kind) {
        long total = 0;
        for (LocationReport report : reports)
            if (report.status() == LocationStatus.CHECKED && report.kind() == kind) total = saturated(total, report.count(itemId));
        return total;
    }
    private static LocationStatus status(List<LocationReport> reports, int index) {
        for (LocationReport report : reports) if (report.index() == index) return report.status();
        return null;
    }
    private static ContainerKind kindOf(ContainerPosition position) { return ContainerBlocks.kind(position.blockId()); }
    private static long saturated(long a, long b) { long sum = a + b; return sum < a ? Long.MAX_VALUE : sum; }
}
