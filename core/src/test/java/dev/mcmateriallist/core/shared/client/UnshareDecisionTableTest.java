package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.SharedClientService.UnshareReason;
import dev.mcmateriallist.core.work.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision table DT-U of docs/test-design.md (JSTQB FL v4.0, 4.2.3) for SharedClientService.unshareCheck, P1-P5 of #11.
 * Conditions: C1 regions SHARED, C2 reference actor is the caller, C3 no recovered journal uncertainty, C4 no unresolved
 * REGIONS original, C5 cache cursor equals the live cursor, C6 no own OK REGIONS confirmation newer than the cache.
 * The single-fault rules R2-R7 (one condition false, all others true) are in SharedClientUnshareTest#eachPreconditionRefusesWithoutChangingDisk;
 * this class covers the reason priority when several conditions fail and the edges of C5/C6.
 */
class UnshareDecisionTableTest {
    private final LocalPlacementId placement = LocalPlacementId.create();
    private final UUID actor = new UUID(0, 1), stranger = new UUID(0, 2);
    private final MaterialTaskId stone = new MaterialTaskId("minecraft:stone");
    private final RegionTaskId tower = RegionTaskId.create();
    private final ProjectScope materialScope = ProjectScope.create(UUID.randomUUID()), regionScope = ProjectScope.create(materialScope.worldNamespace()),
        otherScope = ProjectScope.create(materialScope.worldNamespace());
    private final SharedClientArchive.Reference materialRef = new SharedClientArchive.Reference(UUID.randomUUID(), actor, materialScope),
        regionRef = new SharedClientArchive.Reference(UUID.randomUUID(), actor, regionScope),
        otherRef = new SharedClientArchive.Reference(UUID.randomUUID(), stranger, otherScope);

    private ProjectSnapshot snapshot(ProjectScope scope, long revision) {
        var region = new RegionDescriptor("Tower", new Vector3(0, 0, 0), new Vector3(2, 2, 2));
        var definition = new SharedManifest.Definition("e".repeat(64), new SharedManifest.Baseline("minecraft:overworld", new Vector3(0, 64, 0),
            SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), Map.of(stone, 1L), List.of(region));
        var manifest = new SharedManifest(0, definition, Map.of(tower, region));
        Map<TaskId, TaskState> rows = new HashMap<>(); manifest.taskIds().forEach(id -> rows.put(id, TaskState.empty(id)));
        return new ProjectSnapshot(scope, "dt-u", actor, Set.of(), revision, manifest, rows, Set.of());
    }
    private SharedClientArchive.Operation op(DatasetKind kind, SharedClientArchive.Reference ref, SharedClientArchive.Confirmation confirmation) {
        TaskId id = kind == DatasetKind.REGIONS ? tower : stone;
        return new SharedClientArchive.Operation(kind, ref, UUID.randomUUID(), new SharedCommand(ref.scope(), id, UUID.randomUUID(), 0, 0,
            new SharedCommand.SetDone(true)), confirmation);
    }
    private static SharedClientArchive.Confirmation ok(long revision) { return new SharedClientArchive.Confirmation(SharedEvents.Status.OK, revision); }
    private SharedClientArchive archive(boolean shared, long cacheCursor, boolean recovered, List<SharedClientArchive.Operation> operations) {
        var materials = new SharedClientArchive.Binding(DatasetId.create(), materialRef, new SharedClientArchive.Cache(snapshot(materialScope, 0), 0));
        var regions = shared ? new SharedClientArchive.Binding(DatasetId.create(), regionRef, new SharedClientArchive.Cache(snapshot(regionScope, cacheCursor), cacheCursor))
            : new SharedClientArchive.Binding(DatasetId.create(), null, null);
        return new SharedClientArchive(0, placement, materials, regions, operations, recovered);
    }

    /**
     * DT-U full table: all 2^6 = 64 combinations of C1-C6 (C2, C5, C6 are "don't care" once C1 is false). The expected reason is
     * the first false condition in the specified order C1, C2, C3, C4, C5, C6; all true permits unshare. unresolved always
     * reports every unknown REGIONS original, whatever the reason.
     */
    @Test void dtU_allCombinationsFollowTheSpecifiedPriority() {
        int checked = 0;
        for (int mask = 0; mask < 64; mask++) {
            boolean c1 = (mask & 1) == 0, c2 = (mask & 2) == 0, c3 = (mask & 4) == 0, c4 = (mask & 8) == 0, c5 = (mask & 16) == 0, c6 = (mask & 32) == 0;
            List<SharedClientArchive.Operation> operations = new ArrayList<>();
            if (!c4) operations.add(op(DatasetKind.REGIONS, otherRef, null));         // another account's unknown REGIONS original
            if (!c6) operations.add(op(DatasetKind.REGIONS, regionRef, ok(1)));       // own OK result at revision 1, cache at 0
            var archive = archive(c1, 0, !c3, operations);
            var check = SharedClientService.unshareCheck(archive, c2 ? actor : stranger, c5 ? 0 : 1);
            UnshareReason expected = !c1 ? UnshareReason.NOT_SHARED : !c2 ? UnshareReason.ACCOUNT_MISMATCH : !c3 ? UnshareReason.RECOVERED_UNCERTAIN
                : !c4 ? UnshareReason.UNRESOLVED_OPERATIONS : !c5 ? UnshareReason.JOIN_REQUIRED : !c6 ? UnshareReason.REFRESH_REQUIRED : null;
            assertEquals(expected, check.reason(), "mask " + Integer.toBinaryString(mask));
            assertEquals(c4 ? 0 : 1, check.unresolved(), "mask " + Integer.toBinaryString(mask));
            assertEquals(expected == null, check.allowed());
            checked++;
        }
        assertEquals(64, checked);
    }

    /** DT-U-C5 edge: the cache cursor must equal the live cursor exactly; one behind and one ahead both need a join. */
    @Test void dtU_c5CursorMustMatchExactly() {
        var archive = archive(true, 5, false, List.of());
        assertEquals(UnshareReason.JOIN_REQUIRED, SharedClientService.unshareCheck(archive, actor, 4).reason());
        assertNull(SharedClientService.unshareCheck(archive, actor, 5).reason());
        assertEquals(UnshareReason.JOIN_REQUIRED, SharedClientService.unshareCheck(archive, actor, 6).reason());
    }

    /** DT-U-C6 edge (3-value BVA on the confirmed revision against cache cursor 5): 4 and 5 are inside the copy, 6 is not. */
    @Test void dtU_c6OwnConfirmationRevisionBoundary() {
        for (long revision : new long[]{4, 5, 6}) {
            var archive = archive(true, 5, false, List.of(op(DatasetKind.REGIONS, regionRef, ok(revision))));
            assertEquals(revision > 5 ? UnshareReason.REFRESH_REQUIRED : null, SharedClientService.unshareCheck(archive, actor, 5).reason(), "revision " + revision);
        }
    }

    /**
     * DT-U-C6 partitions: only an OK REGIONS confirmation in the bound scope can be missing from the copy. A newer CONFLICT/DENIED
     * answer changed nothing; a MATERIALS result or another scope's result is not part of this region list.
     */
    @Test void dtU_c6OnlyOwnScopeOkRegionResultsCount() {
        var cases = List.of(
            op(DatasetKind.REGIONS, regionRef, new SharedClientArchive.Confirmation(SharedEvents.Status.CONFLICT, 9)),
            op(DatasetKind.REGIONS, regionRef, new SharedClientArchive.Confirmation(SharedEvents.Status.DENIED, 9)),
            op(DatasetKind.MATERIALS, materialRef, ok(9)),
            op(DatasetKind.REGIONS, otherRef, ok(9)));
        for (var operation : cases)
            assertNull(SharedClientService.unshareCheck(archive(true, 0, false, List.of(operation)), actor, 0).reason(), operation.toString());
    }

    /** DT-U-C4 partitions: an unknown MATERIALS original does not block region unshare; any unknown REGIONS original does (P3 is strict). */
    @Test void dtU_c4OnlyUnknownRegionOriginalsBlock() {
        var materialsUnknown = archive(true, 0, false, List.of(op(DatasetKind.MATERIALS, materialRef, null)));
        assertEquals(new SharedClientService.UnshareCheck(null, 0), SharedClientService.unshareCheck(materialsUnknown, actor, 0));
        var both = archive(true, 0, false, List.of(op(DatasetKind.REGIONS, regionRef, null), op(DatasetKind.REGIONS, otherRef, null),
            op(DatasetKind.MATERIALS, materialRef, null)));
        assertEquals(new SharedClientService.UnshareCheck(UnshareReason.UNRESOLVED_OPERATIONS, 2), SharedClientService.unshareCheck(both, actor, 0));
    }
}
