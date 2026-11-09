package dev.mcmateriallist.fabric.client.work;

import dev.mcmateriallist.core.ui.TextWrap;
import dev.mcmateriallist.core.ui.WorkStatePresentation;
import dev.mcmateriallist.core.work.DatasetKind;
import dev.mcmateriallist.core.work.Progress;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;
import dev.mcmateriallist.fabric.client.material.MaterialWorkSession;
import dev.mcmateriallist.fabric.client.region.RegionWorkSession;
import dev.mcmateriallist.fabric.client.shared.SharedWorkBackend;
import dev.mcmateriallist.fabric.client.shared.SharedWorkScreen;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/**
 * Issue #22: what an ordinary click on the {@code MCMaterialList} footer button opens. It shows the invoking list's work
 * state, why an action is unavailable and what to do next, and leads to the real Track/Refresh, Shared work (with
 * Unshare/Rejoin regions), Location File and Check Materials screens. Opening it only reads state; every write starts
 * from one of those explicitly labelled actions.
 */
public final class WorkHubScreen extends GuiBase {
    private static final int LINE = 10;
    private final MaterialWorkSession materials;
    private final RegionWorkSession regions;
    private final List<Action> actions = new ArrayList<>();
    private List<String> shown = List.of();
    private String built = "";
    private int textBottom;
    private boolean rebuilding;
    private record Action(ButtonGeneric button, String label, BooleanSupplier enabled, Supplier<String> reason) {}
    private record Spec(String label, BooleanSupplier enabled, Supplier<String> reason, Runnable run) {}
    private record Line(String text, int color) {}

    private WorkHubScreen(GuiBase parent, MaterialWorkSession materials, RegionWorkSession regions) {
        setParent(parent); this.materials = materials; this.regions = regions; title = "MCMaterialList";
    }
    public static WorkHubScreen materials(GuiBase parent, MaterialWorkSession session) { return new WorkHubScreen(parent, session, null); }
    public static WorkHubScreen regions(GuiBase parent, RegionWorkSession session) { return new WorkHubScreen(parent, null, session); }
    public static String text(String key, Object... args) { return Component.translatable("mcmateriallist.hub." + key, args).getString(); }

    public DatasetKind kind() { return materials != null ? DatasetKind.MATERIALS : DatasetKind.REGIONS; }
    public SharedWorkBackend shared() { return materials != null ? materials.shared() : regions.shared(); }
    /** The state shown for the invoking list. */
    public WorkStatePresentation.State state() { return materials != null ? materials.state() : regions.state(); }
    /** The status lines drawn in the last frame (possibly fewer than computed when the screen is short). */
    public List<String> lines() { return shown; }

    @Override public void initGui() {
        super.initGui();
        actions.clear();
        if (materials != null) materials.location().ensure();
        List<Spec> specs = specs();
        built = signature(specs);
        int width = Math.min(150, (getScreenWidth() - 28) / 2), rows = (specs.size() + 1) / 2;
        int top = getScreenHeight() - 30 - rows * 24;
        textBottom = top - 4;
        for (int i = 0; i < specs.size(); i++) {
            var spec = specs.get(i);
            var button = addButton(new ButtonGeneric(i % 2 == 0 ? 12 : 16 + width, top + (i / 2) * 24, width, 20,
                MaterialPresentation.clamp(spec.label(), width - 8)), (source, mouse) -> spec.run().run());
            button.setHoverStrings(spec.label());
            actions.add(new Action(button, spec.label(), spec.enabled(), spec.reason()));
        }
        String back = text("back");
        addButton(new ButtonGeneric(12, getScreenHeight() - 30, 90, 20, MaterialPresentation.clamp(back, 82)), (source, mouse) -> GuiBase.openGui(getParent()))
            .setHoverStrings(back);
        refreshActions();
    }

    private List<Spec> specs() {
        List<Spec> specs = new ArrayList<>();
        if (materials != null) {
            specs.add(new Spec(materials.refreshLabel(), materials::refreshEnabled, () -> reason(materials.stateReason(), materials.nextAction()), materials::refresh));
            specs.add(new Spec(text("shared"), () -> true, () -> null, () -> GuiBase.openGui(new SharedWorkScreen(this, materials.shared()))));
            var location = materials.location();
            specs.add(new Spec(location.label(), () -> true, () -> null,
                () -> GuiBase.openGui(new dev.mcmateriallist.fabric.client.location.LocationFileScreen(this, location, materials.shared()))));
            var stock = materials.stock();
            specs.add(new Spec(stock.label(), stock::enabled, () -> dev.mcmateriallist.fabric.client.stock.StockSession.text("reason.no_location"),
                () -> { stock.click(); initGui(); }));
        } else {
            specs.add(new Spec(regions.refreshLabel(), regions::refreshEnabled, () -> reason(regions.stateReason(), regions.nextAction()), regions::refresh));
            specs.add(new Spec(text("shared_regions"), () -> true, () -> null, () -> GuiBase.openGui(new SharedWorkScreen(this, regions.shared()))));
        }
        return specs;
    }
    private static String signature(List<Spec> specs) {
        StringBuilder value = new StringBuilder();
        for (var spec : specs) value.append(spec.label()).append('\n');
        return value.toString();
    }
    private static String reason(String reason, String next) {
        return next == null ? reason : reason + " " + text("next", next);
    }
    private void refreshActions() {
        for (var action : actions) ActionReason.show(action.button(), action.label(), action.enabled().getAsBoolean(), action.reason(), getScreenWidth());
    }

    private List<Line> content() {
        var state = state();
        String reason = materials != null ? materials.stateReason() : regions.stateReason();
        String next = materials != null ? materials.nextAction() : regions.nextAction();
        var backend = shared();
        String selection = backend.snapshot() != null ? backend.snapshot().name()
            : SharedWorkBackend.text(backend.detached() != null ? "detached" : backend.selected() ? "cached_unavailable" : "local_mode");
        Progress progress = materials != null ? materials.progress() : regions.progress();
        String progressText;
        if (materials != null) progressText = progress == null ? MaterialWorkSession.text("untracked")
            : MaterialWorkSession.text("progress", progress.completed(), progress.total(), progress.percentage());
        else progressText = progress == null ? RegionWorkSession.text("untracked")
            : RegionWorkSession.text("progress", progress.completed(), progress.total(), progress.percentage());
        if (progress != null) progressText = dev.mcmateriallist.core.ui.WorkProgressPresentation.style(progressText, progress);
        List<Line> content = new ArrayList<>();
        content.add(new Line(text("state", text("state." + state.name())), color(state)));
        content.add(new Line(reason, 0xFFFFCC55));
        if (next != null) content.add(new Line(text("next", next), 0xFFFFFFFF));
        content.add(new Line(text("server", SharedWorkBackend.serverSharing()), 0xFFAAAAAA));
        content.add(new Line(text("source." + kind().name(), selection), 0xFFFFFFFF));
        content.add(new Line(progressText, 0xFFFFFFFF));
        if (materials != null) {
            var stock = materials.stock().footer();
            if (stock != null) content.add(new Line(stock.text(), stock.color()));
        }
        return content;
    }
    static int color(WorkStatePresentation.State state) {
        return switch (state) {
            case LOCAL, SHARED -> 0xFF55FF55;
            case DETACHED, SYNCING, SHARING_UNAVAILABLE, ACTION_REQUIRED -> 0xFFFFAA00;
            case WAITING -> 0xFFAAAAAA;
            case DISCONNECTED, NO_PERMISSION, DEFINITION_MISMATCH, SAVE_FAILURE -> 0xFFFF5555;
        };
    }

    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        refreshActions();
        if (!rebuilding && !built.equals(signature(specs()))) {
            rebuilding = true;
            mc.schedule(() -> { rebuilding = false; if (mc.gui.screen() == this) initGui(); });
        }
        int width = Math.max(0, getScreenWidth() - 24), max = Math.max(0, (textBottom - 30) / LINE);
        List<String> drawn = new ArrayList<>();
        int y = 30;
        for (var line : content()) {
            for (String part : TextWrap.wrap(line.text(), width, this::getStringWidth)) {
                // Most important first: state, reason and next action; on a very short screen the reason also stays
                // in the hover of the disabled action.
                if (drawn.size() >= max) break;
                drawString(ctx, part, 12, y, line.color());
                drawn.add(part); y += LINE;
            }
        }
        shown = List.copyOf(drawn);
    }
}
