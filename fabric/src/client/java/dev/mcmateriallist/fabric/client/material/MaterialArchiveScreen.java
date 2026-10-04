package dev.mcmateriallist.fabric.client.material;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;

/** Removed definitions are read-only and remain in the durable dataset. */
public final class MaterialArchiveScreen extends GuiBase {
    private final MaterialWorkSession session;
    private int offset;
    public MaterialArchiveScreen(GuiBase parent, MaterialWorkSession session) { setParent(parent); this.session = session; title = MaterialWorkSession.text("archived", session.dataset().archived().size()); }
    @Override public void initGui() {
        super.initGui();
        addButton(new ButtonGeneric(12, getScreenHeight() - 30, -1, 20, MaterialWorkSession.text("back")), (button, mouse) -> GuiBase.openGui(getParent()));
    }
    @Override public boolean onMouseScrolled(double x, double y, double horizontal, double vertical) {
        offset = Math.max(0, Math.min(Math.max(0, session.dataset().archived().size() - 1), offset - (int) Math.signum(vertical))); return true;
    }
    @Override protected void drawContents(GuiContext ctx, int x, int y, float ticks) {
        int line = 30;
        for (var archived : session.dataset().archived().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey(java.util.Comparator.comparing(id -> id.registryId()))).skip(offset).toList()) {
            if (line + 20 > getScreenHeight() - 36) break;
            String value = archived.getKey().registryId() + " | " + MaterialPresentation.info(archived.getValue().state());
            drawString(ctx, MaterialPresentation.clamp(value, getScreenWidth() - 24), 12, line, 0xFFFFFFFF); line += 20;
        }
    }
}
