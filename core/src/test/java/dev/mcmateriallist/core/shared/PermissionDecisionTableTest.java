package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision table DT-P of docs/test-design.md (JSTQB FL v4.0, 4.2.3): ProjectPermissions.allows, the one row policy shared by
 * the server authority and client admission (design section 11, #6). Conditions: the actor's role (none, VIEWER, MEMBER,
 * ADMINISTRATOR), the intent (7 kinds) and the row seen from the actor (5 classes). The expected values are written out
 * literally from the design table, not derived from the implementation. Authority-level evidence for the same policy
 * (DENIED without row content, unchanged bytes) is in SharedPermissionsTest.
 */
class PermissionDecisionTableTest {
    private static final UUID ADMIN = new UUID(0, 1), MEMBER = new UUID(0, 2), VIEWER = new UUID(0, 3), OUTSIDER = new UUID(0, 4), OTHER = new UUID(0, 5);
    private static final MaterialTaskId STONE = new MaterialTaskId("minecraft:stone");
    private static final Instant AT = Instant.EPOCH;

    /** Row classes from the actor's point of view: unassigned open/done, own open/done, someone else's. */
    enum Row { UNASSIGNED_OPEN, UNASSIGNED_DONE, OWN_OPEN, OWN_DONE, OTHER }
    enum Intent { CLAIM, RELEASE, DONE, UNDO, NOTE, ASSIGN, CLEAR }

    private static TaskState row(Row row, UUID actor) {
        return switch (row) {
            case UNASSIGNED_OPEN -> new TaskState(STONE, null, false, "", null, null, 3);
            case UNASSIGNED_DONE -> new TaskState(STONE, null, true, "", OTHER, AT, 3);
            case OWN_OPEN -> new TaskState(STONE, actor, false, "", null, null, 3);
            case OWN_DONE -> new TaskState(STONE, actor, true, "", actor, AT, 3);
            case OTHER -> new TaskState(STONE, OTHER, false, "", null, null, 3);
        };
    }
    private static SharedCommand.Intent intent(Intent intent) {
        return switch (intent) {
            case CLAIM -> new SharedCommand.Claim();
            case RELEASE -> new SharedCommand.Release();
            case DONE -> new SharedCommand.SetDone(true);
            case UNDO -> new SharedCommand.SetDone(false);
            case NOTE -> new SharedCommand.SetNote("n");
            case ASSIGN -> new SharedCommand.Assign(MEMBER);
            case CLEAR -> new SharedCommand.ClearAssignment();
        };
    }
    private static ProjectSnapshot snapshot() {
        var manifest = SharedManifest.initial(new SharedManifest.Definition("f".repeat(64), new SharedManifest.Baseline("minecraft:overworld",
            new Vector3(0, 0, 0), SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), Map.of(STONE, 1L), List.of()));
        return new ProjectSnapshot(ProjectScope.create(UUID.randomUUID()), "dt-p", ADMIN,
            Map.of(ADMIN, ProjectRole.ADMINISTRATOR, MEMBER, ProjectRole.MEMBER, VIEWER, ProjectRole.VIEWER, OTHER, ProjectRole.MEMBER),
            0, manifest, Map.of(STONE, TaskState.empty(STONE)), Set.of());
    }

    /**
     * MEMBER rows of DT-P (design section 11): Y = allowed. Columns: UNASSIGNED_OPEN, UNASSIGNED_DONE, OWN_OPEN, OWN_DONE, OTHER.
     * DONE on an unassigned open row is the one-step self-assignment; an unassigned done row must be claimed before editing;
     * CLAIM is only for an unassigned row (claiming an own row is denied, not a no-op); ASSIGN/CLEAR are administrator-only.
     */
    private static final Map<Intent, String> MEMBER_TABLE = Map.of(
        Intent.CLAIM,   "YY---",
        Intent.RELEASE, "--YY-",
        Intent.DONE,    "Y-YY-",
        Intent.UNDO,    "--YY-",
        Intent.NOTE,    "--YY-",
        Intent.ASSIGN,  "-----",
        Intent.CLEAR,   "-----");

    private static boolean expected(ProjectRole role, Intent intent, Row row) {
        if (role == null || role == ProjectRole.VIEWER) return false;
        if (role == ProjectRole.ADMINISTRATOR) return true;
        return MEMBER_TABLE.get(intent).charAt(row.ordinal()) == 'Y';
    }

    /** DT-P full table: 4 roles x 7 intents x 5 row classes = 140 rules, each checked against the literal expectation. */
    @Test void dtP_allRulesMatchTheDesignTable() {
        var snapshot = snapshot(); int rules = 0;
        Map<UUID, ProjectRole> actors = new LinkedHashMap<>();
        actors.put(OUTSIDER, null); actors.put(VIEWER, ProjectRole.VIEWER); actors.put(MEMBER, ProjectRole.MEMBER); actors.put(ADMIN, ProjectRole.ADMINISTRATOR);
        for (var entry : actors.entrySet()) for (Intent intent : Intent.values()) for (Row row : Row.values()) {
            assertEquals(expected(entry.getValue(), intent, row), ProjectPermissions.allows(snapshot, entry.getKey(), row(row, entry.getKey()), intent(intent)),
                entry.getValue() + " " + intent + " " + row);
            rules++;
        }
        assertEquals(140, rules);
    }

    /**
     * DT-P replay evidence: memberAllows is the role-independent row check kept for durable replay. It must equal the MEMBER
     * column for every intent and row class, so a replayed original is judged against the row it was sent for.
     */
    @Test void dtP_replayRowEvidenceEqualsTheMemberColumn() {
        for (Intent intent : Intent.values()) for (Row row : Row.values())
            assertEquals(expected(ProjectRole.MEMBER, intent, row), ProjectPermissions.memberAllows(MEMBER, row(row, MEMBER), intent(intent)), intent + " " + row);
    }

    /** DT-P invalid inputs: missing snapshot, row, intent or actor never allow (fail closed). */
    @Test void dtP_missingInputsAreDenied() {
        var snapshot = snapshot(); var own = row(Row.OWN_OPEN, ADMIN);
        assertFalse(ProjectPermissions.allows(null, ADMIN, own, new SharedCommand.Claim()));
        assertFalse(ProjectPermissions.allows(snapshot, ADMIN, null, new SharedCommand.Claim()));
        assertFalse(ProjectPermissions.allows(snapshot, ADMIN, own, null));
        assertFalse(ProjectPermissions.allows(snapshot, null, row(Row.UNASSIGNED_OPEN, null), new SharedCommand.Claim()));
        assertFalse(ProjectPermissions.memberAllows(null, row(Row.UNASSIGNED_OPEN, null), new SharedCommand.Claim()));
    }
}
