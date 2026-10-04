package dev.mcmateriallist.fabric.client.mixin;

import dev.mcmateriallist.core.ui.MaterialRowLayout;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.material.*;
import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Keep upstream quantity rendering, tooltips, sorting and Ignore actions. */
@Mixin(value = WidgetMaterialListEntry.class, remap = false)
public abstract class MaterialRowMixin extends WidgetListEntrySortable<MaterialListEntry> {
    @Shadow private static int maxCountLength1;
    @Shadow private static int maxCountLength2;
    @Shadow private static int maxCountLength3;
    @Shadow @Final private WidgetListMaterialList listWidget;
    @Unique private MaterialRowLayout mcmateriallist$layout;
    @Unique private MaterialWorkSession mcmateriallist$work;
    @Unique private MaterialTaskId mcmateriallist$id;
    @Unique private MaterialHeadButton mcmateriallist$head;
    @Unique private MaterialDoneButton mcmateriallist$done;
    @Unique private ButtonGeneric mcmateriallist$notice;
    protected MaterialRowMixin() { super(0, 0, 0, 0, null, 0); }
    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void addMaterialWork(CallbackInfo callback) {
        var ignore = subWidgets.stream().filter(widget -> widget instanceof ButtonGeneric).findFirst().orElse(null);
        if (ignore == null) return;
        mcmateriallist$layout = MaterialRowLayout.fit(width, maxCountLength1, maxCountLength2, maxCountLength3, ignore.getWidth()).orElse(null);
        if (mcmateriallist$layout == null) return;
        mcmateriallist$work = ((MaterialWorkScreen) ((MaterialListWidgetAccess) listWidget).mcmateriallist$screen()).mcmateriallist$session();
        if (entry == null) return;
        mcmateriallist$id = new MaterialTaskId(BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString());
        mcmateriallist$head = addButton(new MaterialHeadButton(x + 4, y + 3, mcmateriallist$work, mcmateriallist$id), (button, mouse) -> mcmateriallist$work.detail(mcmateriallist$id, entry.getStack().getHoverName().getString()));
        mcmateriallist$done = addButton(new MaterialDoneButton(x + mcmateriallist$layout.doneX(), y + 1, mcmateriallist$work, mcmateriallist$id), (button, mouse) -> {
            var state = mcmateriallist$work.state(mcmateriallist$id);
            if (state != null) mcmateriallist$work.command(mcmateriallist$id, new TaskCommand.SetDone(!state.done()));
        });
        mcmateriallist$notice = addButton(new ButtonGeneric(x + mcmateriallist$layout.noticeX(), y + 4, 12, 14, "§e!"), (button, mouse) -> mcmateriallist$work.detail(mcmateriallist$id, entry.getStack().getHoverName().getString()));
        mcmateriallist$notice.setRenderDefaultBackground(false);
    }
    @Inject(method = "getColumnPosX", at = @At("HEAD"), cancellable = true, require = 1)
    private void workColumns(int column, CallbackInfoReturnable<Integer> callback) {
        if (mcmateriallist$layout == null) return;
        callback.setReturnValue(x + switch (column) {
            case 0 -> 24;
            case 1 -> mcmateriallist$layout.totalX();
            case 2 -> mcmateriallist$layout.missingX();
            case 3 -> mcmateriallist$layout.availableX();
            case 4 -> mcmateriallist$layout.noticeX();
            default -> 24;
        });
    }
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/gui/widgets/WidgetMaterialListEntry;drawString(Lfi/dy/masa/malilib/render/GuiContext;IIILjava/lang/String;)V", ordinal = 4), index = 4, require = 1)
    private String clampMaterialName(String name) { return mcmateriallist$layout == null ? name : MaterialPresentation.clamp(name, mcmateriallist$layout.nameWidth()); }
    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void updateNotice(GuiContext ctx, int mouseX, int mouseY, boolean selected, CallbackInfo callback) {
        if (mcmateriallist$notice == null) return;
        var state = mcmateriallist$work.state(mcmateriallist$id);
        boolean notice = state != null && (!state.note().isEmpty() || mcmateriallist$work.changed(mcmateriallist$id)) || mcmateriallist$work.status() != null
            && mcmateriallist$work.status() != dev.mcmateriallist.core.persistence.StoreStatus.OK && mcmateriallist$work.status() != dev.mcmateriallist.core.persistence.StoreStatus.MISSING;
        mcmateriallist$notice.setDisplayString(notice ? "§e!" : "");
        mcmateriallist$notice.setEnabled(notice);
    }
    @Inject(method = "render", at = @At("TAIL"), require = 1)
    private void drawWorkInfo(GuiContext ctx, int mouseX, int mouseY, boolean selected, CallbackInfo callback) {
        if (mcmateriallist$id != null && mcmateriallist$work.showInfo())
            drawString(ctx, x + 44, y + 26, 0xFFBBBBBB, MaterialPresentation.clamp(MaterialPresentation.info(mcmateriallist$work.state(mcmateriallist$id)), Math.max(0, width - 48)));
    }
    @Inject(method = "postRenderHovered", at = @At("HEAD"), cancellable = true, require = 1)
    private void workTooltip(GuiContext ctx, int mouseX, int mouseY, boolean selected, CallbackInfo callback) {
        if (mcmateriallist$id == null) return;
        String text = null;
        var state = mcmateriallist$work.state(mcmateriallist$id);
        if (mcmateriallist$head.isMouseOver(mouseX, mouseY)) text = MaterialPresentation.info(state);
        else if (mcmateriallist$done.isMouseOver(mouseX, mouseY)) text = mcmateriallist$work.writable() ? MaterialWorkSession.text(state != null && state.done() ? "undo" : "done") : mcmateriallist$work.feedback();
        else if (mcmateriallist$notice.isMouseOver(mouseX, mouseY)) text = MaterialPresentation.info(state) + " | " + mcmateriallist$work.feedback() + (mcmateriallist$work.changed(mcmateriallist$id) ? " | " + MaterialWorkSession.text("definition_changed") : "");
        if (text != null) { ctx.renderTooltip(textRenderer, Component.literal(text), mouseX, mouseY); callback.cancel(); }
    }
}
