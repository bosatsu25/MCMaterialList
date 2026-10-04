package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;
import dev.mcmateriallist.fabric.client.region.*;
import fi.dy.masa.litematica.gui.widgets.WidgetPlacementSubRegion;
import fi.dy.masa.litematica.gui.widgets.WidgetListPlacementSubRegions;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Extend pinned row geometry while retaining all upstream handlers and selection. */
@Mixin(value = WidgetPlacementSubRegion.class, remap = false)
public abstract class RegionRowMixin extends WidgetListEntryBase<SubRegionPlacement> {
    @Shadow @Final private WidgetListPlacementSubRegions parent;
    @Shadow @Final private SubRegionPlacement placement;
    @Shadow @Final private int buttonsStartX;
    @Unique private RegionWorkSession mcmateriallist$work;
    @Unique private RegionTaskId mcmateriallist$id;
    @Unique private RegionHeadButton mcmateriallist$head;
    @Unique private RegionDoneButton mcmateriallist$done;
    @Unique private ButtonGeneric mcmateriallist$notice;
    protected RegionRowMixin() { super(0, 0, 0, 0, null, 0); }
    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void addWork(CallbackInfo callback) {
        mcmateriallist$work = ((RegionWorkScreen) parent.getParentGui()).mcmateriallist$session();
        mcmateriallist$id = mcmateriallist$work.id(placement.getName());
        mcmateriallist$head = addButton(new RegionHeadButton(x + 3, y + 3, mcmateriallist$work, mcmateriallist$id), (button, mouse) -> mcmateriallist$work.detail(mcmateriallist$id, placement.getName()));
        mcmateriallist$done = addButton(new RegionDoneButton(buttonsStartX - 21, y + 1, mcmateriallist$work, mcmateriallist$id), (button, mouse) -> {
            var state = mcmateriallist$work.state(mcmateriallist$id);
            if (state != null) mcmateriallist$work.command(mcmateriallist$id, new TaskCommand.SetDone(!state.done()));
        });
        mcmateriallist$notice = addButton(new ButtonGeneric(buttonsStartX - 35, y + 4, 12, 14, ""), (button, mouse) -> mcmateriallist$work.detail(mcmateriallist$id, placement.getName()));
        mcmateriallist$notice.setRenderDefaultBackground(false);
    }
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/gui/widgets/WidgetPlacementSubRegion;drawString(Lfi/dy/masa/malilib/render/GuiContext;IIILjava/lang/String;)V"), index = 1, require = 1)
    private int nameX(int original) { return x + 40; }
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/gui/widgets/WidgetPlacementSubRegion;drawString(Lfi/dy/masa/malilib/render/GuiContext;IIILjava/lang/String;)V"), index = 4, require = 1)
    private String nameWidth(String name) { return MaterialPresentation.clamp(name, Math.max(0, buttonsStartX - 38 - (x + 40))); }
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/gui/Icons;renderAt(Lfi/dy/masa/malilib/render/GuiContext;IIFZZ)V", ordinal = 0), index = 1, require = 1)
    private int iconX(int original) { return x + 22; }
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/placement/SubRegionPlacement;isRegionPlacementModifiedFromDefault()Z"), require = 1)
    private boolean combinedNotice(SubRegionPlacement region) { return false; }
    @Unique private String mcmateriallist$reasons() {
        String value = "";
        if (placement.isRegionPlacementModifiedFromDefault()) value = fi.dy.masa.malilib.util.StringUtils.translate("litematica.hud.schematic_placement.hover_info.placement_sub_region_modified");
        var state = mcmateriallist$work.state(mcmateriallist$id);
        if (state != null && !state.note().isEmpty()) value += (value.isEmpty() ? "" : " | ") + RegionPresentation.info(state);
        if (mcmateriallist$work.review(mcmateriallist$id)) value += (value.isEmpty() ? "" : " | ") + RegionWorkSession.text("definition_changed");
        return value;
    }
    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void notice(GuiContext ctx, int mouseX, int mouseY, boolean selected, CallbackInfo callback) {
        if (mcmateriallist$notice == null) return;
        String reasons = mcmateriallist$reasons();
        mcmateriallist$notice.setDisplayString(reasons.isEmpty() ? "" : "§e!");
        mcmateriallist$notice.setEnabled(!reasons.isEmpty()); mcmateriallist$notice.setHoverStrings(reasons);
    }
    @Inject(method = "render", at = @At("TAIL"), require = 1)
    private void info(GuiContext ctx, int mouseX, int mouseY, boolean selected, CallbackInfo callback) {
        if (mcmateriallist$work.showInfo()) drawString(ctx, x + 40, y + 26, 0xFFBBBBBB, MaterialPresentation.clamp(RegionPresentation.info(mcmateriallist$work.state(mcmateriallist$id)), Math.max(0, width - 44)));
    }
    @Inject(method = "canSelectAt", at = @At("HEAD"), cancellable = true, require = 1)
    private void keepActionHits(MouseButtonEvent click, CallbackInfoReturnable<Boolean> callback) {
        if (mcmateriallist$head != null && (mcmateriallist$head.isMouseOver((int) click.x(), (int) click.y()) || mcmateriallist$done.isMouseOver((int) click.x(), (int) click.y()) || mcmateriallist$notice.isMouseOver((int) click.x(), (int) click.y()))) callback.setReturnValue(false);
    }
    @Inject(method = "postRenderHovered", at = @At("HEAD"), cancellable = true, require = 1)
    private void tooltip(GuiContext ctx, int mouseX, int mouseY, boolean selected, CallbackInfo callback) {
        if (mcmateriallist$head == null) return;
        String text = null;
        if (mcmateriallist$notice.isMouseOver(mouseX, mouseY)) text = mcmateriallist$reasons();
        else if (mcmateriallist$head.isMouseOver(mouseX, mouseY)) text = RegionPresentation.info(mcmateriallist$work.state(mcmateriallist$id));
        else if (mcmateriallist$done.isMouseOver(mouseX, mouseY)) text = mcmateriallist$work.writable(mcmateriallist$id) ? RegionWorkSession.text(mcmateriallist$work.state(mcmateriallist$id).done() ? "undo" : "done") : mcmateriallist$work.review(mcmateriallist$id) ? RegionWorkSession.text("definition_changed") : mcmateriallist$work.feedback();
        if (text != null) { ctx.renderTooltip(textRenderer, Component.literal(text), mouseX, mouseY); callback.cancel(); }
    }
}
