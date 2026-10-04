package dev.mcmateriallist.core.persistence;

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
import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.work.*;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit schema, strict scalar types, duplicate-field checks and bounded parsing. */
public final class DatasetJsonCodec {
    public static final int SCHEMA = 1;
    public static final int MAX_TASKS = 4000;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    public String encode(WorkDataset dataset) {
        JsonObject root = new JsonObject(); root.addProperty("schema", SCHEMA);
        root.addProperty("identityRule", 1); root.addProperty("kind", dataset.kind().name());
        root.addProperty("datasetId", dataset.id().toString()); root.addProperty("placementId", dataset.placementId().toString());
        root.addProperty("generation", dataset.generation());
        JsonArray active = new JsonArray(); JsonArray archived = new JsonArray();
        if (dataset instanceof MaterialWorkDataset materials) {
            materials.definitions().keySet().stream().sorted(Comparator.comparing(MaterialTaskId::externalForm)).forEach(id ->
                active.add(materialTask(materials.definitions().get(id), materials.states().get(id))));
            materials.archived().keySet().stream().sorted(Comparator.comparing(MaterialTaskId::externalForm)).forEach(id -> {
                var task = materials.archived().get(id); archived.add(materialTask(task.definition(), task.state()));
            });
        } else if (dataset instanceof RegionWorkDataset regions) {
            regions.definitions().keySet().stream().sorted(Comparator.comparing(RegionTaskId::externalForm)).forEach(id ->
                active.add(regionTask(regions.definitions().get(id), regions.states().get(id))));
            regions.archived().keySet().stream().sorted(Comparator.comparing(RegionTaskId::externalForm)).forEach(id -> {
                var task = regions.archived().get(id); archived.add(regionTask(task.definition(), task.state()));
            });
            JsonArray review = new JsonArray(); regions.reviewRequired().stream().map(RegionTaskId::externalForm).sorted().forEach(review::add);
            root.add("reviewRequired", review);
        }
        if (active.size() + archived.size() > MAX_TASKS) throw new IllegalArgumentException("Work dataset exceeds task limit");
        root.add("tasks", active); root.add("archived", archived); return gson.toJson(root) + "\n";
    }

    public WorkDataset decode(String text) {
        JsonObject root;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            root = object(read(reader, 0, new int[]{0}));
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON input");
        } catch (IOException | IllegalStateException exception) { throw new IllegalArgumentException("Invalid work JSON", exception); }
        if (number(root, "schema") != SCHEMA) throw new UnsupportedSchemaException();
        if (number(root, "identityRule") != 1) throw new UnsupportedSchemaException();
        DatasetKind kind = DatasetKind.valueOf(string(root, "kind"));
        Set<String> fields = new HashSet<>(Set.of("schema", "identityRule", "kind", "datasetId", "placementId", "generation", "tasks", "archived"));
        if (kind == DatasetKind.REGIONS) fields.add("reviewRequired");
        requireFields(root, fields);
        DatasetId id = DatasetId.parse(string(root, "datasetId")); LocalPlacementId placement = LocalPlacementId.parse(string(root, "placementId"));
        long generation = number(root, "generation"); JsonArray active = array(root.get("tasks")); JsonArray history = array(root.get("archived"));
        if (active.size() + history.size() > MAX_TASKS) throw new IllegalArgumentException("Too many persisted tasks");
        if (kind == DatasetKind.MATERIALS) {
            var definitions = new HashMap<MaterialTaskId, MaterialDefinition>(); var states = new HashMap<MaterialTaskId, TaskState>();
            var archived = new HashMap<MaterialTaskId, ArchivedTask<MaterialDefinition>>();
            for (JsonElement entry : active) {
                var task = decodeMaterial(object(entry)); MaterialTaskId key = task.definition().id();
                uniquePut(definitions, key, task.definition()); states.put(key, task.state());
            }
            for (JsonElement entry : history) { var task = decodeMaterial(object(entry)); uniquePut(archived, task.definition().id(), task); }
            return new MaterialWorkDataset(id, placement, generation, definitions, states, archived);
        }
        var definitions = new HashMap<RegionTaskId, RegionDefinition>(); var states = new HashMap<RegionTaskId, TaskState>();
        var archived = new HashMap<RegionTaskId, ArchivedTask<RegionDefinition>>(); var review = new HashSet<RegionTaskId>();
        for (JsonElement entry : active) {
            var task = decodeRegion(object(entry)); RegionTaskId key = task.definition().id();
            uniquePut(definitions, key, task.definition()); states.put(key, task.state());
        }
        for (JsonElement entry : history) { var task = decodeRegion(object(entry)); uniquePut(archived, task.definition().id(), task); }
        for (JsonElement entry : array(root.get("reviewRequired"))) {
            if (!review.add(RegionTaskId.parse(text(entry)))) throw new IllegalArgumentException("Duplicate review identity");
        }
        return new RegionWorkDataset(id, placement, generation, definitions, states, archived, review);
    }

    private JsonObject materialTask(MaterialDefinition def, TaskState state) {
        JsonObject entry = new JsonObject(); entry.addProperty("id", def.id().externalForm());
        entry.addProperty("total", def.total()); entry.addProperty("missing", def.missing()); entry.addProperty("available", def.available());
        entry.add("state", state(state)); return entry;
    }
    private JsonObject regionTask(RegionDefinition def, TaskState state) {
        JsonObject entry = new JsonObject(); entry.addProperty("id", def.id().externalForm()); entry.addProperty("key", def.descriptor().key());
        entry.add("origin", vector(def.descriptor().relativeOrigin())); entry.add("size", vector(def.descriptor().size()));
        entry.add("state", state(state)); return entry;
    }
    private JsonObject state(TaskState state) {
        JsonObject result = new JsonObject(); result.addProperty("assignee", state.assignee() == null ? null : state.assignee().toString());
        result.addProperty("done", state.done()); result.addProperty("note", state.note());
        result.addProperty("completedBy", state.completedBy() == null ? null : state.completedBy().toString());
        result.addProperty("completedAt", state.completedAt() == null ? null : state.completedAt().toString());
        result.addProperty("rowVersion", state.rowVersion()); return result;
    }
    private ArchivedTask<MaterialDefinition> decodeMaterial(JsonObject entry) {
        requireFields(entry, Set.of("id", "total", "missing", "available", "state"));
        MaterialTaskId id = new MaterialTaskId(string(entry, "id"));
        return new ArchivedTask<>(new MaterialDefinition(id, number(entry, "total"), number(entry, "missing"), number(entry, "available")), decodeState(id, object(entry.get("state"))));
    }
    private ArchivedTask<RegionDefinition> decodeRegion(JsonObject entry) {
        requireFields(entry, Set.of("id", "key", "origin", "size", "state")); RegionTaskId id = RegionTaskId.parse(string(entry, "id"));
        return new ArchivedTask<>(new RegionDefinition(id, new RegionDescriptor(string(entry, "key"), decodeVector(entry.get("origin")), decodeVector(entry.get("size")))), decodeState(id, object(entry.get("state"))));
    }
    private TaskState decodeState(TaskId id, JsonObject state) {
        requireFields(state, Set.of("assignee", "done", "note", "completedBy", "completedAt", "rowVersion"));
        JsonElement done = state.get("done");
        if (!done.isJsonPrimitive() || !done.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected boolean");
        String timestamp = nullableText(state.get("completedAt"));
        return new TaskState(id, nullableUuid(state.get("assignee")), done.getAsBoolean(), string(state, "note"), nullableUuid(state.get("completedBy")), timestamp == null ? null : Instant.parse(timestamp), number(state, "rowVersion"));
    }
    private static JsonArray vector(Vector3 value) { JsonArray result = new JsonArray(); result.add(value.x()); result.add(value.y()); result.add(value.z()); return result; }
    private static Vector3 decodeVector(JsonElement value) {
        JsonArray result = array(value); if (result.size() != 3) throw new IllegalArgumentException("Expected three coordinates");
        return new Vector3(Math.toIntExact(integral(result.get(0))), Math.toIntExact(integral(result.get(1))), Math.toIntExact(integral(result.get(2))));
    }
    private static UUID nullableUuid(JsonElement value) { String text = nullableText(value); return text == null ? null : LocalPlacementId.parse(text).value(); }
    private static String nullableText(JsonElement value) { if (value == null) throw new IllegalArgumentException("Missing JSON field"); return value.isJsonNull() ? null : text(value); }
    private static String string(JsonObject object, String field) { return text(object.get(field)); }
    private static String text(JsonElement value) { if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string"); return value.getAsString(); }
    private static long number(JsonObject object, String field) { return integral(object.get(field)); }
    private static long integral(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");
        try { return value.getAsBigDecimal().longValueExact(); } catch (ArithmeticException exception) { throw new IllegalArgumentException("Integer out of range", exception); }
    }
    private static JsonObject object(JsonElement value) { if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Expected object"); return value.getAsJsonObject(); }
    private static JsonArray array(JsonElement value) { if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Expected array"); return value.getAsJsonArray(); }
    private static void requireFields(JsonObject value, Set<String> expected) { if (!value.keySet().equals(expected)) throw new IllegalArgumentException("Unexpected or missing JSON fields"); }
    private static <K,V> void uniquePut(Map<K,V> map, K key, V value) { if (map.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate task identity"); }
    private static JsonElement read(JsonReader reader, int depth, int[] count) throws IOException {
        if (depth > 16 || ++count[0] > 250000) throw new IllegalArgumentException("JSON complexity limit");
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
