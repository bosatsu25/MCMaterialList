package dev.mcmateriallist.core.work;

import dev.mcmateriallist.core.LocalPlacementId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MaterialWorkDataset(DatasetId id, LocalPlacementId placementId, long generation,
    Map<MaterialTaskId, MaterialDefinition> definitions, Map<MaterialTaskId, TaskState> states,
    Map<MaterialTaskId, ArchivedTask<MaterialDefinition>> archived) implements WorkDataset {
    public MaterialWorkDataset {
        definitions = Map.copyOf(definitions); states = Map.copyOf(states); archived = Map.copyOf(archived);
        DatasetChecks.validate(id, placementId, generation, definitions, states, archived);
        definitions.forEach((key, def) -> { if (!key.equals(def.id())) throw new IllegalArgumentException("Definition identity mismatch"); });
        archived.forEach((key, task) -> { if (!key.equals(task.definition().id())) throw new IllegalArgumentException("Archived definition mismatch"); });
    }
    public static MaterialWorkDataset create(LocalPlacementId placement) { return new MaterialWorkDataset(DatasetId.create(), placement, 0, Map.of(), Map.of(), Map.of()); }
    @Override public DatasetKind kind() { return DatasetKind.MATERIALS; }
    @Override public Progress progress() { return Progress.calculate(states.values()); }
    public MaterialWorkDataset withState(TaskState state) {
        if (!(state.taskId() instanceof MaterialTaskId key) || !states.containsKey(key)) throw new IllegalArgumentException("Unknown task");
        if (state.equals(states.get(key))) return this;
        var updated = new HashMap<>(states); updated.put(key, state);
        return new MaterialWorkDataset(id, placementId, DatasetChecks.next(generation), definitions, updated, archived);
    }
    public Reconciliation<MaterialWorkDataset, MaterialTaskId> reconcile(List<MaterialDefinition> current) {
        var defs = new HashMap<MaterialTaskId, MaterialDefinition>(); var nextStates = new HashMap<MaterialTaskId, TaskState>();
        var history = new HashMap<>(archived); var matched = new HashSet<MaterialTaskId>();
        var added = new HashSet<MaterialTaskId>(); var changed = new HashSet<MaterialTaskId>();
        for (MaterialDefinition def : current) {
            MaterialTaskId key = def.id();
            if (defs.putIfAbsent(key, def) != null) throw new IllegalArgumentException("Duplicate aggregated material identity");
            MaterialDefinition old = definitions.get(key); TaskState state = states.get(key);
            ArchivedTask<MaterialDefinition> restored = history.remove(key);
            if (old == null && restored != null) { old = restored.definition(); state = restored.state(); }
            if (old == null) { added.add(key); state = TaskState.empty(key); }
            else { matched.add(key); if (!old.equals(def)) changed.add(key); }
            nextStates.put(key, state);
        }
        var removed = new HashSet<>(definitions.keySet()); removed.removeAll(defs.keySet());
        for (MaterialTaskId key : removed) history.put(key, new ArchivedTask<>(definitions.get(key), states.get(key)));
        MaterialWorkDataset next = definitions.equals(defs) && states.equals(nextStates) && archived.equals(history) ? this
            : new MaterialWorkDataset(id, placementId, DatasetChecks.next(generation), defs, nextStates, history);
        return new Reconciliation<>(next, matched, added, removed, changed, Set.of());
    }
}
