package dev.mcmateriallist.fabric.test.mixin;

import java.util.List;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = WidgetListBase.class, remap = false)
public interface ListTestAccess {
    @Accessor("lastSelectedEntryIndex") int phase2SelectionIndex();
    @Accessor("posX") int phase2X();
    @Accessor("totalWidth") int phase2Width();
    @Accessor("posY") int phase0Y();
    @Accessor("totalHeight") int phase0Height();
    @Accessor("listWidgets") List<WidgetListEntryBase<?>> phase0Rows();
}
