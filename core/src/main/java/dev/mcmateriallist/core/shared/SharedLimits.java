package dev.mcmateriallist.core.shared;

/** Explicit admission limits, not measured capacity guarantees. */
public final class SharedLimits {
    public static final int ROWS_PER_LIST = 2000, PARTICIPANTS = 20, PROJECTS = 128;
    public static final int OPERATIONS = 4096, RETIRED_ROWS = 4000, QUEUED_WORK = 256;
    public static final int QUEUED_BYTES = 32 * 1024 * 1024;
    public static final int MESSAGE_BYTES = 32 * 1024 * 1024, ARCHIVE_BYTES = 96 * 1024 * 1024;
    public static final int FRAGMENT_BYTES = 60 * 1024, FRAGMENTS = 547, INCOMPLETE_TRANSFERS = 2;
    public static final long TRANSFER_MILLIS = 30_000, RETENTION_SECONDS = 86_400;
    private SharedLimits() {}
    public static void count(int count, int max) {
        if (count < 0 || count > max) throw new IllegalArgumentException("Shared capacity exceeded");
    }
}
