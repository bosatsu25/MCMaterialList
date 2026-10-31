package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerBlocks;
import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.core.location.LocationProfileId;
import dev.mcmateriallist.core.location.WorldIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Client-thread owner of Check Materials results for the selected profile. Memory only: nothing about inventories is written to disk.
 * Readings are kept per registered position rather than index, so they survive a generation change and a later scan as "previous".
 */
public final class StockLedger {
    private final Clock clock;
    private WorldIdentity world;
    private LocationProfileId profileId;
    private long generation;
    private List<ContainerPosition> positions = List.of();
    private Instant startedAt, completedAt;
    private final Map<Integer, LocationReport> reports = new HashMap<>();
    private final Map<ContainerPosition, StockReading> readings = new HashMap<>();
    /** The block positions each kept reading covers: both halves when it was read as a double chest, else its own position. */
    private final Map<ContainerPosition, List<ContainerPosition>> spans = new HashMap<>();

    public StockLedger(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }

    /** Starts a scan of this generation. Another world or profile discards everything first; readings of removed positions are dropped. */
    public void begin(LocationProfile profile) {
        if (!profile.world().equals(world) || !profile.id().equals(profileId)) discard();
        world = profile.world(); profileId = profile.id(); generation = profile.generation(); positions = profile.positions();
        readings.keySet().retainAll(new HashSet<>(positions)); spans.keySet().retainAll(readings.keySet());
        reports.clear(); startedAt = clock.instant(); completedAt = null;
    }
    /** A server frame, timed by the ledger clock. */
    public boolean accept(LocationReport report) { return accept(report, clock.instant()); }
    /** False (and ignored) for a report outside the running scan, a repeated index, or a kind that differs from the registered block. */
    public boolean accept(LocationReport report, Instant at) { return accept(report, at, null); }
    /**
     * halves are the block positions this reading covers (both halves of a double chest); null means the registered position only.
     * A new reading supersedes every kept reading whose covered positions overlap it, so a combined double-chest reading and a later
     * reading of one of its halves are never both counted.
     */
    public boolean accept(LocationReport report, Instant at, List<ContainerPosition> halves) {
        Objects.requireNonNull(report, "report"); Objects.requireNonNull(at, "at");
        if (startedAt == null || completedAt != null || report.index() >= positions.size() || reports.containsKey(report.index())) return false;
        ContainerPosition position = positions.get(report.index());
        if (report.kind() != ContainerBlocks.kind(position.blockId())) return false;
        reports.put(report.index(), report);
        if (report.status().read()) {
            List<ContainerPosition> covered = halves == null || halves.isEmpty() ? List.of(position) : List.copyOf(halves);
            readings.keySet().removeIf(key -> !key.equals(position) && spans.getOrDefault(key, List.of(key)).stream().anyMatch(old -> covers(covered, old)));
            spans.keySet().retainAll(readings.keySet());
            readings.put(position, StockReading.of(report, at)); spans.put(position, covered);
        }
        // DUPLICATE: its contents belong to another index now. CHANGED: the registered container is gone (e.g. a broken half of a
        // double chest whose combined reading would otherwise be counted next to the remaining half). Either way drop the old reading.
        else if (report.status() == LocationStatus.DUPLICATE || report.status() == LocationStatus.CHANGED) { readings.remove(position); spans.remove(position); }
        return true;
    }
    /** Done or cancelled: unreported locations stay unchecked. */
    public void complete() { if (scanning()) completedAt = clock.instant(); }
    public boolean scanning() { return startedAt != null && completedAt == null; }
    public void discard() {
        world = null; profileId = null; generation = 0; positions = List.of(); startedAt = completedAt = null; reports.clear(); readings.clear(); spans.clear();
    }
    /**
     * Empty without a scan; a different world or profile discards everything. A stale scan (five minutes, or another generation) is
     * demoted: nothing is CHECKED and every kept reading of the current positions becomes previous.
     */
    public Optional<StockSnapshot> snapshot(WorldIdentity currentWorld, LocationProfile current) {
        if (startedAt == null) return Optional.empty();
        if (!world.equals(currentWorld) || !current.world().equals(world) || !current.id().equals(profileId)) { discard(); return Optional.empty(); }
        Map<Integer, StockReading> previous = new HashMap<>();
        if (new StockSnapshot(profileId, generation, world, positions, startedAt, completedAt, List.of(), Map.of()).stale(clock.instant(), current.generation())) {
            for (int i = 0; i < current.positions().size(); i++) { StockReading reading = readings.get(current.positions().get(i)); if (reading != null) previous.put(i, reading); }
            return Optional.of(new StockSnapshot(profileId, current.generation(), world, current.positions(), startedAt, completedAt, List.of(), previous));
        }
        for (int i = 0; i < positions.size(); i++) {
            LocationReport report = reports.get(i); StockReading reading = readings.get(positions.get(i));
            if (reading != null && (report == null || report.status() != LocationStatus.CHECKED && report.status() != LocationStatus.DUPLICATE)) previous.put(i, reading);
        }
        return Optional.of(new StockSnapshot(profileId, generation, world, positions, startedAt, completedAt, List.copyOf(reports.values()), previous));
    }
    private static boolean covers(List<ContainerPosition> covered, ContainerPosition at) {
        return covered.stream().anyMatch(half -> half.x() == at.x() && half.y() == at.y() && half.z() == at.z());
    }
}
