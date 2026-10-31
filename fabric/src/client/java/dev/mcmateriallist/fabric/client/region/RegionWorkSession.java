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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Region-owned client view. Disk results publish only on queued client callbacks. */
public final class RegionWorkSession {
    private final GuiPlacementConfiguration screen;
    private final LocalPlacementId placement;
    private final dev.mcmateriallist.fabric.client.shared.SharedWorkBackend shared;
    private RegionWorkDataset confirmed;
    private StoreStatus status;
    private boolean pending, loaded, showInfo, hideDone, recoveryConfirmed;
    private ButtonGeneric refreshButton, recoverButton;
    private int toolbarStart, toolbarWidth, toolbarY;
    private long sharedVersion = -1;
    private boolean sharedRebuild;
    public RegionWorkSession(GuiPlacementConfiguration screen) {
        this.screen = screen;
        var capture = PlacementWorkAdapter.captureRegions(screen.getSchematicPlacement());
        placement = capture.status() == PlacementWorkAdapter.Status.OK ? capture.snapshot().placementId() : null;
        if (placement == null) status = StoreStatus.INVALID_DEFINITION;
        shared = new dev.mcmateriallist.fabric.client.shared.SharedWorkBackend(MCMaterialListClient.shared(), placement, DatasetKind.REGIONS,
            screen::getSchematicPlacement, () -> screen.getSchematicPlacement().getMaterialList(), this::ready);
    }
    public dev.mcmateriallist.fabric.client.shared.SharedWorkBackend shared() { return shared; }
    public static String text(String key, Object... args) { return Component.translatable("mcmateriallist.region." + key, args).getString(); }
    /**
     * The one source of what the native region rows show (#11): the shared server view, else the detached local copy,
     * else the original local dataset. dataset is null for SHARED (rows come from the backend snapshot).
     */
    public enum Source { SHARED, DETACHED, LOCAL }
    public record View(Source source, RegionWorkDataset dataset) {}
    public View view() {
        if (shared.selected()) return new View(Source.SHARED, null);
        var copy = shared.detached();
        return copy != null ? new View(Source.DETACHED, copy.dataset()) : new View(Source.LOCAL, confirmed);
    }
    /** The local dataset the native rows edit when not shared: the detached copy if any, else the original. */
    public RegionWorkDataset dataset() { return view().source() == Source.SHARED ? confirmed : view().dataset(); }
    public boolean detached() { return view().source() == Source.DETACHED; }
    public StoreStatus status() { return status; }
    public boolean pending() { return pending || shared.pending(); }
    public boolean showInfo() { return showInfo; }
    public boolean hideDone() { return hideDone; }
    public boolean compact() {
        var local = dataset();
        return WorkScreenGeometry.compact(screen.getScreenWidth(), screen.getScreenHeight())
            && (detached() || status != StoreStatus.RECOVERY_REQUIRED) && (local == null || local.archived().isEmpty());
    }
    public TaskState state(RegionTaskId id) {
        var view = view();
        return id == null ? null : view.source() == Source.SHARED ? shared.state(id) : view.dataset() == null ? null : view.dataset().states().get(id);
    }
    public boolean review(RegionTaskId id) {
        var view = view();
        return view.source() == Source.SHARED ? shared.review(id) : view.dataset() != null && id != null && view.dataset().activeReviewRequired().contains(id);
    }
    public Progress progress() { var view = view(); return view.source() == Source.SHARED ? shared.progress() : view.dataset() == null ? null : view.dataset().progress(); }
    /** Resolve by exact original descriptor; ambiguity remains unavailable, never guessed. */
    public RegionTaskId id(String key) {
        var view = view();
        if (view.source() == Source.SHARED) return shared.region(key);
        if (view.dataset() == null) return null;
        var capture = PlacementWorkAdapter.captureRegions(screen.getSchematicPlacement());
        if (capture.status() != PlacementWorkAdapter.Status.OK) return null;
        var descriptors = capture.snapshot().regions().stream().filter(region -> region.key().equals(key)).toList();
        if (descriptors.size() != 1) return null;
        var matches = view.dataset().definitions().values().stream().filter(definition -> definition.descriptor().equals(descriptors.getFirst())).toList();
        return matches.size() == 1 ? matches.getFirst().id() : null;
    }
    private PlacementWorkAdapter.Capture capture() { return PlacementWorkAdapter.captureRegions(screen.getSchematicPlacement()); }
    public boolean ready() {
        if (placement == null || MCMaterialListClient.work() == null) return false;
        var capture = capture();
        return capture.status() == PlacementWorkAdapter.Status.OK && placement.equals(capture.snapshot().placementId());
    }
    public boolean writable() {
        return switch (view().source()) {
            case SHARED -> shared.writable();
            // Purely local: the journal never blocks it and nothing is sent (#11).
            case DETACHED -> !pending && ready() && shared.detachedWritable();
            // Unreadable sharing metadata might hide a detached copy, so the original is not edited behind it.
            case LOCAL -> !pending && !shared.loading() && !shared.metadataBlocked() && ready() && status == StoreStatus.OK && confirmed != null;
        };
    }
    public boolean writable(RegionTaskId id) {
        var row = state(id);
        return row != null && (shared.selected() ? shared.allowed(id, new TaskCommand.SetDone(!row.done())) : writable() && !review(id));
    }
    public boolean allowed(RegionTaskId id, TaskCommand intent) {
        return shared.selected() ? shared.allowed(id, intent) : writable() && state(id) != null && !review(id);
    }
    public String feedback() {
        if (shared.loading() || shared.selected()) return shared.feedback();
        if (detached()) return pending || shared.pending() ? text("saving") : shared.feedback();
        if (shared.metadataBlocked()) return shared.feedback();
        if (pending) return text("saving");
        if (status != null && status != StoreStatus.OK && status != StoreStatus.MISSING) return text("storage", status.name());
        return !ready() ? text("waiting") : status == StoreStatus.OK ? text("local") : text("untracked");
    }
    public void toolbar() {
        if (!loaded && ready()) { loaded = true; await(MCMaterialListClient.work().load(placement, DatasetKind.REGIONS), this::publish); }
        toolbarStart = compact() ? 18 + screen.getStringWidth(fi.dy.masa.malilib.util.StringUtils.translate("litematica.gui.label.schematic_placement.sub_regions", screen.getSchematicPlacement().getSubRegionCount())) : 12;
        int end = compact() ? ((GuiButtonsAccess) screen).mcmateriallist$buttons().stream().filter(button -> button.getY() == 44 && button.getX() > toolbarStart)
            .mapToInt(button -> button.getX()).min().orElse(screen.getScreenWidth() - 154) : screen.getScreenWidth() - 148;
        var local = shared.selected() ? null : dataset();
        int slots = (!detached() && status == StoreStatus.RECOVERY_REQUIRED) || (local != null && !local.archived().isEmpty()) ? 4 : 3;
        toolbarWidth = Math.max(20, (end - toolbarStart) / (compact() ? 3 : slots) - 2);
        toolbarY = compact() ? 44 : 64;
        refreshButton = add(0, text(!shared.selected() && !detached() && status == StoreStatus.MISSING ? "track" : "refresh"), (button, mouse) -> refresh());
        add(1, text("hide_done", hideDone ? "ON" : "OFF"), (button, mouse) -> { hideDone = !hideDone; screen.initGui(); });
        add(2, text("show_info", showInfo ? "ON" : "OFF"), (button, mouse) -> { showInfo = !showInfo; screen.initGui(); });
        recoverButton = null;
        if (!shared.selected() && !detached() && status == StoreStatus.RECOVERY_REQUIRED) {
            recoverButton = add(3, text(recoveryConfirmed ? "confirm_recovery" : "recover"), (button, mouse) -> {
                if (!recoveryConfirmed) { recoveryConfirmed = true; screen.addMessage(MessageType.WARNING, text("recovery_warning")); screen.initGui(); }
                else if (!pending && !shared.loading() && ready()) {
                    recoveryConfirmed = false;
                    await(MCMaterialListClient.work().recover(placement, DatasetKind.REGIONS), this::publish);
                }
            });
        } else if (local != null && !local.archived().isEmpty()) {
            add(3, text("archived", local.archived().size()), (button, mouse) -> GuiBase.openGui(new RegionArchiveScreen(screen, this)));
        }
        renderControls();
        var share = screen.addButton(new ButtonGeneric(compact() ? screen.getScreenWidth() - 128 : 126,
            screen.getScreenHeight() - (compact() ? 22 : 44), 116, 20,
            dev.mcmateriallist.fabric.client.shared.SharedWorkBackend.text("entry")),
            (button, mouse) -> GuiBase.openGui(new dev.mcmateriallist.fabric.client.shared.SharedWorkScreen(screen, shared)));
        share.setHoverStrings(dev.mcmateriallist.fabric.client.shared.SharedWorkBackend.text("title"));
    }
    private ButtonGeneric add(int slot, String label, IButtonActionListener action) {
        var button = new ButtonGeneric(toolbarStart + slot * (toolbarWidth + 2), toolbarY, toolbarWidth, 20, MaterialPresentation.clamp(label, toolbarWidth - 8));
        button.setHoverStrings(label); return screen.addButton(button, action);
    }
    private void renderControls() {
        if (refreshButton != null) refreshButton.setEnabled(!shared.loading() && switch (view().source()) {
            case SHARED -> !shared.pending() && ready();
            case DETACHED -> writable();
            case LOCAL -> !pending && !shared.metadataBlocked() && ready() && (status == StoreStatus.OK || status == StoreStatus.MISSING || status == StoreStatus.CONFLICT);
        });
        if (recoverButton != null) recoverButton.setEnabled(!pending && !shared.loading() && ready());
    }
    public void footer(GuiContext ctx) {
        if (!sharedRebuild && sharedVersion != shared.version()) {
            sharedVersion = shared.version(); sharedRebuild = true;
            Minecraft.getInstance().schedule(() -> {
                sharedRebuild = false;
                if (Minecraft.getInstance().gui.screen() == screen) screen.initGui();
            });
        }
        renderControls();
        var valueProgress = progress();
        String progress = valueProgress == null ? text("untracked") : text("progress", valueProgress.completed(), valueProgress.total(), valueProgress.percentage());
        if (valueProgress != null) progress = dev.mcmateriallist.core.ui.WorkProgressPresentation.style(progress, valueProgress);
        int x = compact() ? 120 : 12;
        int y = screen.getScreenHeight() - (compact() ? 17 : 60);
        String value = MaterialPresentation.clamp(progress + "  " + feedback(), Math.max(0, screen.getScreenWidth() - x - 140));
        RenderUtils.drawRect(ctx, x - 2, y - 2, screen.getStringWidth(value) + 4, 12, 0xFF1D2027);
        screen.drawString(ctx, value, x, y, 0xFFFFFFFF);
    }
    public void refresh() {
        if (shared.loading()) return;
        if (shared.selected()) { shared.refresh(); return; }
        if (detached()) {
            if (!writable()) return;
            var observed = capture().snapshot().regions();
            detachedEdit(copy -> copy.reconcile(observed).dataset(), null);
            return;
        }
        if (pending || !ready() || shared.metadataBlocked()) return;
        await(MCMaterialListClient.work().refreshRegions(capture().snapshot()), result -> publish(result.storage()));
    }
    private boolean currentDefinitions() {
        var capture = capture();
        if (capture.status() != PlacementWorkAdapter.Status.OK || !placement.equals(capture.snapshot().placementId())) return false;
        var observed = capture.snapshot().regions().stream().collect(java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(), java.util.stream.Collectors.counting()));
        var local = dataset(); if (local == null) return false;
        var current = local.definitions().values().stream().map(RegionDefinition::descriptor).collect(java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(), java.util.stream.Collectors.counting()));
        if (current.equals(observed)) return true;
        feedbackTarget().addMessage(MessageType.WARNING, text("refresh_required")); return false;
    }
    public void command(RegionTaskId id, TaskCommand command) {
        if (shared.selected()) { shared.command(id, command); return; }
        var mc = Minecraft.getInstance();
        if (!writable(id) || mc.player == null || !currentDefinitions()) return;
        if (detached()) {
            var actor = mc.player.getUUID(); var time = Instant.now(); var outcome = new AtomicReference<TransitionResult>();
            detachedEdit(copy -> { var mutation = copy.apply(id, command, actor, time); outcome.set(mutation.result()); return mutation.dataset(); }, outcome);
            return;
        }
        await(MCMaterialListClient.work().update(placement, id, command, mc.player.getUUID(), Instant.now()), this::updated);
    }
    public void acknowledge(RegionTaskId id) {
        if (shared.selected()) return;
        if (!writable() || !review(id) || !currentDefinitions()) return;
        if (detached()) { detachedEdit(copy -> copy.acknowledge(id), null); return; }
        await(MCMaterialListClient.work().acknowledgeRegion(placement, id), this::updated);
    }
    /** Local Done/acknowledge/reconcile of the detached copy only; the edit runs once on the store queue and is pure. */
    private void detachedEdit(UnaryOperator<RegionWorkDataset> edit, AtomicReference<TransitionResult> outcome) {
        pending = true;
        boolean submitted = shared.updateDetached(edit, stored -> {
            pending = false;
            if (stored != StoreStatus.OK) feedbackTarget().addMessage(MessageType.ERROR, text("storage", stored.name()));
            else if (outcome != null && outcome.get() instanceof TransitionResult.Rejected rejected)
                feedbackTarget().addMessage(MessageType.WARNING, text("rejected", rejected.reason().name()));
            var active = Minecraft.getInstance().gui.screen();
            if (active == screen) screen.initGui();
            else if (active instanceof RegionDetailScreen detail && detail.session() == this) detail.refreshSavedState();
        });
        if (!submitted) pending = false;
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
