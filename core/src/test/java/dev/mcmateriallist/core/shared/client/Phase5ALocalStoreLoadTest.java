package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.Perf5A;
import dev.mcmateriallist.core.persistence.*;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.work.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5A item 3: local regions.json and the detached region copy at 2000 regions, the history headroom left by
 * DatasetJsonCodec.MAX_TASKS (4000 active + archived), and the byte headroom to the 32 MiB file / MESSAGE_BYTES limits.
 */
class Phase5ALocalStoreLoadTest {
    @TempDir Path root;
    private final LocalPlacementId placement = LocalPlacementId.create();

    private static List<RegionDescriptor> regions(int count, int moved, int generation) { return DetachedCapacityTest.regions(count, moved, generation); }

    @Test void regionDatasetSizeAndStoreTimesAtTwoThousandAndAtTheTaskLimit() throws Exception {
        var codec = new DatasetJsonCodec(); var store = new LocalDatasetStore(root);
        var active = RegionWorkDataset.create(placement).reconcile(regions(2000, 0, 0)).dataset();
        // Representative work: every third region done, every tenth with a short note.
        UUID actor = UUID.randomUUID(); int index = 0;
        for (var id : active.definitions().keySet().stream().sorted(Comparator.comparing(RegionTaskId::externalForm)).toList()) {
            if (index % 3 == 0) active = active.apply(id, new TaskCommand.SetDone(true), actor, java.time.Instant.EPOCH).dataset();
            if (index % 10 == 0) active = active.apply(id, new TaskCommand.SetNote("屋根の確認 / check roof edge " + index), actor, java.time.Instant.EPOCH).dataset();
            index++;
        }
        var full = active;
        for (int round = 1; round <= 4; round++) full = full.reconcile(regions(2000, 500, round)).dataset();
        assertEquals(4000, full.definitions().size() + full.archived().size());
        for (var entry : Map.of("active2000", active, "atLimit4000", full).entrySet()) {
            WorkDataset dataset = entry.getValue();
            String[] json = new String[1];
            var encode = Perf5A.measure(3, 10, () -> json[0] = codec.encode(dataset));
            var decode = Perf5A.measure(3, 10, () -> codec.decode(json[0]));
            int bytes = json[0].getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= LocalDatasetStore.MAX_FILE_BYTES);
            var location = LocalPlacementId.create();
            WorkDataset relocated = dataset instanceof RegionWorkDataset r
                ? new RegionWorkDataset(r.id(), location, 0, r.definitions(), r.states(), r.archived(), r.reviewRequired()) : dataset;
            assertEquals(StoreStatus.OK, store.save(relocated, -1).status());
            long[] generation = {0};
            var save = Perf5A.measure(1, 8, () -> {
                var r = (RegionWorkDataset) relocated;
                var next = new RegionWorkDataset(r.id(), location, generation[0] + 1, r.definitions(), r.states(), r.archived(), r.reviewRequired());
                assertEquals(StoreStatus.OK, store.save(next, generation[0]).status()); generation[0]++;
            });
            var load = Perf5A.measure(2, 8, () -> assertTrue(store.load(location, DatasetKind.REGIONS).ok()));
            Perf5A.record("local-regions-json", "dataset", entry.getKey(), "bytes", bytes, "kib", Perf5A.kib(bytes),
                "fileLimitBytes", LocalDatasetStore.MAX_FILE_BYTES, "encode", encode, "decode", decode, "save", save, "load", load);
        }
        // History headroom: with 2000 active regions, 2000 archived definitions remain before refresh is refused as TOO_LARGE.
        for (int changedPerRefresh : new int[] {1, 10, 100, 500})
            Perf5A.record("local-regions-history-headroom", "active", 2000, "taskLimit", DatasetJsonCodec.MAX_TASKS, "changedRegionsPerRefresh", changedPerRefresh,
                "refreshesBeforeRefusal", (DatasetJsonCodec.MAX_TASKS - 2000) / changedPerRefresh);
    }

    @Test void detachedCopyOfTheLoadProfileAndWorstCaseBytes() throws Exception {
        var profile = new Phase5AProfile(5);
        var scope = ProjectScope.create(UUID.randomUUID());
        ProjectSnapshot snapshot = profile.progressed(scope, 11);
        RegionWorkDataset[] copy = new RegionWorkDataset[1];
        var detach = Perf5A.measure(3, 10, () -> copy[0] = RegionDetachment.copy(DatasetId.create(), placement, snapshot));
        int bytes = new DatasetJsonCodec().encode(copy[0]).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= SharedLimits.MESSAGE_BYTES);
        var archive = new SharedClientArchive(0, placement, new SharedClientArchive.Binding(null, null, null),
            new SharedClientArchive.Binding(null, null, null, new SharedClientArchive.Detached(copy[0], scope)), List.of());
        var store = new SharedClientStore(root);
        assertEquals(StoreStatus.OK, store.save(archive, -1).status());
        long[] generation = {0};
        var save = Perf5A.measure(1, 8, () -> {
            var next = new SharedClientArchive(generation[0] + 1, placement, archive.materials(), archive.regions(), List.of());
            assertEquals(StoreStatus.OK, store.save(next, generation[0]).status()); generation[0]++;
        });
        var load = Perf5A.measure(2, 8, () -> assertTrue(store.load(placement).ok()));
        Perf5A.record("detached-copy-load-profile", "regions", copy[0].definitions().size(), "jsonBytes", bytes, "kib", Perf5A.kib(bytes),
            "limitBytes", SharedLimits.MESSAGE_BYTES, "headroomFactor", String.format(Locale.ROOT, "%.0f", SharedLimits.MESSAGE_BYTES / (double) bytes),
            "copy", detach, "clientStoreSave", save, "clientStoreLoad", load);

        // Worst legal tasks: 4096-byte region key and note, plain and fully escaped (control characters become \\u00XX in JSON).
        for (boolean escaped : new boolean[] {false, true}) {
            String text = escaped ? "\u0001".repeat(4096) : "r".repeat(4096);
            List<RegionDescriptor> sample = new ArrayList<>();
            for (int i = 0; i < 100; i++) sample.add(new RegionDescriptor(text.substring(0, 4091) + String.format(Locale.ROOT, "%04d", i) + "x", new Vector3(i, 0, 0), new Vector3(1, 1, 1)));
            var dataset = RegionWorkDataset.create(placement).reconcile(sample).dataset();
            for (var id : List.copyOf(dataset.definitions().keySet()))
                dataset = dataset.apply(id, new TaskCommand.SetNote(text), UUID.randomUUID(), java.time.Instant.EPOCH).dataset();
            long perTask = new DatasetJsonCodec().encode(dataset).getBytes(StandardCharsets.UTF_8).length / 100;
            long atLimit = perTask * DatasetJsonCodec.MAX_TASKS;
            Perf5A.record("worst-case-task-json", "escaped", escaped, "bytesPerTask", perTask, "modelBytesAt4000Tasks", atLimit,
                "fileLimitBytes", LocalDatasetStore.MAX_FILE_BYTES, "maxTasksWithinFileLimit", LocalDatasetStore.MAX_FILE_BYTES / perTask);
        }
    }
}
