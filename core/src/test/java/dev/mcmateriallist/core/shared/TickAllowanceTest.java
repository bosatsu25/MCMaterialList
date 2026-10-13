package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.transport.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TickAllowanceTest {
    @Test void fifthSendIsRejectedUntilNextTickAndGlobalConnectionTwentyFirstIsRejected() {
        var allowance = new TickAllowance(4);
        assertTrue(allowance.take()); assertTrue(allowance.take()); assertTrue(allowance.take()); assertTrue(allowance.take());
        assertFalse(allowance.take()); allowance.reset(); assertTrue(allowance.take());
        var connections = new TransportBudget(20);
        for (int i = 0; i < 20; i++) assertTrue(connections.reserve(1));
        assertFalse(connections.reserve(1)); connections.release(1); assertTrue(connections.reserve(1));
    }
}
