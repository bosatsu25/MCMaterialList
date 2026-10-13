package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.shared.SharedManifest;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.BlockInfoListType;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Render-thread native capture only. Source bytes are hashed by the owned background queue. */
public final class NativeSharedCapture {
    public record Source(LocalPlacementId placement, Path root, Path file, SharedManifest.Baseline baseline,
        List<RegionDescriptor> regions, SharedLoadedSource.Proof loaded) {
        public Source { regions = List.copyOf(regions); }
        public boolean matches(SharedManifest.Definition definition, String digest) {
            return definition.schematicDigest().equals(digest) && definition.baseline().equals(baseline)
                && new HashSet<>(definition.regions()).equals(new HashSet<>(regions)) && definition.regions().size() == regions.size();
        }
    }
    public record CountingStamp(SchematicPlacement placement, Source source, long nativeRevision) {}
    public record Capture(String reason, Source source, Map<MaterialTaskId, Long> materials) {
        public boolean ok() { return source != null; }
    }
    private NativeSharedCapture() {}
    public static Capture capture(SchematicPlacement placement, MaterialListBase list, boolean requireFullMaterials) {
        var regions = PlacementWorkAdapter.captureRegions(placement);
        if (regions.status() != PlacementWorkAdapter.Status.OK) return failure("capture." + regions.status().name());
        var client = Minecraft.getInstance();
        if (client.level == null) return failure("no_world");
        try {
            var schematic = placement.getSchematic();
            if (schematic.getFile() == null) return failure("source.UNSAVED");
            if (!(schematic instanceof SharedLoadedSource loaded) || loaded.mcmateriallist$loadedSource() == null)
                return failure("source_reload_required");
            var proof = loaded.mcmateriallist$loadedSource();
            if (!(schematic.getMetadata() instanceof SharedSchematicRevision revision)
                || proof.editRevision() != revision.mcmateriallist$editRevision()
                || schematic.getMetadata().wasModifiedSinceSaved() && !proof.modifiedAtLoad()) return failure("source_edited");
            if (!proof.file().current()) return failure("source_reload_required");
            if (proof.completedDigest() == null) return failure("source_reload_required");
            var baseline = new SharedManifest.Baseline(client.level.dimension().identifier().toString(), vector(placement.getOrigin()),
                SharedManifest.Baseline.Rotation.valueOf(placement.getRotation().name()), SharedManifest.Baseline.Mirror.valueOf(placement.getMirror().name()));
            var source = new Source(regions.snapshot().placementId(), DataManager.getSchematicsBaseDirectory(), placement.getSchematic().getFile(),
                baseline, regions.snapshot().regions(), proof);
            if (source.file() == null) return failure("source.UNSAVED");
            if (!requireFullMaterials) return new Capture(null, source, Map.of());
            var material = PlacementWorkAdapter.capture(placement, list);
            if (material.status() != PlacementWorkAdapter.Status.OK) return failure("capture." + material.status().name());
            if (!(list instanceof SharedCountingProvenance provenance) || !current(provenance.mcmateriallist$sharedCount())
                || !fullScope(placement, list)) return failure("full_recount_required");
            Map<MaterialTaskId, Long> definitions = new HashMap<>();
            for (var definition : material.snapshot().materials()) {
                if (definitions.putIfAbsent(definition.id(), definition.total()) != null) return failure("definitions_mismatch");
            }
            return new Capture(null, source, Map.copyOf(definitions));
        } catch (IllegalArgumentException invalid) { return failure("definitions_mismatch"); }
    }
    public static CountingStamp starting(SchematicPlacement placement, MaterialListBase list) {
        if (!fullScope(placement, list) || !(placement instanceof SharedPlacementRevision revision)) return null;
        var captured = capture(placement, list, false);
        return captured.ok() ? new CountingStamp(placement, captured.source(), revision.mcmateriallist$sharedRevision()) : null;
    }
    public static boolean current(CountingStamp stamp) {
        if (stamp == null || !(stamp.placement() instanceof SharedPlacementRevision revision)
            || revision.mcmateriallist$sharedRevision() != stamp.nativeRevision()) return false;
        var captured = capture(stamp.placement(), null, false);
        return captured.ok() && stamp.source().equals(captured.source());
    }
    private static boolean fullScope(SchematicPlacement placement, MaterialListBase list) {
        return placement != null && list != null && list.getMaterialListType() == BlockInfoListType.ALL && placement.isEnabled()
            && placement.getAllSubRegionsPlacements().stream().allMatch(region -> region.isEnabled()
                && region.getPos().equals(region.getDefaultPos())
                && region.getRotation() == net.minecraft.world.level.block.Rotation.NONE
                && region.getMirror() == net.minecraft.world.level.block.Mirror.NONE);
    }
    private static Vector3 vector(BlockPos position) { return new Vector3(position.getX(), position.getY(), position.getZ()); }
    private static Capture failure(String reason) { return new Capture(reason, null, Map.of()); }
}
