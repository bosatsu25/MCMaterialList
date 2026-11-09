package dev.mcmateriallist.core.ui;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.Perf5A;
import dev.mcmateriallist.core.work.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 5A item 1: the cached row index answers exactly like the per-row rescans it replaces, and costs O(1) per row. */
class RegionRowIndexTest {
    /** The pre-5A RegionWorkSession.id(key) rule for local/detached work, verbatim. */
    static RegionTaskId legacyLocal(List<RegionDescriptor> observed, RegionWorkDataset dataset, String key) {
        var descriptors = observed.stream().filter(region -> region.key().equals(key)).toList();
        if (descriptors.size() != 1) return null;
        var matches = dataset.definitions().values().stream().filter(definition -> definition.descriptor().equals(descriptors.getFirst())).toList();
        return matches.size() == 1 ? matches.getFirst().id() : null;
    }
    /** The pre-5A SharedWorkBackend.region(key) rule, verbatim. */
    static RegionTaskId legacyShared(List<RegionDescriptor> observed, Map<RegionTaskId, RegionDescriptor> regions, String key) {
        var descriptors = observed.stream().filter(region -> region.key().equals(key)).toList();
        if (descriptors.size() != 1) return null;
        return regions.entrySet().stream().filter(region -> region.getValue().equals(descriptors.getFirst())).map(Map.Entry::getKey).findFirst().orElse(null);
    }
    static List<RegionDescriptor> regions(int count) {
        List<RegionDescriptor> regions = new ArrayList<>();
        for (int i = 0; i < count; i++) regions.add(new RegionDescriptor(String.format(Locale.ROOT, "Region %04d", i), new Vector3(i * 16, 0, 0), new Vector3(16, 16, 16)));
        return regions;
    }

    @Test void matchesLegacyRulesIncludingDuplicatesMovesAndUnknownNames() {
        Random random = new Random(55);
        for (int round = 0; round < 200; round++) {
            List<RegionDescriptor> base = new ArrayList<>();
            int count = 1 + random.nextInt(40);
            for (int i = 0; i < count; i++)
                base.add(new RegionDescriptor("R" + random.nextInt(count), new Vector3(random.nextInt(4), 0, 0), new Vector3(1 + random.nextInt(2), 1, 1)));
            var dataset = RegionWorkDataset.create(LocalPlacementId.create()).reconcile(base).dataset();
            List<RegionDescriptor> observed = new ArrayList<>(base);
            for (int i = 0; i < observed.size(); i++) if (random.nextInt(5) == 0)
                observed.set(i, new RegionDescriptor(observed.get(i).key(), new Vector3(9, random.nextInt(3), 0), observed.get(i).size()));
            if (random.nextBoolean()) observed.add(base.get(random.nextInt(base.size())));
            Map<RegionTaskId, RegionDescriptor> manifest = new HashMap<>();
            new LinkedHashSet<>(base).forEach(descriptor -> manifest.put(RegionTaskId.create(), descriptor));
            var local = RegionRowIndex.forDataset(observed, dataset);
            var shared = RegionRowIndex.forManifest(observed, manifest);
            Set<String> keys = new HashSet<>(); observed.forEach(d -> keys.add(d.key())); keys.add("absent"); keys.add("R" + count);
            for (String key : keys) {
                assertEquals(legacyLocal(observed, dataset, key), local.id(key), "local " + key + " round " + round);
                assertEquals(legacyShared(observed, manifest, key), shared.id(key), "shared " + key + " round " + round);
            }
        }
        assertNull(RegionRowIndex.EMPTY.id("Region 1"));
        assertNull(RegionRowIndex.forDataset(null, null).id(null));
    }

    @Test void perRowResolutionCostAtTwoThousandRegions() throws Exception {
        var observed = regions(2000);
        var dataset = RegionWorkDataset.create(LocalPlacementId.create()).reconcile(observed).dataset();
        List<String> keys = observed.stream().map(RegionDescriptor::key).toList();
        // The pre-5A path rebuilt the descriptor list (a full "capture") and rescanned all definitions for every row.
        var legacy = Perf5A.measure(1, 3, () -> {
            for (String key : keys) assertNotNull(legacyLocal(List.copyOf(regions(2000)), dataset, key));
        });
        var indexed = Perf5A.measure(3, 10, () -> {
            var index = RegionRowIndex.forDataset(List.copyOf(regions(2000)), dataset);
            for (String key : keys) assertNotNull(index.id(key));
        });
        var build = Perf5A.measure(3, 20, () -> RegionRowIndex.forDataset(observed, dataset));
        Perf5A.record("region-rows-resolve-all", "regions", 2000, "legacyPerRowRescan", legacy, "indexOncePerObservation", indexed, "indexBuild", build);
        // Core-side model of one native region capture (2000 validated descriptors). Before 5A each visible row's render
        // captured twice per frame (Done button enablement and its hover feedback, both via ready()); a 1080p list shows 11 rows.
        var capture = Perf5A.measure(20, 50, () -> regions(2000));
        Perf5A.record("region-rows-render-model", "regions", 2000, "captureModel", capture, "visibleRows", 11, "capturesPerRowPerFrameBefore", 2,
            "modelCoreMsPerFrameBefore", Perf5A.ms(capture.p50Nanos() * 22), "capturesPerTickAfter", 1, "modelCoreMsPerTickAfter", Perf5A.ms(capture.p50Nanos()));
        // Generous ceiling only: one full list resolution through the index must stay well below a second on any CI host.
        assertTrue(indexed.p50Nanos() < 1_000_000_000L, "Indexed resolution of 2000 rows took " + Perf5A.ms(indexed.p50Nanos()) + " ms");
    }
}
