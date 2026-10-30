package dev.mcmateriallist.core.location;

import java.util.Comparator;

/** A registered block position; the client layer decides which block ids are supported containers. */
public record ContainerPosition(int x, int y, int z, String blockId) {
    public static final Comparator<ContainerPosition> ORDER =
        Comparator.comparingInt(ContainerPosition::x).thenComparingInt(ContainerPosition::y).thenComparingInt(ContainerPosition::z);
    public ContainerPosition {
        if (Math.abs((long) x) > LocationLimits.MAX_HORIZONTAL || Math.abs((long) z) > LocationLimits.MAX_HORIZONTAL
            || y < LocationLimits.MIN_Y || y > LocationLimits.MAX_Y) throw new IllegalArgumentException("Container position out of range");
        if (!LocationLimits.namespacedId(blockId)) throw new IllegalArgumentException("Invalid block id");
    }
    public boolean at(int px, int py, int pz) { return x == px && y == py && z == pz; }
    public boolean sameCoordinates(ContainerPosition other) { return at(other.x, other.y, other.z); }
}
