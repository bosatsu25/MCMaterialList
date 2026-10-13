package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.shared.SharedSchematicRevision;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicMetadata.class, remap = false)
public abstract class SharedSchematicRevisionMixin implements SharedSchematicRevision {
    @Unique private long mcmateriallist$editRevision;
    @Override public long mcmateriallist$editRevision() { return mcmateriallist$editRevision; }
    @Inject(method = "setModifiedSinceSaved", at = @At("HEAD"), require = 1)
    private void changed(CallbackInfo callback) { mcmateriallist$editRevision = Math.incrementExact(mcmateriallist$editRevision); }
}
