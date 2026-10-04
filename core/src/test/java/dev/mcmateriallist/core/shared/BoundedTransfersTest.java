package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.protocol.SharedTransfers;
import dev.mcmateriallist.core.shared.transport.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BoundedTransfersTest {
    @Test void arrivingFragmentCannotHideExpiredTransferFromResyncDetection() {
        var budget = new TransportBudget(300_000); var receiver = new BoundedTransfers(budget);
        var frames = SharedTransfers.split(UUID.randomUUID(), new byte[61_441]);
        receiver.accept(frames.getFirst(), 0, ignored -> fail("Incomplete"));
        assertThrows(IllegalArgumentException.class,
            () -> receiver.accept(frames.getLast(), 30_000, ignored -> fail("Expired transfer completed")));
        assertEquals(0, budget.used());
    }
    @Test void completedCopyIsReservedUntilConsumedAndOtherReceiverCannotExceedAggregateLimit() {
        var budget = new TransportBudget(12);
        var receiver = new BoundedTransfers(budget);
        var frame = new SharedTransfers.Fragment(UUID.randomUUID(), 0, 1, 6, new byte[6]);
        var delivered = new AtomicInteger();
        receiver.accept(frame, 0, bytes -> {
            assertEquals(12, budget.used()); assertEquals(6, bytes.length); delivered.incrementAndGet();
            var other = new BoundedTransfers(budget);
            assertThrows(IllegalArgumentException.class, () -> other.accept(frame, 0, ignored -> fail("Aggregate overflow")));
        });
        assertEquals(1, delivered.get()); assertEquals(0, budget.used());
    }
    @Test void incompleteExpiryAndMalformedDuplicateReleaseAllReservations() {
        var budget = new TransportBudget(300_000);
        var receiver = new BoundedTransfers(budget);
        var frames = SharedTransfers.split(UUID.randomUUID(), new byte[61_441]);
        receiver.accept(frames.getFirst(), 0, ignored -> fail("Incomplete"));
        assertEquals(122_882, budget.used()); assertTrue(receiver.expire(30_000)); assertEquals(0, budget.used());
        receiver.accept(frames.getFirst(), 30_000, ignored -> fail("Incomplete"));
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(frames.getFirst(), 30_001, ignored -> fail("Duplicate")));
        assertEquals(0, budget.used());
    }
}
