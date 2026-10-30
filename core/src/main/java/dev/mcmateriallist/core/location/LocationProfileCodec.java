package dev.mcmateriallist.core.location;

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
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.persistence.UnsupportedSchemaException;
import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.shared.persistence.SharedSchemaHeader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Schema header first, then explicit fields, strict scalar types, duplicate-field checks and bounded parsing. */
public final class LocationProfileCodec {
    public static final int SCHEMA = 1;
    public static final int MAX_BYTES = 1024 * 1024;
    static final String PROFILE = "LOCATION_PROFILE", SELECTION = "LOCATION_SELECTION";
    private static final Set<String> PROFILE_FIELDS = Set.of("schema", "kind", "profileId", "generation", "displayName", "world", "dimension", "binding", "containers");
    private static final Set<String> SELECTION_FIELDS = Set.of("schema", "kind", "world", "profileId", "generation");
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    public String encode(LocationProfile profile) {
        JsonObject root = header(PROFILE); root.addProperty("profileId", profile.id().toString());
        root.addProperty("generation", profile.generation()); root.addProperty("displayName", profile.displayName());
        root.add("world", world(profile.world())); root.addProperty("dimension", profile.dimension());
        root.add("binding", profile.binding() == null ? JsonNull.INSTANCE : binding(profile.binding()));
        JsonArray containers = new JsonArray();
        for (ContainerPosition position : profile.positions()) {
            JsonObject entry = new JsonObject(); JsonArray pos = new JsonArray();
            pos.add(position.x()); pos.add(position.y()); pos.add(position.z());
            entry.add("pos", pos); entry.addProperty("block", position.blockId()); containers.add(entry);
        }
        root.add("containers", containers); return bounded(gson.toJson(root) + "\n");
    }
    public String encode(LocationSelection selection) {
        JsonObject root = header(SELECTION); root.add("world", world(selection.world()));
        root.addProperty("profileId", selection.profile() == null ? null : selection.profile().toString());
        root.addProperty("generation", selection.generation()); return bounded(gson.toJson(root) + "\n");
    }
    public LocationProfile decodeProfile(byte[] bytes) { return decodeProfile(utf8(bytes)); }
    public LocationSelection decodeSelection(byte[] bytes) { return decodeSelection(utf8(bytes)); }

    public LocationProfile decodeProfile(String text) {
        JsonObject root = parse(text, PROFILE_FIELDS, PROFILE);
        LocationProfileId id = LocationProfileId.parse(string(root, "profileId"));
        long generation = number(root, "generation"); WorldIdentity world = decodeWorld(root.get("world"));
        JsonElement scope = root.get("binding");
        JsonArray containers = array(root.get("containers"));
        if (containers.size() > LocationLimits.MAX_CONTAINERS) throw new IllegalArgumentException("Too many containers");
        List<ContainerPosition> positions = new ArrayList<>(containers.size());
        for (JsonElement element : containers) {
            JsonObject entry = object(element); requireFields(entry, Set.of("pos", "block"));
            JsonArray pos = array(entry.get("pos")); if (pos.size() != 3) throw new IllegalArgumentException("Expected three coordinates");
            positions.add(new ContainerPosition(coordinate(pos.get(0)), coordinate(pos.get(1)), coordinate(pos.get(2)), string(entry, "block")));
        }
        return new LocationProfile(id, generation, string(root, "displayName"), world, string(root, "dimension"),
            scope != null && scope.isJsonNull() ? null : decodeBinding(scope), positions);
    }
    public LocationSelection decodeSelection(String text) {
        JsonObject root = parse(text, SELECTION_FIELDS, SELECTION);
        JsonElement profile = root.get("profileId");
        return new LocationSelection(decodeWorld(root.get("world")), profile != null && profile.isJsonNull() ? null : LocationProfileId.parse(text(profile)), number(root, "generation"));
    }

    private static JsonObject parse(String text, Set<String> fields, String kind) {
        if (text == null || text.length() > MAX_BYTES) throw new IllegalArgumentException("Location document size limit");
        // Classify the version before interpreting any version-specific field.
        StoreStatus header = SharedSchemaHeader.classify(text, fields, SCHEMA);
        if (header == StoreStatus.UNSUPPORTED_SCHEMA) throw new UnsupportedSchemaException();
        if (header != StoreStatus.OK) throw new IllegalArgumentException("Invalid location document header");
        JsonObject root;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            root = object(read(reader, 0, new int[]{0}));
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON input");
        } catch (IOException | IllegalStateException exception) { throw new IllegalArgumentException("Invalid location JSON", exception); }
        requireFields(root, fields);
        if (number(root, "schema") != SCHEMA || !kind.equals(string(root, "kind"))) throw new IllegalArgumentException("Unexpected location document kind");
        return root;
    }
    private static JsonObject header(String kind) { JsonObject root = new JsonObject(); root.addProperty("schema", SCHEMA); root.addProperty("kind", kind); return root; }
    private static String bounded(String json) {
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Location document size limit");
        return json;
    }
    private static JsonObject world(WorldIdentity world) {
        JsonObject result = new JsonObject(); result.addProperty("kind", world.kind().name()); result.addProperty("id", world.namespace().toString()); return result;
    }
    private static WorldIdentity decodeWorld(JsonElement value) {
        JsonObject world = object(value); requireFields(world, Set.of("kind", "id"));
        if (WorldIdentity.Kind.valueOf(string(world, "kind")) != WorldIdentity.Kind.SERVER_NAMESPACE) throw new IllegalArgumentException("Unknown world kind");
        return new WorldIdentity(LocationLimits.canonicalUuid(string(world, "id")));
    }
    private static JsonObject binding(ProjectScope scope) {
        JsonObject result = new JsonObject(); result.addProperty("worldNamespace", scope.worldNamespace().toString());
        result.addProperty("projectId", scope.projectId().toString()); result.addProperty("materialListId", scope.materialListId().toString());
        result.addProperty("regionListId", scope.regionListId().toString()); return result;
    }
    private static ProjectScope decodeBinding(JsonElement value) {
        JsonObject scope = object(value); requireFields(scope, Set.of("worldNamespace", "projectId", "materialListId", "regionListId"));
        return new ProjectScope(LocationLimits.canonicalUuid(string(scope, "worldNamespace")), LocationLimits.canonicalUuid(string(scope, "projectId")),
            LocationLimits.canonicalUuid(string(scope, "materialListId")), LocationLimits.canonicalUuid(string(scope, "regionListId")));
    }
    private static String utf8(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Location document size limit");
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException exception) { throw new IllegalArgumentException("Malformed UTF-8", exception); }
    }
    private static String string(JsonObject object, String field) { return text(object.get(field)); }
    private static String text(JsonElement value) { if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string"); return value.getAsString(); }
    private static long number(JsonObject object, String field) { return integral(object.get(field)); }
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
    private static void requireFields(JsonObject value, Set<String> expected) { if (!value.keySet().equals(expected)) throw new IllegalArgumentException("Unexpected or missing JSON fields"); }
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
