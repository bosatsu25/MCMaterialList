package dev.mcmateriallist.core.shared.client;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoadedSourceDigestsTest {
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static final class Source extends ByteArrayInputStream {
        boolean closed;
        Source(byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
    @Test void observesConsumedPrefixAndDrainsExactOpenedTailWithoutHoldingNativeThread() throws Exception {
        byte[] bytes = new byte[250_003]; new Random(4).nextBytes(bytes);
        var service = new LoadedSourceDigests(); var input = new Source(bytes);
        try {
            var stream = service.observe(input); assertNotNull(stream);
            assertEquals(input.available(), stream.available(), "Observation must preserve native stream availability");
            assertArrayEquals(Arrays.copyOf(bytes, 123), stream.readNBytes(123));
            assertEquals(45, stream.skip(45)); stream.close();
            assertEquals(hash(bytes), stream.digest().get(5, TimeUnit.SECONDS));
            assertTrue(input.closed); assertThrows(IOException.class, stream::read);
        } finally { service.close(); assertTrue(service.awaitClosed(5, TimeUnit.SECONDS)); }
    }
    @Test void deadlineInvalidatesProofButDoesNotCloseNativeOwnedInput() throws Exception {
        var service = new LoadedSourceDigests(20); var input = new Source(new byte[]{1, 2, 3});
        try {
            var stream = service.observe(input);
            assertNull(stream.digest().get(5, TimeUnit.SECONDS)); assertFalse(input.closed);
            assertEquals(1, stream.read()); stream.close(); assertTrue(input.closed);
        } finally { service.close(); assertTrue(service.awaitClosed(5, TimeUnit.SECONDS)); }
    }
    @Test void capacityAndShutdownPreserveNativeOwnershipAndReleaseEveryObservation() throws Exception {
        var service = new LoadedSourceDigests(); var inputs = new ArrayList<Source>();
        var streams = new ArrayList<LoadedSourceDigests.Observation>();
        try {
            for (int i = 0; i < LoadedSourceDigests.ACTIVE; i++) {
                var input = new Source(new byte[]{1}); inputs.add(input); streams.add(service.observe(input));
            }
            var excess = new Source(new byte[]{2}); assertNull(service.observe(excess)); assertFalse(excess.closed);
            service.close();
            for (int i = 0; i < inputs.size(); i++) {
                assertNull(streams.get(i).digest().get(5, TimeUnit.SECONDS)); assertFalse(inputs.get(i).closed);
                assertEquals(1, streams.get(i).read()); streams.get(i).close(); assertTrue(inputs.get(i).closed);
            }
        } finally { for (var stream : streams) stream.close(); service.close(); assertTrue(service.awaitClosed(5, TimeUnit.SECONDS)); }
    }
    @Test void slowOwnedTailMakesOverflowFailClosedWithoutBlockingOrClosingAnotherParser() throws Exception {
        var service = new LoadedSourceDigests(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        InputStream blocking = new InputStream() {
            @Override public int read() throws IOException {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                return -1;
            }
        };
        var second = new Source(new byte[LoadedSourceDigests.CHUNK_BYTES * (LoadedSourceDigests.CHUNKS + 1)]);
        LoadedSourceDigests.Observation observed = null;
        try {
            var first = service.observe(blocking); first.close(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            observed = service.observe(second); assertEquals(second.available(), observed.readAllBytes().length);
            assertNull(observed.digest().get(5, TimeUnit.SECONDS)); assertFalse(second.closed);
            observed.close(); assertTrue(second.closed);
        } finally { release.countDown(); if (observed != null) observed.close(); service.close(); assertTrue(service.awaitClosed(5, TimeUnit.SECONDS)); }
    }
    @Test void plainFallbackInvalidatesFirstAttemptAndUsesOnlySecondOpenedStream() throws Exception {
        var service = new LoadedSourceDigests(); byte[] bytes = "uncompressed native source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            var first = service.observe(new Source(bytes)); first.readNBytes(2); first.close(); first.invalidate();
            var second = service.observe(new Source(bytes)); second.readNBytes(4); second.close();
            assertEquals(hash(bytes), second.digest().get(5, TimeUnit.SECONDS));
        } finally { service.close(); assertTrue(service.awaitClosed(5, TimeUnit.SECONDS)); }
    }
    @Test void failingCloseCannotPublishAValidDigest() throws Exception {
        var service = new LoadedSourceDigests();
        try {
            var stream = service.observe(new ByteArrayInputStream(new byte[]{1}) {
                @Override public void close() throws IOException { throw new IOException("Fixture close failure"); }
            });
            stream.read(); stream.close(); assertNull(stream.digest().get(5, TimeUnit.SECONDS));
        } finally { service.close(); assertTrue(service.awaitClosed(5, TimeUnit.SECONDS)); }
    }
    @Test void invalidatedGenerationsCannotReuseStillQueuedChunkMemoryOrReportIdleEarly() throws Exception {
        var service = new LoadedSourceDigests(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var streams = new ArrayList<LoadedSourceDigests.Observation>();
        try {
            var blocker = service.observe(new InputStream() {
                @Override public int read() throws IOException {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture timeout"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                    return -1;
                }
            });
            streams.add(blocker); blocker.close(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Released observation slots do not release the memory of chunks still queued behind this read.
            for (int i = 0; i < LoadedSourceDigests.ACTIVE; i++) {
                var generation = service.observe(new Source(new byte[LoadedSourceDigests.CHUNK_BYTES * LoadedSourceDigests.CHUNKS]));
                assertNotNull(generation); streams.add(generation); generation.readAllBytes();
                assertFalse(generation.digest().isDone()); generation.invalidate(); generation.close();
            }
            var excess = service.observe(new Source(new byte[LoadedSourceDigests.CHUNK_BYTES])); streams.add(excess);
            excess.readAllBytes();
            assertTrue(excess.digest().isDone(), "Released active slots exceeded the global queued-byte budget");
            assertNull(excess.digest().join()); excess.close(); blocker.invalidate();
            assertFalse(service.awaitIdle(20, TimeUnit.MILLISECONDS), "Released active slots hid pending owned worker jobs");
            release.countDown(); assertTrue(service.awaitIdle(5, TimeUnit.SECONDS));
        } finally {
            release.countDown(); for (var stream : streams) { stream.invalidate(); stream.close(); }
            service.close(); assertTrue(service.awaitClosed(5, TimeUnit.SECONDS));
        }
    }
}
