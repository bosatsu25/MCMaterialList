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
    private final TransportBudget budget;
    private final int slots;
    private final Deque<Message> queue = new ArrayDeque<>();
    private int preparing;
    private long generation;
    public BoundedOutgoing(TransportBudget budget, int slots) {
        this.budget = Objects.requireNonNull(budget);
        if (slots < 1) throw new IllegalArgumentException("Invalid outgoing capacity");
        this.slots = slots;
    }
    public synchronized long generation() { return generation; }
    public boolean offer(UUID connection, ProjectScope scope, byte[] bytes, long now) {
        return offer(connection, scope, bytes, now, generation());
    }
    /** Captured before serialization; clear/remove invalidates in-progress preparation. */
    public boolean offer(UUID connection, ProjectScope scope, byte[] bytes, long now, long expectedGeneration) {
        Objects.requireNonNull(connection); Objects.requireNonNull(bytes);
        if (bytes.length < 1 || bytes.length > SharedLimits.MESSAGE_BYTES) throw new IllegalArgumentException("Invalid message size");
        long reservation = 2L * bytes.length;
        synchronized (this) {
            if (expectedGeneration != generation || queue.size() + preparing >= slots || !budget.reserve(reservation)) return false;
            preparing++;
        }
        boolean queued = false;
        try {
            byte[] copy = bytes.clone(); // Worker copy never holds the tick/publication monitor.
            synchronized (this) {
                if (expectedGeneration != generation) return false;
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
        generation++; // Conservatively cancel preparing copies, without retaining rejected tokens.
        queue.removeIf(message -> {
            if (!message.connection.equals(connection)) return false;
            budget.release(2L * message.bytes.length); return true;
        });
    }
    public synchronized void clear() {
        generation++;
        for (Message message : queue) budget.release(2L * message.bytes.length);
        queue.clear();
    }
}
