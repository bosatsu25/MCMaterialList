package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerKind;
import dev.mcmateriallist.core.location.LocationLimits;
import java.util.Map;
import java.util.Objects;

/**
 * One location of one scan. index is the position's index in the scanned profile generation; kind comes from the registered
 * block id. counts maps item registry id (components ignored, as MaterialTaskId) to a non-negative count.
 */
public record LocationReport(int index, LocationStatus status, ContainerKind kind, Map<String, Long> counts) {
    public LocationReport {
        Objects.requireNonNull(status, "status"); Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(counts, "counts");
        if (index < 0 || index >= StockLimits.MAX_POSITIONS) throw new IllegalArgumentException("Location index out of range");
        if (!status.read() && !counts.isEmpty()) throw new IllegalArgumentException("Only a read location carries counts");
        if (!validCounts(counts)) throw new IllegalArgumentException("Invalid item counts");
        counts = Map.copyOf(counts);
    }
    public static LocationReport empty(int index, LocationStatus status, ContainerKind kind) { return new LocationReport(index, status, kind, Map.of()); }
    public long count(String itemId) { return counts.getOrDefault(itemId, 0L); }
    /** At most 1024 distinct namespaced ids of at most 256 UTF-8 bytes, each with a non-negative count. */
    public static boolean validCounts(Map<String, Long> counts) {
        if (counts == null || counts.size() > StockLimits.MAX_ITEMS) return false;
        for (var entry : counts.entrySet())
            if (!LocationLimits.namespacedId(entry.getKey()) || entry.getValue() == null || entry.getValue() < 0) return false;
        return true;
    }
}
