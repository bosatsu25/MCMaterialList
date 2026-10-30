package dev.mcmateriallist.fabric.client.location;

import dev.mcmateriallist.core.location.*;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;
import dev.mcmateriallist.fabric.client.shared.SharedWorkBackend;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;

/** Registered positions of one profile. Registration validates the looked-at block at click time; binding is metadata only. */
public final class LocationProfileEditScreen extends LocationScreen {
    private static final int TOP = 132, ROW = 22;
    private final SharedWorkBackend shared;
    private final LocationProfileId id;
    private final Runnable changed;
    private LocationWorld.Available context;
    private LocationProfile profile;
    private int page;
    /** shared is the invoking list's backend; only its own shared project may be bound. It may be null. */
    public LocationProfileEditScreen(GuiBase parent, LocationSession session, SharedWorkBackend shared, LocationProfileId id, Runnable changed) {
        super(session, "edit_title"); setParent(parent); this.shared = shared; this.id = id; this.changed = changed;
    }
    public LocationProfile profile() { return profile; }

    @Override public void initGui() {
        if (context == null) {
            if (LocationWorld.resolve() instanceof LocationWorld.Available now) { context = now; load(); }
            else report("reject.unavailable");
        }
        super.initGui();
        int width = Math.min(150, (getScreenWidth() - 28) / 2);
        add(12, 64, width, "add", () -> profile != null, this::register);
        add(16 + width, 64, width, "rename", () -> profile != null, () -> GuiBase.openGui(new LocationNameScreen(this, session, profile, this::reload)));
        if (profile != null && profile.binding() != null) add(12, 88, 2 * width + 4, "unbind", () -> profile != null, this::unbind);
        else add(12, 88, 2 * width + 4, "bind", this::bindable, this::bind);
        List<ContainerPosition> positions = profile == null ? List.of() : profile.positions();
        int rows = Math.max(1, (getScreenHeight() - TOP - 36) / ROW), removeWidth = 70;
        int pages = Math.max(1, (positions.size() + rows - 1) / rows); page = Math.min(page, pages - 1);
        for (int i = page * rows; i < Math.min(positions.size(), (page + 1) * rows); i++) {
            var position = positions.get(i);
            add(getScreenWidth() - 12 - removeWidth, TOP + (i - page * rows) * ROW, removeWidth, LocationSession.text("remove"),
                LocationSession.text("remove_hover", describe(position)), () -> profile != null, () -> remove(position));
        }
        add(12, getScreenHeight() - 30, 90, "back", null, this::back);
        if (pages > 1) add(110, getScreenHeight() - 30, 90, "next", null, () -> { page = (page + 1) % pages; initGui(); });
    }
    private static String describe(ContainerPosition position) {
        return LocationSession.text("position", position.x(), position.y(), position.z(), position.blockId());
    }
    /** The rename child reports a durable change: read the profile again. */
    private void reload() { changed.run(); if (context != null) load(); }
    private void load() {
        var issued = context;
        await(issued, MCMaterialListClient.locations().load(issued.world(), id), result -> {
            profile = result.ok() ? result.value() : null;
            if (!result.ok()) report("storage", result.status().name());
        });
    }
    /** The client must be in this profile's world now; the listener may differ after a reconnect to the same world. */
    private LocationWorld.Available here() {
        if (!(LocationWorld.resolve() instanceof LocationWorld.Available now)) { report("reject.unavailable"); return null; }
        if (!now.world().equals(profile.world())) { report("reject.other_world"); return null; }
        context = now; return now;
    }
    private void register() {
        var now = here(); if (now == null) return;
        if (!now.dimension().equals(profile.dimension())) { report("reject.other_dimension", profile.dimension()); return; }
        var capture = LookedAtContainer.capture();
        if (capture instanceof LookedAtContainer.Rejected rejected) { report("reject." + rejected.key(), rejected.detail()); return; }
        var target = (LookedAtContainer.Target) capture;
        if (ContainerBlocks.registered(profile, target.halves())) { report("reject.duplicate"); return; }
        await(now, MCMaterialListClient.locations().addContainer(now.world(), id, profile.generation(), now.dimension(), target.position()), this::updated);
    }
    private void remove(ContainerPosition position) {
        var now = here(); if (now == null) return;
        // Removal is metadata only, so it is allowed from any dimension of the profile's world.
        await(now, MCMaterialListClient.locations().removeContainer(now.world(), id, profile.generation(), profile.dimension(),
            position.x(), position.y(), position.z()), this::updated);
    }
    private ProjectScope scope() { return shared == null || !shared.selected() ? null : shared.reference().scope(); }
    private boolean bindable() {
        var scope = scope();
        return profile != null && scope != null && scope.worldNamespace().equals(profile.world().namespace()) && !scope.equals(profile.binding());
    }
    private void bind() {
        var scope = scope(); var now = here(); if (now == null || scope == null) return;
        await(now, MCMaterialListClient.locations().bind(now.world(), id, profile.generation(), scope), this::updated);
    }
    private void unbind() {
        var now = here(); if (now == null) return;
        await(now, MCMaterialListClient.locations().unbind(now.world(), id, profile.generation()), this::updated);
    }
    private void updated(LocationUpdate update) {
        report(update);
        if (update.storage().ok()) profile = update.storage().value();
        else {
            if (update.storage().status() == StoreStatus.CONFLICT) report("reloaded");
            load();
        }
        if (update.outcome() == LocationOutcome.CHANGED) changed.run();
    }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        if (profile == null) return;
        drawString(ctx, clamp(LocationSession.text("summary", profile.displayName(), profile.dimension(), profile.positions().size(), LocationLimits.MAX_CONTAINERS)), 12, 30, 0xFFFFFFFF);
        var scope = scope();
        String binding = profile.binding() == null ? "binding.none" : profile.binding().equals(scope) ? "binding.this" : "binding.other";
        drawString(ctx, clamp(LocationSession.text(binding) + " · " + LocationSession.text("bind_help")), 12, 114, 0xFFAAAAAA);
        int rows = Math.max(1, (getScreenHeight() - TOP - 36) / ROW), y = TOP;
        var positions = profile.positions();
        for (int i = page * rows; i < Math.min(positions.size(), (page + 1) * rows); i++, y += ROW)
            drawString(ctx, MaterialPresentation.clamp(describe(positions.get(i)), getScreenWidth() - 106), 12, y + 6, 0xFFFFFFFF);
        if (positions.isEmpty()) drawString(ctx, clamp(LocationSession.text("allowlist")), 12, TOP + 6, 0xFFAAAAAA);
    }
}
