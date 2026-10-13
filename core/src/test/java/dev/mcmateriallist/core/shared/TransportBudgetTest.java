package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.transport.TransportBudget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransportBudgetTest {
    @Test void packetCompletionAndSynchronousFailureShareOneRelease() {
        var budget = new TransportBudget(10);
        var reservation = budget.reserveOnce(10); assertNotNull(reservation);
        assertNull(budget.reserveOnce(1)); assertEquals(10, budget.used());
        reservation.run(); reservation.run(); assertEquals(0, budget.used());
        var next = budget.reserveOnce(10); assertNotNull(next);
        reservation.run(); assertEquals(10, budget.used()); next.run(); assertEquals(0, budget.used());
    }
    @Test void aggregateReservationRejectsBeforeAdmissionAndReleaseRestoresCapacity() {
        var budget = new TransportBudget(128);
        assertTrue(budget.reserve(64)); assertTrue(budget.reserve(64));
        assertFalse(budget.reserve(1)); assertEquals(128, budget.used());
        budget.release(64); assertTrue(budget.reserve(64)); assertFalse(budget.reserve(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.release(129));
    }
}
