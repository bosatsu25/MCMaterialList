package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerKind;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A timed CHECKED or OBSERVED value of one position, kept in memory to show as "previous". */
public record StockReading(LocationStatus source, ContainerKind kind, Map<String, Long> counts, Instant at) {
    public StockReading {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(at, "at");
        if (!source.read() || !LocationReport.validCounts(counts)) throw new IllegalArgumentException("Invalid reading");
        counts = Map.copyOf(counts);
    }
    public static StockReading of(LocationReport report, Instant at) { return new StockReading(report.status(), report.kind(), report.counts(), at); }
}
