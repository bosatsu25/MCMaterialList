package dev.mcmateriallist.core.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Greedy line wrapping for GUI text measured by the caller's font. Lines break after a space where one fits, otherwise
 * between characters, so Japanese text without spaces wraps too. A Minecraft formatting code ({@code §x}) is never split,
 * and common Japanese closing punctuation does not start a line when the previous character can move with it.
 */
public final class TextWrap {
    private static final String NO_LINE_START = "、。，．,.)）」』】!！?？・ーぁぃぅぇぉっゃゅょァィゥェォッャュョ";
    private TextWrap() {}

    /** Every returned line measures at most width, except a single character wider than width on its own line. */
    public static List<String> wrap(String text, int width, ToIntFunction<String> measure) {
        Objects.requireNonNull(text, "text"); Objects.requireNonNull(measure, "measure");
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) wrapParagraph(paragraph, width, measure, lines);
        return lines;
    }

    private static void wrapParagraph(String paragraph, int width, ToIntFunction<String> measure, List<String> lines) {
        int start = 0;
        while (start < paragraph.length()) {
            int end = start, lastSpace = -1;
            while (end < paragraph.length()) {
                int next = advance(paragraph, end);
                if (measure.applyAsInt(paragraph.substring(start, next)) > width && end > start) break;
                if (paragraph.charAt(end) == ' ') lastSpace = end;
                end = next;
            }
            if (end < paragraph.length()) {
                if (lastSpace > start) end = lastSpace;
                else if (NO_LINE_START.indexOf(paragraph.charAt(end)) >= 0) {
                    int previous = retreat(paragraph, end);
                    if (previous > start) end = previous;
                }
            }
            lines.add(stripTrailing(paragraph.substring(start, end)));
            start = end;
            while (start < paragraph.length() && paragraph.charAt(start) == ' ') start++;
        }
        if (paragraph.isEmpty()) lines.add("");
    }

    /** The index after the unit at index: a formatting code pair, a surrogate pair or one char. */
    private static int advance(String value, int index) {
        if (value.charAt(index) == '§' && index + 1 < value.length()) return index + 2;
        return index + Character.charCount(value.codePointAt(index));
    }

    private static int retreat(String value, int index) {
        int previous = index - Character.charCount(value.codePointBefore(index));
        if (previous > 0 && value.charAt(previous - 1) == '§') previous--;
        return previous;
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') end--;
        return value.substring(0, end);
    }
}
