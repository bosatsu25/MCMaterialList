package dev.mcmateriallist.fabric.client.mixin;

import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Upstream exposes no placement getter on this list in the supported version. */
@Mixin(value = MaterialListPlacement.class, remap = false)
public interface MaterialListPlacementAccess {
    @Accessor("placement") SchematicPlacement mcmateriallist$placement();
}
