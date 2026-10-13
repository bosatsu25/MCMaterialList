package dev.mcmateriallist.core.shared.persistence;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.mcmateriallist.core.persistence.StoreStatus;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Bounded streaming classification before any version-specific payload interpretation. */
public final class SharedSchemaHeader {
    private SharedSchemaHeader() {}

    public static StoreStatus classify(String json, Set<String> currentFields) {
        return classify(json, currentFields, 1);
    }
    public static StoreStatus classify(String json, Set<String> currentFields, int supported) {
        if (supported < 1 || supported > 8) throw new IllegalArgumentException("Unsupported schema ceiling");
        try (var reader = new JsonReader(new StringReader(normalizeLongNumbers(json, supported)))) {
            reader.setStrictness(Strictness.STRICT); reader.beginObject();
            var seen = new HashSet<String>();
            boolean invalid = false, schemaSeen = false;
            StoreStatus schema = StoreStatus.CORRUPT;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("schema")) {
                    if (schemaSeen || reader.peek() != JsonToken.NUMBER) return StoreStatus.CORRUPT;
                    schemaSeen = true; schema = numeric(reader.nextString(), supported);
                } else reader.skipValue();
                // Do not retain arbitrary attacker-controlled field names.
                if (!currentFields.contains(name)) invalid = true;
                else if (!seen.add(name)) invalid = true;
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !schemaSeen) return StoreStatus.CORRUPT;
            if (schema == StoreStatus.UNSUPPORTED_SCHEMA) return schema;
            return schema == StoreStatus.OK && !invalid && seen.equals(currentFields) ? StoreStatus.OK : StoreStatus.CORRUPT;
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) { return StoreStatus.CORRUPT; }
    }

    /** Gson's numeric lookahead has a fixed buffer. Preserve classification of longer valid tokens. */
    private static String normalizeLongNumbers(String json, int supported) {
        StringBuilder normalized = null;
        int copied = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char character = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
            else if (character == '-' || character >= '0' && character <= '9') {
                int start = i;
                while (i + 1 < json.length() && ",]}{[: \t\r\n".indexOf(json.charAt(i + 1)) < 0) i++;
                if (i - start < 512) continue;
                String token = json.substring(start, i + 1);
                if (!validNumber(token)) continue;
                if (normalized == null) normalized = new StringBuilder();
                normalized.append(json, copied, start).append(numeric(token, supported) == StoreStatus.UNSUPPORTED_SCHEMA ? supported + 1 : 0);
                copied = i + 1;
            }
        }
        return normalized == null ? json : normalized.append(json, copied, json.length()).toString();
    }

    private static boolean validNumber(String token) {
        int i = token.charAt(0) == '-' ? 1 : 0;
        if (i == token.length()) return false;
        if (token.charAt(i) == '0') i++;
        else {
            if (token.charAt(i) < '1' || token.charAt(i) > '9') return false;
            i = digitsEnd(token, i);
        }
        if (i < token.length() && token.charAt(i) == '.') {
            int start = ++i; i = digitsEnd(token, i); if (i == start) return false;
        }
        if (i < token.length() && (token.charAt(i) == 'e' || token.charAt(i) == 'E')) {
            i++; if (i < token.length() && (token.charAt(i) == '+' || token.charAt(i) == '-')) i++;
            int start = i; i = digitsEnd(token, i); if (i == start) return false;
        }
        return i == token.length();
    }
    private static int digitsEnd(String token, int start) {
        while (start < token.length() && token.charAt(start) >= '0' && token.charAt(start) <= '9') start++;
        return start;
    }

    private static StoreStatus numeric(String token, int supported) {
        if (token.length() == 1 && token.charAt(0) >= '1' && token.charAt(0) <= '0' + supported) return StoreStatus.OK;
        if (token.startsWith("-")) return StoreStatus.CORRUPT;
        int exponentAt = token.indexOf('e');
        if (exponentAt < 0) exponentAt = token.indexOf('E');
        int end = exponentAt < 0 ? token.length() : exponentAt;
        int digits = 0, leading = 0, trailing = 0, fraction = 0, first = -1;
        boolean decimal = false;
        for (int i = 0; i < end; i++) {
            char character = token.charAt(i);
            if (character == '.') { decimal = true; continue; }
            if (character < '0' || character > '9') return StoreStatus.CORRUPT;
            digits++; if (decimal) fraction++;
            if (first < 0 && character == '0') leading++;
            else if (first < 0) first = character - '0';
            trailing = character == '0' ? trailing + 1 : 0;
        }
        if (first < 0) return StoreStatus.CORRUPT;
        // Saturate relative to this bounded token, never expand exponent notation.
        long exponent = 0, cap = (long) token.length() + 2;
        if (exponentAt >= 0) {
            int i = exponentAt + 1; boolean negative = token.charAt(i) == '-';
            if (negative || token.charAt(i) == '+') i++;
            for (; i < token.length(); i++) exponent = Math.min(cap, exponent * 10 + token.charAt(i) - '0');
            if (negative) exponent = -exponent;
        }
        long shift = exponent - fraction;
        if (shift < -trailing) return StoreStatus.CORRUPT; // Fractional values are not schema versions.
        long length = digits - leading + shift;
        return length > 1 || length == 1 && first > supported ? StoreStatus.UNSUPPORTED_SCHEMA : StoreStatus.CORRUPT;
    }
}
