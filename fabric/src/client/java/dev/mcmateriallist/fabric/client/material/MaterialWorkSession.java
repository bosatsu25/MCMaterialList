package dev.mcmateriallist.fabric.client.material;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.*;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.MCMaterialListClient;
import dev.mcmateriallist.fabric.client.litematica.PlacementIdentity;
import dev.mcmateriallist.fabric.client.work.MaterialReadiness;
import dev.mcmateriallist.fabric.client.work.WorkScreenGeometry;
import fi.dy.masa.malilib.render.RenderUtils;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.GuiContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-thread view; only durable I/O results are published. Opening is read-only. */
public final class MaterialWorkSession {
    private final GuiMaterialList screen;
    private final LocalPlacementId placement;
    private MaterialWorkDataset confirmed;
    private StoreStatus status;
    private boolean pending;
    private boolean showInfo;
    private boolean hideDone;
    private boolean recoveryConfirmed;
    private boolean loaded;
    private Set<MaterialTaskId> changed = Set.of();
    private ButtonGeneric refreshButton;
    private ButtonGeneric recoverButton;
    public MaterialWorkSession(GuiMaterialList screen) {
        this.screen = screen;
        LocalPlacementId identity = null;
        var owner = PlacementWorkAdapter.owner(screen.getMaterialList());
        if (owner == null) status = StoreStatus.INVALID_DEFINITION;
        else try {
            identity = PlacementIdentity.get(owner, DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
        } catch (IllegalStateException failure) { status = StoreStatus.INVALID_DEFINITION; }
        placement = identity;
    }
    public static String text(String key, Object... args) { return Component.translatable("mcmateriallist.material." + key, args).getString(); }
    public MaterialWorkDataset dataset() { return confirmed; }
    public StoreStatus status() { return status; }
    public boolean pending() { return pending; }
    public boolean showInfo() { return showInfo; }
    public boolean hideDone() { return hideDone; }
    public boolean compact() {
        return WorkScreenGeometry.compact(screen.getScreenWidth(), screen.getScreenHeight())
            && status != StoreStatus.RECOVERY_REQUIRED && (confirmed == null || confirmed.archived().isEmpty());
    }
    public boolean changed(MaterialTaskId id) { return changed.contains(id); }
    public TaskState state(MaterialTaskId id) { return confirmed == null ? null : confirmed.states().get(id); }
    public boolean ready() {
        return placement != null && MCMaterialListClient.work() != null
            && screen.getMaterialList() instanceof MaterialReadiness readiness && readiness.mcmateriallist$ready()
            && !TaskScheduler.getInstanceClient().hasTask(TaskCountBlocksPlacement.class);
    }
    public boolean writable() { return !pending && ready() && status == StoreStatus.OK && confirmed != null; }
    public String feedback() {
        if (pending) return text("saving");
        if (status != null && status != StoreStatus.OK && status != StoreStatus.MISSING) return text("storage", status.name());
        return !ready() ? text("waiting") : status == StoreStatus.OK ? text("local") : text("untracked");
    }
    public void toolbar() {
        if (!loaded && placement != null && MCMaterialListClient.work() != null) {
            loaded = true;
            await(MCMaterialListClient.work().load(placement, DatasetKind.MATERIALS), this::publish);
        }
        boolean compact = compact();
        var buttons = ((dev.mcmateriallist.fabric.client.mixin.GuiButtonsAccess) screen).mcmateriallist$buttons();
        int refreshX = compact ? buttons.stream().filter(button -> button.getY() == screen.getScreenHeight() - 22)
            .mapToInt(button -> button.getX() + button.getWidth()).max().orElse(12) + 2 : 12;
        refreshButton = addToolbar(refreshX, compact ? screen.getScreenHeight() - 22 : 44, text(status == StoreStatus.MISSING ? "track" : "refresh"), (button, mouse) -> refresh());
        int x = compact ? buttons.stream().filter(button -> button.getY() == 24)
            .mapToInt(button -> button.getX() + button.getWidth()).max().orElse(12) + 2 : refreshX + refreshButton.getWidth() + 4;
        int filterY = compact ? 24 : 44;
        var info = addToolbar(x, filterY, text("show_info", showInfo ? "ON" : "OFF"), (button, mouse) -> {
            showInfo = !showInfo; screen.initGui();
        });
        x += info.getWidth() + 4;
        var hide = addToolbar(x, filterY, text("hide_done", hideDone ? "ON" : "OFF"), (button, mouse) -> {
            hideDone = !hideDone; screen.initGui();
        });
        x += hide.getWidth() + 4;
        if (compact) x = refreshX + refreshButton.getWidth() + 2;
        recoverButton = null;
        if (status == StoreStatus.RECOVERY_REQUIRED) {
            recoverButton = addToolbar(x, compact ? screen.getScreenHeight() - 58 : 44, text(recoveryConfirmed ? "confirm_recovery" : "recover"), (button, mouse) -> {
                if (!recoveryConfirmed) { recoveryConfirmed = true; screen.addMessage(MessageType.WARNING, text("recovery_warning")); screen.initGui(); }
                else if (!pending && ready()) {
                    recoveryConfirmed = false;
                    await(MCMaterialListClient.work().recover(placement, DatasetKind.MATERIALS), this::publish);
                }
            });
        } else if (confirmed != null && !confirmed.archived().isEmpty()) {
            addToolbar(x, compact ? screen.getScreenHeight() - 58 : 44, text("archived", confirmed.archived().size()), (button, mouse) -> GuiBase.openGui(new MaterialArchiveScreen(screen, this)));
        }
        renderControls();
    }
    private ButtonGeneric addToolbar(int x, int y, String label, IButtonActionListener action) {
        int width = Math.min(screen.getStringWidth(label) + 10, Math.max(28, (screen.getScreenWidth() - 24) / 4 - 4));
        var button = new ButtonGeneric(x, y, width, 20, MaterialPresentation.clamp(label, width - 8));
        button.setHoverStrings(label); return screen.addButton(button, action);
    }
    public void renderControls() {
        if (refreshButton != null) refreshButton.setEnabled(!pending && ready() && (status == StoreStatus.OK || status == StoreStatus.MISSING || status == StoreStatus.CONFLICT));
        if (recoverButton != null) recoverButton.setEnabled(!pending && ready());
    }
    public void footer(GuiContext ctx) {
        renderControls();
        String progress = confirmed == null ? text("untracked") : text("progress", confirmed.progress().completed(), confirmed.progress().total(), confirmed.progress().percentage());
        if (confirmed != null) progress = dev.mcmateriallist.core.ui.WorkProgressPresentation.style(progress, confirmed.progress());
        int y = screen.getScreenHeight() - (compact() ? 46 : 72);
        String value = MaterialPresentation.clamp(progress + "  " + feedback(), Math.max(0, screen.getScreenWidth() - 24));
        RenderUtils.drawRect(ctx, 10, y - 2, screen.getStringWidth(value) + 4, 12, 0xFF1D2027);
        screen.drawString(ctx, value, 12, y, 0xFFFFFFFF);
    }
    public void refresh() {
        if (pending || placement == null || MCMaterialListClient.work() == null) return;
        var capture = PlacementWorkAdapter.capture(PlacementWorkAdapter.owner(screen.getMaterialList()), screen.getMaterialList());
        if (capture.status() != PlacementWorkAdapter.Status.OK || !placement.equals(capture.snapshot().placementId())) {
            screen.addMessage(MessageType.WARNING, text("waiting")); return;
        }
        await(MCMaterialListClient.work().refreshMaterials(capture.snapshot()), result -> {
            if (result.storage().ok() && result.reconciliation() != null) {
                var notices = new java.util.HashSet<>(changed);
                notices.addAll(result.reconciliation().changed()); notices.removeAll(result.reconciliation().removed());
                changed = Set.copyOf(notices);
            }
            publish(result.storage());
        });
    }
    public void upstreamCompleted() { if (confirmed != null && !pending) refresh(); }
    public void command(MaterialTaskId id, TaskCommand command) {
        var mc = Minecraft.getInstance();
        if (!writable() || mc.player == null || state(id) == null) return;
        var capture = PlacementWorkAdapter.capture(PlacementWorkAdapter.owner(screen.getMaterialList()), screen.getMaterialList());
        if (capture.status() != PlacementWorkAdapter.Status.OK || !placement.equals(capture.snapshot().placementId())) return;
        // A command cannot silently archive/unarchive definitions or use an obsolete quantity observation.
        if (!confirmed.definitions().equals(capture.snapshot().materials().stream().collect(java.util.stream.Collectors.toMap(MaterialDefinition::id, def -> def)))) {
            feedbackTarget().addMessage(MessageType.WARNING, text("refresh_required")); return;
        }
        await(MCMaterialListClient.work().update(placement, id, command, mc.player.getUUID(), Instant.now()), result -> {
            publish(result.storage());
            if (result.transition() instanceof TransitionResult.Rejected rejected)
                feedbackTarget().addMessage(MessageType.WARNING, text("rejected", rejected.reason().name()));
        });
    }
    public void detail(MaterialTaskId id, String name) { GuiBase.openGui(new MaterialDetailScreen(screen, this, id, name)); }
    private void publish(StoreResult result) {
        status = result.status();
        if (status != StoreStatus.RECOVERY_REQUIRED) recoveryConfirmed = false;
        if (result.ok() && result.dataset() instanceof MaterialWorkDataset dataset) confirmed = dataset;
        // Preserve the last confirmed view on errors. Never display a recovery candidate as current.
        if (!result.ok() && status != StoreStatus.MISSING) feedbackTarget().addMessage(MessageType.ERROR, text("storage", status.name()));
    }
    private GuiBase feedbackTarget() {
        var active = Minecraft.getInstance().gui.screen();
        return active instanceof MaterialDetailScreen detail && detail.session() == this ? detail : screen;
    }
    private <T> void await(CompletableFuture<T> future, Consumer<T> receiver) {
        pending = true;
        // execute() can run inline for an already-completed future during initGui().
        // schedule() always queues, preventing reentrant initialization/duplicate widgets.
        future.whenComplete((result, failure) -> Minecraft.getInstance().schedule(() -> {
            pending = false;
            if (failure == null) receiver.accept(result); else publish(StoreResult.failure(StoreStatus.IO_FAILURE));
            var active = Minecraft.getInstance().gui.screen();
            if (active == screen) screen.initGui();
            else if (active instanceof MaterialDetailScreen detail && detail.session() == this) detail.refreshSavedState();
        }));
    }
    public static String playerName(UUID player) {
        if (player == null) return text("unassigned");
        var mc = Minecraft.getInstance();
        var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player);
        if (info != null) return info.getProfile().name();
        if (mc.player != null && player.equals(mc.player.getUUID())) return mc.player.getGameProfile().name();
        return text("unknown_player");
    }
}
