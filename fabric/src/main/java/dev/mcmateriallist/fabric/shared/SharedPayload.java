package dev.mcmateriallist.fabric.shared;

import dev.mcmateriallist.core.shared.protocol.SharedTransfers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** One bounded application frame; registered on both directions before receivers. */
public record SharedPayload(SharedTransfers.Fragment fragment) implements CustomPacketPayload {
    public static final Type<SharedPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcmateriallist", "shared_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SharedPayload> CODEC = new StreamCodec<>() {
        @Override public SharedPayload decode(RegistryFriendlyByteBuf buffer) {
            if (buffer.readableBytes() < 4) throw new IllegalArgumentException("Truncated shared length");
            int size = buffer.readInt();
            if (size < 37 || size > SharedTransfers.FRAME_BYTES || size != buffer.readableBytes())
                throw new IllegalArgumentException("Invalid shared frame length");
            byte[] bytes = new byte[size]; buffer.readBytes(bytes);
            return new SharedPayload(SharedTransfers.decode(bytes));
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, SharedPayload payload) {
            byte[] frame = SharedTransfers.encode(payload.fragment());
            buffer.writeInt(frame.length); buffer.writeBytes(frame);
        }
    };
    @Override public Type<SharedPayload> type() { return TYPE; }
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }
}
