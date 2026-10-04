package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.fabric.client.material.MaterialDetailScreen;
import dev.mcmateriallist.core.work.MaterialTaskId;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MaterialDetailScreen.class, remap = false)
public interface MaterialDetailTestAccess {
    @Accessor("note") GuiTextFieldMultiLine phase2Note();
    @Accessor("id") MaterialTaskId phase2Id();
}
