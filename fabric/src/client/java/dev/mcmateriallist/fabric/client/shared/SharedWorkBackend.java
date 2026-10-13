package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.SharedClientArchive;
import dev.mcmateriallist.core.work.*;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.Objects;
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
    public boolean admin() { var snapshot = snapshot(); return writable() && snapshot != null && Objects.equals(snapshot.creator(), SharedWorkController.actor()); }
    public void viewer(UUID target, boolean granted) { controller.viewer(this, target, granted); }
}
