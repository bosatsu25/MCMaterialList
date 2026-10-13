package dev.mcmateriallist.core.shared.transport;

import dev.mcmateriallist.core.shared.protocol.SharedTransfers;
import dev.mcmateriallist.core.shared.SharedLimits;
import java.util.*;
import java.util.function.Consumer;

/** Receiver-thread aggregate admission, in addition to the producer's fragment validation. */
public final class BoundedTransfers {
    public static final class Expired extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private Expired() { super("Shared transfer expired"); }
    }
    private record Reservation(long bytes, long started) {}
    private final TransportBudget budget;
    private final SharedTransfers transfers = new SharedTransfers();
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private long lastTime = Long.MIN_VALUE;
    public BoundedTransfers(TransportBudget budget) { this.budget = Objects.requireNonNull(budget); }
    public void accept(SharedTransfers.Fragment fragment, long now, Consumer<byte[]> consume) {
        if (expire(now)) throw new Expired();
        UUID id = fragment.transferId();
        if (!reservations.containsKey(id)) {
            long bytes = 2L * fragment.totalBytes();
            if (reservations.size() >= SharedLimits.INCOMPLETE_TRANSFERS || !budget.reserve(bytes))
                throw new IllegalArgumentException("Shared transfer capacity");
            reservations.put(id, new Reservation(bytes, now));
        }
        try {
            var completed = transfers.accept(fragment, now);
            if (completed.isPresent()) {
                try { consume.accept(completed.get()); } finally { release(id); }
            }
        } catch (RuntimeException failure) { release(id); throw failure; }
    }
    public boolean expire(long now) {
        if (now < lastTime) { clear(); throw new IllegalArgumentException("Nonmonotonic transfer clock"); }
        lastTime = now;
        List<UUID> expired = reservations.entrySet().stream()
            .filter(entry -> now - entry.getValue().started() >= SharedLimits.TRANSFER_MILLIS)
            .map(Map.Entry::getKey).toList();
        for (UUID id : expired) release(id);
        transfers.expire(now);
        return !expired.isEmpty();
    }
    private void release(UUID id) {
        Reservation reservation = reservations.remove(id);
        if (reservation != null) budget.release(reservation.bytes());
    }
    public void clear() {
        for (var reservation : reservations.values()) budget.release(reservation.bytes());
        reservations.clear(); transfers.clear();
    }
}
