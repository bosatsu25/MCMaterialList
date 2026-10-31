package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerBlocks;
import dev.mcmateriallist.core.location.ContainerKind;
import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationLimits;
import dev.mcmateriallist.core.stock.protocol.StockProtocol;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One request's scan, stepped by StockScheduler on the server thread. Authorization is decided before any probe call, both when
 * the scan is planned and again before every container (R2, R3). Positions are processed in index order; a container is keyed by
 * its canonical position from live partner state, and any later index that reaches an already read half becomes DUPLICATE.
 * Holds no reference to work state: it only produces reports.
 */
public final class StockScan implements StockScheduler.Job {
    /** Live R1/R2 check. It must not consult the world. */
    @FunctionalInterface public interface Access { boolean allows(String dimension, ContainerPosition position); }
    public record Summary(int reports, int checked, int maxContainersPerTick, long maxNanosPerTick, boolean cancelled) {}
    private record Coordinates(int x, int y, int z) { static Coordinates of(ContainerPosition p) { return new Coordinates(p.x(), p.y(), p.z()); } }
    private final String dimension;
    private final List<ContainerPosition> positions;
    private final ContainerProbe probe;
    private final Access access;
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final ArrayDeque<LocationReport> output = new ArrayDeque<>();
    private final Set<Coordinates> read = new HashSet<>();
    private int reported, checked, maxContainers;
    private long maxNanos;
    private boolean cancelled;

    /** Same coordinates repeated in the request collapse to the first index; the rest are DUPLICATE without a probe call. */
    public StockScan(String dimension, List<ContainerPosition> positions, ContainerProbe probe, Access access) {
        if (!LocationLimits.namespacedId(dimension)) throw new IllegalArgumentException("Invalid dimension");
        this.positions = List.copyOf(positions);
        if (this.positions.size() > StockLimits.MAX_POSITIONS) throw new IllegalArgumentException("Too many positions");
        this.dimension = dimension; this.probe = Objects.requireNonNull(probe, "probe"); this.access = Objects.requireNonNull(access, "access");
        Set<Coordinates> requested = new HashSet<>();
        for (int i = 0; i < this.positions.size(); i++) {
            ContainerPosition position = this.positions.get(i);
            if (!requested.add(Coordinates.of(position))) emit(i, LocationStatus.DUPLICATE);
            else if (!access.allows(dimension, position)) emit(i, LocationStatus.UNAUTHORIZED);
            else pending.add(i);
        }
    }
    @Override public boolean done() { return cancelled || pending.isEmpty(); }
    @Override public void step() {
        if (done()) return;
        int index = pending.poll(); ContainerPosition position = positions.get(index);
        if (!access.allows(dimension, position)) { emit(index, LocationStatus.UNAUTHORIZED); return; }
        // The registered id is request data, so refusing it reveals nothing about the world.
        if (!ContainerBlocks.supported(position.blockId())) { emit(index, LocationStatus.CHANGED); return; }
        if (!(probe.shape(dimension, position) instanceof ContainerProbe.Present shape)) { emit(index, LocationStatus.UNLOADED); return; }
        if (!shape.blockId().equals(position.blockId())) { emit(index, LocationStatus.CHANGED); return; }
        List<ContainerPosition> halves;
        if (shape.partner() == null) halves = List.of(position);
        else if (!shape.partnerLoaded()) { emit(index, LocationStatus.UNLOADED); return; }  // never half a chest
        else {
            try { ContainerBlocks.canonical(position, shape.partner()); } catch (IllegalArgumentException inconsistent) { emit(index, LocationStatus.UNREADABLE); return; }
            halves = List.of(position, shape.partner());
        }
        if (halves.stream().map(Coordinates::of).anyMatch(read::contains)) { emit(index, LocationStatus.DUPLICATE); return; }
        halves.forEach(half -> read.add(Coordinates.of(half)));
        if (probe.read(dimension, halves) instanceof ContainerProbe.Contents contents && LocationReport.validCounts(contents.counts())) {
            LocationReport report = new LocationReport(index, LocationStatus.CHECKED, kind(position), contents.counts());
            if (StockProtocol.reportFits(report)) { output.add(report); return; }
        }
        emit(index, LocationStatus.UNREADABLE);
    }
    @Override public void served(int containers, long nanos) { maxContainers = Math.max(maxContainers, containers); maxNanos = Math.max(maxNanos, nanos); }
    /** Pending and undrained positions are never reported; the client keeps them unchecked. */
    public void cancel() { cancelled = true; pending.clear(); output.clear(); }
    /** The next report to stream, or null. Callers send at most FRAMES_PER_TICK per tick; the summary counts what was polled. */
    public LocationReport poll() {
        LocationReport report = output.poll();
        if (report != null) { reported++; if (report.status() == LocationStatus.CHECKED) checked++; }
        return report;
    }
    /** Done and every report drained. */
    public boolean finished() { return done() && output.isEmpty(); }
    public Summary summary() { return new Summary(reported, checked, maxContainers, maxNanos, cancelled); }
    public String dimension() { return dimension; }
    private void emit(int index, LocationStatus status) { output.add(LocationReport.empty(index, status, kind(positions.get(index)))); }
    private static ContainerKind kind(ContainerPosition position) { return ContainerBlocks.kind(position.blockId()); }
}
