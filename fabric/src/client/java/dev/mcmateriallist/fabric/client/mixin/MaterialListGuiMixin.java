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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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
        callback.setReturnValue(Math.max(0, callback.getReturnValue() - (mcmateriallist$session().compact() ? 8 : 62)));
    }

    @Inject(method = "initGui()V", at = @At("TAIL"), require = 0)
    private void addPhase0Button(CallbackInfo callback) {
        Phase0Ui.addMaterialButton((GuiBase) (Object) this);
        if (mcmateriallist$session().compact()) {
            var buttons = ((GuiButtonsAccess) this).mcmateriallist$buttons();
            // The four upstream export/cache buttons keep their listeners in the footer.
            int end = buttons.stream().filter(button -> button.getY() == getScreenHeight() - 22)
                .mapToInt(button -> button.getX() + button.getWidth()).max().orElse(12);
            var diagnostic = buttons.getLast();
            diagnostic.setPosition(end + 2, getScreenHeight() - 22);
        }
        mcmateriallist$session().toolbar();
    }

    @Inject(method = "initGui()V", at = @At("HEAD"), require = 1)
    private void positionMaterialBrowser(CallbackInfo callback) {
        int y = mcmateriallist$session().compact() ? 44 : 66;
        if (getListY() != y) {
            var view = dev.mcmateriallist.fabric.client.work.WorkListViewState.capture(getListWidget());
            setListPosition(getListX(), y);
            reCreateListWidget();
            view.restore(getListWidget());
        }
    }

    @ModifyVariable(method = "initGui()V", at = @At("STORE"), ordinal = 0, require = 1)
    private boolean exportsInFooter(boolean isNarrow) { return true; }

    @Override public void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks); mcmateriallist$session().footer(ctx);
    }

    @Inject(method = "onTaskCompleted", at = @At("TAIL"), require = 1)
    private void recountWork(CallbackInfo callback) {
        // Upstream invokes this listener before setMaterialListEntries() returns.
        // Capture after its readiness flag and scheduler removal have completed.
        if (mcmateriallist$work != null) {
            var client = net.minecraft.client.Minecraft.getInstance();
            var service = dev.mcmateriallist.fabric.client.MCMaterialListClient.work();
            var level = client.level;
            var session = mcmateriallist$work;
            client.schedule(() -> {
                var active = client.gui.screen();
                if (service != null && service == dev.mcmateriallist.fabric.client.MCMaterialListClient.work()
                    && level != null && level == client.level
                    && (active == (Object) this || active instanceof dev.mcmateriallist.fabric.client.material.MaterialDetailScreen detail && detail.session() == session))
                    session.upstreamCompleted();
            });
        }
    }
}
