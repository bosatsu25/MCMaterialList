package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.transport.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedOutgoingTest {
    @Test void queueBoundsCountAndBytesAndPollProducesOnlyOneFullFrame() {
        var budget = new TransportBudget(300_000); var queue = new BoundedOutgoing(budget, 2); var token = UUID.randomUUID();
        assertTrue(queue.offer(token, null, new byte[61_441], 0));
        assertTrue(queue.offer(token, null, new byte[1], 0));
        assertFalse(queue.offer(token, null, new byte[1], 0));
        assertEquals(122_884, budget.used());
        var first = queue.poll(0); assertEquals(61_440, first.fragment().bytes().length); assertEquals(0, first.fragment().index());
        assertEquals(122_884, budget.used());
        var last = queue.poll(0); assertEquals(1, last.fragment().bytes().length); assertEquals(1, last.fragment().index());
        assertEquals(2, budget.used()); queue.remove(token); assertEquals(0, budget.used()); assertNull(queue.poll(0));
        assertFalse(queue.offer(token, null, new byte[150_001], 0));
    }
    @Test void fixedLifetimeCancelsQueuedContentAndReleasesBytes() {
        var budget = new TransportBudget(20); var queue = new BoundedOutgoing(budget, 2); var token = UUID.randomUUID();
        assertTrue(queue.offer(token, null, new byte[10], 0));
        assertEquals(Set.of(token), queue.expire(30_000)); assertNull(queue.poll(30_000)); assertEquals(0, budget.used());
    }
    @Test void copiesCallerBytesAndRejectsPreparationFromCancelledGeneration() {
        var budget = new TransportBudget(20); var queue = new BoundedOutgoing(budget, 2); var token = UUID.randomUUID();
        byte[] bytes = { 7 }; long generation = queue.generation();
        assertTrue(queue.offer(token, null, bytes, 0, generation)); bytes[0] = 9;
        assertEquals(7, queue.poll(0).fragment().bytes()[0]); assertEquals(0, budget.used());
        queue.clear(); assertFalse(queue.offer(token, null, bytes, 0, generation)); assertEquals(0, budget.used());
        generation = queue.generation(); queue.remove(token);
        assertFalse(queue.offer(token, null, bytes, 0, generation)); assertNull(queue.poll(0));
        assertTrue(queue.offer(token, null, bytes, 0, queue.generation())); queue.clear(); assertEquals(0, budget.used());
    }
    @Test void removingOneConnectionKeepsAnotherConnectionsPreparation() {
        var budget = new TransportBudget(20); var queue = new BoundedOutgoing(budget, 2);
        var kept = UUID.randomUUID(); var removed = UUID.randomUUID();
        long generation = queue.generation();
        queue.remove(removed);
        assertTrue(queue.offer(kept, null, new byte[] { 1 }, 0, generation));
        assertFalse(queue.offer(removed, null, new byte[] { 2 }, 0, generation));
        assertEquals(kept, queue.poll(0).connection()); assertNull(queue.poll(0)); assertEquals(0, budget.used());
    }
    @Test void boundedRemovalRecordsFailClosedForOldPreparations() {
        var budget = new TransportBudget(20); var queue = new BoundedOutgoing(budget, 2); var token = UUID.randomUUID();
        long generation = queue.generation();
        for (int i = 0; i < 1_000; i++) queue.remove(UUID.randomUUID());
        // Evicted records raise the floor: an old capture is cancelled conservatively, a fresh one is accepted.
        assertFalse(queue.offer(token, null, new byte[] { 1 }, 0, generation));
        assertTrue(queue.offer(token, null, new byte[] { 1 }, 0, queue.generation()));
        queue.clear(); assertEquals(0, budget.used());
    }
}
