package dev.mcmateriallist.fabric.client.location;

import dev.mcmateriallist.core.location.LocationLimits;
import dev.mcmateriallist.core.location.LocationOutcome;
import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;
import fi.dy.masa.malilib.render.GuiContext;

/** Create/rename input. The name is a stored value only; files are always named by profile UUID. */
public final class LocationNameScreen extends LocationScreen {
    private LocationProfile original;
    private final Runnable changed;
    private GuiTextFieldMultiLine input;
    private String draft;
    /** original null creates a profile in the current dimension and makes it current. */
    public LocationNameScreen(GuiBase parent, LocationSession session, LocationProfile original, Runnable changed) {
        super(session, original == null ? "create_title" : "rename_title"); setParent(parent);
        this.original = original; this.changed = changed; draft = original == null ? "" : original.displayName();
    }
    @Override public void initGui() {
        if (input != null) draft = input.getValueWrapper();
        super.initGui();
        input = new GuiTextFieldMultiLine.Builder().setX(12).setY(64).setWidth(Math.min(440, getScreenWidth() - 24)).setHeight(30).setBackground(true).build(font, "");
        input.setValueWrapper(draft);
        addTextFieldMultiLine(input, 256, field -> { draft = field.getValueWrapper(); return true; });
        add(12, 104, Math.min(150, getScreenWidth() - 24), original == null ? "create_confirm" : "rename_confirm",
            () -> LocationWorld.resolve() instanceof LocationWorld.Available, this::submit);
        add(12, getScreenHeight() - 30, 90, "back", null, this::back);
    }
    private void submit() {
        String name = LocationLimits.normalizeName(input.getValueWrapper());
        if (!LocationLimits.displayName(name)) { report("invalid_name"); return; }
        if (!(LocationWorld.resolve() instanceof LocationWorld.Available context)) { report("reject.unavailable"); return; }
        var service = MCMaterialListClient.locations();
        if (original == null) {
            await(context, service.create(context.world(), context.dimension(), name), created -> {
                if (created.outcome() != LocationOutcome.CHANGED) { report(created); return; }
                session.reload(); changed.run();
                // The new profile becomes current only if the player is still here; a choice made meanwhile is kept.
                if (mc.gui.screen() != this) return;
                await(context, service.select(context.world(), created.storage().value().id()), selected -> {
                    session.reload(); changed.run();
                    if (selected.outcome() != LocationOutcome.CHANGED && selected.outcome() != LocationOutcome.UNCHANGED) {
                        // The profile exists but is not current (e.g. an unreadable current-file choice); say so instead of leaving.
                        if (selected.outcome() != null) report("outcome." + selected.outcome().name());
                        else report("storage", selected.selection().status().name());
                        return;
                    }
                    if (mc.gui.screen() == this) back();
                });
            });
            return;
        }
        if (!context.world().equals(original.world())) { report("reject.other_world"); return; }
        await(context, service.rename(context.world(), original.id(), original.generation(), name), result -> {
            if (result.outcome() != LocationOutcome.CHANGED) {
                report(result);
                // Refresh the expected generation (a stale one would conflict forever); the typed draft is kept.
                var target = original;
                await(context, service.load(context.world(), target.id()), latest -> {
                    if (latest.ok() && latest.value().id().equals(target.id())) original = latest.value();
                    changed.run();
                });
                return;
            }
            session.reload(); changed.run();
            if (mc.gui.screen() == this) back();
        });
    }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        drawString(ctx, clamp(LocationSession.text("name_help")), 12, 30, 0xFFAAAAAA);
    }
}
