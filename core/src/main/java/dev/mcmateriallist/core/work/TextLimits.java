package dev.mcmateriallist.core.work;

import java.nio.charset.StandardCharsets;

public final class TextLimits {
    public static final int NOTE_BYTES = 4096;
    private TextLimits() {}
    public static boolean valid(String text, int maxBytes) {
        return text != null && StandardCharsets.UTF_8.newEncoder().canEncode(text)
            && text.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
