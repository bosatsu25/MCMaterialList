package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.*;
import java.util.*;

public record SharedManifest(long version, Definition definition, Map<RegionTaskId, RegionDescriptor> regions) {
    public record Baseline(String dimension, Vector3 origin, Rotation rotation, Mirror mirror) {
        public enum Rotation { NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90 }
        public enum Mirror { NONE, LEFT_RIGHT, FRONT_BACK }
        public Baseline {
            new MaterialTaskId(dimension); Objects.requireNonNull(origin);
            Objects.requireNonNull(rotation); Objects.requireNonNull(mirror);
        }
    }
    /** Submitted definitions only: client observed totals/missing/available have no field here. */
    public record Definition(String schematicDigest, Baseline baseline,
                             Map<MaterialTaskId, Long> materials, List<RegionDescriptor> regions) {
        public Definition {
            if (schematicDigest == null || !schematicDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Expected SHA-256 digest");
            Objects.requireNonNull(baseline);
            SharedLimits.count(materials.size(), SharedLimits.ROWS_PER_LIST);
            SharedLimits.count(regions.size(), SharedLimits.ROWS_PER_LIST);
            materials = Map.copyOf(materials); regions = List.copyOf(regions);
            if (materials.values().stream().anyMatch(n -> n < 0) || new HashSet<>(regions).size() != regions.size())
                throw new IllegalArgumentException("Invalid definitions");
        }
    }
    public SharedManifest {
        if (version < 0) throw new IllegalArgumentException("Invalid manifest version");
        Objects.requireNonNull(definition); regions = Map.copyOf(regions);
        if (regions.size() != definition.regions().size()
            || !new HashSet<>(regions.values()).equals(new HashSet<>(definition.regions())))
            throw new IllegalArgumentException("Invalid region mapping");
    }
    public static SharedManifest initial(Definition definition) { return reconcile(null, definition); }
    public static SharedManifest reconcile(SharedManifest before, Definition next) {
        Map<RegionDescriptor, RegionTaskId> old = new HashMap<>();
        if (before != null) before.regions.forEach((id, descriptor) -> old.put(descriptor, id));
        Map<RegionTaskId, RegionDescriptor> mapped = new HashMap<>();
        for (RegionDescriptor descriptor : next.regions()) {
            RegionTaskId id = old.get(descriptor);
            mapped.put(id == null ? RegionTaskId.create() : id, descriptor);
        }
        return new SharedManifest(before == null ? 0 : Math.incrementExact(before.version), next, mapped);
    }
    public Set<TaskId> taskIds() {
        Set<TaskId> ids = new HashSet<>(definition.materials().keySet()); ids.addAll(regions.keySet()); return Set.copyOf(ids);
    }
}
