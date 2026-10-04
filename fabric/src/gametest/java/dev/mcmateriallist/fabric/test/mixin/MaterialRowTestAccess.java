package dev.mcmateriallist.fabric.test.mixin;

import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = WidgetMaterialListEntry.class, remap = false)
public interface MaterialRowTestAccess {
    @Invoker("getColumnPosX") int phase2Column(int column);
}
