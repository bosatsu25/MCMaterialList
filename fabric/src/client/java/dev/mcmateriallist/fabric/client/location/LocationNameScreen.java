package dev.mcmateriallist.fabric.client.location;

import dev.mcmateriallist.core.location.LocationLimits;
import dev.mcmateriallist.core.location.LocationOutcome;
import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.core.location.LocationUpdate;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.concurrent.CompletableFuture;

/** Create/rename input. The name is a stored value only; files are always named by profile UUID. */
public final class LocationNameScreen extends LocationScreen {
    private final LocationProfile original;
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
        CompletableFuture<LocationUpdate> update;
        if (original == null) {
            // The new profile becomes the world's current one; a failed selection leaves it listed but not current.
            update = service.create(context.world(), context.dimension(), name).thenCompose(created -> created.outcome() != LocationOutcome.CHANGED
                ? CompletableFuture.completedFuture(created) : service.select(context.world(), created.storage().value().id()).thenApply(selection -> created));
        } else if (!context.world().equals(original.world())) { report("reject.other_world"); return; }
        else update = service.rename(context.world(), original.id(), original.generation(), name);
        await(context, update, result -> {
            if (result.outcome() == LocationOutcome.CHANGED) { session.reload(); changed.run(); back(); return; }
            report(result);
        });
    }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        drawString(ctx, clamp(LocationSession.text("name_help")), 12, 30, 0xFFAAAAAA);
    }
}
