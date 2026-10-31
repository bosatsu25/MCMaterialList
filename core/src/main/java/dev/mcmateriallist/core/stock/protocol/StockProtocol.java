package dev.mcmateriallist.core.stock.protocol;

import dev.mcmateriallist.core.location.ContainerKind;
import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationProfileId;
import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.stock.LocationReport;
import dev.mcmateriallist.core.stock.LocationStatus;
import dev.mcmateriallist.core.stock.StockGrant;
import dev.mcmateriallist.core.stock.StockLimits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Own channel and codec, independent of SharedProtocol so neither its version nor persisted client blobs change. Big-endian,
 * protocol version then a message tag; one message is one frame of at most 61,440 bytes. Unknown versions or tags, out-of-range
 * counts, invalid ids and trailing bytes are all rejected with IllegalArgumentException. Scalar fields follow SharedBinary's layout;
 * the helpers are private copies so the shared protocol code stays untouched.
 */
public final class StockProtocol {
    public static final String CHANNEL = "mcmateriallist:stock_v1";
    public static final int VERSION = 1;
    private static final int ID_BYTES = StockLimits.ITEM_ID_BYTES, REPORT_HEADER = 4 + 1 + 16 + 4 + 1 + 1 + 4, COUNT_ENTRY = 4 + 8;
    private StockProtocol() {}

    /** True when the report's frame fits; a scan reports UNREADABLE instead of a frame that cannot be sent. */
    public static boolean reportFits(LocationReport report) {
        long bytes = REPORT_HEADER;
        for (String id : report.counts().keySet()) bytes += COUNT_ENTRY + id.getBytes(StandardCharsets.UTF_8).length;
        return bytes <= StockLimits.FRAME_BYTES;
    }
    public static byte[] encode(StockMessage message) {
        Objects.requireNonNull(message);
        return encode(StockLimits.FRAME_BYTES, out -> {
            out.writeInt(VERSION);
            switch (message) {
                case StockMessage.ScanRequest m -> { out.writeByte(0); uuid(out, m.requestId()); uuid(out, m.profileId().value()); out.writeLong(m.profileGeneration());
                    out.writeBoolean(m.binding() != null); if (m.binding() != null) scope(out, m.binding()); text(out, m.dimension(), ID_BYTES); positions(out, m.positions()); }
                case StockMessage.Cancel m -> { out.writeByte(1); uuid(out, m.requestId()); }
                case StockMessage.Report m -> { out.writeByte(2); uuid(out, m.requestId()); report(out, m.report()); }
                case StockMessage.Done m -> { out.writeByte(3); uuid(out, m.requestId()); out.writeByte(m.outcome().ordinal()); out.writeInt(m.reports());
                    out.writeInt(m.checked()); out.writeInt(m.maxContainersPerTick()); out.writeLong(m.maxNanosPerTick()); }
                case StockMessage.Refused m -> { out.writeByte(4); uuid(out, m.requestId()); out.writeByte(m.reason().ordinal()); }
                case StockMessage.GrantRequest m -> { out.writeByte(5); uuid(out, m.requestId()); scope(out, m.scope()); text(out, m.dimension(), ID_BYTES); positions(out, m.positions()); }
                case StockMessage.RevokeRequest m -> { out.writeByte(6); uuid(out, m.requestId()); scope(out, m.scope()); text(out, m.dimension(), ID_BYTES); positions(out, m.positions()); }
                case StockMessage.GrantReply m -> { out.writeByte(7); uuid(out, m.requestId()); out.writeByte(m.status().ordinal()); text(out, m.dimension(), ID_BYTES);
                    out.writeInt(m.grants().size());
                    for (StockGrant g : m.grants()) { out.writeInt(g.x()); out.writeInt(g.y()); out.writeInt(g.z()); uuid(out, g.approver()); out.writeLong(g.grantedAt()); } }
            }
        });
    }
    public static StockMessage decode(byte[] bytes) {
        return decode(bytes, StockLimits.FRAME_BYTES, in -> {
            if (in.readInt() != VERSION) throw new IllegalArgumentException("Unsupported stock protocol");
            return switch (in.readUnsignedByte()) {
                case 0 -> new StockMessage.ScanRequest(uuid(in), new LocationProfileId(uuid(in)), in.readLong(), bool(in) ? scope(in) : null, text(in, ID_BYTES), positions(in));
                case 1 -> new StockMessage.Cancel(uuid(in));
                case 2 -> new StockMessage.Report(uuid(in), report(in));
                case 3 -> new StockMessage.Done(uuid(in), enumeration(in, StockMessage.Outcome.values()), in.readInt(), in.readInt(), in.readInt(), in.readLong());
                case 4 -> new StockMessage.Refused(uuid(in), enumeration(in, StockMessage.RefusalReason.values()));
                case 5 -> new StockMessage.GrantRequest(uuid(in), scope(in), text(in, ID_BYTES), positions(in));
                case 6 -> new StockMessage.RevokeRequest(uuid(in), scope(in), text(in, ID_BYTES), positions(in));
                case 7 -> {
                    UUID request = uuid(in); StockMessage.GrantStatus status = enumeration(in, StockMessage.GrantStatus.values()); String dimension = text(in, ID_BYTES);
                    int n = count(in, StockLimits.MAX_GRANTS); List<StockGrant> grants = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) grants.add(new StockGrant(dimension, in.readInt(), in.readInt(), in.readInt(), uuid(in), in.readLong()));
                    yield new StockMessage.GrantReply(request, status, dimension, grants);
                }
                default -> throw new IllegalArgumentException("Unknown stock message");
            };
        });
    }
    private static void positions(DataOutputStream out, List<ContainerPosition> positions) throws IOException {
        out.writeInt(positions.size());
        for (ContainerPosition p : positions) { out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z()); text(out, p.blockId(), ID_BYTES); }
    }
    private static List<ContainerPosition> positions(DataInputStream in) throws IOException {
        int n = count(in, StockLimits.MAX_POSITIONS); List<ContainerPosition> positions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) positions.add(new ContainerPosition(in.readInt(), in.readInt(), in.readInt(), text(in, ID_BYTES)));
        return positions;
    }
    private static void report(DataOutputStream out, LocationReport report) throws IOException {
        out.writeInt(report.index()); out.writeByte(report.status().ordinal()); out.writeByte(report.kind().ordinal());
        List<String> ids = report.counts().keySet().stream().sorted().toList(); out.writeInt(ids.size());
        for (String id : ids) { text(out, id, ID_BYTES); out.writeLong(report.counts().get(id)); }
    }
    private static LocationReport report(DataInputStream in) throws IOException {
        int index = in.readInt(); LocationStatus status = enumeration(in, LocationStatus.values()); ContainerKind kind = enumeration(in, ContainerKind.values());
        int n = count(in, StockLimits.MAX_ITEMS); Map<String, Long> counts = new HashMap<>();
        for (int i = 0; i < n; i++) if (counts.put(text(in, ID_BYTES), in.readLong()) != null) throw new IllegalArgumentException("Duplicate item");
        return new LocationReport(index, status, kind, counts);
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface private interface Reader<T> { T read(DataInputStream in) throws IOException; }
    private static byte[] encode(int limit, Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { writer.write(out); }
        catch (IOException exception) { throw new IllegalArgumentException("Cannot encode stock message"); }
        if (bytes.size() > limit) throw new IllegalArgumentException("Stock frame size limit");
        return bytes.toByteArray();
    }
    private static <T> T decode(byte[] bytes, int limit, Reader<T> reader) {
        Objects.requireNonNull(bytes); if (bytes.length > limit) throw new IllegalArgumentException("Stock frame size limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            T value = reader.read(in);
            if (in.available() != 0) throw new IllegalArgumentException("Trailing stock bytes");
            return value;
        } catch (IOException exception) { throw new IllegalArgumentException("Invalid stock message"); }
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void scope(DataOutputStream out, ProjectScope value) throws IOException {
        uuid(out, value.worldNamespace()); uuid(out, value.projectId()); uuid(out, value.materialListId()); uuid(out, value.regionListId());
    }
    private static ProjectScope scope(DataInputStream in) throws IOException { return new ProjectScope(uuid(in), uuid(in), uuid(in), uuid(in)); }
    private static void text(DataOutputStream out, String value, int max) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) throw new IllegalArgumentException("Stock text size limit");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in, int max) throws IOException {
        int length = count(in, max); if (length > in.available()) throw new EOFException();
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
    }
    private static int count(DataInputStream in, int max) throws IOException {
        int n = in.readInt(); if (n < 0 || n > max) throw new IllegalArgumentException("Stock count out of range"); return n;
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte(); if (value > 1) throw new IllegalArgumentException("Invalid boolean"); return value == 1;
    }
    private static <E> E enumeration(DataInputStream in, E[] values) throws IOException {
        int n = in.readUnsignedByte(); if (n >= values.length) throw new IllegalArgumentException("Invalid enum"); return values[n];
    }
}
