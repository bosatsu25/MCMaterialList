package dev.mcmateriallist.fabric.test.mixin;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = GuiListBase.class, remap = false)
public interface GuiListTestAccess {
    @Invoker("getListWidget") WidgetListBase<?, ?> phase0List();
}
