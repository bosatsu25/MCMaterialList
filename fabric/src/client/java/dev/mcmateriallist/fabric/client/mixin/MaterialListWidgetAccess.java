package dev.mcmateriallist.fabric.client.mixin;

import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = WidgetListMaterialList.class, remap = false)
public interface MaterialListWidgetAccess {
    @Accessor("gui") GuiMaterialList mcmateriallist$screen();
}
