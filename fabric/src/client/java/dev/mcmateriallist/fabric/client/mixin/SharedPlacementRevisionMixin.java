package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.shared.SharedPlacementRevision;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicPlacement.class, remap = false)
public abstract class SharedPlacementRevisionMixin implements SharedPlacementRevision {
    @Unique private long mcmateriallist$sharedRevision;
    @Override public long mcmateriallist$sharedRevision() { return mcmateriallist$sharedRevision; }
    @Inject(method = {"onModified(Lfi/dy/masa/litematica/schematic/placement/SchematicPlacementManager;)V",
        "onModified(Ljava/lang/String;Lfi/dy/masa/litematica/schematic/placement/SchematicPlacementManager;)V"}, at = @At("HEAD"), require = 1)
    private void changed(CallbackInfo callback) { mcmateriallist$sharedRevision = Math.incrementExact(mcmateriallist$sharedRevision); }
}
