package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.shared.ProjectScope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only fault injection: drops the real decoded authoritative answer of an armed operation exactly once. */
public final class MultiplayerFaults {
    private static final Set<UUID> ARMED = ConcurrentHashMap.newKeySet(), DROPPED = ConcurrentHashMap.newKeySet();
    private static final Map<ProjectScope, Long> DELTAS = new ConcurrentHashMap<>();
    private MultiplayerFaults() {}
    public static void dropResult(UUID operation) { ARMED.add(operation); }
    public static boolean shouldDrop(UUID operation) {
        if (!ARMED.remove(operation)) return false;
        DROPPED.add(operation); return true;
    }
    public static boolean dropped(UUID operation) { return DROPPED.contains(operation); }
    /** Every decoded delta, before production filtering, so delivery after revocation is observable. */
    public static void delta(ProjectScope scope, long revision) { DELTAS.merge(scope, revision, Math::max); }
    public static long latestDelta(ProjectScope scope) { return DELTAS.getOrDefault(scope, -1L); }
}
