package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.fabric.client.litematica.Phase0Ui;
import dev.mcmateriallist.fabric.client.region.*;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.gui.widgets.WidgetListPlacementSubRegions;
import fi.dy.masa.litematica.gui.widgets.WidgetPlacementSubRegion;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.render.GuiContext;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = GuiPlacementConfiguration.class, remap = false)
public abstract class PlacementConfigurationGuiMixin extends GuiListBase<SubRegionPlacement, WidgetPlacementSubRegion, WidgetListPlacementSubRegions> implements RegionWorkScreen {
    @Shadow @Final public SchematicPlacement placement;
    @Unique private RegionWorkSession mcmateriallist$work;
    protected PlacementConfigurationGuiMixin() { super(10, 62); }
    @Override public RegionWorkSession mcmateriallist$session() {
        if (mcmateriallist$work == null) mcmateriallist$work = new RegionWorkSession((GuiPlacementConfiguration) (Object) this);
        return mcmateriallist$work;
    }
    @Inject(method = "getBrowserHeight()I", at = @At("RETURN"), cancellable = true, require = 1)
    private void reserveWork(CallbackInfoReturnable<Integer> callback) { callback.setReturnValue(Math.max(0, callback.getReturnValue() - Phase0Ui.FOOTER_HEIGHT - 24)); }
    @ModifyArg(method = "createListWidget(II)Lfi/dy/masa/litematica/gui/widgets/WidgetListPlacementSubRegions;", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/gui/widgets/WidgetListPlacementSubRegions;<init>(IIIILfi/dy/masa/litematica/gui/GuiPlacementConfiguration;)V"), index = 1, require = 1)
    private int shiftBrowser(int y) { return y + 24; }
    @Inject(method = "initGui()V", at = @At("TAIL"), require = 1)
    private void addWork(CallbackInfo callback) { Phase0Ui.addPlacementButton(this, placement); mcmateriallist$session().toolbar(); }
    @Override public void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) { super.drawContents(ctx, mouseX, mouseY, ticks); mcmateriallist$session().footer(ctx); }
}
