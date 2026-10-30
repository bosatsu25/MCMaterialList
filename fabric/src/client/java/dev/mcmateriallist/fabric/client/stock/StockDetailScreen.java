package dev.mcmateriallist.fabric.client.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationProfile;
import dev.mcmateriallist.core.stock.LocationReport;
import dev.mcmateriallist.core.stock.LocationStatus;
import dev.mcmateriallist.core.stock.StockGrant;
import dev.mcmateriallist.core.stock.StockReading;
import dev.mcmateriallist.core.stock.StockSnapshot;
import dev.mcmateriallist.core.stock.protocol.StockMessage;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Paged locations of the last check with status and time, after the LocationProfileEditScreen row pattern. Grant/Revoke rows appear
 * only after the server confirmed, by answering a grant listing with OK, that this player may grant for the bound project (a server
 * admin who is its Administrator). Granting changes who may read; it never changes work.
 */
public final class StockDetailScreen extends GuiBase {
    private static final int TOP = 88, ROW = 22;
    private final StockSession session;
    private List<StockGrant> grants;
    private StockMessage.GrantStatus grantStatus;
    private UUID pending;
    private int page;
    private boolean listed;

    public StockDetailScreen(GuiBase parent, StockSession session) {
        this.session = session; setParent(parent); title = StockSession.text("detail_title");
    }
    public StockMessage.GrantStatus grantStatus() { return grantStatus; }
    public List<StockGrant> grants() { return grants; }
    public boolean pending() { return pending != null; }

    @Override public void initGui() {
        super.initGui();
        LocationProfile profile = session.profile();
        if (!listed && profile != null && profile.binding() != null) { listed = true; grantRequest(profile, List.of(), false); }
        int width = Math.min(150, (getScreenWidth() - 28) / 2);
        add(12, 54, width, StockSession.text(StockClient.scanning() ? "cancel" : "check_again"), session::enabled, () -> { session.click(); initGui(); });
        if (admin(profile)) add(16 + width, 54, width, StockSession.text("grant_all"), () -> pending == null,
            () -> grantRequest(profile, profile.positions(), false));
        StockSnapshot snapshot = session.snapshot().orElse(null);
        List<ContainerPosition> positions = snapshot == null ? List.of() : snapshot.positions();
        int rows = rows(), pages = Math.max(1, (positions.size() + rows - 1) / rows), buttonWidth = 70;
        page = Math.min(page, pages - 1);
        if (admin(profile) && snapshot != null) for (int i = page * rows; i < Math.min(positions.size(), (page + 1) * rows); i++) {
            ContainerPosition position = positions.get(i);
            boolean granted = granted(position);
            add(getScreenWidth() - 12 - buttonWidth, TOP + (i - page * rows) * ROW, buttonWidth, StockSession.text(granted ? "revoke" : "grant"),
                StockSession.text(granted ? "revoke_hover" : "grant_hover", describe(position)), () -> pending == null, () -> grantRequest(profile, List.of(position), granted));
        }
        add(12, getScreenHeight() - 30, 90, StockSession.text("back"), null, () -> GuiBase.openGui(getParent()));
        if (pages > 1) add(110, getScreenHeight() - 30, 90, StockSession.text("next"), null, () -> { page = (page + 1) % pages; initGui(); });
    }
    private boolean admin(LocationProfile profile) {
        return profile != null && profile.binding() != null && grantStatus == StockMessage.GrantStatus.OK && grants != null;
    }
    private boolean granted(ContainerPosition position) { return grants != null && grants.stream().anyMatch(grant -> grant.at(grant.dimension(), position)); }
    private ButtonGeneric add(int x, int y, int width, String label, java.util.function.BooleanSupplier gate, Runnable action) {
        return add(x, y, width, label, label, gate, action);
    }
    private ButtonGeneric add(int x, int y, int width, String label, String hover, java.util.function.BooleanSupplier gate, Runnable action) {
        var button = addButton(new ButtonGeneric(x, y, width, 20, MaterialPresentation.clamp(label, width - 8)), (source, mouse) -> action.run());
        button.setHoverStrings(hover);
        if (gate != null) button.setEnabled(gate.getAsBoolean());
        return button;
    }
    /** Positions are the registered (canonical) positions of the bound profile; an empty list only lists the grants. */
    private void grantRequest(LocationProfile profile, List<ContainerPosition> positions, boolean revoke) {
        if (pending != null) return;
        var scope = profile.binding(); String dimension = profile.dimension();
        Function<UUID, StockMessage> request = revoke ? id -> new StockMessage.RevokeRequest(id, scope, dimension, positions)
            : id -> new StockMessage.GrantRequest(id, scope, dimension, positions);
        pending = StockClientNetworking.send(request, this::replied);
    }
    private void replied(StockMessage message) {
        pending = null;
        if (message instanceof StockMessage.GrantReply reply) {
            grantStatus = reply.status();
            if (reply.status() == StockMessage.GrantStatus.OK) grants = reply.grants();
        }
        if (mc.gui.screen() == this) initGui();
    }
    private int rows() { return Math.max(1, (getScreenHeight() - TOP - 36) / ROW); }
    private static String describe(ContainerPosition position) { return StockSession.text("position", position.x(), position.y(), position.z(), position.blockId()); }

    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        int width = getScreenWidth() - 24;
        var line = session.footer();
        drawString(ctx, MaterialPresentation.clamp(line == null ? StockSession.text("none") : line.text(), width), 12, 30, line == null ? StockSession.TEXT : line.color());
        String grant = grantStatus == null ? (pending != null ? StockSession.text("grant_loading") : "")
            : grantStatus == StockMessage.GrantStatus.OK ? StockSession.text("grant_help") : StockSession.text("grant_status." + grantStatus.name());
        drawString(ctx, MaterialPresentation.clamp(grant, width), 12, 42, 0xFFAAAAAA);
        StockSnapshot snapshot = session.snapshot().orElse(null);
        if (snapshot == null) return;
        int rows = rows(), y = TOP;
        int textWidth = getScreenWidth() - (grantStatus == StockMessage.GrantStatus.OK ? 106 : 24);
        for (int i = page * rows; i < Math.min(snapshot.positions().size(), (page + 1) * rows); i++, y += ROW) {
            String status; int color = StockSession.TEXT;
            LocationReport report = snapshot.report(i).orElse(null);
            StockReading previous = snapshot.previous().get(i);
            if (report != null && report.status() == LocationStatus.OBSERVED && previous != null)
                { status = StockSession.text("row.observed", StockSession.time(previous.at())); color = StockSession.PREVIOUS; }
            else if (report != null && report.status() == LocationStatus.CHECKED) status = StockSession.text("status.CHECKED") + " " + StockSession.time(snapshot.startedAt());
            else {
                status = report == null ? StockSession.text("status.unchecked") : StockSession.text("status." + report.status().name());
                if (report != null && report.status() != LocationStatus.DUPLICATE) color = 0xFFFFCC55;
                if (previous != null) { status += " · " + StockSession.text("row.previous", StockSession.time(previous.at()), StockSession.minutesAgo(previous.at())); color = StockSession.PREVIOUS; }
            }
            if (report != null && report.kind() == dev.mcmateriallist.core.location.ContainerKind.MACHINE) status += " · " + StockSession.text("row.machine");
            drawString(ctx, MaterialPresentation.clamp(describe(snapshot.positions().get(i)) + " · " + status, textWidth), 12, y + 6, color);
        }
        if (snapshot.positions().isEmpty()) drawString(ctx, MaterialPresentation.clamp(StockSession.text("empty"), width), 12, TOP + 6, 0xFFAAAAAA);
    }
}
