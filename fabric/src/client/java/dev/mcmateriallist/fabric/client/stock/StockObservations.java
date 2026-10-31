package dev.mcmateriallist.fabric.client.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.core.location.WorldIdentity;
import dev.mcmateriallist.core.stock.LocationReport;
import dev.mcmateriallist.core.stock.StockLimits;
import dev.mcmateriallist.fabric.client.location.LocationWorld;
import dev.mcmateriallist.fabric.client.location.LookedAtContainer;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Client fallback evidence, memory only, client thread only. (a) The player's own inventory, recounted every tick and always shown
 * on its own, never as warehouse stock. (b) Registered containers the player actually opened: a container screen is attributed to
 * a registered position only when, at the moment it opens, the looked-at block is that registered position of the last known
 * Location File and the menu's container slot count matches the block. Its contents are then OBSERVED at the time last seen.
 */
public final class StockObservations {
    public record Observation(WorldIdentity world, String dimension, ContainerPosition position, Map<String, Long> counts, Instant at) {}
    /** halves are both chest positions when the opened container was a double chest, else just position. */
    private record Open(WorldIdentity world, String dimension, ContainerPosition position, List<ContainerPosition> halves, int slots) {}
    private static final int PLAYER_SLOTS = 36;
    private static final Map<ContainerPosition, Observation> observed = new LinkedHashMap<>();
    private static final Map<ContainerPosition, List<ContainerPosition>> halves = new HashMap<>();
    private static Map<String, Long> inventory = Map.of();
    private static Object menu;
    private static Open open;
    private StockObservations() {}

    /** Called from the client tick. Reading a menu never sends anything or changes it. */
    public static void tick(Minecraft client) {
        if (client.player == null || client.level == null) { menu = null; open = null; inventory = Map.of(); return; }
        inventory = count(client.player.getInventory(), client.player.getInventory().getContainerSize());
        AbstractContainerMenu current = client.player.containerMenu;
        if (current == null || current == client.player.inventoryMenu) { menu = current; open = null; return; }
        if (current != menu) { menu = current; open = attribute(current); }
        if (open != null) record(open, current);
    }
    public static Map<String, Long> inventory() { return inventory; }
    /** The OBSERVED reading of one registered position of profile, or null. */
    public static Observation of(LocationProfile profile, ContainerPosition position) {
        Observation value = observed.get(position);
        return value != null && value.world().equals(profile.world()) && value.dimension().equals(profile.dimension()) ? value : null;
    }
    /**
     * True when position is the other half of a double chest observed under another registered position: its items are
     * already in that observation, so a registration of this half must not add them again (or an older single-chest reading).
     */
    public static boolean covered(LocationProfile profile, ContainerPosition position) {
        for (var entry : observed.entrySet()) {
            Observation value = entry.getValue();
            if (!value.world().equals(profile.world()) || !value.dimension().equals(profile.dimension()) || same(entry.getKey(), position)) continue;
            List<ContainerPosition> halves = halves().get(entry.getKey());
            if (halves != null && halves.stream().anyMatch(half -> same(half, position))) return true;
        }
        return false;
    }
    public static int size() { return observed.size(); }
    public static void discard() { observed.clear(); halves.clear(); inventory = Map.of(); menu = null; open = null; }
    private static Map<ContainerPosition, List<ContainerPosition>> halves() { return halves; }
    private static boolean same(ContainerPosition a, ContainerPosition b) { return a.x() == b.x() && a.y() == b.y() && a.z() == b.z(); }

    private static Open attribute(AbstractContainerMenu current) {
        LocationProfile profile = StockClient.known();
        if (profile == null || !(LocationWorld.resolve() instanceof LocationWorld.Available here)
            || !here.world().equals(profile.world()) || !here.dimension().equals(profile.dimension())) return null;
        if (!(LookedAtContainer.capture() instanceof LookedAtContainer.Target target)) return null;
        ContainerPosition position = target.position();
        ContainerPosition registered = profile.container(position.x(), position.y(), position.z()).orElse(null);
        if (registered == null || !registered.blockId().equals(position.blockId())) return null;
        int slots = target.halves().size() == 2 ? 54 : slots(position.blockId());
        if (current.slots.size() - PLAYER_SLOTS != slots) return null;
        return new Open(here.world(), here.dimension(), registered, target.halves(), slots);
    }
    private static void record(Open target, AbstractContainerMenu current) {
        Map<String, Long> counts = new HashMap<>();
        for (int i = 0; i < target.slots(); i++) add(counts, current.slots.get(i).getItem());
        if (!LocationReport.validCounts(counts)) return;
        // Readings of either half (e.g. from before two single chests were joined) are superseded by this one.
        observed.keySet().removeIf(key -> target.halves().stream().anyMatch(half -> same(half, key)));
        halves.keySet().removeIf(key -> !observed.containsKey(key));
        while (observed.size() >= StockLimits.MAX_POSITIONS) { var eldest = observed.keySet().iterator().next(); observed.remove(eldest); halves.remove(eldest); }
        observed.put(target.position(), new Observation(target.world(), target.dimension(), target.position(), Map.copyOf(counts), Instant.now()));
        halves.put(target.position(), List.copyOf(target.halves()));
    }
    private static Map<String, Long> count(Container container, int size) {
        Map<String, Long> counts = new HashMap<>();
        for (int i = 0; i < size; i++) add(counts, container.getItem(i));
        return Map.copyOf(counts);
    }
    private static void add(Map<String, Long> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        // Same identity rule as the server: registry id only; shulker box items count as themselves (depth 0).
        counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), (long) stack.getCount(), Long::sum);
    }
    private static int slots(String blockId) {
        return switch (blockId) {
            case "minecraft:hopper" -> 5;
            case "minecraft:dropper", "minecraft:dispenser" -> 9;
            default -> 27;
        };
    }
}
