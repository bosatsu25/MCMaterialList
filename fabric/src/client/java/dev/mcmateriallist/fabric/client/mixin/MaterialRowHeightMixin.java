package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.material.MaterialWorkScreen;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Inject the erased upstream method, so row creation and space checks share the height. */
@Mixin(value = WidgetListBase.class, remap = false)
public abstract class MaterialRowHeightMixin {
    @Inject(method = "getBrowserEntryHeightFor(Ljava/lang/Object;)I", at = @At("HEAD"), cancellable = true, require = 1)
    private void expandedMaterialHeight(Object entry, CallbackInfoReturnable<Integer> callback) {
        if ((Object) this instanceof WidgetListMaterialList list && entry instanceof MaterialListEntry
            && ((MaterialWorkScreen) ((MaterialListWidgetAccess) list).mcmateriallist$screen()).mcmateriallist$session().showInfo()) callback.setReturnValue(40);
        if ((Object) this instanceof fi.dy.masa.litematica.gui.widgets.WidgetListPlacementSubRegions regions
            && entry instanceof fi.dy.masa.litematica.schematic.placement.SubRegionPlacement
            && ((dev.mcmateriallist.fabric.client.region.RegionWorkScreen) regions.getParentGui()).mcmateriallist$session().showInfo()) callback.setReturnValue(40);
    }
}
