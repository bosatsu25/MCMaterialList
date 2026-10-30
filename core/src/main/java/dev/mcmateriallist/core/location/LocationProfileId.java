package dev.mcmateriallist.core.location;

import java.util.Objects;
import java.util.UUID;

/** Opaque profile identity; the only profile input to a filesystem path. */
public record LocationProfileId(UUID value) {
    public LocationProfileId { Objects.requireNonNull(value, "value"); }
    public static LocationProfileId create() { return new LocationProfileId(UUID.randomUUID()); }
    public static LocationProfileId parse(String text) { return new LocationProfileId(LocationLimits.canonicalUuid(text)); }
    @Override public String toString() { return value.toString(); }
}
