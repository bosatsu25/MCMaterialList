package dev.mcmateriallist.fabric.test.mixin;

import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.scheduler.tasks.TaskProcessChunkBase;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TaskProcessChunkBase.class, remap = false)
public interface CountTaskTestAccess {
    @Accessor("schematicWorld") WorldSchematic phase1World();
    @Accessor("boxesInChunks") ArrayListMultimap<ChunkPos, IntBoundingBox> phase1Boxes();
}
