package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.work.TransitionResult;

/** Changed state is returned only after the snapshot write succeeds. */
public record UpdateResult(StoreResult storage, TransitionResult transition) {}
