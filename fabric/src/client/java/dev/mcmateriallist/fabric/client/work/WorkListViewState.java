package dev.mcmateriallist.fabric.client.work;

import dev.mcmateriallist.fabric.client.mixin.SearchBoxAccess;
import dev.mcmateriallist.fabric.client.mixin.ListSelectionAccess;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import java.util.Set;

/** Preserve native view state when an immutable-position list changes layout. */
public record WorkListViewState<T>(int scroll, Set<T> selected, T activeSelection, int selectionIndex, String filter, boolean open, boolean focused) {
    public WorkListViewState { selected = Set.copyOf(selected); }
    public static <T> WorkListViewState<T> capture(WidgetListBase<T, ?> list) {
        var search = list.getSearchBarWidget();
        var field = search == null ? null : ((SearchBoxAccess) search).mcmateriallist$searchBox();
        var selection = (ListSelectionAccess) list;
        return new WorkListViewState<>(list.getScrollbar().getValue(), Set.copyOf(list.getSelectedEntries()),
            list.getLastSelectedEntry(), selection.mcmateriallist$selectionIndex(),
            field == null ? "" : field.getTextWrapper(), search != null && search.isSearchOpen(), field != null && field.isFocusedWrapper());
    }
    public void restore(WidgetListBase<T, ?> list) {
        var search = list.getSearchBarWidget();
        if (search != null) {
            search.setSearchOpen(open);
            var field = ((SearchBoxAccess) search).mcmateriallist$searchBox();
            field.setTextWrapper(filter);
            field.setFocusedWrapper(focused);
        }
        list.getSelectedEntries().addAll(selected);
        list.initGui();
        // Replaying setLastSelectedEntry would toggle the region listener and multi-selection.
        // Preserve the exact native entry/index, including entries outside the current filter.
        var selection = (ListSelectionAccess) list;
        selection.mcmateriallist$selection(activeSelection);
        selection.mcmateriallist$selectionIndex(selectionIndex);
        list.getScrollbar().setValue(scroll);
    }
}
