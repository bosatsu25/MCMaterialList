package dev.mcmateriallist.fabric.test.mixin;

import java.util.List;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = WidgetContainer.class, remap = false)
public interface WidgetContainerTestAccess {
    @Accessor("subWidgets") List<WidgetBase> phase0Children();
}
