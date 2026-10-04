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
}
