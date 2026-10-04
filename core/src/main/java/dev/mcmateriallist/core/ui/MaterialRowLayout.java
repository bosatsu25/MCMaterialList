package dev.mcmateriallist.core.ui;

import java.util.Optional;

/** GUI-relative column offsets; names yield space before quantity/action hit areas. */
public record MaterialRowLayout(int totalX, int missingX, int availableX,
    int noticeX, int doneX, int ignoreX, int nameWidth) {
    public static Optional<MaterialRowLayout> fit(int width, int totalWidth, int missingWidth,
                                                  int availableWidth, int ignoreWidth) {
        if (width < 0 || totalWidth < 0 || missingWidth < 0 || availableWidth < 0 || ignoreWidth < 0)
            throw new IllegalArgumentException("Negative layout size");
        // Upstream places the active header's 12px sort icon at nextColumn - 21.
        long required = 52L + totalWidth + missingWidth + availableWidth + 72 + 36 + ignoreWidth;
        if (width < required) return Optional.empty();
        int ignore = width - ignoreWidth;
        int done = ignore - 22;
        int notice = done - 14;
        int nameWidth = Math.min(192, width - (int) required);
        int total = 52 + nameWidth;
        int missing = total + totalWidth + 24;
        int available = missing + missingWidth + 24;
        return Optional.of(new MaterialRowLayout(total, missing, available, notice, done, ignore, nameWidth));
    }
}
