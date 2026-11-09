package dev.mcmateriallist.core.persistence;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.work.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 5A: exceeding the 4000 active+archived task limit is an explicit bounded refusal, never CORRUPT, and keeps the file. */
class CapacityRefusalTest {
    @TempDir Path root;
    private final LocalPlacementId placement = LocalPlacementId.create();

    static List<RegionDescriptor> regions(int count, int generation) {
        List<RegionDescriptor> regions = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            regions.add(new RegionDescriptor(String.format(Locale.ROOT, "Region %04d", i), new Vector3(i * 16, generation, 0), new Vector3(16, 16, 16)));
        return regions;
    }
    /** The first {@code moved} regions get a new origin, so each refresh archives exactly {@code moved} old definitions. */
    static List<RegionDescriptor> moved(int count, int moved, int generation) {
        List<RegionDescriptor> regions = new ArrayList<>(regions(count, 0));
        for (int i = 0; i < moved; i++) regions.set(i, new RegionDescriptor(regions.get(i).key(), new Vector3(i * 16, generation, 0), new Vector3(16, 16, 16)));
        return regions;
    }

    @Test void encoderReportsTaskLimitAsCapacityNotGenericInvalidInput() {
        var dataset = RegionWorkDataset.create(placement).reconcile(regions(2000, 0)).dataset();
        for (int round = 1; round <= 4; round++) dataset = dataset.reconcile(moved(2000, 500, round)).dataset();
        assertEquals(4000, dataset.definitions().size() + dataset.archived().size());
        new DatasetJsonCodec().encode(dataset); // exactly at the limit still encodes
        var over = dataset.reconcile(moved(2000, 500, 5)).dataset();
        assertThrows(CapacityExceededException.class, () -> new DatasetJsonCodec().encode(over));
    }

    @Test void localRegionRefreshPastTheLimitIsTooLargeAndKeepsThePreviousFile() throws Exception {
        try (var service = new LocalWorkService(root)) {
            assertEquals(StoreStatus.OK, service.refreshRegions(new WorkSnapshot(placement, List.of(), regions(2000, 0))).get().storage().status());
            for (int round = 1; round <= 4; round++)
                assertEquals(StoreStatus.OK, service.refreshRegions(new WorkSnapshot(placement, List.of(), moved(2000, 500, round))).get().storage().status());
            Path file = root.resolve("placements").resolve(placement.toString()).resolve("regions.json");
            byte[] before = Files.readAllBytes(file);
            var refused = service.refreshRegions(new WorkSnapshot(placement, List.of(), moved(2000, 500, 5))).get();
            assertEquals(StoreStatus.TOO_LARGE, refused.storage().status(), "Over-limit history must be a bounded refusal");
            assertArrayEquals(before, Files.readAllBytes(file), "A refused save must not touch the primary file");
            var reloaded = service.load(placement, DatasetKind.REGIONS).get();
            assertEquals(StoreStatus.OK, reloaded.status());
            assertEquals(4000, ((RegionWorkDataset) reloaded.dataset()).definitions().size() + ((RegionWorkDataset) reloaded.dataset()).archived().size());
            // Work on the active rows continues after the refusal.
            var id = ((RegionWorkDataset) reloaded.dataset()).definitions().keySet().stream()
                .filter(key -> !((RegionWorkDataset) reloaded.dataset()).reviewRequired().contains(key)).findFirst().orElseThrow();
            assertEquals(StoreStatus.OK, service.update(placement, id, new TaskCommand.SetDone(true), java.util.UUID.randomUUID(), java.time.Instant.EPOCH).get().storage().status());
        }
    }
}
