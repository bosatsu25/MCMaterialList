package dev.mcmateriallist.core.location;

import java.util.Objects;

/** Per-world current profile choice; a null profile means nothing is selected. */
public record LocationSelection(WorldIdentity world, LocationProfileId profile, long generation) {
    public LocationSelection {
        Objects.requireNonNull(world, "world");
        if (generation < 0) throw new IllegalArgumentException("Negative generation");
    }
    public LocationSelection select(LocationProfileId next) { return new LocationSelection(world, next, Math.incrementExact(generation)); }
}
