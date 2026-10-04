package dev.mcmateriallist.core.work;

import dev.mcmateriallist.core.LocalPlacementId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RegionWorkDataset(DatasetId id, LocalPlacementId placementId, long generation,
    Map<RegionTaskId, RegionDefinition> definitions, Map<RegionTaskId, TaskState> states,
    Map<RegionTaskId, ArchivedTask<RegionDefinition>> archived, Set<RegionTaskId> reviewRequired) implements WorkDataset {
    public RegionWorkDataset {
        definitions = Map.copyOf(definitions); states = Map.copyOf(states); archived = Map.copyOf(archived); reviewRequired = Set.copyOf(reviewRequired);
        DatasetChecks.validate(id, placementId, generation, definitions, states, archived);
        if (!states.keySet().containsAll(reviewRequired)) throw new IllegalArgumentException("Unknown review task");
        definitions.forEach((key, def) -> { if (!key.equals(def.id())) throw new IllegalArgumentException("Definition identity mismatch"); });
        archived.forEach((key, task) -> { if (!key.equals(task.definition().id())) throw new IllegalArgumentException("Archived definition mismatch"); });
    }
    public static RegionWorkDataset create(LocalPlacementId placement) { return new RegionWorkDataset(DatasetId.create(), placement, 0, Map.of(), Map.of(), Map.of(), Set.of()); }
    @Override public DatasetKind kind() { return DatasetKind.REGIONS; }
    @Override public Progress progress() { return Progress.calculate(states.values()); }
    public RegionWorkDataset withState(TaskState state) {
        if (!(state.taskId() instanceof RegionTaskId key) || !states.containsKey(key)) throw new IllegalArgumentException("Unknown task");
        if (reviewRequired.contains(key)) throw new IllegalArgumentException("Reevaluate mismatched region first");
        if (state.equals(states.get(key))) return this;
        var updated = new HashMap<>(states); updated.put(key, state);
        return new RegionWorkDataset(id, placementId, DatasetChecks.next(generation), definitions, updated, archived, reviewRequired);
    }
    /** Accept only the NEW definition. This never transfers another region's old state. */
    public RegionWorkDataset acknowledge(RegionTaskId key) {
        if (!reviewRequired.contains(key)) return this;
        var reviewed = new HashSet<>(reviewRequired); reviewed.remove(key);
        return new RegionWorkDataset(id, placementId, DatasetChecks.next(generation), definitions, states, archived, reviewed);
    }
    public Reconciliation<RegionWorkDataset, RegionTaskId> reconcile(List<RegionDescriptor> current) {
        var all = new HashMap<>(archived);
        definitions.forEach((key, def) -> all.put(key, new ArchivedTask<>(def, states.get(key))));
        var defs = new HashMap<RegionTaskId, RegionDefinition>(); var nextStates = new HashMap<RegionTaskId, TaskState>();
        var history = new HashMap<>(all); var matched = new HashSet<RegionTaskId>(); var added = new HashSet<RegionTaskId>();
        var review = new HashSet<RegionTaskId>();
        for (RegionDescriptor descriptor : current) {
            var candidates = all.entrySet().stream().filter(e -> e.getValue().definition().descriptor().equals(descriptor)).toList();
            long occurrences = current.stream().filter(descriptor::equals).count();
            if (candidates.size() == 1 && occurrences == 1) {
                var candidate = candidates.getFirst(); RegionTaskId key = candidate.getKey();
                defs.put(key, candidate.getValue().definition()); nextStates.put(key, candidate.getValue().state());
                history.remove(key); matched.add(key); if (reviewRequired.contains(key)) review.add(key);
            } else {
                RegionTaskId key = RegionTaskId.create(); defs.put(key, new RegionDefinition(key, descriptor)); nextStates.put(key, TaskState.empty(key)); added.add(key);
                boolean mismatch = occurrences > 1 || !candidates.isEmpty() || all.values().stream().anyMatch(task -> {
                    RegionDescriptor old = task.definition().descriptor();
                    return old.key().equals(descriptor.key()) || (old.relativeOrigin().equals(descriptor.relativeOrigin()) && old.size().equals(descriptor.size()));
                });
                if (mismatch) review.add(key);
            }
        }
        var removed = new HashSet<>(definitions.keySet()); removed.removeAll(defs.keySet());
        RegionWorkDataset next = definitions.equals(defs) && states.equals(nextStates) && archived.equals(history) && reviewRequired.equals(review) ? this
            : new RegionWorkDataset(id, placementId, DatasetChecks.next(generation), defs, nextStates, history, review);
        return new Reconciliation<>(next, matched, added, removed, Set.of(), review);
    }
}
