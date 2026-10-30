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
    private final dev.mcmateriallist.fabric.client.shared.SharedWorkBackend shared;
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
    private long sharedVersion = -1;
    private boolean sharedRebuild;
    private final dev.mcmateriallist.fabric.client.location.LocationSession locations;
    private boolean locationRebuild;
    public MaterialWorkSession(GuiMaterialList screen) {
        this.screen = screen;
        LocalPlacementId identity = null;
        var owner = PlacementWorkAdapter.owner(screen.getMaterialList());
        if (owner == null) status = StoreStatus.INVALID_DEFINITION;
        else try {
            identity = PlacementIdentity.get(owner, DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
        } catch (IllegalStateException failure) { status = StoreStatus.INVALID_DEFINITION; }
        placement = identity;
        shared = new dev.mcmateriallist.fabric.client.shared.SharedWorkBackend(MCMaterialListClient.shared(), placement, DatasetKind.MATERIALS,
            () -> PlacementWorkAdapter.owner(screen.getMaterialList()), screen::getMaterialList, this::ready);
        locations = new dev.mcmateriallist.fabric.client.location.LocationSession(() -> { if (Minecraft.getInstance().gui.screen() == screen) screen.initGui(); });
    }
    public dev.mcmateriallist.fabric.client.shared.SharedWorkBackend shared() { return shared; }
    public dev.mcmateriallist.fabric.client.location.LocationSession location() { return locations; }
    public static String text(String key, Object... args) { return Component.translatable("mcmateriallist.material." + key, args).getString(); }
    public MaterialWorkDataset dataset() { return confirmed; }
    public StoreStatus status() { return status; }
    public boolean pending() { return pending || shared.pending() || locations.pending(); }
    public boolean showInfo() { return showInfo; }
    public boolean hideDone() { return hideDone; }
    public boolean compact() {
        return WorkScreenGeometry.compact(screen.getScreenWidth(), screen.getScreenHeight())
            && status != StoreStatus.RECOVERY_REQUIRED && (confirmed == null || confirmed.archived().isEmpty());
    }
    public boolean changed(MaterialTaskId id) { return shared.selected() ? shared.review(id) : changed.contains(id); }
    public TaskState state(MaterialTaskId id) { return shared.selected() ? shared.state(id) : confirmed == null ? null : confirmed.states().get(id); }
    public Progress progress() { return shared.selected() ? shared.progress() : confirmed == null ? null : confirmed.progress(); }
    public boolean ready() {
        return placement != null && MCMaterialListClient.work() != null
            && screen.getMaterialList() instanceof MaterialReadiness readiness && readiness.mcmateriallist$ready()
            && !TaskScheduler.getInstanceClient().hasTask(TaskCountBlocksPlacement.class);
    }
    public boolean writable() { return shared.selected() ? shared.writable() : !pending && !shared.loading() && ready() && status == StoreStatus.OK && confirmed != null; }
    public boolean allowed(MaterialTaskId id, TaskCommand intent) {
        return shared.selected() ? shared.allowed(id, intent) : writable() && state(id) != null;
    }
    public String feedback() {
        if (shared.loading() || shared.selected()) return shared.feedback();
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
        refreshButton = addToolbar(refreshX, compact ? screen.getScreenHeight() - 22 : 44, text(!shared.selected() && status == StoreStatus.MISSING ? "track" : "refresh"), (button, mouse) -> refresh());
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
        if (!shared.selected() && status == StoreStatus.RECOVERY_REQUIRED) {
            recoverButton = addToolbar(x, compact ? screen.getScreenHeight() - 58 : 44, text(recoveryConfirmed ? "confirm_recovery" : "recover"), (button, mouse) -> {
                if (!recoveryConfirmed) { recoveryConfirmed = true; screen.addMessage(MessageType.WARNING, text("recovery_warning")); screen.initGui(); }
                else if (!pending && !shared.loading() && ready()) {
                    recoveryConfirmed = false;
                    await(MCMaterialListClient.work().recover(placement, DatasetKind.MATERIALS), this::publish);
                }
            });
        } else if (!shared.selected() && confirmed != null && !confirmed.archived().isEmpty()) {
            addToolbar(x, compact ? screen.getScreenHeight() - 58 : 44, text("archived", confirmed.archived().size()), (button, mouse) -> GuiBase.openGui(new MaterialArchiveScreen(screen, this)));
        }
        renderControls();
        int shareX = compact ? buttons.stream().filter(button -> button.getY() == screen.getScreenHeight() - 22)
            .mapToInt(button -> button.getX() + button.getWidth()).max().orElse(12) + 2 : 126;
        var share = addToolbar(shareX, screen.getScreenHeight() - (compact ? 22 : 58), dev.mcmateriallist.fabric.client.shared.SharedWorkBackend.text("entry"),
            (button, mouse) -> GuiBase.openGui(new dev.mcmateriallist.fabric.client.shared.SharedWorkScreen(screen, shared)));
        // The localized upstream menu extends into the footer at some scales.
        if (compact && buttons.stream().anyMatch(other -> other != share
            && share.getX() < other.getX() + other.getWidth() && share.getX() + share.getWidth() > other.getX()
            && share.getY() < other.getY() + other.getHeight() && share.getY() + share.getHeight() > other.getY())) {
            share.setX(hide.getX() + hide.getWidth() + 4);
            share.setY(filterY);
        }
        share.setHoverStrings(dev.mcmateriallist.fabric.client.shared.SharedWorkBackend.text("title"));
        // Location File follows Show Info/Hide Done where that row has room; Share keeps its verified slot and the
        // button takes the first free toolbar row instead, narrowing (full hover label) only if no row fits.
        locations.ensure();
        String location = locations.label();
        int footer = screen.getScreenHeight() - 22, lower = screen.getScreenHeight() - 58;
        int[] slot = slot(toolbarWidth(location), compact ? new int[][]{{filterY, 0}, {footer, 0}, {lower, refreshX + refreshButton.getWidth() + 2}}
            : new int[][]{{filterY, 0}, {lower, 0}, {footer, 0}});
        addToolbar(slot[0], slot[1], slot[2], location, (button, mouse) ->
            GuiBase.openGui(new dev.mcmateriallist.fabric.client.location.LocationFileScreen(screen, locations, shared)));
    }
    private int toolbarWidth(String label) { return Math.min(screen.getStringWidth(label) + 10, Math.max(28, (screen.getScreenWidth() - 24) / 4 - 4)); }
    private ButtonGeneric addToolbar(int x, int y, String label, IButtonActionListener action) { return addToolbar(x, y, toolbarWidth(label), label, action); }
    private ButtonGeneric addToolbar(int x, int y, int width, String label, IButtonActionListener action) {
        var button = new ButtonGeneric(x, y, width, 20, MaterialPresentation.clamp(label, width - 8));
        button.setHoverStrings(label); return screen.addButton(button, action);
    }
    /** {x, y, width}: after the last control of the first row ({y, minimum x}) that fits inside the screen, else the widest. */
    private int[] slot(int width, int[][] rows) {
        var buttons = ((dev.mcmateriallist.fabric.client.mixin.GuiButtonsAccess) screen).mcmateriallist$buttons();
        int limit = screen.getScreenWidth() - 12;
        int[] widest = null;
        for (int[] row : rows) {
            int y = row[0];
            int x = Math.max(row[1], buttons.stream().filter(other -> other.getY() < y + 20 && other.getY() + other.getHeight() > y)
                .mapToInt(other -> other.getX() + other.getWidth() + 4).max().orElse(12));
            if (x + width <= limit) return new int[]{x, y, width};
            if (widest == null || limit - x > widest[2]) widest = new int[]{x, y, limit - x};
        }
        return new int[]{widest[0], widest[1], Math.max(28, widest[2])};
    }
    public void renderControls() {
        if (refreshButton != null) refreshButton.setEnabled(!shared.loading() && (shared.selected() ? !shared.pending() && ready() : !pending && ready() && (status == StoreStatus.OK || status == StoreStatus.MISSING || status == StoreStatus.CONFLICT)));
        if (recoverButton != null) recoverButton.setEnabled(!pending && !shared.loading() && ready());
    }
    public void footer(GuiContext ctx) {
        if (!locationRebuild && locations.stale()) {
            locationRebuild = true;
            Minecraft.getInstance().schedule(() -> {
                locationRebuild = false;
                if (Minecraft.getInstance().gui.screen() == screen) screen.initGui();
            });
        }
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
        int y = screen.getScreenHeight() - (compact() ? 46 : 72);
        String value = MaterialPresentation.clamp(progress + "  " + feedback(), Math.max(0, screen.getScreenWidth() - 24));
        RenderUtils.drawRect(ctx, 10, y - 2, screen.getStringWidth(value) + 4, 12, 0xFF1D2027);
        screen.drawString(ctx, value, 12, y, 0xFFFFFFFF);
    }
    public void refresh() {
        if (shared.loading()) return;
        if (shared.selected()) { shared.refresh(); return; }
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
    public void upstreamCompleted() { if (!shared.loading() && !shared.selected() && confirmed != null && !pending) refresh(); }
    public void command(MaterialTaskId id, TaskCommand command) {
        if (shared.selected()) { shared.command(id, command); return; }
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
