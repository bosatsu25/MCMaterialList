package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.SharedClientArchive;
import dev.mcmateriallist.core.work.*;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/** Narrow native-screen source selector. Local datasets never pass through this backend. */
public final class SharedWorkBackend {
    private final SharedWorkController controller;
    private final SharedWorkController.Entry entry;
    private final DatasetKind kind;
    private final Supplier<SchematicPlacement> placement;
    private final Supplier<MaterialListBase> materials;
    private final BooleanSupplier ready;
    public SharedWorkBackend(SharedWorkController controller, LocalPlacementId id, DatasetKind kind,
            Supplier<SchematicPlacement> placement, Supplier<MaterialListBase> materials, BooleanSupplier ready) {
        this.controller = controller; this.entry = controller.attach(id); this.kind = kind;
        this.placement = placement; this.materials = materials; this.ready = ready;
        controller.register(this);
    }
    public static String text(String key, Object... args) { return Component.translatable("mcmateriallist.shared." + key, args).getString(); }
    SharedWorkController.Entry entry() { return entry; }
    public DatasetKind kind() { return kind; }
    NativeSharedCapture.Capture capture(boolean full) { return NativeSharedCapture.capture(placement.get(), materials.get(), full); }
    boolean nativeReady() { return ready.getAsBoolean(); }
    public SharedClientArchive.Reference reference() { return SharedWorkController.reference(entry, kind); }
    public Object context() { return java.util.Arrays.asList(reference(), SharedWorkController.actor()); }
    public boolean selected() { return reference() != null; }
    public boolean loading() { return entry != null && entry.loading; }
    public boolean pending() { return entry != null && (entry.loading || entry.busy > 0 || controller.cachePending(this)); }
    public long version() { return entry == null ? 0 : entry.version; }
    public boolean writable() { return controller.writable(this); }
    public String feedback() { return text(controller.reason(this)); }
    public String taskFeedback(TaskId task, TaskCommand intent) {
        String reason = controller.reason(this);
        var snapshot = snapshot();
        if ((reason.equals("shared") || reason.equals("viewer")) && snapshot != null
                && task != null && intent != null && snapshot.rows().containsKey(task) && !allowed(task, intent))
            return text("task_permission_required");
        return text(reason);
    }
    public ProjectSnapshot snapshot() { return controller.snapshot(entry, kind); }
    public TaskState state(TaskId id) { var snapshot = snapshot(); return snapshot == null || id == null ? null : snapshot.rows().get(id); }
    public boolean review(TaskId id) { var snapshot = snapshot(); return snapshot != null && id != null && snapshot.needsReconfirmation().contains(id); }
    public Progress progress() { var snapshot = snapshot(); return snapshot == null ? null : kind == DatasetKind.MATERIALS ? snapshot.materialProgress() : snapshot.regionProgress(); }
    public RegionTaskId region(String key) {
        var snapshot = snapshot(); var current = capture(false);
        if (snapshot == null || !current.ok()) return null;
        var descriptors = current.source().regions().stream().filter(region -> region.key().equals(key)).toList();
        if (descriptors.size() != 1) return null;
        return snapshot.manifest().regions().entrySet().stream().filter(region -> region.getValue().equals(descriptors.getFirst()))
            .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
    }
    public void command(TaskId task, TaskCommand command) { controller.command(this, task, command); }
    public void discover() { controller.discover(this); }
    public void create(String name) { controller.create(this, name); }
    public void join(ProjectScope scope) { controller.join(this, scope, !selected() || !reference().scope().equals(scope)); }
    public void refresh() { if (selected()) controller.join(this, reference().scope(), false); }
    public void local() { controller.local(this); }
    public boolean canRecover() { return entry != null && entry.storage == StoreStatus.RECOVERY_REQUIRED && !pending(); }
    public void recover() { controller.recover(this); }
    public ProjectRole role() { var snapshot = snapshot(); return snapshot == null ? null : snapshot.role(SharedWorkController.actor()); }
    public boolean allowed(TaskId task, TaskCommand intent) { return controller.allowed(this, task, intent); }
    public boolean administer() { return controller.administer(this, false); }
    public boolean canUpdateDefinition() { return controller.administer(this, true); }
    public boolean admin() { return administer(); }
    public void membership(UUID target, ProjectRole role) { controller.membership(this, target, role); }
    public void updateDefinition() { controller.updateDefinition(this); }
    public void viewer(UUID target, boolean granted) { controller.viewer(this, target, granted); }
    // Issue #11: REGIONS-only detached local copy.
    /** The detached local region copy, or null when this list is shared or original local work. */
    public SharedClientArchive.Detached detached() { return SharedWorkController.detached(entry, kind); }
    /** Sharing metadata cannot be read, so original local region editing is blocked (a detached copy might be hidden). */
    /** No entry (placement capacity or no identity) means its sharing metadata, and so any detached copy, is unknown. */
    public boolean metadataBlocked() { return entry == null || SharedWorkController.metadataBlocked(entry); }
    public boolean canUnshare() { return controller.unshareReason(this) == null; }
    public boolean canRejoin() { return controller.rejoinReason(this) == null; }
    /** Null when Unshare regions is allowed; otherwise the translated reason. */
    public String unshareFeedback() {
        String reason = controller.unshareReason(this);
        return reason == null ? null : reason.equals("unshare.unresolved_operations") ? text(reason, controller.unresolved(this)) : text(reason);
    }
    public String rejoinFeedback() { String reason = controller.rejoinReason(this); return reason == null ? null : text(reason); }
    public void unshare() { controller.unshare(this); }
    public void rejoin() { controller.rejoin(this); }
    public boolean detachedWritable() { return controller.detachedWritable(this); }
    /** Pure edit of the detached copy on the store queue; false when not submitted (after is then never called). */
    public boolean updateDetached(java.util.function.UnaryOperator<RegionWorkDataset> edit, java.util.function.Consumer<StoreStatus> after) {
        return controller.updateDetached(this, edit, after);
    }
    /** Where Rejoin writes (or wrote) this copy; never read back by the application. */
    public java.nio.file.Path detachedExport(RegionWorkDataset dataset) { return controller.detachedExport(this, dataset); }
}
