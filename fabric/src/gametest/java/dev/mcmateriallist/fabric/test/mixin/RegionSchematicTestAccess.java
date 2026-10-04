package dev.mcmateriallist.fabric.test.mixin;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test-only original definition publication; never writes the fixture file. */
@Mixin(value = LitematicaSchematic.class, remap = false)
public interface RegionSchematicTestAccess { @Accessor("subRegionPositions") Map<String, BlockPos> phase2Origins(); }
