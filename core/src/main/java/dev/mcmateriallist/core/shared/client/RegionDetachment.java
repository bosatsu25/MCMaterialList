package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.shared.ProjectSnapshot;
import dev.mcmateriallist.core.work.*;
import java.util.*;

/** Issue #11: the one-way shared-to-local region copy. Nothing reads a detached copy back into shared state (I5). */
public final class RegionDetachment {
    private RegionDetachment() {}
    /**
     * Definitions are the manifest's region mapping; states are the confirmed server rows unchanged (row versions included,
     * as provenance only); review markers are the server's reconfirmation set restricted to regions. No history, generation 0.
     */
    public static RegionWorkDataset copy(DatasetId newId, LocalPlacementId placement, ProjectSnapshot snapshot) {
        Objects.requireNonNull(newId); Objects.requireNonNull(placement); Objects.requireNonNull(snapshot);
        if (newId.value().equals(snapshot.scope().regionListId())) throw new IllegalArgumentException("Detached copy reuses the shared list identity");
        Map<RegionTaskId, RegionDefinition> definitions = new HashMap<>(); Map<RegionTaskId, TaskState> states = new HashMap<>();
        snapshot.manifest().regions().forEach((id, descriptor) -> {
            definitions.put(id, new RegionDefinition(id, descriptor)); states.put(id, Objects.requireNonNull(snapshot.rows().get(id)));
        });
        Set<RegionTaskId> review = new HashSet<>();
        snapshot.needsReconfirmation().forEach(id -> { if (id instanceof RegionTaskId region) review.add(region); });
        return new RegionWorkDataset(newId, placement, 0, definitions, states, Map.of(), review);
    }
}
