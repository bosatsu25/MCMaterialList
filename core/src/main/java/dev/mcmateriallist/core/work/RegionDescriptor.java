package dev.mcmateriallist.core.work;

import java.util.Objects;

/** Original schematic coordinates, independent of placement world origin/enabled state. */
public record RegionDescriptor(String key, Vector3 relativeOrigin, Vector3 size) {
    public RegionDescriptor {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(relativeOrigin, "relativeOrigin"); Objects.requireNonNull(size, "size");
        if (key.isBlank() || !TextLimits.valid(key, 4096)) throw new IllegalArgumentException("Invalid region key");
        for (int axis : new int[]{size.x(), size.y(), size.z()}) {
            if (axis == 0 || axis == Integer.MIN_VALUE) throw new IllegalArgumentException("Invalid region dimension");
        }
    }
}
