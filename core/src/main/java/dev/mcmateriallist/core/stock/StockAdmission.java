package dev.mcmateriallist.core.stock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * R4 admission, server thread only: a cooldown between one player's starts and a server-wide cap on running scans. A player
 * holds at most one slot; when an admitted player already had a scan running, the caller cancels that scan.
 */
public final class StockAdmission {
    public enum Verdict { ADMITTED, COOLDOWN, CAPACITY }
    private final Clock clock;
    private final Duration cooldown;
    private final int capacity;
    private final Set<UUID> active = new HashSet<>();
    private final Map<UUID, Instant> started = new HashMap<>();

    public StockAdmission(Clock clock) { this(clock, StockLimits.COOLDOWN, StockLimits.CONCURRENT_SCANS); }
    public StockAdmission(Clock clock, Duration cooldown, int capacity) {
        if (capacity < 1 || cooldown.isNegative()) throw new IllegalArgumentException("Invalid admission limits");
        this.clock = Objects.requireNonNull(clock, "clock"); this.cooldown = cooldown; this.capacity = capacity;
    }
    public Verdict admit(UUID actor) {
        Objects.requireNonNull(actor, "actor"); Instant now = clock.instant();
        // Only entries still inside their cooldown matter, so the map stays bounded by recent starters.
        started.values().removeIf(at -> Duration.between(at, now).compareTo(cooldown) >= 0);
        if (started.containsKey(actor)) return Verdict.COOLDOWN;
        if (!active.contains(actor) && active.size() >= capacity) return Verdict.CAPACITY;
        active.add(actor); started.put(actor, now); return Verdict.ADMITTED;
    }
    /** Done, cancelled, or the player disconnected. */
    public void finish(UUID actor) { active.remove(actor); }
    public int running() { return active.size(); }
}
