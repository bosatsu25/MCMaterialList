package dev.mcmateriallist.core.ui;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextWrapTest {
    /** Monospace: one unit per character, formatting codes are invisible, CJK characters are double width. */
    private static final ToIntFunction<String> WIDTH = value -> {
        int width = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '§') { i++; continue; }
            width += c > 0x2E80 ? 2 : 1;
        }
        return width;
    };

    @Test void breaksAfterSpacesAndNeverExceedsTheWidth() {
        var lines = TextWrap.wrap("Join / synchronize online before unsharing.", 16, WIDTH);
        assertEquals(List.of("Join /", "synchronize", "online before", "unsharing."), lines);
        lines.forEach(line -> assertTrue(WIDTH.applyAsInt(line) <= 16, line));
    }

    @Test void japaneseWithoutSpacesWrapsBetweenCharactersAndKeepsClosingPunctuationOffTheLineStart() {
        String text = "オンラインで参加・同期してから共有解除してください。";
        var lines = TextWrap.wrap(text, 10, WIDTH);
        assertEquals(text, String.join("", lines));
        for (var line : lines) {
            assertTrue(WIDTH.applyAsInt(line) <= 10, line);
            assertTrue(line.isEmpty() || "、。・".indexOf(line.charAt(0)) < 0, "Line starts with closing punctuation: " + line);
        }
    }

    @Test void formattingCodesAreNeverSplitAndParagraphsArePreserved() {
        var lines = TextWrap.wrap("§cabcdef§r\n\nxy", 3, WIDTH);
        assertEquals(List.of("§cabc", "def§r", "", "xy"), lines);
    }

    @Test void aSingleCharacterWiderThanTheWidthStillMakesProgress() {
        assertEquals(List.of("共", "有"), TextWrap.wrap("共有", 1, WIDTH));
        assertEquals(List.of(""), TextWrap.wrap("", 10, WIDTH));
    }
}
