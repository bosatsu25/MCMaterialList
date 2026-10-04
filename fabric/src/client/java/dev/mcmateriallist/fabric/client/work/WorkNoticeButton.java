package dev.mcmateriallist.fabric.client.work;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;

/** Native Litematica notice bubble, with the same visibility for rendering and hits. */
public final class WorkNoticeButton extends ButtonGeneric {
    public WorkNoticeButton(int x, int y) {
        super(x, y, 12, 14, "", Icons.NOTICE_EXCLAMATION_11);
        setRenderDefaultBackground(false);
        setNotice(false);
    }
    public void setNotice(boolean value) { visible = value; setEnabled(value); }
    // This native notice is one sprite, unlike the three-state button icon strip.
    @Override protected int getTextureOffset(boolean hovered) { return 0; }
    @Override public boolean isMouseOver(int mouseX, int mouseY) { return visible && super.isMouseOver(mouseX, mouseY); }
    @Override public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
        if (visible) super.render(ctx, mouseX, mouseY, selected);
    }
}
