package dev.mcmateriallist.fabric.client.work;

import dev.mcmateriallist.core.ui.TextWrap;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/**
 * Issue #22: a disabled action always explains itself. The hover keeps the complete label first (tests and the
 * native controls identify buttons by it), followed by the reason and the next action, wrapped to fit the screen.
 */
public final class ActionReason {
    private ActionReason() {}

    /**
     * Enables the button, or disables it and appends the wrapped reason (which names the next action) to its hover. The
     * reason is computed only while the button is disabled.
     */
    public static void show(ButtonBase button, String label, boolean enabled, Supplier<String> reason, int screenWidth) {
        button.setEnabled(enabled);
        List<String> hover = new ArrayList<>();
        hover.add(label);
        String value = enabled || reason == null ? null : reason.get();
        if (value != null && !value.isBlank()) hover.addAll(wrap(value, screenWidth));
        if (!hover.equals(button.getHoverStrings())) button.setHoverStrings(hover.toArray(String[]::new));
    }

    /** Tooltip lines at most half the screen wide (at least 120 GUI pixels), so a tooltip stays inside the GUI. */
    public static List<String> wrap(String text, int screenWidth) {
        var font = Minecraft.getInstance().font;
        return TextWrap.wrap(text, Math.max(120, screenWidth / 2), font::width);
    }
}
