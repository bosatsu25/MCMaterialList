package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.fabric.client.region.RegionDetailScreen;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RegionDetailScreen.class, remap = false)
public interface RegionDetailTestAccess {
    @Accessor("note") GuiTextFieldMultiLine phase2Note();
    @Accessor("id") dev.mcmateriallist.core.work.RegionTaskId phase2Id();
}
