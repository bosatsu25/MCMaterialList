package dev.mcmateriallist.core.ui;

import dev.mcmateriallist.core.work.Progress;

/** Shared fraction styling; callers supply their own translated prefix. */
public final class WorkProgressPresentation {
    private WorkProgressPresentation() {}
    public static String style(String translated, Progress progress) {
        if (progress.completed() >= progress.total()) return translated;
        String fraction = progress.completed() + " / " + progress.total() + " (" + progress.percentage() + "%)";
        return translated.replace(fraction, "\u00a7c" + fraction + "\u00a7r");
    }
}
