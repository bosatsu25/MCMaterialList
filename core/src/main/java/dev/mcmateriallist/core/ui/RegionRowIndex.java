package dev.mcmateriallist.core.ui;

import dev.mcmateriallist.core.work.*;
import java.util.*;

/**
 * Immutable row-name to region-task resolution for one observation (Phase 5A). Rendering resolves every visible region row
 * by its native name; doing that by rescanning all observed descriptors and all definitions per row is O(rows x regions).
 * This index answers the same question in O(1) after one O(regions) pass, with the existing rule: a name resolves only when
 * it occurs exactly once in the observation and its exact descriptor maps to exactly one task. Ambiguity stays unresolved,
 * never guessed. It is a render-time cache only: command-time validation must still use a fresh observation.
 */
public final class RegionRowIndex {
    public static final RegionRowIndex EMPTY = new RegionRowIndex(Map.of());
    private final Map<String, RegionTaskId> ids;
    private RegionRowIndex(Map<String, RegionTaskId> ids) { this.ids = Map.copyOf(ids); }

    /** Original local or detached work: definitions may contain blocked duplicate descriptors, which stay unresolved. */
    public static RegionRowIndex forDataset(List<RegionDescriptor> observed, RegionWorkDataset dataset) {
        if (observed == null || dataset == null) return EMPTY;
        Map<RegionDescriptor, RegionTaskId> unique = new HashMap<>(); Set<RegionDescriptor> ambiguous = new HashSet<>();
        for (RegionDefinition definition : dataset.definitions().values())
            if (unique.putIfAbsent(definition.descriptor(), definition.id()) != null) ambiguous.add(definition.descriptor());
        ambiguous.forEach(unique::remove);
        return of(observed, unique);
    }
    /** A shared manifest's region mapping (descriptors are distinct by manifest validation). */
    public static RegionRowIndex forManifest(List<RegionDescriptor> observed, Map<RegionTaskId, RegionDescriptor> regions) {
        if (observed == null || regions == null) return EMPTY;
        Map<RegionDescriptor, RegionTaskId> unique = new HashMap<>(); Set<RegionDescriptor> ambiguous = new HashSet<>();
        regions.forEach((id, descriptor) -> { if (unique.putIfAbsent(descriptor, id) != null) ambiguous.add(descriptor); });
        ambiguous.forEach(unique::remove);
        return of(observed, unique);
    }
    private static RegionRowIndex of(List<RegionDescriptor> observed, Map<RegionDescriptor, RegionTaskId> unique) {
        Map<String, RegionDescriptor> byKey = new HashMap<>(); Set<String> repeated = new HashSet<>();
        for (RegionDescriptor descriptor : observed) if (byKey.putIfAbsent(descriptor.key(), descriptor) != null) repeated.add(descriptor.key());
        Map<String, RegionTaskId> ids = new HashMap<>();
        byKey.forEach((key, descriptor) -> {
            RegionTaskId id = repeated.contains(key) ? null : unique.get(descriptor);
            if (id != null) ids.put(key, id);
        });
        return new RegionRowIndex(ids);
    }
    /** The task of the row with this native name, or null when absent or ambiguous. */
    public RegionTaskId id(String key) { return key == null ? null : ids.get(key); }
    public int size() { return ids.size(); }
}
