package dev.mcmateriallist.fabric.client.material;

import dev.mcmateriallist.core.work.MaterialTaskId;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;

/** Fixed 16px head; use only existing player information, no skin lookup service. */
public final class MaterialHeadButton extends ButtonGeneric {
    private final MaterialWorkSession session;
    private final MaterialTaskId id;
    public MaterialHeadButton(int x, int y, MaterialWorkSession session, MaterialTaskId id) {
        super(x, y, 16, 16, ""); this.session = session; this.id = id; setRenderDefaultBackground(false);
    }
    @Override public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
        super.render(ctx, mouseX, mouseY, selected);
        var state = session.state(id);
        if (state == null || state.assignee() == null) {
            RenderUtils.drawRect(ctx, getX(), getY(), 16, 16, 0xFFEEEEEE);
            RenderUtils.drawRect(ctx, getX() + 3, getY() + 8, 3, 3, 0xFF333333);
            RenderUtils.drawRect(ctx, getX() + 10, getY() + 8, 3, 3, 0xFF333333);
        } else {
            var mc = Minecraft.getInstance();
            var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(state.assignee());
            var skin = info == null ? DefaultPlayerSkin.getDefaultSkin() : info.getSkin();
            ctx.blit(RenderPipelines.GUI_TEXTURED, skin.body().texturePath(), getX(), getY(), 8, 8, 16, 16, 8, 8, 64, 64);
            ctx.blit(RenderPipelines.GUI_TEXTURED, skin.body().texturePath(), getX(), getY(), 40, 8, 16, 16, 8, 8, 64, 64);
        }
        if (state != null && state.done()) mark(ctx, getX() + 1, getY() + 5, true);
    }
    public static void mark(GuiContext ctx, int x, int y, boolean check) {
        // Pixel marks are independent of font glyph coverage.
        if (check) {
            for (int i = 0; i < 4; i++) RenderUtils.drawRect(ctx, x + i, y + 4 + i, 2, 2, 0xFF22DD22);
            for (int i = 0; i < 8; i++) RenderUtils.drawRect(ctx, x + 4 + i, y + 7 - i, 2, 2, 0xFF22DD22);
        } else for (int i = 0; i < 12; i++) {
            RenderUtils.drawRect(ctx, x + i, y + i, 2, 2, 0xFFEE2222);
            RenderUtils.drawRect(ctx, x + 11 - i, y + i, 2, 2, 0xFFEE2222);
        }
    }
}
