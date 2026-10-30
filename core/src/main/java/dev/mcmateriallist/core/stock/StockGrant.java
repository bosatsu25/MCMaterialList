package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationLimits;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** One granted canonical container position, with the approving server admin and the grant time in epoch milliseconds. */
public record StockGrant(String dimension, int x, int y, int z, UUID approver, long grantedAt) {
    public static final Comparator<StockGrant> ORDER = Comparator.comparing(StockGrant::dimension)
        .thenComparingInt(StockGrant::x).thenComparingInt(StockGrant::y).thenComparingInt(StockGrant::z);
    public StockGrant {
        Objects.requireNonNull(approver, "approver");
        if (!LocationLimits.namespacedId(dimension)) throw new IllegalArgumentException("Invalid dimension");
        if (Math.abs((long) x) > LocationLimits.MAX_HORIZONTAL || Math.abs((long) z) > LocationLimits.MAX_HORIZONTAL
            || y < LocationLimits.MIN_Y || y > LocationLimits.MAX_Y) throw new IllegalArgumentException("Grant position out of range");
        if (grantedAt < 0) throw new IllegalArgumentException("Invalid grant time");
    }
    public boolean at(String otherDimension, int px, int py, int pz) { return dimension.equals(otherDimension) && x == px && y == py && z == pz; }
    public boolean at(String otherDimension, ContainerPosition position) { return at(otherDimension, position.x(), position.y(), position.z()); }
}
