package dev.mcmateriallist.core.shared.transport;

/** Shared reservation counter. Reservations include completed copies, not measured Java heap. */
public final class TransportBudget {
    /** Shared by synchronous send failure and asynchronous completion; releases at most once. */
    public static final class Reservation implements Runnable {
        private final TransportBudget owner;
        private final long bytes;
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        private Reservation(TransportBudget owner, long bytes) { this.owner = owner; this.bytes = bytes; }
        @Override public void run() { if (released.compareAndSet(false, true)) owner.release(bytes); }
    }
    private final long limit;
    private long used;
    public TransportBudget(long limit) { if (limit < 1) throw new IllegalArgumentException("Invalid limit"); this.limit = limit; }
    public synchronized boolean reserve(long bytes) {
        if (bytes < 1) throw new IllegalArgumentException("Invalid reservation");
        if (bytes > limit - used) return false;
        used += bytes; return true;
    }
    public Reservation reserveOnce(long bytes) { return reserve(bytes) ? new Reservation(this, bytes) : null; }
    public synchronized void release(long bytes) {
        if (bytes < 1 || bytes > used) throw new IllegalArgumentException("Invalid release");
        used -= bytes;
    }
    public synchronized long used() { return used; }
}
