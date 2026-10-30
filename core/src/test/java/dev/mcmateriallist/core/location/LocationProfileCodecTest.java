package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.persistence.UnsupportedSchemaException;
import dev.mcmateriallist.core.shared.ProjectScope;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocationProfileCodecTest {
    private static final UUID WORLD = UUID.fromString("1111aaaa-1111-4111-8111-11111111bbbb");
    private static final LocationProfileId ID = LocationProfileId.parse("2222cccc-2222-4222-8222-22222222dddd");
    private static final ProjectScope SCOPE = new ProjectScope(WORLD, UUID.fromString("3333eeee-3333-4333-8333-33333333ffff"),
        UUID.fromString("4444abcd-4444-4444-8444-44444444abcd"), UUID.fromString("5555dcba-5555-4555-8555-55555555dcba"));
    private final LocationProfileCodec codec = new LocationProfileCodec();

    @Test void encodesTheDocumentedShapeDeterministicallyAndRoundTrips() {
        var profile = new LocationProfile(ID, 3, "taichi_ms", new WorldIdentity(WORLD), "minecraft:overworld", SCOPE,
            List.of(new ContainerPosition(10, 64, -3, "minecraft:chest"), new ContainerPosition(-5, -64, 2, "minecraft:barrel"), new ContainerPosition(10, 63, 9, "minecraft:chest")));
        String json = codec.encode(profile);
        assertEquals("{\"schema\":1,\"kind\":\"LOCATION_PROFILE\",\"profileId\":\"2222cccc-2222-4222-8222-22222222dddd\",\"generation\":3,"
            + "\"displayName\":\"taichi_ms\",\"world\":{\"kind\":\"SERVER_NAMESPACE\",\"id\":\"1111aaaa-1111-4111-8111-11111111bbbb\"},"
            + "\"dimension\":\"minecraft:overworld\",\"binding\":{\"worldNamespace\":\"1111aaaa-1111-4111-8111-11111111bbbb\","
            + "\"projectId\":\"3333eeee-3333-4333-8333-33333333ffff\",\"materialListId\":\"4444abcd-4444-4444-8444-44444444abcd\","
            + "\"regionListId\":\"5555dcba-5555-4555-8555-55555555dcba\"},\"containers\":[{\"pos\":[-5,-64,2],\"block\":\"minecraft:barrel\"},"
            + "{\"pos\":[10,63,9],\"block\":\"minecraft:chest\"},{\"pos\":[10,64,-3],\"block\":\"minecraft:chest\"}]}\n", json);
        assertEquals(profile, codec.decodeProfile(json));
        assertEquals(json, codec.encode(codec.decodeProfile(json.getBytes(StandardCharsets.UTF_8))));
        var japanese = LocationProfile.create(new WorldIdentity(WORLD), "minecraft:the_nether", "  倉庫 <A&B> \"引用\" \\ 🧱 ").bind(SCOPE).unbind();
        assertEquals("倉庫 <A&B> \"引用\" \\ 🧱", japanese.displayName()); assertEquals(2, japanese.generation()); assertNull(japanese.binding());
        assertEquals(codec.encode(japanese), codec.encode(codec.decodeProfile(codec.encode(japanese))));
        var selection = new LocationSelection(new WorldIdentity(WORLD), ID, 7);
        assertEquals("{\"schema\":1,\"kind\":\"LOCATION_SELECTION\",\"world\":{\"kind\":\"SERVER_NAMESPACE\",\"id\":\"1111aaaa-1111-4111-8111-11111111bbbb\"},"
            + "\"profileId\":\"2222cccc-2222-4222-8222-22222222dddd\",\"generation\":7}\n", codec.encode(selection));
        assertEquals(selection, codec.decodeSelection(codec.encode(selection)));
        var cleared = selection.select(null);
        assertEquals(cleared, codec.decodeSelection(codec.encode(cleared))); assertTrue(codec.encode(cleared).contains("\"profileId\":null"));
        corrupt(() -> codec.decodeProfile(codec.encode(selection)));
        corrupt(() -> codec.decodeSelection(json));
        corrupt(() -> codec.decodeSelection(codec.encode(selection).replace("LOCATION_SELECTION", "LOCATION_PROFILE")));
    }

    @Test void rejectsUnknownDuplicateMissingFieldsAndWrongTypes() {
        String json = codec.encode(sample());
        corrupt(() -> codec.decodeProfile(json.replace("\"generation\":3,", "\"generation\":3,\"extra\":1,")));
        corrupt(() -> codec.decodeProfile(json.replace("\"generation\":3,", "\"generation\":3,\"generation\":3,")));
        corrupt(() -> codec.decodeProfile(json.replace("\"binding\":null,", "")));
        corrupt(() -> codec.decodeProfile(json.replace("\"kind\":\"SERVER_NAMESPACE\",", "\"kind\":\"SERVER_NAMESPACE\",\"address\":\"x\",")));
        corrupt(() -> codec.decodeProfile(json.replace("\"kind\":\"SERVER_NAMESPACE\",", "\"kind\":\"SERVER_ADDRESS\",")));
        corrupt(() -> codec.decodeProfile(json.replace("\"block\":\"minecraft:chest\"", "\"block\":\"minecraft:chest\",\"count\":1")));
        corrupt(() -> codec.decodeProfile(json.replace(",\"block\":\"minecraft:chest\"", "")));
        corrupt(() -> codec.decodeProfile(json.replace("\"generation\":3", "\"generation\":\"3\"")));
        corrupt(() -> codec.decodeProfile(json.replace("\"binding\":null", "\"binding\":[]")));
        corrupt(() -> codec.decodeProfile(json.replace("\"displayName\":\"taichi_ms\"", "\"displayName\":7")));
        corrupt(() -> codec.decodeProfile(json.replace("[10,64,-3]", "[10,64]")));
        corrupt(() -> codec.decodeProfile(json.replace("[10,64,-3]", "[10,64,-3,0]")));
        corrupt(() -> codec.decodeProfile(json.replace("[10,64,-3]", "[10,true,-3]")));
        corrupt(() -> codec.decodeProfile(json.replace("\"block\":\"minecraft:chest\"", "\"block\":null")));
        corrupt(() -> codec.decodeProfile(json.replace("\"containers\":[", "\"containers\":{\"a\":[").replace("]}\n", "]}}\n")));
        corrupt(() -> codec.decodeProfile(json + "{}"));
        corrupt(() -> codec.decodeProfile("{\"schema\":1"));
        corrupt(() -> codec.decodeProfile("[]"));
    }

    @Test void requiresCanonicalUuidsAndBoundedIntegers() {
        String json = codec.encode(sample());
        corrupt(() -> codec.decodeProfile(json.replace(ID.toString(), ID.toString().toUpperCase(java.util.Locale.ROOT))));
        corrupt(() -> codec.decodeProfile(json.replace("\"id\":\"" + WORLD, "\"id\":\"" + WORLD.toString().toUpperCase(java.util.Locale.ROOT))));
        corrupt(() -> codec.decodeProfile(json.replace(ID.toString(), "222cccc-2222-4222-8222-22222222dddd")));
        corrupt(() -> codec.decodeProfile(json.replace(ID.toString(), "{" + ID + "}")));
        corrupt(() -> codec.decodeProfile(json.replace("\"generation\":3", "\"generation\":-1")));
        corrupt(() -> codec.decodeProfile(json.replace("\"generation\":3", "\"generation\":9223372036854775808")));
        corrupt(() -> codec.decodeProfile(json.replace("\"generation\":3", "\"generation\":3.0")));
        corrupt(() -> codec.decodeProfile(json.replace("\"generation\":3", "\"generation\":3e0")));
        corrupt(() -> codec.decodeProfile(json.replace("[10,64,-3]", "[2147483648,64,-3]")));
        corrupt(() -> codec.decodeProfile(json.replace("[10,64,-3]", "[30000001,64,-3]")));
        corrupt(() -> codec.decodeProfile(json.replace("[10,64,-3]", "[10,64,-30000001]")));
        corrupt(() -> codec.decodeProfile(json.replace("[10,64,-3]", "[10,2048,-3]")));
        corrupt(() -> codec.decodeProfile(json.replace("[10,64,-3]", "[10,-2049,-3]")));
        assertEquals(-2048, codec.decodeProfile(json.replace("[10,64,-3]", "[30000000,-2048,-30000000]")).positions().get(0).y());
        var withBinding = codec.encode(new LocationProfile(ID, 0, "x", new WorldIdentity(WORLD), "minecraft:overworld", SCOPE, List.of()));
        corrupt(() -> codec.decodeProfile(withBinding.replace(SCOPE.projectId().toString(), SCOPE.projectId().toString().toUpperCase(java.util.Locale.ROOT))));
        corrupt(() -> codec.decodeProfile(withBinding.replace(SCOPE.regionListId().toString(), SCOPE.materialListId().toString())));
        var selection = codec.encode(new LocationSelection(new WorldIdentity(WORLD), ID, 0));
        corrupt(() -> codec.decodeSelection(selection.replace(ID.toString(), ID.toString().toUpperCase(java.util.Locale.ROOT))));
        corrupt(() -> codec.decodeSelection(selection.replace("\"generation\":0", "\"generation\":-1")));
    }

    @Test void boundsDepthNodesSizeAndUtf8() {
        String json = codec.encode(sample());
        corrupt(() -> codec.decodeProfile(json.replace("[10,64,-3]", "[[[[[[[[[[[10]]]]]]]]]],64,-3]")));
        corrupt(() -> codec.decodeProfile(json.replace("\"binding\":null", "\"binding\":" + "[".repeat(100000) + "]".repeat(100000))));
        corrupt(() -> codec.decodeProfile(new byte[]{'{', '"', (byte) 0xc3, 0x28, '"', '}'}));
        byte[] valid = json.getBytes(StandardCharsets.UTF_8); byte[] broken = valid.clone();
        int at = json.indexOf("taichi"); broken[at] = (byte) 0xff;
        corrupt(() -> codec.decodeProfile(broken));
        corrupt(() -> codec.decodeProfile(new byte[LocationProfileCodec.MAX_BYTES + 1]));
        corrupt(() -> codec.decodeProfile(json.replace("taichi_ms", "\\ud800")));
    }

    @Test void enforcesContainerCapacityUniquenessAndNamespacedIds() {
        var world = new WorldIdentity(WORLD);
        List<ContainerPosition> many = new ArrayList<>();
        for (int i = 0; i <= LocationLimits.MAX_CONTAINERS; i++) many.add(new ContainerPosition(i, 0, 0, "minecraft:chest"));
        assertThrows(IllegalArgumentException.class, () -> new LocationProfile(ID, 0, "x", world, "minecraft:overworld", null, many));
        var full = new LocationProfile(ID, 0, "x", world, "minecraft:overworld", null, many.subList(0, LocationLimits.MAX_CONTAINERS));
        String fullJson = codec.encode(full); assertEquals(full, codec.decodeProfile(fullJson));
        corrupt(() -> codec.decodeProfile(fullJson.replace("]}\n", ",{\"pos\":[-1,0,0],\"block\":\"minecraft:chest\"}]}\n")));
        assertThrows(IllegalArgumentException.class, () -> new LocationProfile(ID, 0, "x", world, "minecraft:overworld", null,
            List.of(new ContainerPosition(1, 2, 3, "minecraft:chest"), new ContainerPosition(1, 2, 3, "minecraft:barrel"))));
        String json = codec.encode(sample());
        corrupt(() -> codec.decodeProfile(json.replace("]}\n", ",{\"pos\":[10,64,-3],\"block\":\"minecraft:barrel\"}]}\n")));
        for (String dimension : List.of("Minecraft:overworld", "overworld", "minecraft:", ":overworld", "minecraft:over world", "minecraft:ovérworld",
            "minecraft:a:b", "minecraft:" + "a".repeat(247))) {
            corrupt(() -> codec.decodeProfile(json.replace("minecraft:overworld", dimension)));
            assertThrows(IllegalArgumentException.class, () -> LocationProfile.create(world, dimension, "x"));
        }
        assertEquals("minecraft:" + "a".repeat(246), LocationProfile.create(world, "minecraft:" + "a".repeat(246), "x").dimension());
        assertEquals("mod-x.y_z:dim/sub.0", LocationProfile.create(world, "mod-x.y_z:dim/sub.0", "x").dimension());
        corrupt(() -> codec.decodeProfile(json.replace("minecraft:chest", "Chest")));
        assertThrows(IllegalArgumentException.class, () -> new ContainerPosition(0, 0, 0, "minecraft:../chest x"));
    }

    @Test void rejectsUnsafeOrOutOfRangeDisplayNames() {
        var world = new WorldIdentity(WORLD);
        String json = codec.encode(sample());
        for (String name : List.of("", "   ", "　", "a".repeat(65), "🧱".repeat(65), "a\u0007b", "a\nb", "a\u0085b", "a‮b", "a‪b",
            "a⁦b", "a⁩b", "a‎b", "a‏b", "a؜b", "\ud800")) {
            assertThrows(IllegalArgumentException.class, () -> LocationProfile.create(world, "minecraft:overworld", name), name);
            assertFalse(LocationLimits.displayName(name));
        }
        assertEquals(64, LocationProfile.create(world, "minecraft:overworld", "🧱".repeat(64)).displayName().codePointCount(0, 128));
        assertEquals("a".repeat(64), LocationProfile.create(world, "minecraft:overworld", " " + "a".repeat(64) + "\t").displayName());
        corrupt(() -> codec.decodeProfile(json.replace("taichi_ms", " taichi_ms")));
        corrupt(() -> codec.decodeProfile(json.replace("taichi_ms", "")));
        corrupt(() -> codec.decodeProfile(json.replace("taichi_ms", "a\\u202eb")));
        corrupt(() -> codec.decodeProfile(json.replace("taichi_ms", "a\\u0000b")));
        corrupt(() -> codec.decodeProfile(json.replace("taichi_ms", "a".repeat(65))));
        assertThrows(IllegalArgumentException.class, () -> sample().rename("⁧"));
    }

    @Test void rejectsBindingToAnotherWorld() {
        var other = ProjectScope.create(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> new LocationProfile(ID, 0, "x", new WorldIdentity(WORLD), "minecraft:overworld", other, List.of()));
        assertThrows(IllegalArgumentException.class, () -> sample().bind(other));
        String json = codec.encode(sample().bind(SCOPE));
        corrupt(() -> codec.decodeProfile(json.replace("\"worldNamespace\":\"" + WORLD, "\"worldNamespace\":\"" + other.worldNamespace())));
        assertEquals(4, sample().bind(SCOPE).generation());
        assertThrows(ArithmeticException.class, () -> new LocationProfile(ID, Long.MAX_VALUE, "x", new WorldIdentity(WORLD), "minecraft:overworld", null, List.of()).rename("y"));
    }

    @Test void classifiesSchemaBeforeInterpretingFields() {
        String json = codec.encode(sample());
        assertThrows(UnsupportedSchemaException.class, () -> codec.decodeProfile(json.replace("\"schema\":1", "\"schema\":2")));
        assertThrows(UnsupportedSchemaException.class, () -> codec.decodeProfile("{\"schema\":2,\"future\":[1.5,{\"x\":true}]}"));
        assertThrows(UnsupportedSchemaException.class, () -> codec.decodeProfile("{\"schema\":100000000000000000000000}"));
        assertThrows(UnsupportedSchemaException.class, () -> codec.decodeSelection("{\"schema\":2}"));
        for (String schema : List.of("0", "1.5", "\"1\"", "-1", "null", "true", "0.5"))
            corrupt(() -> codec.decodeProfile(json.replace("\"schema\":1", "\"schema\":" + schema)));
        corrupt(() -> codec.decodeProfile(json.replace("\"schema\":1,", "")));
        corrupt(() -> codec.decodeProfile(json.replace("\"schema\":1,", "\"schema\":1,\"schema\":2,")));
        corrupt(() -> codec.decodeSelection("{\"schema\":0}"));
    }

    private static LocationProfile sample() {
        return new LocationProfile(ID, 3, "taichi_ms", new WorldIdentity(WORLD), "minecraft:overworld", null, List.of(new ContainerPosition(10, 64, -3, "minecraft:chest")));
    }
    private static void corrupt(org.junit.jupiter.api.function.Executable decode) {
        var failure = assertThrows(IllegalArgumentException.class, decode);
        assertFalse(failure instanceof UnsupportedSchemaException, "Expected corrupt, not unsupported schema");
    }
}
