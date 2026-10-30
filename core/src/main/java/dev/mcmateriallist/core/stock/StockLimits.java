package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.LocationLimits;
import java.time.Duration;

/** Explicit Phase 4B admission and budget limits; the per-tick values are gates, measured nanoseconds are not. */
public final class StockLimits {
    public static final int MAX_POSITIONS = LocationLimits.MAX_CONTAINERS, MAX_ITEMS = 1024, ITEM_ID_BYTES = 256, MAX_GRANTS = 512;
    public static final int FRAME_BYTES = 61_440, CONTAINERS_PER_TICK = 8, FRAMES_PER_TICK = 4, CONCURRENT_SCANS = 4;
    public static final long NANOS_PER_TICK = 1_000_000;
    public static final Duration FRESH = Duration.ofMinutes(5), COOLDOWN = Duration.ofSeconds(5);
    private StockLimits() {}
}
