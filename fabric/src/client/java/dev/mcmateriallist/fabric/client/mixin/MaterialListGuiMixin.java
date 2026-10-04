package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.litematica.Phase0Ui;
import dev.mcmateriallist.fabric.client.material.MaterialWorkScreen;
import dev.mcmateriallist.fabric.client.material.MaterialWorkSession;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.gui.GuiBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "fi.dy.masa.litematica.gui.GuiMaterialList", remap = false)
public abstract class MaterialListGuiMixin extends GuiListBase<MaterialListEntry, WidgetMaterialListEntry, WidgetListMaterialList> implements MaterialWorkScreen {
    protected MaterialListGuiMixin() { super(10, 44); }
    @Unique private MaterialWorkSession mcmateriallist$work;
    @Override public MaterialWorkSession mcmateriallist$session() {
        if (mcmateriallist$work == null) mcmateriallist$work = new MaterialWorkSession((GuiMaterialList) (Object) this);
        return mcmateriallist$work;
    }
    @Inject(method = "getBrowserHeight()I", at = @At("RETURN"), cancellable = true, require = 0)
    private void reserveFooter(CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(Math.max(0, callback.getReturnValue() - Phase0Ui.FOOTER_HEIGHT - 22));
    }

    @Inject(method = "initGui()V", at = @At("TAIL"), require = 0)
    private void addPhase0Button(CallbackInfo callback) {
        Phase0Ui.addMaterialButton((GuiBase) (Object) this);
        mcmateriallist$session().toolbar();
    }

    @ModifyArg(method = "createListWidget(II)Lfi/dy/masa/litematica/gui/widgets/WidgetListMaterialList;", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/gui/widgets/WidgetListMaterialList;<init>(IIIILfi/dy/masa/litematica/gui/GuiMaterialList;)V"), index = 1, require = 1)
    private int shiftMaterialBrowser(int y) { return y + 22; }

    @Override public void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks); mcmateriallist$session().footer(ctx);
    }

    @Inject(method = "onTaskCompleted", at = @At("TAIL"), require = 1)
    private void recountWork(CallbackInfo callback) { if (mcmateriallist$work != null) mcmateriallist$work.upstreamCompleted(); }
}
