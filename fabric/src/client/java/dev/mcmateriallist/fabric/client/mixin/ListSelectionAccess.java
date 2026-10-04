package dev.mcmateriallist.fabric.client.mixin;

import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Native TYPE erases to Object; only snapshot values from the same list owner are restored. */
@Mixin(value = WidgetListBase.class, remap = false)
public interface ListSelectionAccess {
    @Accessor("lastSelectedEntry") void mcmateriallist$selection(Object entry);
    @Accessor("lastSelectedEntryIndex") int mcmateriallist$selectionIndex();
    @Accessor("lastSelectedEntryIndex") void mcmateriallist$selectionIndex(int index);
}
