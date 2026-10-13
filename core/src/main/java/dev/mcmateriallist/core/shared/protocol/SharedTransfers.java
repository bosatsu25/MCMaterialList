package dev.mcmateriallist.core.shared.protocol;

import dev.mcmateriallist.core.shared.SharedLimits;
import java.io.IOException;
import java.util.*;

/** One instance per authenticated connection, owned by its receiving thread. Uses monotonic milliseconds. */
public final class SharedTransfers {
    public static final int FRAME_BYTES = SharedLimits.FRAGMENT_BYTES + 36;
    public record Fragment(UUID transferId, int index, int count, int totalBytes, byte[] bytes) {
        public Fragment {
            Objects.requireNonNull(transferId); Objects.requireNonNull(bytes);
            if (totalBytes < 1 || totalBytes > SharedLimits.MESSAGE_BYTES || count < 1 || count > SharedLimits.FRAGMENTS
                || count != (totalBytes + SharedLimits.FRAGMENT_BYTES - 1) / SharedLimits.FRAGMENT_BYTES || index < 0 || index >= count
                || bytes.length != (index == count - 1 ? totalBytes - index * SharedLimits.FRAGMENT_BYTES : SharedLimits.FRAGMENT_BYTES))
                throw new IllegalArgumentException("Invalid shared fragment");
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private record Transfer(int count, int total, long started, byte[][] parts, BitSet present) {}
    private final Map<UUID, Transfer> transfers = new HashMap<>();
    private long lastTime = Long.MIN_VALUE;
    public static List<Fragment> split(UUID transferId, byte[] message) {
        if (message.length < 1 || message.length > SharedLimits.MESSAGE_BYTES) throw new IllegalArgumentException("Invalid transfer size");
        int count = (message.length + SharedLimits.FRAGMENT_BYTES - 1) / SharedLimits.FRAGMENT_BYTES;
        List<Fragment> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new Fragment(transferId, i, count, message.length,
            Arrays.copyOfRange(message, i * SharedLimits.FRAGMENT_BYTES, Math.min(message.length, (i + 1) * SharedLimits.FRAGMENT_BYTES))));
        return List.copyOf(result);
    }
    public static byte[] encode(Fragment f) {
        return encodeFrame(f, SharedProtocol.VERSION);
    }
    /** The only schema-1 frame this implementation emits is a single exact Hello. */
    public static byte[] encodeNegotiationFrame(Fragment f) {
        if (!isNegotiationHello(f)) throw new IllegalArgumentException("Invalid negotiation fragment");
        return encodeFrame(f, 1);
    }
    public static boolean isNegotiationHello(Fragment f) {
        if (f.index() != 0 || f.count() != 1 || f.totalBytes() != 9) return false;
        byte[] bytes = f.bytes();
        return bytes.length == 9 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0
            && bytes[3] == 1 && bytes[4] == 0;
    }
    private static byte[] encodeFrame(Fragment f, int version) {
        return SharedBinary.encode(FRAME_BYTES, out -> {
            out.writeInt(version); SharedBinary.uuid(out, f.transferId()); out.writeInt(f.index());
            out.writeInt(f.count()); out.writeInt(f.totalBytes()); out.writeInt(f.bytes.length); out.write(f.bytes);
        });
    }
    public static Fragment decode(byte[] frame) {
        return SharedBinary.decode(frame, FRAME_BYTES, in -> {
            int version = in.readInt();
            if (version != SharedProtocol.VERSION && version != 1) throw new IllegalArgumentException("Unsupported fragment version");
            UUID id = SharedBinary.uuid(in); int index = in.readInt(), count = in.readInt(), total = in.readInt();
            int size = SharedBinary.count(in, SharedLimits.FRAGMENT_BYTES);
            if (size > in.available()) throw new IOException("Truncated shared frame");
            Fragment fragment = new Fragment(id, index, count, total, in.readNBytes(size));
            if (version == 1 && (frame.length != 45 || !isNegotiationHello(fragment)))
                throw new IllegalArgumentException("Unsupported legacy fragment");
            if (version == SharedProtocol.VERSION && isNegotiationHello(fragment))
                throw new IllegalArgumentException("Mixed negotiation versions");
            return fragment;
        });
    }
    /** Invalid/changed/duplicate fragments discard that transfer and fail closed. Expiry is never extended. */
    public Optional<byte[]> accept(Fragment fragment, long nowMillis) {
        expire(nowMillis);
        Transfer transfer = transfers.get(fragment.transferId());
        if (transfer == null) {
            if (transfers.size() >= SharedLimits.INCOMPLETE_TRANSFERS) throw new IllegalArgumentException("Too many incomplete transfers");
            transfer = new Transfer(fragment.count(), fragment.totalBytes(), nowMillis, new byte[fragment.count()][], new BitSet(fragment.count()));
            transfers.put(fragment.transferId(), transfer);
        }
        if (transfer.count() != fragment.count() || transfer.total() != fragment.totalBytes() || transfer.present().get(fragment.index())) {
            transfers.remove(fragment.transferId()); throw new IllegalArgumentException("Inconsistent shared transfer");
        }
        transfer.parts()[fragment.index()] = fragment.bytes(); transfer.present().set(fragment.index());
        if (transfer.present().cardinality() != transfer.count()) return Optional.empty();
        transfers.remove(fragment.transferId()); byte[] result = new byte[transfer.total()];
        for (int i = 0; i < transfer.count(); i++) System.arraycopy(transfer.parts()[i], 0, result, i * SharedLimits.FRAGMENT_BYTES, transfer.parts()[i].length);
        return Optional.of(result);
    }
    public void expire(long nowMillis) {
        if (nowMillis < lastTime) { transfers.clear(); throw new IllegalArgumentException("Nonmonotonic transfer clock"); }
        lastTime = nowMillis;
        transfers.values().removeIf(t -> nowMillis - t.started() >= SharedLimits.TRANSFER_MILLIS);
    }
    public int incompleteCount() { return transfers.size(); }
    public void clear() { transfers.clear(); }
}
