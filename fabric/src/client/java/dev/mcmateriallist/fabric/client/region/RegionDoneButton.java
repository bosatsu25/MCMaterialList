package dev.mcmateriallist.fabric.client.region;

import dev.mcmateriallist.core.work.RegionTaskId;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;

public final class RegionDoneButton extends ButtonGeneric {
    private final RegionWorkSession session;
    private final RegionTaskId id;
    public RegionDoneButton(int x, int y, RegionWorkSession session, RegionTaskId id) { super(x, y, 20, 20, ""); this.session = session; this.id = id; }
    @Override public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
        setEnabled(session.writable(id));
        super.render(ctx, mouseX, mouseY, selected);
        var state = session.state(id);
        if (state != null) RegionHeadButton.mark(ctx, getX() + 3, getY() + 3, !state.done());
    }
}
