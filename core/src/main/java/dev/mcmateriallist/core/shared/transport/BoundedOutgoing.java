package dev.mcmateriallist.core.shared.transport;

import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.shared.SharedLimits;
import dev.mcmateriallist.core.shared.protocol.SharedTransfers;
import java.util.*;

/** Worker encodes once; sender slices a single frame per poll instead of allocating a fragment list. */
public final class BoundedOutgoing {
    public record Frame(UUID connection, ProjectScope scope, SharedTransfers.Fragment fragment) {}
    private static final class Message {
        final UUID connection, transfer = UUID.randomUUID();
        final ProjectScope scope;
        final byte[] bytes;
        final long started;
        int index;
        Message(UUID connection, ProjectScope scope, byte[] bytes, long started) {
            this.connection = connection; this.scope = scope; this.bytes = bytes; this.started = started;
        }
    }
    private static final int REMOVALS = 256;
    private final TransportBudget budget;
    private final int slots;
    private final Deque<Message> queue = new ArrayDeque<>();
    private int preparing;
    private long generation, floor;
    // Removal generation per connection; the eldest record folds into the global floor, so memory stays bounded.
    private final LinkedHashMap<UUID, Long> removed = new LinkedHashMap<>();
    public BoundedOutgoing(TransportBudget budget, int slots) {
        this.budget = Objects.requireNonNull(budget);
        if (slots < 1) throw new IllegalArgumentException("Invalid outgoing capacity");
        this.slots = slots;
    }
    public synchronized long generation() { return generation; }
    public boolean offer(UUID connection, ProjectScope scope, byte[] bytes, long now) {
        return offer(connection, scope, bytes, now, generation());
    }
    private boolean cancelled(UUID connection, long expectedGeneration) {
        return expectedGeneration < floor || expectedGeneration < removed.getOrDefault(connection, Long.MIN_VALUE);
    }
    /** Captured before serialization; clear, or remove of the same connection, invalidates in-progress preparation. */
    public boolean offer(UUID connection, ProjectScope scope, byte[] bytes, long now, long expectedGeneration) {
        Objects.requireNonNull(connection); Objects.requireNonNull(bytes);
        if (bytes.length < 1 || bytes.length > SharedLimits.MESSAGE_BYTES) throw new IllegalArgumentException("Invalid message size");
        long reservation = 2L * bytes.length;
        synchronized (this) {
            if (cancelled(connection, expectedGeneration) || queue.size() + preparing >= slots || !budget.reserve(reservation)) return false;
            preparing++;
        }
        boolean queued = false;
        try {
            byte[] copy = bytes.clone(); // Worker copy never holds the tick/publication monitor.
            synchronized (this) {
                if (cancelled(connection, expectedGeneration)) return false;
                queue.addLast(new Message(connection, scope, copy, now)); queued = true; return true;
            }
        } finally {
            synchronized (this) { preparing--; if (!queued) budget.release(reservation); }
        }
    }
    /** Caller first handles expire(); at most one 60KiB fragment allocation per poll. */
    public synchronized Frame poll(long now) {
        Message message = queue.peekFirst();
        if (message == null || now - message.started >= SharedLimits.TRANSFER_MILLIS) return null;
        int offset = message.index * SharedLimits.FRAGMENT_BYTES;
        int count = (message.bytes.length + SharedLimits.FRAGMENT_BYTES - 1) / SharedLimits.FRAGMENT_BYTES;
        var frame = new SharedTransfers.Fragment(message.transfer, message.index++, count, message.bytes.length,
            Arrays.copyOfRange(message.bytes, offset, Math.min(message.bytes.length, offset + SharedLimits.FRAGMENT_BYTES)));
        if (message.index == count) { queue.removeFirst(); budget.release(2L * message.bytes.length); }
        return new Frame(message.connection, message.scope, frame);
    }
    public synchronized Set<UUID> expire(long now) {
        Set<UUID> expired = new HashSet<>();
        queue.removeIf(message -> {
            if (now - message.started < SharedLimits.TRANSFER_MILLIS) return false;
            expired.add(message.connection); budget.release(2L * message.bytes.length); return true;
        });
        return Set.copyOf(expired);
    }
    public synchronized void remove(UUID connection) {
        // Cancel only this connection's preparing copies: another connection's cancelled copy surfaces as a false CAPACITY.
        generation++; removed.remove(connection); removed.put(connection, generation);
        if (removed.size() > REMOVALS) {
            var eldest = removed.entrySet().iterator(); floor = Math.max(floor, eldest.next().getValue()); eldest.remove();
        }
        queue.removeIf(message -> {
            if (!message.connection.equals(connection)) return false;
            budget.release(2L * message.bytes.length); return true;
        });
    }
    public synchronized void clear() {
        generation++; floor = generation; removed.clear();
        for (Message message : queue) budget.release(2L * message.bytes.length);
        queue.clear();
    }
}
