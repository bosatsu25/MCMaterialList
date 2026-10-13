package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.shared.ProjectSnapshot;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;

/** Explicit management for the invoking list only. Native rows remain in their original screens. */
public final class SharedWorkScreen extends GuiBase {
    private final SharedWorkBackend backend;
    private final List<ButtonGeneric> requests = new ArrayList<>();
    private long version = -1;
    private Object projects;
    private int page;
    private ButtonGeneric local;
    private ButtonGeneric membership, definition;
    private boolean rebuilding, confirmRecovery;
    private DefinitionConfirmation confirmDefinition;
    private record DefinitionConfirmation(Object context, ProjectSnapshot snapshot, long cursor) {}
    public SharedWorkScreen(GuiBase parent, SharedWorkBackend backend) {
        setParent(parent); this.backend = backend; title = SharedWorkBackend.text("title");
    }
    @Override public void initGui() {
        boolean confirmed = definitionConfirmed();
        super.initGui(); requests.clear(); local = membership = definition = null; version = backend.version(); projects = SharedClientNetworking.state().projects();
        int gap = 4, width = Math.min(150, (getScreenWidth() - 28) / 2);
        requests.add(add(12, 64, width, "discover", backend::discover));
        requests.add(add(16 + width, 64, width, "create", () -> GuiBase.openGui(new SharedInputScreen(this, backend, SharedInputScreen.Mode.CREATE, null))));
        if (backend.selected()) {
            requests.add(add(12, 88, width, "resync", backend::refresh));
            local = add(16 + width, 88, width, "local", backend::local);
        }
        if (backend.selected() && backend.role() == dev.mcmateriallist.core.shared.ProjectRole.ADMINISTRATOR) {
            membership = add(12, 112, width, "membership", () -> GuiBase.openGui(new SharedMembershipScreen(this, backend)));
            definition = add(16 + width, 112, width, confirmed ? "confirm_definition" : "update_definition", () -> {
                var current = definitionContext();
                if (current != null && current.equals(confirmDefinition)) {
                    confirmDefinition = null;
                    backend.updateDefinition();
                } else confirmDefinition = current;
                initGui();
            });
            definition.setHoverStrings(SharedWorkBackend.text("definition_explanation"));
        }
        if (backend.canRecover()) add(16 + width, 112, width, confirmRecovery ? "confirm_recovery" : "recover", () -> {
            if (confirmRecovery) { backend.recover(); confirmRecovery = false; } else { confirmRecovery = true; initGui(); }
        });
        var available = SharedClientNetworking.state().projects();
        int rows = Math.max(1, (getScreenHeight() - 200) / 24);
        int pages = Math.max(1, (available.size() + rows - 1) / rows); page = Math.min(page, pages - 1);
        for (int i = page * rows; i < Math.min(available.size(), (page + 1) * rows); i++) {
            var project = available.get(i); int y = 144 + (i - page * rows) * 24;
            var button = addButton(new ButtonGeneric(12, y, getScreenWidth() - 24, 20,
                MaterialPresentation.clamp(SharedWorkBackend.text("join", project.name()), getScreenWidth() - 36)),
                (source, mouse) -> backend.join(project.scope()));
            button.setHoverStrings(SharedWorkBackend.text("join", project.name())); requests.add(button);
        }
        add(12, getScreenHeight() - 30, 90, "back", () -> GuiBase.openGui(getParent()));
        if (pages > 1) add(110, getScreenHeight() - 30, 90, "next", () -> { page = (page + 1) % pages; initGui(); });
    }
    private ButtonGeneric add(int x, int y, int width, String key, Runnable action) {
        String label = SharedWorkBackend.text(key);
        var button = addButton(new ButtonGeneric(x, y, width, 20, MaterialPresentation.clamp(label, width - 8)), (source, mouse) -> action.run());
        button.setHoverStrings(label); return button;
    }
    private DefinitionConfirmation definitionContext() {
        var snapshot = backend.snapshot();
        return snapshot == null || !backend.canUpdateDefinition() ? null
            : new DefinitionConfirmation(backend.context(), snapshot, SharedClientNetworking.state().cursor());
    }
    private boolean definitionConfirmed() {
        if (confirmDefinition != null && !confirmDefinition.equals(definitionContext())) confirmDefinition = null;
        return confirmDefinition != null;
    }
    @Override protected void drawButtons(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        if (confirmDefinition != null && !confirmDefinition.equals(definitionContext())) {
            confirmDefinition = null;
            initGui();
        }
        super.drawButtons(ctx, mouseX, mouseY, ticks);
    }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        String selection = backend.snapshot() == null ? SharedWorkBackend.text(backend.selected() ? "cached_unavailable" : "local_mode") : backend.snapshot().name();
        drawString(ctx, MaterialPresentation.clamp(SharedWorkBackend.text("selection." + backend.kind().name(), selection), getScreenWidth() - 24), 12, 30, 0xFFFFFFFF);
        drawString(ctx, MaterialPresentation.clamp(backend.feedback(), getScreenWidth() - 24), 12, 46, 0xFFFFCC55);
        boolean supported = SharedClientNetworking.state().capabilities() != null
            && SharedClientNetworking.state().capabilities().protocolVersion() == dev.mcmateriallist.core.shared.protocol.SharedProtocol.VERSION;
        requests.forEach(button -> button.setEnabled(supported && !backend.pending()));
        if (membership != null) membership.setEnabled(backend.administer());
        if (definition != null) definition.setEnabled(backend.canUpdateDefinition());
        if (local != null) local.setEnabled(!backend.pending());
        if (!rebuilding && (version != backend.version() || projects != SharedClientNetworking.state().projects())) {
            rebuilding = true;
            mc.schedule(() -> { rebuilding = false; if (mc.gui.screen() == this) initGui(); });
        }
    }
}
