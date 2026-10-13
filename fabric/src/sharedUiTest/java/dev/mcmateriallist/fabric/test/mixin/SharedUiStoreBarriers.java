package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.shared.ProjectSnapshot;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.test.SharedUiDelays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hold real work before submission, or real read results before publication. Never fabricate store outcomes. */
@Mixin(value = SharedClientService.class, remap = false)
public abstract class SharedUiStoreBarriers {
    @Inject(method = "cache", at = @At("HEAD"), cancellable = true)
    private void cache(LocalPlacementId placement, DatasetKind kind, SharedClientArchive.Reference original,
            ProjectSnapshot snapshot, long cursor, CallbackInfoReturnable<CompletableFuture<SharedClientStore.Result>> callback) {
        var gate = SharedUiDelays.cache;
        if (gate != null) {
            SharedUiDelays.cache = null; SharedUiDelays.cacheHeld = true;
            callback.setReturnValue(gate.thenCompose(ignored -> ((SharedClientService) (Object) this).cache(placement, kind, original, snapshot, cursor)));
        }
    }
    @Inject(method = "select", at = @At("HEAD"), cancellable = true)
    private void select(LocalPlacementId placement, DatasetKind kind, DatasetId local, UUID actor,
            ProjectSnapshot snapshot, long cursor, CallbackInfoReturnable<CompletableFuture<SharedClientStore.Result>> callback) {
        var gate = SharedUiDelays.selection;
        if (gate != null) {
            SharedUiDelays.selection = null; SharedUiDelays.selectionHeld = true;
            callback.setReturnValue(gate.thenCompose(ignored -> ((SharedClientService) (Object) this).select(placement, kind, local, actor, snapshot, cursor)));
        }
    }
    @Inject(method = "load", at = @At("RETURN"), cancellable = true)
    private void load(CallbackInfoReturnable<CompletableFuture<SharedClientStore.Result>> callback) {
        var gate = SharedUiDelays.load;
        if (gate != null) {
            SharedUiDelays.load = null;
            callback.setReturnValue(callback.getReturnValue().thenCompose(result -> {
                SharedUiDelays.loadHeld = true; return gate.thenApply(ignored -> result);
            }));
        }
    }
}
