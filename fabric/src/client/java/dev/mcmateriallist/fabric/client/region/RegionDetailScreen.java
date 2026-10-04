package dev.mcmateriallist.fabric.client.region;

import dev.mcmateriallist.core.work.RegionTaskId;
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
public final class RegionDetailScreen extends GuiBase {
    private final RegionWorkSession session;
    private final RegionTaskId id;
    private final List<ButtonGeneric> actions = new ArrayList<>();
    private GuiTextFieldMultiLine note;
    private String draft;
    private ButtonGeneric accept;
    public RegionDetailScreen(GuiBase parent, RegionWorkSession session, RegionTaskId id, String name) {
        setParent(parent); this.session = session; this.id = id; title = name;
        var state = session.state(id); draft = state == null ? "" : state.note();
    }
    public RegionWorkSession session() { return session; }
    public void refreshSavedState() { initGui(); }
    @Override public void initGui() {
        if (note != null) draft = note.getValueWrapper();
        super.initGui(); actions.clear();
        accept = null;
        int width = Math.max(80, Math.min(460, getScreenWidth() - 24));
        note = new GuiTextFieldMultiLine.Builder().setX(12).setY(112).setWidth(width).setHeight(Math.max(32, Math.min(90, getScreenHeight() - 180))).setBackground(true).setScrollbar(true).build(font, "");
        // Builder's string is a message/hint, not an initial editable value.
        note.setValueWrapper(draft);
        addTextFieldMultiLine(note, TextLimits.NOTE_BYTES, field -> { draft = field.getValueWrapper(); return true; });
        int x = 12;
        for (String key : session.review(id) ? new String[]{} : new String[]{"claim", "release", "save_note"}) {
            var button = addButton(new ButtonGeneric(x, 80, -1, 20, RegionWorkSession.text(key)), (source, mouse) -> {
                if (key.equals("save_note")) {
                    String value = note.getValueWrapper();
                    if (!TextLimits.valid(value, TextLimits.NOTE_BYTES)) { addMessage(MessageType.ERROR, RegionWorkSession.text("note_limit")); return; }
                    session.command(id, new TaskCommand.SetNote(value));
                } else session.command(id, key.equals("claim") ? new TaskCommand.Claim() : new TaskCommand.Release());
            }); actions.add(button); x += button.getWidth() + 4;
        }
        if (session.review(id)) {
            accept = addButton(new ButtonGeneric(12, 80, -1, 20, RegionWorkSession.text("accept_definition")), (button, mouse) -> session.acknowledge(id));
            var descriptor = session.dataset().definitions().get(id).descriptor();
            accept.setHoverStrings(RegionWorkSession.text("review_explanation"), descriptor.key() + " | " + descriptor.relativeOrigin() + " | " + descriptor.size());
        }
        addButton(new ButtonGeneric(12, getScreenHeight() - 30, -1, 20, RegionWorkSession.text("back")), (button, mouse) -> GuiBase.openGui(getParent()));
    }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        var state = session.state(id);
        var actor = mc.player == null ? null : mc.player.getUUID();
        if (accept != null) accept.setEnabled(session.writable() && session.review(id));
        for (int i = 0; i < actions.size(); i++) {
            boolean allowed = state != null && actor != null && (i == 0 ? state.assignee() == null || actor.equals(state.assignee())
                : i == 1 ? actor.equals(state.assignee()) : true);
            actions.get(i).setEnabled(session.writable(id) && allowed);
            actions.get(i).setHoverStrings(allowed ? session.feedback() : RegionWorkSession.text("ownership_required"));
        }
        drawString(ctx, dev.mcmateriallist.fabric.client.material.MaterialPresentation.clamp(RegionWorkSession.text("assignee", dev.mcmateriallist.fabric.client.material.MaterialWorkSession.playerName(state == null ? null : state.assignee())), getScreenWidth() - 24), 12, 30, 0xFFFFFFFF);
        drawString(ctx, dev.mcmateriallist.fabric.client.material.MaterialPresentation.clamp(state != null && state.done() ? RegionWorkSession.text("completed", dev.mcmateriallist.fabric.client.material.MaterialWorkSession.playerName(state.completedBy()), state.completedAt().toString()) : RegionWorkSession.text("incomplete"), getScreenWidth() - 24), 12, 46, 0xFFFFFFFF);
        drawString(ctx, session.review(id) ? RegionWorkSession.text("definition_changed") : session.feedback(), 12, 62, 0xFFFFCC55);
        drawString(ctx, RegionWorkSession.text("note_limit"), 12, note.getY() + Math.max(32, Math.min(90, getScreenHeight() - 180)) + 4, 0xFFAAAAAA);
    }
}
