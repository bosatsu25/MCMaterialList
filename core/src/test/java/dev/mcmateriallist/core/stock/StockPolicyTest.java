package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.shared.ProjectRole;
import dev.mcmateriallist.core.shared.ProjectScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockPolicyTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private final ProjectScope scope = ProjectScope.create(UUID.randomUUID());
    private final UUID admin = UUID.randomUUID(), actor = UUID.randomUUID();
    private final ContainerPosition granted = new ContainerPosition(1, 64, 1, "minecraft:chest"), other = new ContainerPosition(9, 64, 9, "minecraft:chest");
    private final StockGrants grants = StockGrants.empty(scope.projectId()).grant(OVERWORLD, List.of(granted), admin, 1000);

    @Test void r1AdminReadsAnythingR2NeedsMemberAndAGrant() {
        assertArrayEquals(new boolean[]{true, true}, StockPolicy.decide(true, null, null, OVERWORLD, List.of(granted, other)));
        assertArrayEquals(new boolean[]{true, false}, StockPolicy.decide(false, ProjectRole.MEMBER, grants, OVERWORLD, List.of(granted, other)));
        assertArrayEquals(new boolean[]{true, false}, StockPolicy.decide(false, ProjectRole.ADMINISTRATOR, grants, OVERWORLD, List.of(granted, other)));
        assertArrayEquals(new boolean[]{false, false}, StockPolicy.decide(false, ProjectRole.VIEWER, grants, OVERWORLD, List.of(granted, other)));
        assertArrayEquals(new boolean[]{false, false}, StockPolicy.decide(false, null, grants, OVERWORLD, List.of(granted, other)));
        assertFalse(StockPolicy.allows(false, ProjectRole.MEMBER, grants, "minecraft:the_nether", granted));
        assertFalse(StockPolicy.allows(false, ProjectRole.MEMBER, null, OVERWORLD, granted));
        assertTrue(StockPolicy.mayGrant(true, ProjectRole.ADMINISTRATOR));
        assertFalse(StockPolicy.mayGrant(true, ProjectRole.MEMBER)); assertFalse(StockPolicy.mayGrant(false, ProjectRole.ADMINISTRATOR));
    }
    @Test void r3UnauthorizedIsAnsweredWithoutTouchingTheProbe() {
        ContainerProbe forbidden = new ContainerProbe() {
            @Override public Shape shape(String dimension, ContainerPosition registered) { throw new AssertionError("probe called for " + registered); }
            @Override public Reading read(String dimension, List<ContainerPosition> halves) { throw new AssertionError("probe called"); }
        };
        Map<ProjectScope, ProjectRole> roles = new HashMap<>(Map.of(scope, ProjectRole.VIEWER));
        var access = StockPolicy.live(() -> false, actor, scope, (who, s) -> Optional.ofNullable(who.equals(actor) ? roles.get(s) : null), id -> grants);
        var scan = new StockScan(OVERWORLD, List.of(granted, other), forbidden, access);
        while (!scan.done()) scan.step();
        assertEquals(LocationStatus.UNAUTHORIZED, scan.poll().status()); assertEquals(LocationStatus.UNAUTHORIZED, scan.poll().status());
        // Unbound profile, or a scope the actor cannot read: nothing for a non-admin.
        assertFalse(StockPolicy.live(() -> false, actor, null, (w, s) -> Optional.of(ProjectRole.ADMINISTRATOR), id -> grants).allows(OVERWORLD, granted));
        assertFalse(StockPolicy.live(() -> false, actor, scope, (w, s) -> Optional.empty(), id -> grants).allows(OVERWORLD, granted));
        // A registry of another project never authorizes this one.
        var foreign = StockGrants.empty(UUID.randomUUID()).grant(OVERWORLD, List.of(granted), admin, 1);
        assertFalse(StockPolicy.live(() -> false, actor, scope, (w, s) -> Optional.of(ProjectRole.MEMBER), id -> foreign).allows(OVERWORLD, granted));
    }
    @Test void liveAccessFollowsDeopDemotionAndRevocation() {
        AtomicBoolean op = new AtomicBoolean(true); AtomicReference<ProjectRole> role = new AtomicReference<>(ProjectRole.MEMBER);
        AtomicReference<StockGrants> registry = new AtomicReference<>(grants);
        var access = StockPolicy.live(op::get, actor, scope, (w, s) -> Optional.ofNullable(role.get()), id -> registry.get());
        assertTrue(access.allows(OVERWORLD, other));
        op.set(false); assertFalse(access.allows(OVERWORLD, other)); assertTrue(access.allows(OVERWORLD, granted));
        role.set(ProjectRole.VIEWER); assertFalse(access.allows(OVERWORLD, granted));
        role.set(ProjectRole.MEMBER); registry.set(grants.revoke(OVERWORLD, List.of(granted))); assertFalse(access.allows(OVERWORLD, granted));
    }
    @Test void grantsAreCanonicalUniqueAndBounded() {
        assertSame(grants, grants.grant(OVERWORLD, List.of(granted), UUID.randomUUID(), 5));
        assertSame(grants, grants.revoke(OVERWORLD, List.of(other)));
        assertEquals(1, grants.generation()); assertEquals(admin, grants.grants().get(0).approver());
        assertEquals(List.of(), grants.in("minecraft:the_nether"));
        var full = StockGrants.empty(scope.projectId());
        List<ContainerPosition> many = new java.util.ArrayList<>();
        for (int i = 0; i < StockLimits.MAX_GRANTS; i++) many.add(new ContainerPosition(i, 0, 0, "minecraft:barrel"));
        var filled = full.grant(OVERWORLD, many, admin, 0); assertEquals(512, filled.grants().size());
        assertThrows(IllegalArgumentException.class, () -> filled.grant(OVERWORLD, List.of(other), admin, 0));
        var g = grants.grants().get(0);
        assertThrows(IllegalArgumentException.class, () -> new StockGrants(scope.projectId(), 0, List.of(g, g)));
        assertThrows(IllegalArgumentException.class, () -> new StockGrant(OVERWORLD, 0, 0, 0, admin, -1));
        assertThrows(IllegalArgumentException.class, () -> new StockGrant(OVERWORLD, 0, 4096, 0, admin, 0));
    }
    @Test void admissionCooldownAndCapacity() {
        Instant[] now = {Instant.parse("2026-10-30T12:00:00Z")};
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        var admission = new StockAdmission(clock, Duration.ofSeconds(5), 2);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(a)); assertEquals(StockAdmission.Verdict.COOLDOWN, admission.admit(a));
        assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(b)); assertEquals(StockAdmission.Verdict.CAPACITY, admission.admit(c));
        now[0] = now[0].plusSeconds(5);
        // A running player's new request replaces its own slot rather than taking another.
        assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(a)); assertEquals(2, admission.running());
        admission.finish(b); assertEquals(StockAdmission.Verdict.ADMITTED, admission.admit(c));
    }
}
