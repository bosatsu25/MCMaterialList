package dev.mcmateriallist.fabric.client.work;

import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.Compatibility;
import dev.mcmateriallist.fabric.client.litematica.PlacementIdentity;
import dev.mcmateriallist.fabric.client.mixin.MaterialListPlacementAccess;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksPlacement;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** Capture only on the client thread; hand immutable, Minecraft-free observations to I/O. */
public final class PlacementWorkAdapter {
    public enum Status { OK, UNSUPPORTED, UNSAVED_OR_DUPLICATE, UNREGISTERED, WRONG_LIST, MATERIALS_BUSY, MATERIALS_NOT_READY, INVALID_DEFINITION }
    public record Capture(Status status, WorkSnapshot snapshot) {}
    private PlacementWorkAdapter() {}

    public static Capture capture(SchematicPlacement placement, MaterialListBase materials) {
        if (!Compatibility.isSupported()) return failure(Status.UNSUPPORTED);
        if (placement == null) return failure(Status.UNREGISTERED);
        var active = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
        if (active.stream().noneMatch(other -> other == placement)) return failure(Status.UNREGISTERED);
        if (!(materials instanceof MaterialListPlacementAccess access) || access.mcmateriallist$placement() != placement)
            return failure(Status.WRONG_LIST);
        // getMaterialsAll may still be the old/empty list while an upstream refresh is queued.
        if (TaskScheduler.getInstanceClient().hasTask(TaskCountBlocksPlacement.class)) return failure(Status.MATERIALS_BUSY);
        if (!(materials instanceof MaterialReadiness readiness) || !readiness.mcmateriallist$ready()) return failure(Status.MATERIALS_NOT_READY);
        dev.mcmateriallist.core.LocalPlacementId identity;
        try { identity = PlacementIdentity.get(placement, active); }
        catch (IllegalStateException exception) { return failure(Status.UNSAVED_OR_DUPLICATE); }
        try {
            var materialDefs = new ArrayList<MaterialDefinition>();
            for (var row : materials.getMaterialsAll()) {
                if (row.getStack().isEmpty()) return failure(Status.INVALID_DEFINITION);
                var id = new MaterialTaskId(BuiltInRegistries.ITEM.getKey(row.getStack().getItem()).toString());
                materialDefs.add(new MaterialDefinition(id, row.getCountTotal(), row.getCountMissing(), row.getCountAvailable()));
            }
            var schematic = placement.getSchematic();
            var origins = schematic.getAreaPositions(); var sizes = schematic.getAreaSizes();
            if (!origins.keySet().equals(sizes.keySet())) return failure(Status.INVALID_DEFINITION);
            var regionDefs = new ArrayList<RegionDescriptor>();
            for (var key : origins.keySet()) regionDefs.add(new RegionDescriptor(key, vector(origins.get(key)), vector(sizes.get(key))));
            return new Capture(Status.OK, new WorkSnapshot(identity, materialDefs, regionDefs));
        } catch (IllegalArgumentException exception) { return failure(Status.INVALID_DEFINITION); }
    }
    public static SchematicPlacement owner(MaterialListBase materials) {
        return materials instanceof MaterialListPlacement && materials instanceof MaterialListPlacementAccess access ? access.mcmateriallist$placement() : null;
    }
    private static Vector3 vector(BlockPos pos) { return new Vector3(pos.getX(), pos.getY(), pos.getZ()); }
    private static Capture failure(Status status) { return new Capture(status, null); }
}
