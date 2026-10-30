package dev.mcmateriallist.fabric.client.location;

import dev.mcmateriallist.core.location.ContainerBlocks;
import dev.mcmateriallist.core.location.ContainerPosition;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Click-time validation of the block the player is looking at. Only the block identity is read, never its contents. Check Materials
 * also uses it, when a container screen opens, to attribute an observation to a registered position.
 */
public final class LookedAtContainer {
    public sealed interface Capture permits Target, Rejected {}
    /** halves holds both positions of a double chest; position is the single canonical one that is stored. */
    public record Target(ContainerPosition position, List<ContainerPosition> halves) implements Capture {}
    public record Rejected(String key, String detail) implements Capture {}
    private LookedAtContainer() {}

    public static Capture capture() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            return new Rejected("not_block", "");
        BlockPos pos = hit.getBlockPos();
        if (!mc.level.hasChunkAt(pos)) return new Rejected("not_loaded", "");
        // Reach needs no separate check: the game only produces a BLOCK hit result within the player's interaction range.
        BlockState state = mc.level.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!ContainerBlocks.supported(id)) return new Rejected("unsupported_block", id);
        ContainerPosition here;
        try { here = position(pos, id); } catch (IllegalArgumentException outside) { return new Rejected("out_of_range", ""); }
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (!mc.level.hasChunkAt(other)) return new Rejected("not_loaded", "");
            BlockState partner = mc.level.getBlockState(other);
            // Only the same block pointing back is the other half; an inconsistent neighbour leaves this a single chest.
            if (partner.is(state.getBlock()) && partner.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && other.relative(ChestBlock.getConnectedDirection(partner)).equals(pos)) {
                ContainerPosition second;
                try { second = position(other, id); } catch (IllegalArgumentException outside) { return new Rejected("out_of_range", ""); }
                return new Target(ContainerBlocks.canonical(here, second), List.of(here, second));
            }
        }
        return new Target(here, List.of(here));
    }
    private static ContainerPosition position(BlockPos pos, String id) { return new ContainerPosition(pos.getX(), pos.getY(), pos.getZ(), id); }
}
