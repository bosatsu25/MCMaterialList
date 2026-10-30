package dev.mcmateriallist.fabric.client.location;

import dev.mcmateriallist.core.location.LocationUpdate;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shared gating, feedback and durable-completion handling. Controls are disabled while any I/O is pending. */
public abstract class LocationScreen extends GuiBase {
    protected final LocationSession session;
    private final Map<ButtonGeneric, BooleanSupplier> gates = new LinkedHashMap<>();
    private int pending;
    private boolean rebuilding;
    private String feedbackKey;
    private String feedback = "";
    protected LocationScreen(LocationSession session, String titleKey) { this.session = session; title = LocationSession.text(titleKey); }
    public boolean pending() { return pending > 0; }
    /** Lang key (below mcmateriallist.location.) of the last result or rejection shown. */
    public String feedbackKey() { return feedbackKey; }
    public String feedback() { return feedback; }
    protected void report(String key, Object... args) { feedbackKey = key; feedback = LocationSession.text(key, args); }
    /** CHANGED is shown only after the save; a null outcome means storage refused first. */
    protected void report(LocationUpdate update) {
        if (update.outcome() != null) report("outcome." + update.outcome().name());
        else report("storage", update.storage().status().name());
    }
    @Override public void initGui() { super.initGui(); gates.clear(); }
    protected ButtonGeneric add(int x, int y, int width, String key, BooleanSupplier gate, Runnable action) {
        return add(x, y, width, LocationSession.text(key), LocationSession.text(key), gate, action);
    }
    protected ButtonGeneric add(int x, int y, int width, String label, String hover, BooleanSupplier gate, Runnable action) {
        var button = addButton(new ButtonGeneric(x, y, width, 20, MaterialPresentation.clamp(label, width - 8)), (source, mouse) -> action.run());
        button.setHoverStrings(hover);
        if (gate != null) { gates.put(button, gate); button.setEnabled(pending == 0 && gate.getAsBoolean()); }
        return button;
    }
    protected void back() { GuiBase.openGui(getParent()); }
    /** A result applies only while the issuing world/listener is current; the write itself is already durable either way. */
    protected <T> void await(LocationWorld.Available context, CompletableFuture<T> future, Consumer<T> receiver) {
        pending++;
        // schedule() always queues; execute() could run inline for a completed future during initGui().
        future.whenComplete((value, failure) -> mc.schedule(() -> {
            pending--;
            if (!context.current()) report("world_changed");
            else if (failure != null) report("storage", StoreStatus.IO_FAILURE.name());
            else receiver.accept(value);
            if (mc.gui.screen() == this) initGui();
        }));
    }
    /** True when the screen must be rebuilt for a changed world; it must become false after initGui(). */
    protected boolean stale() { return false; }
    protected String clamp(String value) { return MaterialPresentation.clamp(value, getScreenWidth() - 24); }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        gates.forEach((button, gate) -> button.setEnabled(pending == 0 && gate.getAsBoolean()));
        drawString(ctx, clamp(pending > 0 ? LocationSession.text("saving") : feedback), 12, 46, 0xFFFFCC55);
        if (!rebuilding && stale()) {
            rebuilding = true;
            mc.schedule(() -> { rebuilding = false; if (mc.gui.screen() == this) initGui(); });
        }
    }
}
