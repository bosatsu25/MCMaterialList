package dev.mcmateriallist.core.work;

import java.util.Set;

public record Reconciliation<D, I extends TaskId>(D dataset, Set<I> matched, Set<I> added,
                                                Set<I> removed, Set<I> changed, Set<I> reviewRequired) {
    public Reconciliation {
        matched = Set.copyOf(matched); added = Set.copyOf(added); removed = Set.copyOf(removed);
        changed = Set.copyOf(changed); reviewRequired = Set.copyOf(reviewRequired);
    }
}
