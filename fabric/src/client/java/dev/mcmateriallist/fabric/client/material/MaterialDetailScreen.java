package dev.mcmateriallist.fabric.client.material;

import dev.mcmateriallist.core.work.MaterialTaskId;
import dev.mcmateriallist.core.work.TaskCommand;
import dev.mcmateriallist.core.work.TextLimits;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;

/** Local task details. Closing or changing ownership does not implicitly save the note. */
public final class MaterialDetailScreen extends GuiBase {
    private final MaterialWorkSession session;
    private final MaterialTaskId id;
    private final List<ButtonGeneric> actions = new ArrayList<>();
    private GuiTextFieldMultiLine note;
    private String draft;
    public MaterialDetailScreen(GuiBase parent, MaterialWorkSession session, MaterialTaskId id, String name) {
        setParent(parent); this.session = session; this.id = id; title = name;
        var state = session.state(id); draft = state == null ? "" : state.note();
    }
    public MaterialWorkSession session() { return session; }
    public void refreshSavedState() { initGui(); }
    @Override public void initGui() {
        if (note != null) draft = note.getValueWrapper();
        super.initGui(); actions.clear();
        int width = Math.max(80, Math.min(460, getScreenWidth() - 24));
        note = new GuiTextFieldMultiLine.Builder().setX(12).setY(112).setWidth(width).setHeight(Math.max(32, Math.min(90, getScreenHeight() - 180))).setBackground(true).setScrollbar(true).build(font, draft);
        addTextFieldMultiLine(note, TextLimits.NOTE_BYTES, field -> { draft = field.getValueWrapper(); return true; });
        int x = 12;
        for (String key : new String[]{"claim", "release", "save_note"}) {
            var button = addButton(new ButtonGeneric(x, 80, -1, 20, MaterialWorkSession.text(key)), (source, mouse) -> {
                if (key.equals("save_note")) {
                    String value = note.getValueWrapper();
                    if (!TextLimits.valid(value, TextLimits.NOTE_BYTES)) { addMessage(MessageType.ERROR, MaterialWorkSession.text("note_limit")); return; }
                    session.command(id, new TaskCommand.SetNote(value));
                } else session.command(id, key.equals("claim") ? new TaskCommand.Claim() : new TaskCommand.Release());
            }); actions.add(button); x += button.getWidth() + 4;
        }
        addButton(new ButtonGeneric(12, getScreenHeight() - 30, -1, 20, MaterialWorkSession.text("back")), (button, mouse) -> GuiBase.openGui(getParent()));
    }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        var state = session.state(id);
        var actor = mc.player == null ? null : mc.player.getUUID();
        for (int i = 0; i < actions.size(); i++) {
            boolean allowed = state != null && actor != null && (i == 0 ? state.assignee() == null || actor.equals(state.assignee())
                : i == 1 ? actor.equals(state.assignee()) : true);
            actions.get(i).setEnabled(session.writable() && allowed);
            actions.get(i).setHoverStrings(allowed ? session.feedback() : MaterialWorkSession.text("ownership_required"));
        }
        drawString(ctx, MaterialPresentation.clamp(MaterialWorkSession.text("assignee", MaterialWorkSession.playerName(state == null ? null : state.assignee())), getScreenWidth() - 24), 12, 30, 0xFFFFFFFF);
        drawString(ctx, MaterialPresentation.clamp(state != null && state.done() ? MaterialWorkSession.text("completed", MaterialWorkSession.playerName(state.completedBy()), state.completedAt().toString()) : MaterialWorkSession.text("incomplete"), getScreenWidth() - 24), 12, 46, 0xFFFFFFFF);
        drawString(ctx, session.changed(id) ? MaterialWorkSession.text("definition_changed") : session.feedback(), 12, 62, 0xFFFFCC55);
        drawString(ctx, MaterialWorkSession.text("note_limit"), 12, note.getY() + Math.max(32, Math.min(90, getScreenHeight() - 180)) + 4, 0xFFAAAAAA);
    }
}
