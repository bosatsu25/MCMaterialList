package dev.mcmateriallist.fabric.test.mixin;

import fi.dy.masa.litematica.util.SchematicUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The real native edit body, without the unrelated camera/raytrace input prerequisite. */
@Mixin(value = SchematicUtils.class, remap = false)
public interface SharedUiSchematicEdit {
    @Invoker("setTargetedSchematicBlockState")
    static boolean edit(BlockPos position, BlockState state) { throw new AssertionError("Native edit invoker unavailable"); }
}
