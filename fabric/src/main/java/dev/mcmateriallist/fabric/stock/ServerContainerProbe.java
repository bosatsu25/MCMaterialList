package dev.mcmateriallist.fabric.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.stock.ContainerProbe;
import dev.mcmateriallist.core.stock.StockLimits;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Live server-thread world access for StockScan. Every position is checked with hasChunkAt (which never loads a chunk) before any
 * block or block-entity access. A container that still carries an ungenerated loot table is UNREADABLE and its items are never
 * touched, because reading them would generate the loot and change the world. Item identity is the registry id; components and
 * the contents of shulker box items (depth 0) are ignored.
 */
final class ServerContainerProbe implements ContainerProbe {
    /** Which block entities are read as a plain inventory; the default reads allowlisted vanilla Container block entities. */
    @FunctionalInterface interface InventoryProvider { Container inventory(BlockEntity entity); }
    static final InventoryProvider VANILLA_CONTAINER_PROVIDER = entity -> entity instanceof Container container ? container : null;
    private final MinecraftServer server;
    private final InventoryProvider provider;

    ServerContainerProbe(MinecraftServer server) { this(server, VANILLA_CONTAINER_PROVIDER); }
    ServerContainerProbe(MinecraftServer server, InventoryProvider provider) {
        this.server = Objects.requireNonNull(server, "server"); this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override public Shape shape(String dimension, ContainerPosition registered) {
        ServerLevel level = level(dimension);
        BlockPos pos = pos(registered);
        if (level == null || !level.hasChunkAt(pos)) return new Unloaded();
        BlockState state = level.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        // The partner rule of LookedAtContainer: only the same block pointing back is the other half of one double chest.
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            ContainerPosition partner;
            try { partner = new ContainerPosition(other.getX(), other.getY(), other.getZ(), id); }
            catch (IllegalArgumentException outside) { return Present.single(id); }
            if (!level.hasChunkAt(other)) return new Present(id, partner, false);
            BlockState second = level.getBlockState(other);
            if (second.is(state.getBlock()) && second.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && other.relative(ChestBlock.getConnectedDirection(second)).equals(pos)) return new Present(id, partner, true);
        }
        return Present.single(id);
    }

    @Override public Reading read(String dimension, List<ContainerPosition> halves) {
        ServerLevel level = level(dimension);
        if (level == null) return new Unreadable();
        Map<String, Long> counts = new HashMap<>();
        for (ContainerPosition half : halves) {
            BlockPos pos = pos(half);
            if (!level.hasChunkAt(pos)) return new Unreadable();
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity == null || ungeneratedLoot(entity)) return new Unreadable();
            Container inventory = provider.inventory(entity);
            if (inventory == null) return new Unreadable();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) continue;
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
                if (counts.size() > StockLimits.MAX_ITEMS) return new Unreadable();
            }
        }
        return new Contents(counts);
    }

    /** Decided from the loot-table key alone; getItem would unpack (generate) the loot first. */
    private static boolean ungeneratedLoot(BlockEntity entity) {
        return entity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null;
    }
    private ServerLevel level(String dimension) {
        for (ServerLevel level : server.getAllLevels()) if (level.dimension().identifier().toString().equals(dimension)) return level;
        return null;
    }
    private static BlockPos pos(ContainerPosition position) { return new BlockPos(position.x(), position.y(), position.z()); }
}
