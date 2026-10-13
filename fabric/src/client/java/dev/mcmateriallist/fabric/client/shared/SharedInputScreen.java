package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.*;

/** Explicit project-name / supplied participant UUID input. No external identity lookup. */
public final class SharedInputScreen extends GuiBase {
    public enum Mode { CREATE, VIEWER, ASSIGN }
    private final SharedWorkBackend backend;
    private final Mode mode;
    private final TaskId task;
    private final Object original;
    private final List<ButtonGeneric> actions = new ArrayList<>();
    private GuiTextFieldMultiLine input;
    private String draft = "";
    public SharedInputScreen(GuiBase parent, SharedWorkBackend backend, Mode mode, TaskId task) {
        setParent(parent); this.backend = backend; this.mode = mode; this.task = task; original = backend.context();
        title = SharedWorkBackend.text("form." + mode.name());
    }
    @Override public void initGui() {
        if (input != null) draft = input.getValueWrapper();
        super.initGui(); actions.clear();
        input = new GuiTextFieldMultiLine.Builder().setX(12).setY(64).setWidth(Math.min(440, getScreenWidth() - 24)).setHeight(30).setBackground(true).build(font, "");
        input.setValueWrapper(draft);
        addTextFieldMultiLine(input, mode == Mode.CREATE ? 256 : 36, field -> { draft = field.getValueWrapper(); return true; });
        String[] keys = mode == Mode.CREATE ? new String[]{"create_confirm"} : mode == Mode.VIEWER ? new String[]{"grant", "remove"} : new String[]{"assign", "clear_assignment"};
        int x = 12;
        for (String key : keys) {
            var button = addButton(new ButtonGeneric(x, 104, -1, 20, SharedWorkBackend.text(key)), (source, mouse) -> submit(key));
            actions.add(button); x += button.getWidth() + 4;
        }
        addButton(new ButtonGeneric(12, getScreenHeight() - 30, -1, 20, SharedWorkBackend.text("back")), (button, mouse) -> GuiBase.openGui(getParent()));
    }
    private void submit(String key) {
        if (!Objects.equals(original, backend.context())) return;
        if (mode == Mode.CREATE) { backend.create(input.getValueWrapper().strip()); return; }
        if (key.equals("clear_assignment")) { backend.command(task, new TaskCommand.Assign(null)); return; }
        try {
            String value = input.getValueWrapper().strip();
            UUID target = UUID.fromString(value);
            if (!target.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            if (mode == Mode.VIEWER) backend.viewer(target, key.equals("grant")); else backend.command(task, new TaskCommand.Assign(target));
        } catch (IllegalArgumentException invalid) { addMessage(MessageType.ERROR, SharedWorkBackend.text("invalid_uuid")); }
    }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        boolean same = Objects.equals(original, backend.context());
        if (!same && input != null) { draft = ""; input.setValueWrapper(""); }
        actions.forEach(button -> button.setEnabled(same && !backend.pending() && (mode == Mode.CREATE || backend.writable())
            && (mode != Mode.ASSIGN || backend.state(task) != null)));
        drawString(ctx, MaterialPresentation.clamp(SharedWorkBackend.text(mode == Mode.CREATE ? "empty_work" : "uuid_help"), getScreenWidth() - 24), 12, 30, 0xFFFFFFFF);
        drawString(ctx, MaterialPresentation.clamp(SharedWorkBackend.text(mode == Mode.CREATE ? "preserve_local" : "no_lookup"), getScreenWidth() - 24), 12, 46, 0xFFAAAAAA);
        drawString(ctx, MaterialPresentation.clamp(same ? backend.feedback() : SharedWorkBackend.text("selection_changed"), getScreenWidth() - 24), 12, 136, 0xFFFFCC55);
    }
}
