package dev.mcmateriallist.fabric.client.litematica;

import dev.mcmateriallist.core.LocalPlacementId;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.Objects;

/** Wraps upstream's random UUID, persisted by its placement JSON hooks. */
public final class PlacementIdentity {
    private PlacementIdentity() {}

    public static LocalPlacementId get(SchematicPlacement placement, Iterable<SchematicPlacement> activePlacements) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(activePlacements, "activePlacements");
        if (!placement.shouldBeSaved() || placement.getSchematicFile() == null) {
            throw new IllegalStateException("Save the schematic and placement before using persistent identity");
        }
        LocalPlacementId id = new LocalPlacementId(placement.getHashId());
        for (SchematicPlacement other : activePlacements) {
            if (other != placement && id.value().equals(other.getHashId())) {
                throw new IllegalStateException("Duplicate placement identity: recreate the copied placement before continuing");
            }
        }
        return id;
    }

    /**
     * Whether placement still holds exactly this identity without throwing: saved, file-backed, registered among
     * activePlacements, carrying expected's UUID and not sharing it with another active placement (#14). Cheap enough
     * for render-time readiness checks.
     */
    public static boolean holds(SchematicPlacement placement, Iterable<SchematicPlacement> activePlacements, LocalPlacementId expected) {
        if (placement == null || activePlacements == null || expected == null) return false;
        if (!placement.shouldBeSaved() || placement.getSchematicFile() == null || !expected.value().equals(placement.getHashId())) return false;
        boolean registered = false;
        for (SchematicPlacement other : activePlacements) {
            if (other == placement) registered = true;
            else if (expected.value().equals(other.getHashId())) return false;
        }
        return registered;
    }
}
