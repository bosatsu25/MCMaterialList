package dev.mcmateriallist.core;

import dev.mcmateriallist.core.location.ContainerBlocks;
import dev.mcmateriallist.core.location.ContainerKind;
import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationLimits;
import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.core.location.WorldIdentity;
import dev.mcmateriallist.core.persistence.DatasetJsonCodec;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.SharedClientArchive;
import dev.mcmateriallist.core.shared.client.SharedClientService;
import dev.mcmateriallist.core.shared.client.SharedClientStore;
import dev.mcmateriallist.core.shared.protocol.SharedTransfers;
import dev.mcmateriallist.core.stock.StockAdmission;
import dev.mcmateriallist.core.stock.StockLimits;
import dev.mcmateriallist.core.stock.StockScan;
import dev.mcmateriallist.core.stock.StockScheduler;
import dev.mcmateriallist.core.work.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Equivalence partitioning and 3-value boundary value analysis (JSTQB FL v4.0, 4.2.1 / 4.2.2) of the explicit core limits.
 * Every method carries the condition ID of docs/test-design.md section 2. Values already exercised by an existing test are
 * listed there and deliberately not repeated here.
 */
class BoundaryValueTest {
    @TempDir Path root;
    private static final UUID ACTOR = new UUID(0, 1);
    private static final Instant T0 = Instant.parse("2026-11-09T00:00:00Z");
    private static final MaterialTaskId STONE = new MaterialTaskId("minecraft:stone");

    private static String bytes(int count) { return "n".repeat(count); }
    private static TransitionResult setNote(String note) {
        return TaskTransitions.apply(TaskState.empty(STONE), new TaskCommand.SetNote(note), ACTOR, T0);
    }

    /** BV-01a: note limit TextLimits.NOTE_BYTES = 4096 UTF-8 bytes, single-byte characters: 4095 / 4096 valid, 4097 invalid. */
    @Test void bv01aNoteBytesAsciiBoundary() {
        assertEquals(4096, TextLimits.NOTE_BYTES);
        for (int valid : new int[]{TextLimits.NOTE_BYTES - 1, TextLimits.NOTE_BYTES}) {
            assertTrue(TextLimits.valid(bytes(valid), TextLimits.NOTE_BYTES), "valid " + valid);
            assertInstanceOf(TransitionResult.Changed.class, setNote(bytes(valid)), "local SetNote " + valid);
            assertDoesNotThrow(() -> new SharedCommand.SetNote(bytes(valid)), "shared SetNote " + valid);
        }
        int invalid = TextLimits.NOTE_BYTES + 1;
        assertFalse(TextLimits.valid(bytes(invalid), TextLimits.NOTE_BYTES));
        assertEquals(TransitionResult.Reason.INVALID_INPUT, assertInstanceOf(TransitionResult.Rejected.class, setNote(bytes(invalid))).reason());
        assertThrows(IllegalArgumentException.class, () -> new SharedCommand.SetNote(bytes(invalid)));
        assertThrows(IllegalArgumentException.class, () -> new TaskState(STONE, null, false, bytes(invalid), null, null, 0));
    }

    /** BV-01b: the limit counts UTF-8 bytes, not characters: 3-byte and 4-byte characters around 4096 bytes. */
    @Test void bv01bNoteBytesMultibyteBoundary() {
        String kana4095 = "あ".repeat(1365);                 // 4095 bytes
        assertTrue(TextLimits.valid(kana4095, TextLimits.NOTE_BYTES));
        assertTrue(TextLimits.valid(kana4095 + "a", TextLimits.NOTE_BYTES));   // 4096 bytes
        assertFalse(TextLimits.valid(kana4095 + "ab", TextLimits.NOTE_BYTES)); // 4097 bytes, only 1367 characters
        assertFalse(TextLimits.valid("あ".repeat(1366), TextLimits.NOTE_BYTES)); // 4098 bytes
        String emoji4096 = "😀".repeat(1024);                // 1024 code points, 2048 chars, 4096 bytes
        assertTrue(TextLimits.valid(emoji4096, TextLimits.NOTE_BYTES));
        assertFalse(TextLimits.valid(emoji4096 + "a", TextLimits.NOTE_BYTES));
    }

    /** EP-01: note partitions outside the length dimension: empty (valid lower bound, 0 bytes), null and unencodable (invalid). */
    @Test void ep01NoteValidityPartitions() {
        assertTrue(TextLimits.valid("", TextLimits.NOTE_BYTES));
        assertFalse(TextLimits.valid(null, TextLimits.NOTE_BYTES));
        assertFalse(TextLimits.valid("a\ud800b", TextLimits.NOTE_BYTES), "Lone surrogate cannot be stored as UTF-8");
        assertEquals(TransitionResult.Reason.INVALID_INPUT, assertInstanceOf(TransitionResult.Rejected.class, setNote(null)).reason());
        assertEquals(TransitionResult.Reason.INVALID_INPUT, assertInstanceOf(TransitionResult.Rejected.class, setNote("\udc00")).reason());
    }

    private static SharedManifest manifest() {
        return SharedManifest.initial(new SharedManifest.Definition("d".repeat(64), new SharedManifest.Baseline("minecraft:overworld",
            new Vector3(0, 64, 0), SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), Map.of(STONE, 1L), List.of()));
    }
    private static ProjectSnapshot withMembers(int count) {
        var manifest = manifest(); Map<UUID, ProjectRole> members = new HashMap<>();
        members.put(ACTOR, ProjectRole.ADMINISTRATOR);
        for (int i = 1; i < count; i++) members.put(new UUID(1, i), ProjectRole.VIEWER);
        return new ProjectSnapshot(ProjectScope.create(UUID.randomUUID()), "bva", ACTOR, members, 0, manifest,
            Map.of(STONE, TaskState.empty(STONE)), Set.of());
    }

    /** BV-02: SharedLimits.PARTICIPANTS = 20 enforced by every snapshot: 19 / 20 accepted, 21 rejected; count() lower edge -1 / 0. */
    @Test void bv02ParticipantsBoundary() {
        assertEquals(19, withMembers(SharedLimits.PARTICIPANTS - 1).members().size());
        assertEquals(20, withMembers(SharedLimits.PARTICIPANTS).members().size());
        assertThrows(IllegalArgumentException.class, () -> withMembers(SharedLimits.PARTICIPANTS + 1));
        assertThrows(IllegalArgumentException.class, () -> SharedLimits.count(-1, SharedLimits.PARTICIPANTS));
        assertDoesNotThrow(() -> SharedLimits.count(0, SharedLimits.PARTICIPANTS));
        assertDoesNotThrow(() -> SharedLimits.count(1, SharedLimits.PARTICIPANTS));
    }

    /** BV-03: SharedLimits.ROWS_PER_LIST = 2000 submitted materials per definition: 1999 / 2000 accepted, 2001 rejected. */
    @Test void bv03RowsPerListBoundary() {
        var baseline = manifest().definition().baseline();
        for (int count : new int[]{SharedLimits.ROWS_PER_LIST - 1, SharedLimits.ROWS_PER_LIST, SharedLimits.ROWS_PER_LIST + 1}) {
            Map<MaterialTaskId, Long> materials = new HashMap<>();
            for (int i = 0; i < count; i++) materials.put(new MaterialTaskId("m:item_" + i), 1L);
            if (count <= SharedLimits.ROWS_PER_LIST) assertEquals(count, new SharedManifest.Definition("d".repeat(64), baseline, materials, List.of()).materials().size());
            else assertThrows(IllegalArgumentException.class, () -> new SharedManifest.Definition("d".repeat(64), baseline, materials, List.of()));
        }
    }

    /** BV-04: SharedLimits.MESSAGE_BYTES (32 MiB) on the fragmenting transfer: 0 / 1 and MAX-1 / MAX / MAX+1 bytes. */
    @Test void bv04MessageBytesBoundary() {
        assertThrows(IllegalArgumentException.class, () -> SharedTransfers.split(UUID.randomUUID(), new byte[0]));
        assertEquals(1, SharedTransfers.split(UUID.randomUUID(), new byte[1]).size());
        assertEquals(SharedLimits.FRAGMENTS, SharedTransfers.split(UUID.randomUUID(), new byte[SharedLimits.MESSAGE_BYTES - 1]).size());
        var max = SharedTransfers.split(UUID.randomUUID(), new byte[SharedLimits.MESSAGE_BYTES]);
        assertEquals(SharedLimits.FRAGMENTS, max.size(), "The largest message must fit the fragment count limit exactly");
        assertEquals(SharedLimits.MESSAGE_BYTES - (SharedLimits.FRAGMENTS - 1) * SharedLimits.FRAGMENT_BYTES, max.getLast().bytes().length);
        assertThrows(IllegalArgumentException.class, () -> SharedTransfers.split(UUID.randomUUID(), new byte[SharedLimits.MESSAGE_BYTES + 1]));
    }

    private static SharedClientArchive.Operation operation(ProjectScope scope, SharedClientArchive.Reference ref, SharedClientArchive.Confirmation confirmation) {
        return new SharedClientArchive.Operation(DatasetKind.MATERIALS, ref, UUID.randomUUID(),
            new SharedCommand(scope, STONE, UUID.randomUUID(), 0, 0, new SharedCommand.Claim()), confirmation);
    }
    private static SharedClientArchive journal(LocalPlacementId placement, int unknown, int confirmed) {
        var scope = ProjectScope.create(UUID.randomUUID()); var ref = new SharedClientArchive.Reference(UUID.randomUUID(), ACTOR, scope);
        List<SharedClientArchive.Operation> operations = new ArrayList<>();
        for (int i = 0; i < confirmed; i++) operations.add(operation(scope, ref, new SharedClientArchive.Confirmation(SharedEvents.Status.OK, i)));
        for (int i = 0; i < unknown; i++) operations.add(operation(scope, ref, null));
        var empty = new SharedClientArchive.Binding(null, null, null);
        return new SharedClientArchive(0, placement, empty, empty, operations);
    }

    /** BV-05: SharedClientArchive.MAX_OPERATIONS = 128 journal entries per save unit: 127 / 128 accepted, 129 rejected. */
    @Test void bv05ClientJournalCapacityBoundary() {
        var placement = LocalPlacementId.create();
        assertEquals(127, journal(placement, 63, 64).operations().size());
        assertEquals(128, journal(placement, 64, 64).operations().size());
        assertThrows(IllegalArgumentException.class, () -> journal(placement, 65, 64));
    }

    /**
     * BV-06: SharedClientService.UNKNOWN_OPERATIONS = 64 unresolved originals: with 63 unknown another admission is journaled
     * (the 64th), with 64 it is refused BUSY (the 65th, already in SharedClientServiceTest) without touching the journal.
     */
    @Test void bv06UnknownOperationsBoundary() {
        var snapshot = withMembers(1); var placement = LocalPlacementId.create();
        var ref = new SharedClientArchive.Reference(UUID.randomUUID(), ACTOR, snapshot.scope());
        var original = journal(placement, SharedClientService.UNKNOWN_OPERATIONS - 1, 0);
        var archive = new SharedClientArchive(0, placement, new SharedClientArchive.Binding(DatasetId.create(), ref, new SharedClientArchive.Cache(snapshot, 0)),
            original.regions(), original.operations());
        assertEquals(StoreStatus.OK, new SharedClientStore(root).save(archive, -1).status());
        try (var service = new SharedClientService(root)) {
            var command = new SharedCommand(snapshot.scope(), STONE, UUID.randomUUID(), 0, 0, new SharedCommand.SetDone(true));
            var admitted = service.admit(placement, DatasetKind.MATERIALS, ref, ACTOR, UUID.randomUUID(), command).join();
            assertTrue(admitted.ok(), String.valueOf(admitted.status()));
            assertEquals(SharedClientService.UNKNOWN_OPERATIONS, admitted.value().operations().stream().filter(o -> o.confirmation() == null).count());
        }
    }

    private static MaterialWorkDataset materials(int active, int archived) {
        Map<MaterialTaskId, MaterialDefinition> definitions = new HashMap<>(); Map<MaterialTaskId, TaskState> states = new HashMap<>();
        Map<MaterialTaskId, ArchivedTask<MaterialDefinition>> history = new HashMap<>();
        for (int i = 0; i < active; i++) { var id = new MaterialTaskId("m:active_" + i); definitions.put(id, new MaterialDefinition(id, 1, 1, 0)); states.put(id, TaskState.empty(id)); }
        for (int i = 0; i < archived; i++) { var id = new MaterialTaskId("m:archived_" + i); history.put(id, new ArchivedTask<>(new MaterialDefinition(id, 1, 1, 0), TaskState.empty(id))); }
        return new MaterialWorkDataset(DatasetId.create(), LocalPlacementId.create(), 0, definitions, states, history);
    }

    /** BV-07: DatasetJsonCodec.MAX_TASKS = 4000 active + archived tasks per local dataset: 3999 / 4000 round-trip, 4001 refused both ways. */
    @Test void bv07DatasetTaskLimitBoundary() {
        var codec = new DatasetJsonCodec();
        var below = materials(3999, 0); assertEquals(below, codec.decode(codec.encode(below)));
        var exact = materials(3000, 1000); String exactJson = codec.encode(exact); assertEquals(exact, codec.decode(exactJson));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(materials(4001, 0)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(materials(4000, 1)), "Archived tasks count toward the limit");
        // A file one task over the limit (hand-edited) is refused on decode before any task is parsed.
        String extra = "{\"id\":\"m:extra\",\"total\":1,\"missing\":1,\"available\":0,\"state\":{\"assignee\":null,\"done\":false,\"note\":\"\","
            + "\"completedBy\":null,\"completedAt\":null,\"rowVersion\":0}},";
        String over = exactJson.replace("\"tasks\":[", "\"tasks\":[" + extra);
        assertNotEquals(exactJson, over);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(over));
    }

    private static final class ManualClock extends Clock {
        Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** BV-08: StockLimits.COOLDOWN = 5 s between one player's starts: 4.999 s refused, 5.000 s (existing) and 5.001 s admitted. */
    @Test void bv08StockCooldownBoundary() {
        assertEquals(Duration.ofSeconds(5), StockLimits.COOLDOWN);
        for (long elapsedMillis : new long[]{4_999, 5_000, 5_001}) {
            var clock = new ManualClock(); var admission = new StockAdmission(clock); UUID player = UUID.randomUUID();
            assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(player));
            clock.now = T0.plusMillis(elapsedMillis);
            assertEquals(elapsedMillis < 5_000 ? StockAdmission.Verdict.COOLDOWN : StockAdmission.Verdict.ADMITTED, admission.admit(player), elapsedMillis + " ms");
        }
    }

    /** BV-09: StockLimits.CONCURRENT_SCANS = 4 with the production constructor: the 4th scan is admitted, the 5th refused, a finish frees a slot. */
    @Test void bv09ConcurrentScansBoundary() {
        var admission = new StockAdmission(new ManualClock());
        for (int i = 1; i <= StockLimits.CONCURRENT_SCANS; i++) {
            assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(new UUID(2, i)), "scan " + i); assertEquals(i, admission.running());
        }
        UUID fifth = new UUID(2, 5);
        assertEquals(StockAdmission.Verdict.CAPACITY, admission.admit(fifth)); assertEquals(4, admission.running());
        admission.finish(new UUID(2, 1));
        assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(fifth)); assertEquals(4, admission.running());
    }

    /** BV-10: StockLimits.CONTAINERS_PER_TICK = 8: a scan of 7 / 8 containers ends in one tick, 9 needs a second tick with 1 container. */
    @Test void bv10ContainersPerTickBoundary() {
        for (int containers : new int[]{7, 8, 9}) {
            var world = new dev.mcmateriallist.core.stock.ContainerProbe() {
                @Override public Shape shape(String dimension, ContainerPosition registered) { return Present.single(registered.blockId()); }
                @Override public Reading read(String dimension, List<ContainerPosition> halves) { return new Contents(Map.of("minecraft:stone", 1L)); }
            };
            List<ContainerPosition> positions = new ArrayList<>();
            for (int i = 0; i < containers; i++) positions.add(new ContainerPosition(i, 64, 0, "minecraft:barrel"));
            long[] clock = {0};
            var scheduler = new StockScheduler(StockLimits.CONTAINERS_PER_TICK, StockLimits.NANOS_PER_TICK, () -> clock[0]);
            scheduler.add(new StockScan("minecraft:overworld", positions, world, (d, p) -> true));
            assertEquals(Math.min(containers, 8), scheduler.tick(), containers + " containers, first tick");
            assertEquals(containers > 8 ? 1 : 0, scheduler.tick(), containers + " containers, second tick");
            assertEquals(0, scheduler.size());
        }
    }

    private static final WorldIdentity WORLD = new WorldIdentity(new UUID(3, 3));

    /** BV-11: LocationLimits.MAX_CONTAINERS = 512 per Location File: 511 / 512 accepted (also via withContainer), 513 rejected. */
    @Test void bv11LocationContainerBoundary() {
        var profile = LocationProfile.create(WORLD, "minecraft:overworld", "bva");
        for (int i = 0; i < LocationLimits.MAX_CONTAINERS - 1; i++) profile = profile.withContainer(new ContainerPosition(i, 0, 0, "minecraft:chest"));
        assertEquals(511, profile.positions().size());
        var full = profile.withContainer(new ContainerPosition(-1, 0, 0, "minecraft:chest"));
        assertEquals(512, full.positions().size());
        assertThrows(IllegalArgumentException.class, () -> full.withContainer(new ContainerPosition(-2, 0, 0, "minecraft:chest")));
    }

    /**
     * BV-12: display name 1..64 code points after strip (LocationLimits.NAME_CODE_POINTS). Lower edge 0 / 1 / 2 and upper edge
     * 63 / 64 / 65 (0, 64 and 65 already in LocationProfileCodecTest). NAME_BYTES = 256 cannot bind before 64 code points
     * (at most 4 UTF-8 bytes each), so its own boundary is infeasible: 64 four-byte code points are exactly 256 bytes.
     */
    @Test void bv12LocationNameBoundary() {
        assertFalse(LocationLimits.displayName(""));
        assertTrue(LocationLimits.displayName("a"));
        assertTrue(LocationLimits.displayName("ab"));
        assertTrue(LocationLimits.displayName("名".repeat(63)));
        assertTrue(LocationLimits.displayName("名".repeat(64)));
        assertFalse(LocationLimits.displayName("名".repeat(65)));
        assertEquals(256, "🧱".repeat(64).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(LocationLimits.displayName("🧱".repeat(64)), "64 four-byte code points meet both limits exactly");
    }

    /** BV-13: container coordinates |x|,|z| <= 30,000,000 and -2048 <= y <= 2047 (LocationLimits), 3 values at each of the six edges. */
    @Test void bv13ContainerCoordinateBoundaries() {
        int h = LocationLimits.MAX_HORIZONTAL;
        for (int v : new int[]{h - 1, h, -h + 1, -h}) {
            assertDoesNotThrow(() -> new ContainerPosition(v, 0, 0, "minecraft:chest"), "x " + v);
            assertDoesNotThrow(() -> new ContainerPosition(0, 0, v, "minecraft:chest"), "z " + v);
        }
        for (int v : new int[]{h + 1, -h - 1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new ContainerPosition(v, 0, 0, "minecraft:chest"), "x " + v);
            assertThrows(IllegalArgumentException.class, () -> new ContainerPosition(0, 0, v, "minecraft:chest"), "z " + v);
        }
        for (int y : new int[]{LocationLimits.MIN_Y, LocationLimits.MIN_Y + 1, LocationLimits.MAX_Y - 1, LocationLimits.MAX_Y})
            assertDoesNotThrow(() -> new ContainerPosition(0, y, 0, "minecraft:chest"), "y " + y);
        for (int y : new int[]{LocationLimits.MIN_Y - 1, LocationLimits.MAX_Y + 1})
            assertThrows(IllegalArgumentException.class, () -> new ContainerPosition(0, y, 0, "minecraft:chest"), "y " + y);
    }

    /**
     * EP-02: ContainerBlocks allowlist as equivalence partitions. Valid: every one of the 23 members (chest, trapped chest,
     * barrel, plain and 16 dyed shulker boxes, 3 machines). Invalid partitions: other vanilla containers, wrong namespace,
     * wrong case / missing namespace, misspelt colour, empty and null. Kind: exactly the three machines are MACHINE.
     */
    @Test void ep02ContainerAllowlistPartitions() {
        List<String> colours = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
            "purple", "blue", "brown", "green", "red", "black");
        List<String> storage = new ArrayList<>(List.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box"));
        colours.forEach(colour -> storage.add("minecraft:" + colour + "_shulker_box"));
        List<String> machines = List.of("minecraft:hopper", "minecraft:dropper", "minecraft:dispenser");
        assertEquals(20, storage.size());
        for (String id : storage) { assertTrue(ContainerBlocks.supported(id), id); assertEquals(ContainerKind.STORAGE, ContainerBlocks.kind(id), id); }
        for (String id : machines) { assertTrue(ContainerBlocks.supported(id), id); assertEquals(ContainerKind.MACHINE, ContainerBlocks.kind(id), id); }
        for (String id : Arrays.asList("minecraft:ender_chest", "minecraft:furnace", "minecraft:crafter", "minecraft:chiseled_bookshelf",
                "othermod:barrel", "barrel", "minecraft:Barrel", "minecraft:silver_shulker_box", "minecraft:shulker_box ", "", null))
            assertFalse(ContainerBlocks.supported(id), String.valueOf(id));
    }
}
