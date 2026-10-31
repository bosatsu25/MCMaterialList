package dev.mcmateriallist.fabric.stock;

import dev.mcmateriallist.core.stock.StockLimits;
import dev.mcmateriallist.core.stock.protocol.StockMessage;
import dev.mcmateriallist.core.stock.protocol.StockProtocol;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One Check Materials message as one bounded frame on its own channel (mcmateriallist:stock_v1), registered in both directions.
 * SharedPayload and its protocol version are untouched, so a server without this channel simply lacks the capability.
 */
public record StockPayload(StockMessage message) implements CustomPacketPayload {
    public StockPayload { Objects.requireNonNull(message, "message"); }
    public static final Type<StockPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcmateriallist", "stock_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StockPayload> CODEC = new StreamCodec<>() {
        @Override public StockPayload decode(RegistryFriendlyByteBuf buffer) {
            if (buffer.readableBytes() < 4) throw new IllegalArgumentException("Truncated stock length");
            int size = buffer.readInt();
            if (size < 5 || size > StockLimits.FRAME_BYTES || size != buffer.readableBytes()) throw new IllegalArgumentException("Invalid stock frame length");
            byte[] bytes = new byte[size]; buffer.readBytes(bytes);
            return new StockPayload(StockProtocol.decode(bytes));
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, StockPayload payload) {
            byte[] frame = StockProtocol.encode(payload.message());
            buffer.writeInt(frame.length); buffer.writeBytes(frame);
        }
    };
    @Override public Type<StockPayload> type() { return TYPE; }
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }
    static {
        if (!TYPE.id().toString().equals(StockProtocol.CHANNEL)) throw new IllegalStateException("Stock channel mismatch");
    }
}
