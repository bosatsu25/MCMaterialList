package dev.mcmateriallist.core.location;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContainerBlocksTest {
    @Test void onlyTheAllowlistIsRegistrable() {
        for (String id : List.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box", "minecraft:white_shulker_box",
                "minecraft:light_blue_shulker_box", "minecraft:light_gray_shulker_box", "minecraft:black_shulker_box", "minecraft:hopper",
                "minecraft:dropper", "minecraft:dispenser"))
            assertTrue(ContainerBlocks.supported(id), id);
        for (String id : java.util.Arrays.asList("minecraft:ender_chest", "minecraft:stone", "minecraft:chest_minecart", "minecraft:crafter",
                "minecraft:furnace", "minecraft:copper_chest", "minecraft:grey_shulker_box", "modded:chest", "chest", "MINECRAFT:CHEST", "", null))
            assertFalse(ContainerBlocks.supported(id), String.valueOf(id));
    }
    @Test void doubleChestHalvesShareTheSmallerPosition() {
        ContainerPosition west = new ContainerPosition(4, 64, -2, "minecraft:chest"), east = new ContainerPosition(5, 64, -2, "minecraft:chest");
        assertEquals(west, ContainerBlocks.canonical(west, east)); assertEquals(west, ContainerBlocks.canonical(east, west));
        ContainerPosition north = new ContainerPosition(-7, 10, -9, "minecraft:trapped_chest"), south = new ContainerPosition(-7, 10, -8, "minecraft:trapped_chest");
        assertEquals(north, ContainerBlocks.canonical(south, north));
        assertThrows(IllegalArgumentException.class, () -> ContainerBlocks.canonical(west, new ContainerPosition(6, 64, -2, "minecraft:chest")));
        assertThrows(IllegalArgumentException.class, () -> ContainerBlocks.canonical(west, new ContainerPosition(4, 65, -2, "minecraft:chest")));
        assertThrows(IllegalArgumentException.class, () -> ContainerBlocks.canonical(west, new ContainerPosition(5, 64, -2, "minecraft:trapped_chest")));
        assertThrows(IllegalArgumentException.class, () -> ContainerBlocks.canonical(west, west));
    }
    @Test void eitherRegisteredHalfMakesTheContainerADuplicate() {
        var profile = LocationProfile.create(new WorldIdentity(UUID.randomUUID()), "minecraft:overworld", "taichi_ms")
            .withContainer(new ContainerPosition(4, 64, -2, "minecraft:chest"));
        ContainerPosition west = new ContainerPosition(4, 64, -2, "minecraft:chest"), east = new ContainerPosition(5, 64, -2, "minecraft:chest");
        assertTrue(ContainerBlocks.registered(profile, List.of(east, west)));
        assertTrue(ContainerBlocks.registered(profile, List.of(west)));
        assertFalse(ContainerBlocks.registered(profile, List.of(east)));
        assertFalse(ContainerBlocks.registered(profile, List.of(new ContainerPosition(4, 65, -2, "minecraft:barrel"))));
    }
}
