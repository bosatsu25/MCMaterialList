package dev.mcmateriallist.fabric.test;

/** Issue #34 test-only switches; never included in the application archive. */
public final class Phase5GFaults {
    /** While true, the client sees a server without the mcmateriallist:stock_v1 channel (the capability check answers false). */
    public static volatile boolean stockChannelAbsent;
    private Phase5GFaults() {}
}
