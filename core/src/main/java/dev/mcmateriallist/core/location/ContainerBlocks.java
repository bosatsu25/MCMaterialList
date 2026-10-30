package dev.mcmateriallist.core.location;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 4A registrable container allowlist, by block registry id. Everything else (ender chests included) is refused.
 * Hoppers, droppers and dispensers may hold items only in transit, so Check Materials reports them as MACHINE.
 */
public final class ContainerBlocks {
    private static final List<String> COLOURS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final Set<String> SUPPORTED, MACHINES = Set.of("minecraft:hopper", "minecraft:dropper", "minecraft:dispenser");
    static {
        Set<String> ids = new HashSet<>(List.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box",
            "minecraft:hopper", "minecraft:dropper", "minecraft:dispenser"));
        for (String colour : COLOURS) ids.add("minecraft:" + colour + "_shulker_box");
        SUPPORTED = Set.copyOf(ids);
    }
    private ContainerBlocks() {}

    public static boolean supported(String blockId) { return blockId != null && SUPPORTED.contains(blockId); }
    /** Derived from the registered id only, so it never reveals the live block. Any id other than the three machines is STORAGE. */
    public static ContainerKind kind(String blockId) {
        return MACHINES.contains(Objects.requireNonNull(blockId, "blockId")) ? ContainerKind.MACHINE : ContainerKind.STORAGE;
    }
    /** A double chest is stored once, at the lexicographically smaller x, y, z of its two halves. */
    public static ContainerPosition canonical(ContainerPosition first, ContainerPosition second) {
        Objects.requireNonNull(first); Objects.requireNonNull(second);
        if (!first.blockId().equals(second.blockId()) || Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z()) != 1 || first.y() != second.y())
            throw new IllegalArgumentException("Not two horizontally adjacent halves of one container");
        return ContainerPosition.ORDER.compare(first, second) <= 0 ? first : second;
    }
    /** True when any half of the container already occupies a registered position of the profile. */
    public static boolean registered(LocationProfile profile, List<ContainerPosition> halves) {
        return halves.stream().anyMatch(half -> profile.container(half.x(), half.y(), half.z()).isPresent());
    }
}
