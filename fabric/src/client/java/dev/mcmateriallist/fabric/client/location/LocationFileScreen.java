package dev.mcmateriallist.fabric.client.location;

import dev.mcmateriallist.core.location.*;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.shared.SharedWorkBackend;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;

/** Per-world profile chooser. Rows are identified by profile UUID; display names are values only. */
public final class LocationFileScreen extends LocationScreen {
    private final SharedWorkBackend shared;
    private LocationWorld.Resolution world;
    private List<ProfileEntry> entries;
    private CurrentLocation current;
    private LocationProfileId confirmRecovery;
    private boolean confirmSelection, dirty;
    private int page;
    private record Loaded(LocationResult<List<ProfileEntry>> list, CurrentLocation current) {}
    /** shared is the invoking list's backend, offered for binding only; it may be null. */
    public LocationFileScreen(GuiBase parent, LocationSession session, SharedWorkBackend shared) {
        super(session, "title"); setParent(parent); this.shared = shared;
    }
    /** Null until the listing of the current world has been read. */
    public List<ProfileEntry> entries() { return entries; }
    public LocationProfile selected() {
        return current != null && current.profile() != null && current.profile().ok() ? current.profile().value() : null;
    }
    /** A child changed durable state: read the listing again from disk. */
    void invalidate() { dirty = true; }

    @Override public void initGui() {
        var now = LocationWorld.resolve();
        if (world == null || !LocationWorld.same(now, world)) {
            world = now; entries = null; current = null; confirmRecovery = null; confirmSelection = false; page = 0; dirty = false;
            if (now instanceof LocationWorld.Available available) load(available);
        } else if (dirty && now instanceof LocationWorld.Available available) { dirty = false; load(available); }
        super.initGui();
        int width = Math.min(150, (getScreenWidth() - 28) / 2);
        add(12, 64, width, "new", this::ready, () -> GuiBase.openGui(new LocationNameScreen(this, session, null, this::invalidate)));
        add(16 + width, 64, width, "edit", () -> ready() && selected() != null,
            () -> GuiBase.openGui(new LocationProfileEditScreen(this, session, shared, selected().id(), this::invalidate)));
        if (current != null && current.selection().status() == StoreStatus.RECOVERY_REQUIRED)
            add(12, 88, 2 * width + 4, confirmSelection ? "confirm_recovery" : "recover_selection", this::ready, this::recoverSelection);
        int top = 116, rows = Math.max(1, (getScreenHeight() - top - 36) / 24), recoverWidth = 100;
        List<ProfileEntry> list = entries == null ? List.of() : entries;
        int pages = Math.max(1, (list.size() + rows - 1) / rows); page = Math.min(page, pages - 1);
        for (int i = page * rows; i < Math.min(list.size(), (page + 1) * rows); i++) {
            var entry = list.get(i); int y = top + (i - page * rows) * 24;
            boolean recoverable = entry.status() == StoreStatus.RECOVERY_REQUIRED;
            int rowWidth = getScreenWidth() - 24 - (recoverable ? recoverWidth + 4 : 0);
            String label = row(entry);
            add(12, y, rowWidth, label, label, () -> ready() && entry.status() == StoreStatus.OK, () -> select(entry.id()));
            if (recoverable) add(16 + rowWidth, y, recoverWidth, entry.id().equals(confirmRecovery) ? "confirm_recovery" : "recover",
                this::ready, () -> recover(entry.id()));
        }
        add(12, getScreenHeight() - 30, 90, "back", null, this::back);
        if (pages > 1) add(110, getScreenHeight() - 30, 90, "next", null, () -> { page = (page + 1) % pages; initGui(); });
    }
    private boolean ready() { return world instanceof LocationWorld.Available && entries != null && !pending(); }
    private String row(ProfileEntry entry) {
        var profile = entry.profile();
        if (entry.status() == StoreStatus.RECOVERY_REQUIRED) return LocationSession.text("row_recovery", profile.displayName());
        if (entry.status() != StoreStatus.OK) return LocationSession.text("row_unreadable", entry.status().name());
        var selected = selected();
        return LocationSession.text(selected != null && selected.id().equals(entry.id()) ? "row_current" : "row",
            profile.displayName(), profile.dimension(), profile.positions().size());
    }
    private LocationWorld.Available context() {
        // Every action resolves the world again and must still be the one the listing was read from.
        if (LocationWorld.resolve() instanceof LocationWorld.Available now && LocationWorld.same(now, world)) return now;
        report("world_changed"); initGui(); return null;
    }
    private void load(LocationWorld.Available context) {
        var service = MCMaterialListClient.locations();
        await(context, service.list(context.world()).thenCombine(service.current(context.world()), Loaded::new), loaded -> {
            entries = loaded.list().ok() ? loaded.list().value() : List.of(); current = loaded.current();
            if (!loaded.list().ok()) report("storage", loaded.list().status().name());
        });
    }
    private void select(LocationProfileId id) {
        var context = context(); if (context == null) return;
        await(context, MCMaterialListClient.locations().select(context.world(), id), update -> {
            if (update.outcome() == LocationOutcome.CHANGED || update.outcome() == LocationOutcome.UNCHANGED) report("selected");
            else if (update.outcome() != null) report("outcome." + update.outcome().name());
            else report("storage", update.selection().status().name());
            session.reload(); load(context);
        });
    }
    private void recover(LocationProfileId id) {
        if (!id.equals(confirmRecovery)) { confirmRecovery = id; addMessage(MessageType.WARNING, LocationSession.text("recovery_warning")); initGui(); return; }
        confirmRecovery = null;
        var context = context(); if (context == null) return;
        await(context, MCMaterialListClient.locations().recover(context.world(), id), result -> {
            if (result.ok()) report("recovered"); else report("storage", result.status().name());
            session.reload(); load(context);
        });
    }
    private void recoverSelection() {
        if (!confirmSelection) { confirmSelection = true; addMessage(MessageType.WARNING, LocationSession.text("recovery_warning")); initGui(); return; }
        confirmSelection = false;
        var context = context(); if (context == null) return;
        await(context, MCMaterialListClient.locations().recoverSelection(context.world()), result -> {
            if (result.ok()) report("recovered"); else report("storage", result.status().name());
            session.reload(); load(context);
        });
    }
    @Override protected boolean stale() { return world != null && !LocationWorld.same(LocationWorld.resolve(), world); }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        var now = LocationWorld.resolve();
        drawString(ctx, clamp(now instanceof LocationWorld.Available available ? LocationSession.text("world", available.dimension())
            : LocationSession.text("reason." + ((LocationWorld.Unavailable) now).reason().name())), 12, 30, now instanceof LocationWorld.Available ? 0xFFFFFFFF : 0xFFFF5555);
        String selection = null;
        if (current != null && !current.selection().ok() && current.selection().status() != StoreStatus.MISSING
            && current.selection().status() != StoreStatus.RECOVERY_REQUIRED) selection = LocationSession.text("selection_unreadable", current.selection().status().name());
        else if (current != null && current.profile() != null && !current.profile().ok()) selection = LocationSession.text("selected_unreadable", current.profile().status().name());
        if (selection != null) drawString(ctx, clamp(selection), 12, 94, 0xFFFF5555);
        if (entries != null && entries.isEmpty() && now instanceof LocationWorld.Available) drawString(ctx, clamp(LocationSession.text("empty")), 12, 122, 0xFFAAAAAA);
    }
}
