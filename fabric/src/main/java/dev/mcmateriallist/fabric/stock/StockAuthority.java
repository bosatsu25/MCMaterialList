package dev.mcmateriallist.fabric.stock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * R1: the only place that decides "server admin". The permission API changed in recent Minecraft versions, so this stays one
 * isolated method. A server admin is the owner of a singleplayer/LAN world or an operator on the server's op list; both can
 * already read any container with /data get block, so a Check Materials read exposes nothing new to them.
 */
public final class StockAuthority {
    private StockAuthority() {}

    /** Server thread only. The identity comes from the connection's player, never from a payload. */
    public static boolean serverAdmin(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) return false;
        NameAndId identity = new NameAndId(player.getUUID(), player.getGameProfile().name());
        return server.isSingleplayerOwner(identity) || server.getPlayerList().isOp(identity);
    }
}
