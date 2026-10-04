package dev.mcmateriallist.core.work;

public record DatasetMutation<D extends WorkDataset>(D dataset, TransitionResult result) {}
