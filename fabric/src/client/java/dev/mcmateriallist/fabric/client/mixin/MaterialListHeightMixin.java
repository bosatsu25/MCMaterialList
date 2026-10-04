package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.material.MaterialWorkScreen;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.mcmateriallist.core.work.MaterialTaskId;
import java.util.Collection;

@Mixin(value = WidgetListMaterialList.class, remap = false)
public abstract class MaterialListHeightMixin extends WidgetListBase<MaterialListEntry, WidgetMaterialListEntry> {
    @Shadow @Final private GuiMaterialList gui;
    protected MaterialListHeightMixin() { super(0, 0, 0, 0, null); }
    @Inject(method = "getAllEntries", at = @At("RETURN"), cancellable = true, require = 1)
    private void filterCompleted(CallbackInfoReturnable<Collection<MaterialListEntry>> callback) {
        var work = ((MaterialWorkScreen) gui).mcmateriallist$session();
        if (!work.hideDone()) return;
        callback.setReturnValue(callback.getReturnValue().stream().filter(entry -> {
            var state = work.state(new MaterialTaskId(BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString()));
            return state == null || !state.done();
        }).toList());
    }
}
