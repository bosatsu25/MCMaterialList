package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.region.RegionWorkScreen;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.gui.widgets.WidgetListPlacementSubRegions;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import java.util.Collection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WidgetListPlacementSubRegions.class, remap = false)
public abstract class RegionListMixin {
    @Shadow @Final private GuiPlacementConfiguration parent;
    @Inject(method = "getAllEntries", at = @At("RETURN"), cancellable = true, require = 1)
    private void hideCompleted(CallbackInfoReturnable<Collection<SubRegionPlacement>> callback) {
        var work = ((RegionWorkScreen) parent).mcmateriallist$session();
        if (work.hideDone()) callback.setReturnValue(callback.getReturnValue().stream().filter(entry -> {
            var state = work.state(work.id(entry.getName())); return state == null || !state.done();
        }).toList());
    }
}
