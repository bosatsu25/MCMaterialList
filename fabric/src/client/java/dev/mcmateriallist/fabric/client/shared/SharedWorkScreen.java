package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.shared.ProjectSnapshot;
import dev.mcmateriallist.core.work.DatasetKind;
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
    private ButtonGeneric membership, definition, discover, regions;
    private boolean rebuilding, confirmRecovery;
    private DefinitionConfirmation confirmDefinition;
    private record DefinitionConfirmation(Object context, ProjectSnapshot snapshot, long cursor) {}
    /** Issue #11: subject is the shared snapshot (Unshare) or the detached copy (Rejoin); any change cancels. */
    private RegionConfirmation confirmRegions;
    private record RegionConfirmation(boolean rejoin, Object context, Object subject, long cursor) {}
    public SharedWorkScreen(GuiBase parent, SharedWorkBackend backend) {
        setParent(parent); this.backend = backend; title = SharedWorkBackend.text("title");
    }
    @Override public void initGui() {
        boolean confirmed = definitionConfirmed(), regionsConfirmed = regionsConfirmed();
        super.initGui(); requests.clear(); local = membership = definition = regions = null; version = backend.version(); projects = SharedClientNetworking.state().projects();
        int gap = 4, width = Math.min(150, (getScreenWidth() - 28) / 2);
        discover = add(12, 64, width, "discover", backend::discover); requests.add(discover);
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
        // REGIONS only (#11): one explicit two-step row below administration; the project list moves down one row.
        int top = 144;
        if (backend.kind() == DatasetKind.REGIONS) {
            boolean rejoin = backend.detached() != null;
            regions = add(12, 136, width, rejoin ? regionsConfirmed ? "confirm_rejoin" : "rejoin" : regionsConfirmed ? "confirm_unshare" : "unshare", () -> {
                var current = regionsContext();
                if (current != null && current.equals(confirmRegions)) {
                    confirmRegions = null;
                    if (current.rejoin()) backend.rejoin(); else backend.unshare();
                } else confirmRegions = current;
                initGui();
            });
            regionsHover();
            top = 168;
        }
        var available = SharedClientNetworking.state().projects();
        int rows = Math.max(1, (getScreenHeight() - top - 56) / 24);
        int pages = Math.max(1, (available.size() + rows - 1) / rows); page = Math.min(page, pages - 1);
        for (int i = page * rows; i < Math.min(available.size(), (page + 1) * rows); i++) {
            var project = available.get(i); int y = top + (i - page * rows) * 24;
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
    private RegionConfirmation regionsContext() {
        long cursor = SharedClientNetworking.state().cursor();
        var copy = backend.detached();
        if (copy != null) return backend.canRejoin() ? new RegionConfirmation(true, backend.context(), copy, cursor) : null;
        var snapshot = backend.snapshot();
        return snapshot == null || !backend.canUnshare() ? null : new RegionConfirmation(false, backend.context(), snapshot, cursor);
    }
    private boolean regionsConfirmed() {
        if (confirmRegions != null && !confirmRegions.equals(regionsContext())) confirmRegions = null;
        return confirmRegions != null;
    }
    private void regionsHover() {
        if (regions == null) return;
        boolean rejoin = backend.detached() != null;
        String blocked = rejoin ? backend.rejoinFeedback() : backend.unshareFeedback();
        regions.setEnabled(blocked == null);
        regions.setHoverStrings(SharedWorkBackend.text(rejoin ? "rejoin_explanation" : "unshare_explanation"),
            blocked != null ? blocked : SharedWorkBackend.text(confirmRegions != null ? "confirm_hint" : rejoin ? "rejoin" : "unshare"));
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
        if (confirmRegions != null && !confirmRegions.equals(regionsContext())) {
            confirmRegions = null;
            initGui();
        }
        super.drawButtons(ctx, mouseX, mouseY, ticks);
    }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        String selection = backend.snapshot() != null ? backend.snapshot().name()
            : SharedWorkBackend.text(backend.detached() != null ? "detached" : backend.selected() ? "cached_unavailable" : "local_mode");
        drawString(ctx, MaterialPresentation.clamp(SharedWorkBackend.text("selection." + backend.kind().name(), selection), getScreenWidth() - 24), 12, 30, 0xFFFFFFFF);
        drawString(ctx, MaterialPresentation.clamp(backend.feedback(), getScreenWidth() - 24), 12, 46, 0xFFFFCC55);
        boolean supported = SharedClientNetworking.state().capabilities() != null
            && SharedClientNetworking.state().capabilities().protocolVersion() == dev.mcmateriallist.core.shared.protocol.SharedProtocol.VERSION;
        // Create and project joins would replace a detached copy; only Rejoin shared regions may (#11).
        requests.forEach(button -> button.setEnabled(supported && !backend.pending() && (button == discover || backend.detached() == null)));
        regionsHover();
        if (membership != null) membership.setEnabled(backend.administer());
        if (definition != null) definition.setEnabled(backend.canUpdateDefinition());
        if (local != null) local.setEnabled(!backend.pending());
        if (!rebuilding && (version != backend.version() || projects != SharedClientNetworking.state().projects())) {
            rebuilding = true;
            mc.schedule(() -> { rebuilding = false; if (mc.gui.screen() == this) initGui(); });
        }
    }
}
