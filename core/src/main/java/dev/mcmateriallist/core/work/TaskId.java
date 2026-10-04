package dev.mcmateriallist.core.work;

public sealed interface TaskId permits MaterialTaskId, RegionTaskId {
    String externalForm();
}
