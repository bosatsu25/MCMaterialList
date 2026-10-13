package dev.mcmateriallist.fabric.shared;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Content-free connection diagnostics; no identity, row or user text. */
public record SharedControlPayload(Reason reason) implements CustomPacketPayload {
    public SharedControlPayload { java.util.Objects.requireNonNull(reason); }
    public enum Reason { LOADING, CAPACITY, INVALID_INPUT, UNSUPPORTED, UNAVAILABLE, RESYNC_REQUIRED }
    public static final Type<SharedControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcmateriallist", "shared_control_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SharedControlPayload> CODEC = new StreamCodec<>() {
        @Override public SharedControlPayload decode(RegistryFriendlyByteBuf buffer) {
            if (buffer.readableBytes() != 1) throw new IllegalArgumentException("Invalid shared diagnostic");
            int value = buffer.readUnsignedByte();
            if (value >= Reason.values().length || buffer.isReadable()) throw new IllegalArgumentException("Invalid shared diagnostic");
            return new SharedControlPayload(Reason.values()[value]);
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, SharedControlPayload payload) { buffer.writeByte(payload.reason().ordinal()); }
    };
    @Override public Type<SharedControlPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC); }
}
