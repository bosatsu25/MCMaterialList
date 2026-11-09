package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.shared.ProjectRole;
import dev.mcmateriallist.core.shared.ProjectScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision tables DT-S (StockPolicy R1/R2 and grant permission) and DT-A (StockAdmission R4) of docs/test-design.md
 * (JSTQB FL v4.0, 4.2.3), from the owner decisions of docs/phase-4b-check-materials.md. StockPolicyTest checks representative
 * rules and the live re-evaluation; this class enumerates every combination.
 */
class StockDecisionTableTest {
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    private final ProjectScope scope = ProjectScope.create(UUID.randomUUID());
    private final ContainerPosition granted = new ContainerPosition(1, 64, 1, "minecraft:chest"), ungranted = new ContainerPosition(2, 64, 2, "minecraft:chest");
    private final StockGrants grants = StockGrants.empty(scope.projectId()).grant(OVERWORLD, List.of(granted), UUID.randomUUID(), 0);

    /**
     * DT-S-1: allows(serverAdmin, role, position granted in this dimension). 2 x 4 (role none/VIEWER/MEMBER/ADMINISTRATOR) x 2 (granted)
     * x 2 (same dimension) = 32 rules. Expected: admin -> always; otherwise role >= MEMBER and granted and same dimension.
     */
    @Test void dtS1_readDecisionAllCombinations() {
        int rules = 0;
        for (boolean admin : new boolean[]{true, false})
            for (ProjectRole role : Arrays.asList(null, ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMINISTRATOR))
                for (boolean isGranted : new boolean[]{true, false})
                    for (boolean sameDimension : new boolean[]{true, false}) {
                        boolean memberOrAbove = role == ProjectRole.MEMBER || role == ProjectRole.ADMINISTRATOR;
                        boolean expected = admin || (memberOrAbove && isGranted && sameDimension);
                        String dimension = sameDimension ? OVERWORLD : NETHER;
                        assertEquals(expected, StockPolicy.allows(admin, role, grants, dimension, isGranted ? granted : ungranted),
                            admin + " " + role + " " + isGranted + " " + sameDimension);
                        rules++;
                    }
        assertEquals(32, rules);
        // A missing registry is the "no grant" partition for non-admins only.
        assertTrue(StockPolicy.allows(true, null, null, OVERWORLD, granted));
        assertFalse(StockPolicy.allows(false, ProjectRole.ADMINISTRATOR, null, OVERWORLD, granted));
    }

    /** DT-S-2: mayGrant(serverAdmin, role): only a server admin who is the project's ADMINISTRATOR may grant or revoke (8 rules). */
    @Test void dtS2_grantDecisionAllCombinations() {
        for (boolean admin : new boolean[]{true, false})
            for (ProjectRole role : Arrays.asList(null, ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMINISTRATOR))
                assertEquals(admin && role == ProjectRole.ADMINISTRATOR, StockPolicy.mayGrant(admin, role), admin + " " + role);
    }

    private static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-11-09T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /**
     * DT-A: StockAdmission.admit. Conditions C1 actor inside its cooldown, C2 actor already holds a running slot, C3 all slots taken.
     * Rules: R1 C1 -> COOLDOWN (C2, C3 don't care); R2 !C1 C2 -> ADMITTED, reusing its own slot (C3 don't care);
     * R3 !C1 !C2 C3 -> CAPACITY; R4 !C1 !C2 !C3 -> ADMITTED. All 8 combinations are built (some C1/C2 pairs need the clock).
     */
    @Test void dtA_admissionAllCombinations() {
        for (int mask = 0; mask < 8; mask++) {
            boolean c1 = (mask & 1) != 0, c2 = (mask & 2) != 0, c3 = (mask & 4) != 0;
            var clock = new ManualClock(); var admission = new StockAdmission(clock, java.time.Duration.ofSeconds(5), 2);
            UUID actor = new UUID(9, 9);
            if (c2) assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(actor));             // actor runs a scan
            if (c3) for (int i = admission.running(); i < 2; i++) assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(new UUID(8, i)));
            clock.now = clock.now.plusSeconds(10);                                                     // every earlier start is out of cooldown
            if (c1 && c2) assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(actor));       // restart: same slot, cooldown again
            if (c1 && !c2) {                                                                           // cooling down without a slot
                if (admission.running() == 2) admission.finish(new UUID(8, 1));
                assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(actor)); admission.finish(actor);
                if (c3) assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(new UUID(7, 7)));
            }
            assertEquals(c3, admission.running() == 2, "precondition C3 mask " + mask);
            StockAdmission.Verdict expected = c1 ? StockAdmission.Verdict.COOLDOWN : c2 ? StockAdmission.Verdict.ADMITTED
                : c3 ? StockAdmission.Verdict.CAPACITY : StockAdmission.Verdict.ADMITTED;
            int before = admission.running();
            assertEquals(expected, admission.admit(actor), "mask " + mask);
            int after = admission.running();
            assertEquals(expected == StockAdmission.Verdict.ADMITTED && !c2 ? before + 1 : before, after, "slots mask " + mask);
        }
    }
}
