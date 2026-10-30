package dev.mcmateriallist.core.stock.protocol;

import dev.mcmateriallist.core.location.ContainerKind;
import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationProfileId;
import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.stock.LocationReport;
import dev.mcmateriallist.core.stock.LocationStatus;
import dev.mcmateriallist.core.stock.StockGrant;
import dev.mcmateriallist.core.stock.StockLimits;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockProtocolTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private final UUID request = UUID.randomUUID();
    private final ProjectScope scope = ProjectScope.create(UUID.randomUUID());

    @Test void everyMessageRoundTrips() {
        List<ContainerPosition> positions = List.of(new ContainerPosition(-30_000_000, -2048, 30_000_000, "minecraft:light_gray_shulker_box"),
            new ContainerPosition(1, 2047, 3, "minecraft:hopper"), new ContainerPosition(1, 2047, 3, "minecraft:hopper"));
        List<StockMessage> messages = List.of(
            new StockMessage.ScanRequest(request, LocationProfileId.create(), 7, scope, OVERWORLD, positions),
            new StockMessage.ScanRequest(request, LocationProfileId.create(), 0, null, "minecraft:the_nether", List.of()),
            new StockMessage.Cancel(request),
            new StockMessage.Report(request, new LocationReport(511, LocationStatus.CHECKED, ContainerKind.MACHINE, Map.of("minecraft:stone", Long.MAX_VALUE, "minecraft:dirt", 0L))),
            new StockMessage.Report(request, LocationReport.empty(0, LocationStatus.UNAUTHORIZED, ContainerKind.STORAGE)),
            new StockMessage.Done(request, StockMessage.Outcome.CANCELLED, 3, 1, 8, 987_654),
            new StockMessage.Refused(request, StockMessage.RefusalReason.COOLDOWN),
            new StockMessage.GrantRequest(request, scope, OVERWORLD, positions), new StockMessage.GrantRequest(request, scope, OVERWORLD, List.of()),
            new StockMessage.RevokeRequest(request, scope, OVERWORLD, positions),
            new StockMessage.GrantReply(request, StockMessage.GrantStatus.OK, OVERWORLD, List.of(new StockGrant(OVERWORLD, 1, 2, 3, UUID.randomUUID(), 1_700_000_000_000L))),
            new StockMessage.GrantReply(request, StockMessage.GrantStatus.UNAUTHORIZED, OVERWORLD, List.of()));
        for (StockMessage message : messages) {
            byte[] bytes = StockProtocol.encode(message);
            assertEquals(message, StockProtocol.decode(bytes), message.toString());
            assertArrayEquals(bytes, StockProtocol.encode(StockProtocol.decode(bytes)));
            byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
            assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(trailing));
            for (int cut = 0; cut < bytes.length; cut += Math.max(1, bytes.length / 7)) {
                byte[] truncated = Arrays.copyOf(bytes, cut);
                assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(truncated));
            }
        }
    }
    @Test void unknownVersionTagAndEnumValuesAreRejected() {
        byte[] cancel = StockProtocol.encode(new StockMessage.Cancel(request));
        byte[] version = cancel.clone(); version[3] = 2; assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(version));
        for (int tag : new int[]{8, 255}) { byte[] unknown = cancel.clone(); unknown[4] = (byte) tag; assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(unknown)); }
        byte[] refused = StockProtocol.encode(new StockMessage.Refused(request, StockMessage.RefusalReason.INVALID));
        refused[refused.length - 1] = 4; assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(refused));
        byte[] report = StockProtocol.encode(new StockMessage.Report(request, LocationReport.empty(0, LocationStatus.OBSERVED, ContainerKind.STORAGE)));
        byte[] status = report.clone(); status[25] = 8; assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(status));
        byte[] kind = report.clone(); kind[26] = 2; assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(kind));
        byte[] scan = StockProtocol.encode(new StockMessage.ScanRequest(request, LocationProfileId.create(), 0, null, OVERWORLD, List.of()));
        scan[4 + 1 + 32 + 8] = 2; assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(scan));
        assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(new byte[StockLimits.FRAME_BYTES + 1]));
    }
    @Test void countsAndIdsAreBoundedAndStrict() {
        // Negative count, duplicate id, invalid id, counts on an unread status: all rebuilt by hand.
        assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(report(0, 0, List.of("minecraft:stone"), List.of(-1L))));
        assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(report(0, 0, List.of("minecraft:stone", "minecraft:stone"), List.of(1L, 2L))));
        assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(report(0, 0, List.of("Minecraft:Stone"), List.of(1L))));
        assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(report(0, 1, List.of("minecraft:stone"), List.of(1L))));
        assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(report(512, 1, List.of(), List.of())));
        assertEquals(LocationStatus.CHECKED, ((StockMessage.Report) StockProtocol.decode(report(0, 0, List.of("minecraft:stone"), List.of(1L)))).report().status());
        List<String> ids = new ArrayList<>(); List<Long> counts = new ArrayList<>();
        for (int i = 0; i <= StockLimits.MAX_ITEMS; i++) { ids.add("m:" + i); counts.add(1L); }
        assertThrows(IllegalArgumentException.class, () -> StockProtocol.decode(report(0, 0, ids, counts)));
        assertThrows(IllegalArgumentException.class, () -> new StockMessage.ScanRequest(request, LocationProfileId.create(), -1, null, OVERWORLD, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StockMessage.ScanRequest(request, LocationProfileId.create(), 0, null, OVERWORLD,
            List.of(new ContainerPosition(0, 0, 0, "minecraft:ender_chest"))));
        assertThrows(IllegalArgumentException.class, () -> new StockMessage.GrantReply(request, StockMessage.GrantStatus.OK, OVERWORLD,
            List.of(new StockGrant("minecraft:the_nether", 0, 0, 0, request, 0))));
        assertThrows(IllegalArgumentException.class, () -> new StockMessage.Done(request, StockMessage.Outcome.COMPLETE, 1, 2, 0, 0));
    }
    @Test void worstCaseRequestsFitOneFrameAndOversizedReportsAreDetected() {
        List<ContainerPosition> positions = new ArrayList<>();
        for (int i = 0; i < StockLimits.MAX_POSITIONS; i++) positions.add(new ContainerPosition(-30_000_000 + i, -2048, -30_000_000, "minecraft:light_gray_shulker_box"));
        String dimension = "a:" + "b".repeat(254);
        byte[] scan = StockProtocol.encode(new StockMessage.ScanRequest(request, LocationProfileId.create(), Long.MAX_VALUE, scope, dimension, positions));
        assertTrue(scan.length <= StockLimits.FRAME_BYTES, String.valueOf(scan.length));
        List<ContainerPosition> tooMany = new ArrayList<>(positions); tooMany.add(positions.get(0));
        assertThrows(IllegalArgumentException.class, () -> new StockMessage.ScanRequest(request, LocationProfileId.create(), 0, null, OVERWORLD, tooMany));
        List<StockGrant> grants = new ArrayList<>();
        for (int i = 0; i < StockLimits.MAX_GRANTS; i++) grants.add(new StockGrant(dimension, i, 0, 0, request, Long.MAX_VALUE));
        assertTrue(StockProtocol.encode(new StockMessage.GrantReply(request, StockMessage.GrantStatus.OK, dimension, grants)).length <= StockLimits.FRAME_BYTES);
        Map<String, Long> counts = new HashMap<>();
        for (int i = 0; i < 240; i++) counts.put("minecraft:" + "x".repeat(240) + i, 1L);   // about 63.6 KB of entries
        LocationReport big = new LocationReport(0, LocationStatus.CHECKED, ContainerKind.STORAGE, counts);
        assertFalse(StockProtocol.reportFits(big));
        assertThrows(IllegalArgumentException.class, () -> StockProtocol.encode(new StockMessage.Report(request, big)));
        Map<String, Long> fits = new HashMap<>(); for (int i = 0; i < StockLimits.MAX_ITEMS; i++) fits.put("minecraft:item_" + i, (long) i);
        LocationReport typical = new LocationReport(3, LocationStatus.CHECKED, ContainerKind.STORAGE, fits);
        assertTrue(StockProtocol.reportFits(typical));
        assertEquals(StockProtocol.encode(new StockMessage.Report(request, typical)).length, reportBytes(typical));
    }

    private static int reportBytes(LocationReport report) {
        int bytes = 4 + 1 + 16 + 4 + 1 + 1 + 4;
        for (String id : report.counts().keySet()) bytes += 4 + id.length() + 8;
        return bytes;
    }
    private byte[] report(int index, int status, List<String> ids, List<Long> counts) {
        ByteBuffer buffer = ByteBuffer.allocate(StockLimits.FRAME_BYTES * 2);
        buffer.putInt(StockProtocol.VERSION).put((byte) 2).putLong(request.getMostSignificantBits()).putLong(request.getLeastSignificantBits());
        buffer.putInt(index).put((byte) status).put((byte) 0).putInt(ids.size());
        for (int i = 0; i < ids.size(); i++) { byte[] id = ids.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8); buffer.putInt(id.length).put(id).putLong(counts.get(i)); }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
}
