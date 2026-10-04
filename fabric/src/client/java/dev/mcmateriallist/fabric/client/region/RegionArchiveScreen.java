package dev.mcmateriallist.fabric.client.region;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;

/** Removed definitions are read-only and remain in the durable dataset. */
public final class RegionArchiveScreen extends GuiBase {
    private final RegionWorkSession session;
    private int offset;
    public RegionArchiveScreen(GuiBase parent, RegionWorkSession session) { setParent(parent); this.session = session; title = RegionWorkSession.text("archived", session.dataset().archived().size()); }
    @Override public void initGui() {
        super.initGui();
        addButton(new ButtonGeneric(12, getScreenHeight() - 30, -1, 20, RegionWorkSession.text("back")), (button, mouse) -> GuiBase.openGui(getParent()));
    }
    @Override public boolean onMouseScrolled(double x, double y, double horizontal, double vertical) {
        offset = Math.max(0, Math.min(Math.max(0, session.dataset().archived().size() - 1), offset - (int) Math.signum(vertical))); return true;
    }
    @Override protected void drawContents(GuiContext ctx, int x, int y, float ticks) {
        int line = 30;
        for (var archived : session.dataset().archived().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey(java.util.Comparator.comparing(id -> id.toString()))).skip(offset).toList()) {
            if (line + 20 > getScreenHeight() - 36) break;
            var descriptor = archived.getValue().definition().descriptor();
            String value = descriptor.key() + " | " + descriptor.relativeOrigin() + " | " + descriptor.size() + " | " + RegionPresentation.info(archived.getValue().state());
            drawString(ctx, dev.mcmateriallist.fabric.client.material.MaterialPresentation.clamp(value, getScreenWidth() - 24), 12, line, 0xFFFFFFFF);
            if (GuiBase.isMouseOver(x, y, 12, line, getScreenWidth() - 24, 20)) fi.dy.masa.malilib.render.RenderUtils.drawHoverText(ctx, x, y, java.util.List.of(value));
            line += 20;
        }
    }
}
