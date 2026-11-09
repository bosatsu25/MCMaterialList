package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.work.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 5A: a detached region copy whose local history outgrows the task limit is refused as TOO_LARGE, never CORRUPT. */
class DetachedCapacityTest {
    @TempDir Path root;
    private final LocalPlacementId placement = LocalPlacementId.create();
    private final ProjectScope from = ProjectScope.create(UUID.randomUUID());

    /** The first {@code moved} regions sit at y=generation, so each round archives exactly {@code moved} old definitions. */
    static List<RegionDescriptor> regions(int count, int moved, int generation) {
        List<RegionDescriptor> regions = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            regions.add(new RegionDescriptor(String.format(Locale.ROOT, "Region %04d", i), new Vector3(i * 16, i < moved ? generation : 0, 0), new Vector3(16, 16, 16)));
        return regions;
    }
    private SharedClientArchive archive(long generation, RegionWorkDataset copy) {
        return new SharedClientArchive(generation, placement, new SharedClientArchive.Binding(null, null, null),
            new SharedClientArchive.Binding(null, null, null, new SharedClientArchive.Detached(copy, from)), List.of());
    }

    @Test void detachedCopyPastTheTaskLimitIsTooLargeAndTheStoredCopySurvives() throws Exception {
        var store = new SharedClientStore(root);
        var copy = RegionWorkDataset.create(placement).reconcile(regions(2000, 0, 0)).dataset();
        assertEquals(StoreStatus.OK, store.save(archive(0, copy), -1).status());
        Path file = root.resolve("shared-client").resolve("placement-" + placement + ".json");
        long generation = 0;
        for (int round = 1; round <= 4; round++) {
            copy = copy.reconcile(regions(2000, 500, round)).dataset();
            assertEquals(StoreStatus.OK, store.save(archive(generation + 1, copy), generation).status()); generation++;
        }
        byte[] before = Files.readAllBytes(file);
        var over = copy.reconcile(regions(2000, 500, 5)).dataset();
        assertEquals(4500, over.definitions().size() + over.archived().size());
        assertEquals(StoreStatus.TOO_LARGE, store.save(archive(generation + 1, over), generation).status());
        assertArrayEquals(before, Files.readAllBytes(file), "A refused save must not touch the primary file");
        var loaded = store.load(placement);
        assertEquals(StoreStatus.OK, loaded.status());
        assertEquals(copy, loaded.value().regions().detached().dataset());
    }

    @Test void serviceLevelDetachedEditReportsTooLarge() throws Exception {
        var copy = RegionWorkDataset.create(placement).reconcile(regions(2000, 0, 0)).dataset();
        for (int round = 1; round <= 4; round++) copy = copy.reconcile(regions(2000, 500, round)).dataset();
        assertEquals(StoreStatus.OK, new SharedClientStore(root).save(archive(0, copy), -1).status());
        try (var service = new SharedClientService(root)) {
            var start = copy;
            var result = service.updateDetached(placement, start.id(), start.generation(), dataset -> dataset.reconcile(regions(2000, 500, 5)).dataset()).get();
            assertEquals(StoreStatus.TOO_LARGE, result.status());
            assertEquals(start, service.load(placement).get().value().regions().detached().dataset());
        }
    }
}
