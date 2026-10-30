package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.work.TextLimits;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/** Shared syntactic bounds. Display names are values only and never become path components. */
public final class LocationLimits {
    public static final int MAX_CONTAINERS = 512;
    public static final int MAX_PROFILES = 256;
    public static final int NAME_CODE_POINTS = 64;
    public static final int NAME_BYTES = 256;
    public static final int ID_BYTES = 256;
    public static final int MAX_HORIZONTAL = 30_000_000;
    public static final int MIN_Y = -2048;
    public static final int MAX_Y = 2047;
    private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private LocationLimits() {}

    public static boolean namespacedId(String id) {
        return id != null && id.getBytes(StandardCharsets.UTF_8).length <= ID_BYTES && NAMESPACED.matcher(id).matches();
    }
    /** Exact normalized form: stripped, 1-64 code points, at most 256 UTF-8 bytes, no controls or bidi formatting. */
    public static boolean displayName(String name) {
        if (name == null || name.isEmpty() || !name.equals(name.strip()) || !TextLimits.valid(name, NAME_BYTES)) return false;
        int count = name.codePointCount(0, name.length());
        return count <= NAME_CODE_POINTS && name.codePoints().noneMatch(c -> Character.isISOControl(c) || bidi(c));
    }
    public static String normalizeName(String name) { return name == null ? null : name.strip(); }
    private static boolean bidi(int c) { return c >= 0x202A && c <= 0x202E || c >= 0x2066 && c <= 0x2069 || c == 0x200E || c == 0x200F || c == 0x061C; }
    /** Lowercase canonical text only, so the same identity always has the same bytes and path. */
    public static UUID canonicalUuid(String text) {
        if (text == null) throw new IllegalArgumentException("Missing UUID");
        UUID value = UUID.fromString(text);
        if (!value.toString().equals(text)) throw new IllegalArgumentException("Identity must be a canonical UUID");
        return value;
    }
}
