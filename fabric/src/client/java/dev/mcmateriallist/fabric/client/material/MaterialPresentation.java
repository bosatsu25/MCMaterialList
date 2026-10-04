package dev.mcmateriallist.fabric.client.material;

import dev.mcmateriallist.core.work.TaskState;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;

public final class MaterialPresentation {
    private MaterialPresentation() {}
    public static String clamp(String value, int width) {
        if (width < Minecraft.getInstance().font.width("...")) return "";
        return StringUtils.clampTextToRenderLength(value, width, LeftRight.RIGHT, "...");
    }
    public static String info(TaskState state) {
        if (state == null) return MaterialWorkSession.text("untracked");
        return MaterialWorkSession.playerName(state.assignee()) + " | " + state.note().replace('\n', ' ')
            + (state.done() ? " | " + MaterialWorkSession.text("completed", MaterialWorkSession.playerName(state.completedBy()), state.completedAt().toString()) : "");
    }
}
