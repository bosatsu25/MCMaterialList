package dev.mcmateriallist.core;

import java.util.Objects;
import java.util.UUID;

/** An opaque local placement identity, independent of names and coordinates. */
public record LocalPlacementId(UUID value) {
    public LocalPlacementId {
        Objects.requireNonNull(value, "value");
    }

    public static LocalPlacementId create() {
        return new LocalPlacementId(UUID.randomUUID());
    }

    public static LocalPlacementId parse(String text) {
        Objects.requireNonNull(text, "text");
        UUID value = UUID.fromString(text);
        if (!value.toString().equalsIgnoreCase(text)) {
            throw new IllegalArgumentException("Placement identity must be a canonical UUID");
        }
        return new LocalPlacementId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
