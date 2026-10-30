package dev.mcmateriallist.core.stock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.mcmateriallist.core.location.LocationLimits;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.persistence.UnsupportedSchemaException;
import dev.mcmateriallist.core.shared.persistence.SharedSchemaHeader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Same strict rules as LocationProfileCodec: schema header first, exact fields, strict scalars, duplicate-field checks, bounded parsing. */
public final class StockGrantCodec {
    public static final int SCHEMA = 1;
    public static final int MAX_BYTES = 256 * 1024;
    static final String KIND = "STOCK_GRANTS";
    private static final Set<String> FIELDS = Set.of("schema", "kind", "projectId", "generation", "grants");
    private static final Set<String> GRANT_FIELDS = Set.of("dimension", "pos", "approver", "grantedAt");
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    public String encode(StockGrants grants) {
        JsonObject root = new JsonObject(); root.addProperty("schema", SCHEMA); root.addProperty("kind", KIND);
        root.addProperty("projectId", grants.projectId().toString()); root.addProperty("generation", grants.generation());
        JsonArray list = new JsonArray();
        for (StockGrant grant : grants.grants()) {
            JsonObject entry = new JsonObject(); JsonArray pos = new JsonArray(); pos.add(grant.x()); pos.add(grant.y()); pos.add(grant.z());
            entry.addProperty("dimension", grant.dimension()); entry.add("pos", pos);
            entry.addProperty("approver", grant.approver().toString()); entry.addProperty("grantedAt", grant.grantedAt()); list.add(entry);
        }
        root.add("grants", list);
        String json = gson.toJson(root) + "\n";
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Grant document size limit");
        return json;
    }
    public StockGrants decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Grant document size limit");
        try { return decode(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()); }
        catch (CharacterCodingException exception) { throw new IllegalArgumentException("Malformed UTF-8", exception); }
    }
    public StockGrants decode(String text) {
        if (text == null || text.length() > MAX_BYTES) throw new IllegalArgumentException("Grant document size limit");
        // Classify the version before interpreting any version-specific field.
        StoreStatus header = SharedSchemaHeader.classify(text, FIELDS, SCHEMA);
        if (header == StoreStatus.UNSUPPORTED_SCHEMA) throw new UnsupportedSchemaException();
        if (header != StoreStatus.OK) throw new IllegalArgumentException("Invalid grant document header");
        JsonObject root;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            root = object(read(reader, 0, new int[]{0}));
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON input");
        } catch (IOException | IllegalStateException exception) { throw new IllegalArgumentException("Invalid grant JSON", exception); }
        fields(root, FIELDS);
        if (integral(root.get("schema")) != SCHEMA || !KIND.equals(text(root.get("kind")))) throw new IllegalArgumentException("Unexpected grant document kind");
        JsonArray list = array(root.get("grants"));
        if (list.size() > StockLimits.MAX_GRANTS) throw new IllegalArgumentException("Too many grants");
        List<StockGrant> grants = new ArrayList<>(list.size());
        for (JsonElement element : list) {
            JsonObject entry = object(element); fields(entry, GRANT_FIELDS);
            JsonArray pos = array(entry.get("pos")); if (pos.size() != 3) throw new IllegalArgumentException("Expected three coordinates");
            grants.add(new StockGrant(text(entry.get("dimension")), coordinate(pos.get(0)), coordinate(pos.get(1)), coordinate(pos.get(2)),
                LocationLimits.canonicalUuid(text(entry.get("approver"))), integral(entry.get("grantedAt"))));
        }
        StockGrants result = new StockGrants(LocationLimits.canonicalUuid(text(root.get("projectId"))), integral(root.get("generation")), grants);
        // Canonical order only, so the same registry always has the same bytes.
        if (!result.grants().equals(grants)) throw new IllegalArgumentException("Grants out of order");
        return result;
    }

    private static String text(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
        return value.getAsString();
    }
    private static long integral(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");
        try { return value.getAsBigDecimal().longValueExact(); } catch (ArithmeticException exception) { throw new IllegalArgumentException("Integer out of range", exception); }
    }
    private static int coordinate(JsonElement value) {
        long number = integral(value);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException("Coordinate out of range");
        return (int) number;
    }
    private static JsonObject object(JsonElement value) { if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Expected object"); return value.getAsJsonObject(); }
    private static JsonArray array(JsonElement value) { if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Expected array"); return value.getAsJsonArray(); }
    private static void fields(JsonObject value, Set<String> expected) { if (!value.keySet().equals(expected)) throw new IllegalArgumentException("Unexpected or missing JSON fields"); }
    private static JsonElement read(JsonReader reader, int depth, int[] count) throws IOException {
        if (depth > 8 || ++count[0] > 16384) throw new IllegalArgumentException("JSON complexity limit");
        JsonToken token = reader.peek();
        if (token == JsonToken.BEGIN_OBJECT) {
            reader.beginObject(); JsonObject result = new JsonObject();
            while (reader.hasNext()) { String name = reader.nextName(); if (result.has(name)) throw new IllegalArgumentException("Duplicate JSON field"); result.add(name, read(reader, depth + 1, count)); }
            reader.endObject(); return result;
        }
        if (token == JsonToken.BEGIN_ARRAY) {
            reader.beginArray(); JsonArray result = new JsonArray();
            while (reader.hasNext()) result.add(read(reader, depth + 1, count)); reader.endArray(); return result;
        }
        if (token == JsonToken.STRING) return new JsonPrimitive(reader.nextString());
        if (token == JsonToken.NUMBER) {
            String integer = reader.nextString();
            if (!integer.matches("-?(0|[1-9][0-9]{0,18})")) throw new IllegalArgumentException("Expected bounded decimal integer");
            return new JsonPrimitive(Long.parseLong(integer));
        }
        if (token == JsonToken.BOOLEAN) return new JsonPrimitive(reader.nextBoolean());
        if (token == JsonToken.NULL) { reader.nextNull(); return JsonNull.INSTANCE; }
        throw new IllegalArgumentException("Unexpected JSON token");
    }
}
