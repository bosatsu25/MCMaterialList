package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable grant registry of one project; every change returns the next generation, and an unchanged result returns this.
 * Grants are unique by dimension and coordinates and sorted by StockGrant.ORDER.
 */
public record StockGrants(UUID projectId, long generation, List<StockGrant> grants) {
    public StockGrants {
        Objects.requireNonNull(projectId, "projectId");
        if (generation < 0) throw new IllegalArgumentException("Negative generation");
        if (grants.size() > StockLimits.MAX_GRANTS) throw new IllegalArgumentException("Too many grants");
        List<StockGrant> sorted = new ArrayList<>(grants); sorted.sort(StockGrant.ORDER);
        for (int i = 1; i < sorted.size(); i++) if (StockGrant.ORDER.compare(sorted.get(i - 1), sorted.get(i)) == 0) throw new IllegalArgumentException("Duplicate grant");
        grants = List.copyOf(sorted);
    }
    /** The implicit state of a project without a grant file. */
    public static StockGrants empty(UUID projectId) { return new StockGrants(projectId, 0, List.of()); }
    public boolean grants(String dimension, ContainerPosition position) { return grants.stream().anyMatch(g -> g.at(dimension, position)); }
    public List<StockGrant> in(String dimension) { return grants.stream().filter(g -> g.dimension().equals(dimension)).toList(); }
    /** Existing grants keep their approver and time. Throws IllegalArgumentException beyond MAX_GRANTS. */
    public StockGrants grant(String dimension, List<ContainerPosition> positions, UUID approver, long grantedAt) {
        List<StockGrant> next = new ArrayList<>(grants);
        for (ContainerPosition position : positions)
            if (next.stream().noneMatch(g -> g.at(dimension, position))) next.add(new StockGrant(dimension, position.x(), position.y(), position.z(), approver, grantedAt));
        return next.size() == grants.size() ? this : new StockGrants(projectId, Math.incrementExact(generation), next);
    }
    public StockGrants revoke(String dimension, List<ContainerPosition> positions) {
        List<StockGrant> next = new ArrayList<>(grants); next.removeIf(g -> positions.stream().anyMatch(p -> g.at(dimension, p)));
        return next.size() == grants.size() ? this : new StockGrants(projectId, Math.incrementExact(generation), next);
    }
}
