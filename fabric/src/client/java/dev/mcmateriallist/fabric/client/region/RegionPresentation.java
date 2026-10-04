package dev.mcmateriallist.fabric.client.region;

import dev.mcmateriallist.core.work.TaskState;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;

/** Share player/state formatting while keeping the untracked dataset label independent. */
public final class RegionPresentation {
    private RegionPresentation() {}
    public static String info(TaskState state) { return state == null ? RegionWorkSession.text("untracked") : MaterialPresentation.info(state); }
}
