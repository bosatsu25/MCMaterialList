package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.core.shared.client.SchematicSourceDigest;
import dev.mcmateriallist.fabric.client.shared.SharedLoadedSource;
import dev.mcmateriallist.fabric.client.shared.SharedSchematicRevision;
import dev.mcmateriallist.fabric.client.shared.SharedSourceLoading;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class SharedLoadedSourceMixin implements SharedLoadedSource {
    @Unique private SchematicSourceDigest.Stamp mcmateriallist$loading;
    @Unique private SharedSourceLoading.Candidate mcmateriallist$candidate;
    @Unique private Proof mcmateriallist$loaded;
    @Unique private long mcmateriallist$generation;
    @Unique private final Object mcmateriallist$instance = new Object();
    @Override public Proof mcmateriallist$loadedSource() { return mcmateriallist$loaded; }
    @Inject(method = "readFromFile(Lfi/dy/masa/litematica/util/FileType;)Z", at = @At("HEAD"), require = 1)
    private void beforeRead(CallbackInfoReturnable<Boolean> callback) {
        mcmateriallist$loaded = null;
        mcmateriallist$loading = SchematicSourceDigest.stamp(((LitematicaSchematic) (Object) this).getFile());
        mcmateriallist$candidate = SharedSourceLoading.begin(mcmateriallist$loading);
        mcmateriallist$generation = Math.incrementExact(mcmateriallist$generation);
    }
    @Inject(method = "readFromFile(Lfi/dy/masa/litematica/util/FileType;)Z", at = @At("RETURN"), require = 1)
    private void afterRead(CallbackInfoReturnable<Boolean> callback) {
        var candidate = mcmateriallist$loading; mcmateriallist$loading = null;
        var digest = SharedSourceLoading.finish(mcmateriallist$candidate, callback.getReturnValueZ());
        mcmateriallist$candidate = null;
        var schematic = (LitematicaSchematic) (Object) this;
        if (callback.getReturnValueZ() && candidate != null && candidate.current()
            && schematic.getMetadata() instanceof SharedSchematicRevision revision)
            mcmateriallist$loaded = new Proof(candidate, mcmateriallist$instance, mcmateriallist$generation,
                revision.mcmateriallist$editRevision(), schematic.getMetadata().wasModifiedSinceSaved(), digest);
    }
    @Redirect(method = "readNbtFromFile", at = @At(value = "INVOKE",
        target = "Lfi/dy/masa/malilib/util/nbt/NbtUtils;readNbtFromFile(Ljava/nio/file/Path;)Lnet/minecraft/nbt/CompoundTag;"), require = 1)
    private static net.minecraft.nbt.CompoundTag readObserved(java.nio.file.Path path) { return SharedSourceLoading.read(path); }
}
