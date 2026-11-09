package dev.mcmateriallist.fabric.test.mixin;

import dev.mcmateriallist.fabric.client.stock.StockClientNetworking;
import dev.mcmateriallist.fabric.test.Phase5GFaults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Issue #34: a server without the stock channel, as the client sees it. StockClientNetworking.available() is exactly
 * ClientPlayNetworking.canSend(stock_v1) on the current connection; answering false here takes the same "send returns null"
 * fallback branch that a server without MCMaterialList produces. Nothing is fabricated on the server side; the flag is off
 * by default and only Phase5GCoverageClientTest sets it, inside try/finally.
 */
@Mixin(value = StockClientNetworking.class, remap = false)
public abstract class StockChannelAbsence {
    @Inject(method = "available", at = @At("HEAD"), cancellable = true)
    private static void absent(CallbackInfoReturnable<Boolean> callback) {
        if (Phase5GFaults.stockChannelAbsent) callback.setReturnValue(false);
    }
}
