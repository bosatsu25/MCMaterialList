package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.transport.BoundedAdmissions;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedAdmissionsTest {
    @Test void closeBetweenOpenObservationAndEnqueueStillCompletesFalse() throws InterruptedException {
        var mailbox = new BoundedAdmissions<CompletableFuture<Boolean>>(1, result -> result.complete(false));
        var result = new CompletableFuture<Boolean>();
        var observed = new CountDownLatch(1); var enqueue = new CountDownLatch(1);
        var producerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread producer = new Thread(() -> {
            try {
                if (mailbox.open()) {
                    observed.countDown();
                    if (!enqueue.await(2, TimeUnit.SECONDS)) throw new AssertionError("Admission barrier timed out");
                    mailbox.offer(result);
                } else result.complete(false);
            } catch (Throwable failure) { producerFailure.set(failure); }
        }, "test-admission-producer");
        producer.start();
        try {
            assertTrue(observed.await(2, TimeUnit.SECONDS));
            mailbox.close(); enqueue.countDown(); producer.join(2_000);
            assertFalse(producer.isAlive()); assertNull(producerFailure.get());
            assertTrue(result.isDone(), "Closed admission stranded its transport future");
            assertFalse(result.join()); assertNull(mailbox.poll());
        } finally { enqueue.countDown(); producer.join(2_000); }
    }
    @Test void closeAndCapacityRejectOnlyTransportAdmission() {
        var mailbox = new BoundedAdmissions<CompletableFuture<Boolean>>(1, result -> result.complete(false));
        var accepted = new CompletableFuture<Boolean>(); var excess = new CompletableFuture<Boolean>();
        assertTrue(mailbox.offer(accepted)); assertFalse(mailbox.offer(excess)); assertFalse(excess.join());
        mailbox.close(); assertFalse(accepted.join()); assertFalse(mailbox.open()); assertNull(mailbox.poll());
    }
}
