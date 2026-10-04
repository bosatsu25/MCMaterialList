package dev.mcmateriallist.fabric.client.region;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.*;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.client.work.WorkScreenGeometry;
import dev.mcmateriallist.fabric.client.mixin.GuiButtonsAccess;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.GuiContext;
import java.time.Instant;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Region-owned client view. Disk results publish only on queued client callbacks. */
public final class RegionWorkSession {
    private final GuiPlacementConfiguration screen;
    private final LocalPlacementId placement;
    private RegionWorkDataset confirmed;
    private StoreStatus status;
    private boolean pending, loaded, showInfo, hideDone, recoveryConfirmed;
    private ButtonGeneric refreshButton, recoverButton;
    private int toolbarStart, toolbarWidth, toolbarY;
    public RegionWorkSession(GuiPlacementConfiguration screen) {
        this.screen = screen;
        var capture = PlacementWorkAdapter.captureRegions(screen.getSchematicPlacement());
        placement = capture.status() == PlacementWorkAdapter.Status.OK ? capture.snapshot().placementId() : null;
        if (placement == null) status = StoreStatus.INVALID_DEFINITION;
    }
    public static String text(String key, Object... args) { return Component.translatable("mcmateriallist.region." + key, args).getString(); }
    public RegionWorkDataset dataset() { return confirmed; }
    public StoreStatus status() { return status; }
    public boolean pending() { return pending; }
    public boolean showInfo() { return showInfo; }
    public boolean hideDone() { return hideDone; }
    public boolean compact() {
        return WorkScreenGeometry.compact(screen.getScreenWidth(), screen.getScreenHeight())
            && status != StoreStatus.RECOVERY_REQUIRED && (confirmed == null || confirmed.archived().isEmpty());
    }
    public TaskState state(RegionTaskId id) { return confirmed == null || id == null ? null : confirmed.states().get(id); }
    public boolean review(RegionTaskId id) { return confirmed != null && id != null && confirmed.activeReviewRequired().contains(id); }
    /** Resolve by exact original descriptor; ambiguity remains unavailable, never guessed. */
    public RegionTaskId id(String key) {
        if (confirmed == null) return null;
        var capture = PlacementWorkAdapter.captureRegions(screen.getSchematicPlacement());
        if (capture.status() != PlacementWorkAdapter.Status.OK) return null;
        var descriptors = capture.snapshot().regions().stream().filter(region -> region.key().equals(key)).toList();
        if (descriptors.size() != 1) return null;
        var matches = confirmed.definitions().values().stream().filter(definition -> definition.descriptor().equals(descriptors.getFirst())).toList();
        return matches.size() == 1 ? matches.getFirst().id() : null;
    }
    private PlacementWorkAdapter.Capture capture() { return PlacementWorkAdapter.captureRegions(screen.getSchematicPlacement()); }
    public boolean ready() {
        if (placement == null || MCMaterialListClient.work() == null) return false;
        var capture = capture();
        return capture.status() == PlacementWorkAdapter.Status.OK && placement.equals(capture.snapshot().placementId());
    }
    public boolean writable() { return !pending && ready() && status == StoreStatus.OK && confirmed != null; }
    public boolean writable(RegionTaskId id) { return writable() && state(id) != null && !review(id); }
    public String feedback() {
        if (pending) return text("saving");
        if (status != null && status != StoreStatus.OK && status != StoreStatus.MISSING) return text("storage", status.name());
        return !ready() ? text("waiting") : status == StoreStatus.OK ? text("local") : text("untracked");
    }
    public void toolbar() {
        if (!loaded && ready()) { loaded = true; await(MCMaterialListClient.work().load(placement, DatasetKind.REGIONS), this::publish); }
        toolbarStart = compact() ? 18 + screen.getStringWidth(fi.dy.masa.malilib.util.StringUtils.translate("litematica.gui.label.schematic_placement.sub_regions", screen.getSchematicPlacement().getSubRegionCount())) : 12;
        int end = compact() ? ((GuiButtonsAccess) screen).mcmateriallist$buttons().stream().filter(button -> button.getY() == 44 && button.getX() > toolbarStart)
            .mapToInt(button -> button.getX()).min().orElse(screen.getScreenWidth() - 154) : screen.getScreenWidth() - 148;
        toolbarWidth = Math.max(20, (end - toolbarStart) / (compact() ? 3 : 4) - 2);
        toolbarY = compact() ? 44 : 64;
        refreshButton = add(0, text(status == StoreStatus.MISSING ? "track" : "refresh"), (button, mouse) -> refresh());
        add(1, text("hide_done", hideDone ? "ON" : "OFF"), (button, mouse) -> { hideDone = !hideDone; screen.initGui(); });
        add(2, text("show_info", showInfo ? "ON" : "OFF"), (button, mouse) -> { showInfo = !showInfo; screen.initGui(); });
        recoverButton = null;
        if (status == StoreStatus.RECOVERY_REQUIRED) {
            recoverButton = add(3, text(recoveryConfirmed ? "confirm_recovery" : "recover"), (button, mouse) -> {
                if (!recoveryConfirmed) { recoveryConfirmed = true; screen.addMessage(MessageType.WARNING, text("recovery_warning")); screen.initGui(); }
                else if (!pending && ready()) {
                    recoveryConfirmed = false;
                    await(MCMaterialListClient.work().recover(placement, DatasetKind.REGIONS), this::publish);
                }
            });
        } else if (confirmed != null && !confirmed.archived().isEmpty()) {
            add(3, text("archived", confirmed.archived().size()), (button, mouse) -> GuiBase.openGui(new RegionArchiveScreen(screen, this)));
        }
        renderControls();
    }
    private ButtonGeneric add(int slot, String label, IButtonActionListener action) {
        var button = new ButtonGeneric(toolbarStart + slot * (toolbarWidth + 2), toolbarY, toolbarWidth, 20, MaterialPresentation.clamp(label, toolbarWidth - 8));
        button.setHoverStrings(label); return screen.addButton(button, action);
    }
    private void renderControls() {
        if (refreshButton != null) refreshButton.setEnabled(!pending && ready() && (status == StoreStatus.OK || status == StoreStatus.MISSING || status == StoreStatus.CONFLICT));
        if (recoverButton != null) recoverButton.setEnabled(!pending && ready());
    }
    public void footer(GuiContext ctx) {
        renderControls();
        String progress = confirmed == null ? text("untracked") : text("progress", confirmed.progress().completed(), confirmed.progress().total(), confirmed.progress().percentage());
        if (confirmed != null && confirmed.progress().completed() < confirmed.progress().total()) {
            String fraction = confirmed.progress().completed() + " / " + confirmed.progress().total() + " (" + confirmed.progress().percentage() + "%)";
            progress = progress.replace(fraction, "\u00a7c" + fraction + "\u00a7r");
        }
        int x = compact() ? 120 : 12;
        int y = screen.getScreenHeight() - (compact() ? 17 : 60);
        String value = MaterialPresentation.clamp(progress + "  " + feedback(), Math.max(0, screen.getScreenWidth() - x - 140));
        RenderUtils.drawRect(ctx, x - 2, y - 2, screen.getStringWidth(value) + 4, 12, 0xFF1D2027);
        screen.drawString(ctx, value, x, y, 0xFFFFFFFF);
    }
    public void refresh() {
        if (pending || !ready()) return;
        await(MCMaterialListClient.work().refreshRegions(capture().snapshot()), result -> publish(result.storage()));
    }
    private boolean currentDefinitions() {
        var capture = capture();
        if (capture.status() != PlacementWorkAdapter.Status.OK || !placement.equals(capture.snapshot().placementId())) return false;
        var observed = capture.snapshot().regions().stream().collect(java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(), java.util.stream.Collectors.counting()));
        var current = confirmed.definitions().values().stream().map(RegionDefinition::descriptor).collect(java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(), java.util.stream.Collectors.counting()));
        if (current.equals(observed)) return true;
        feedbackTarget().addMessage(MessageType.WARNING, text("refresh_required")); return false;
    }
    public void command(RegionTaskId id, TaskCommand command) {
        var mc = Minecraft.getInstance();
        if (!writable(id) || mc.player == null || !currentDefinitions()) return;
        await(MCMaterialListClient.work().update(placement, id, command, mc.player.getUUID(), Instant.now()), this::updated);
    }
    public void acknowledge(RegionTaskId id) {
        if (!writable() || !review(id) || !currentDefinitions()) return;
        await(MCMaterialListClient.work().acknowledgeRegion(placement, id), this::updated);
    }
    private void updated(UpdateResult result) {
        publish(result.storage());
        if (result.transition() instanceof TransitionResult.Rejected rejected) feedbackTarget().addMessage(MessageType.WARNING, text("rejected", rejected.reason().name()));
    }
    public void detail(RegionTaskId id, String name) { GuiBase.openGui(new RegionDetailScreen(screen, this, id, name)); }
    private void publish(StoreResult result) {
        status = result.status();
        if (status != StoreStatus.RECOVERY_REQUIRED) recoveryConfirmed = false;
        if (result.ok() && result.dataset() instanceof RegionWorkDataset dataset) confirmed = dataset;
        if (!result.ok() && status != StoreStatus.MISSING) feedbackTarget().addMessage(MessageType.ERROR, text("storage", status.name()));
    }
    private GuiBase feedbackTarget() {
        var active = Minecraft.getInstance().gui.screen();
        return active instanceof RegionDetailScreen detail && detail.session() == this ? detail : screen;
    }
    private <T> void await(CompletableFuture<T> future, Consumer<T> receiver) {
        pending = true;
        future.whenComplete((result, failure) -> Minecraft.getInstance().schedule(() -> {
            pending = false;
            if (failure == null) receiver.accept(result); else publish(StoreResult.failure(StoreStatus.IO_FAILURE));
            var active = Minecraft.getInstance().gui.screen();
            if (active == screen) screen.initGui();
            else if (active instanceof RegionDetailScreen detail && detail.session() == this) detail.refreshSavedState();
        }));
    }
}
