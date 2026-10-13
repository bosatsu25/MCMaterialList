package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.shared.protocol.SharedProtocol;
import dev.mcmateriallist.fabric.shared.SharedControlPayload;
import java.nio.ByteBuffer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** Test-only frozen v1 Hello parser on the actual registered socket; no external v1 process. */
public final class LegacyPeerFixture {
    private static volatile boolean armed;
    private static volatile byte[] encoded;
    private static volatile boolean sawFrame;
    private static volatile boolean accepted;
    private static final ThreadLocal<Integer> START = new ThreadLocal<>();
    private LegacyPeerFixture() {}
    public static void begin() { encoded = null; sawFrame = false; accepted = false; armed = true; }
    public static void finish() { armed = false; encoded = null; START.remove(); }
    public static boolean sawFrame() { return sawFrame; }
    public static boolean accepted() { return accepted; }
    public static void encoding(RegistryFriendlyByteBuf buffer) { if (armed) START.set(buffer.writerIndex()); }
    public static void encoded(RegistryFriendlyByteBuf buffer) {
        Integer start = START.get(); START.remove();
        if (!armed || start == null) return;
        int size = buffer.writerIndex() - start;
        if (size < 0 || size > 4 + 61_480) return;
        byte[] data = new byte[size]; buffer.getBytes(start, data); encoded = data;
    }
    public static boolean receive(ServerPlayer player) {
        if (!armed) return false;
        sawFrame = true;
        accepted = exactV1Negotiation(encoded);
        ServerPlayNetworking.send(player, new SharedControlPayload(accepted
            ? SharedControlPayload.Reason.UNSUPPORTED : SharedControlPayload.Reason.INVALID_INPUT));
        return true;
    }
    private static boolean exactV1Negotiation(byte[] packet) {
        if (packet == null || packet.length != 49) return false;
        ByteBuffer in = ByteBuffer.wrap(packet);
        if (in.getInt() != 45 || in.getInt() != 1) return false;
        in.getLong(); in.getLong(); // v1 transfer ID has no negotiation semantics.
        return in.getInt() == 0 && in.getInt() == 1
            && in.getInt() == 9 && in.getInt() == 9 && in.getInt() == 1
            && in.get() == 0 && in.getInt() == SharedProtocol.VERSION && !in.hasRemaining();
    }
}
