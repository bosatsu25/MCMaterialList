package dev.mcmateriallist.fabric.client.material;

import dev.mcmateriallist.core.work.MaterialTaskId;
import dev.mcmateriallist.core.work.TaskCommand;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;

public final class MaterialDoneButton extends ButtonGeneric {
    private final MaterialWorkSession session;
    private final MaterialTaskId id;
    public MaterialDoneButton(int x, int y, MaterialWorkSession session, MaterialTaskId id) {
        super(x, y, 20, 20, ""); this.session = session; this.id = id;
        setEnabled(allowed());
    }
    @Override public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
        setEnabled(allowed());
        super.render(ctx, mouseX, mouseY, selected);
        var state = session.state(id);
        if (state != null) MaterialHeadButton.mark(ctx, getX() + 3, getY() + 3, !state.done());
    }
    private boolean allowed() {
        var row = session.state(id);
        boolean allowed = row != null && session.allowed(id, new TaskCommand.SetDone(!row.done()));
        setHoverStrings(!allowed && session.shared().selected()
            ? session.shared().taskFeedback(id, row == null ? null : new TaskCommand.SetDone(!row.done())) : session.feedback());
        return allowed;
    }
}
