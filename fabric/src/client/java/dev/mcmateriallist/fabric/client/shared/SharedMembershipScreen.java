package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.shared.ProjectRole;
import dev.mcmateriallist.fabric.client.material.MaterialPresentation;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldMultiLine;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.*;

/** Bounded participant editor. Names come only from the current in-game player list. */
public final class SharedMembershipScreen extends GuiBase {
    private final SharedWorkBackend backend;
    private final Object original;
    private final List<ButtonGeneric> actions = new ArrayList<>();
    private final List<ButtonGeneric> participantRows = new ArrayList<>();
    private GuiTextFieldMultiLine input;
    private String draft = "";
    private int page;
    private boolean choosing;

    public SharedMembershipScreen(GuiBase parent, SharedWorkBackend backend) {
        setParent(parent); this.backend = backend; original = backend.context();
        title = SharedWorkBackend.text("membership");
    }
    @Override public void initGui() {
        boolean authorized = authorized();
        if (input != null && authorized) draft = input.getValueWrapper();
        if (!authorized) { draft = ""; page = 0; choosing = false; }
        title = SharedWorkBackend.text("membership");
        super.initGui(); actions.clear(); participantRows.clear();
        boolean compact = compact();
        if (!compact) choosing = false;
        int width = getScreenWidth();
        if (!compact || !choosing) {
            input = new GuiTextFieldMultiLine.Builder().setX(12).setY(compact ? 50 : 64)
                .setWidth(Math.min(440, width - 24)).setHeight(compact ? 26 : 30)
                .setBackground(true).build(font, "");
            input.setValueWrapper(draft);
            addTextFieldMultiLine(input, 36, field -> { draft = field.getValueWrapper(); return true; });
            int x = 12, index = 0;
            for (var choice : List.of(new Choice("grant_viewer", ProjectRole.VIEWER),
                    new Choice("grant_member", ProjectRole.MEMBER), new Choice("promote_admin", ProjectRole.ADMINISTRATOR),
                    new Choice("remove_participant", null))) {
                String label = SharedWorkBackend.text(choice.key());
                int buttonY = compact ? 82 : 104;
                int buttonWidth = width < 600 ? (width - 28) / 2 : -1;
                if (width < 600) { x = 12 + (index % 2) * (buttonWidth + 4); buttonY += (index / 2) * 24; }
                var button = addButton(new ButtonGeneric(x, buttonY, buttonWidth, 20,
                    width < 600 ? MaterialPresentation.clamp(label, buttonWidth - 8) : label),
                    (source, mouse) -> submit(choice.role()));
                button.setHoverStrings(label);
                button.setEnabled(authorized);
                actions.add(button);
                if (width >= 600) x += button.getWidth() + 4;
                index++;
            }
        } else {
            input = null;
        }
        if (compact) {
            if (choosing) {
                for (int i = 0; i < 5; i++) addParticipantRow(50 + i * 20, i, 5);
                addButton(new ButtonGeneric(68, getScreenHeight() - 24, 136, 20,
                    SharedWorkBackend.text("edit_participant")), (source, mouse) -> { choosing = false; initGui(); });
                addButton(new ButtonGeneric(208, getScreenHeight() - 24, width - 220, 20,
                    SharedWorkBackend.text("next")), (source, mouse) -> nextPage(5));
            } else {
                addButton(new ButtonGeneric(84, getScreenHeight() - 24, Math.min(180, width - 96), 20,
                    SharedWorkBackend.text("choose_participant")), (source, mouse) -> {
                        if (authorized()) { choosing = true; initGui(); }
                    });
            }
        } else {
            int listY = listY();
            int rows = Math.max(0, Math.min(20, (getScreenHeight() - 42 - listY) / 20));
            for (int i = 0; i < rows; i++) addParticipantRow(listY + i * 20, i, rows);
            if (rows > 0) addButton(new ButtonGeneric(110, getScreenHeight() - 30, -1, 20,
                SharedWorkBackend.text("next")), (source, mouse) -> nextPage(rows));
        }
        addButton(new ButtonGeneric(12, getScreenHeight() - (compact ? 24 : 30), -1, 20,
            SharedWorkBackend.text("back")), (source, mouse) -> GuiBase.openGui(getParent()));
    }
    private void addParticipantRow(int y, int slot, int rows) {
        // The button must not cache participant names or UUIDs: it renders before drawContents.
        var button = addButton(new ButtonGeneric(12, y, getScreenWidth() - 24, 18, ""), (source, mouse) -> {
            var ids = participants();
            int index = page * rows + slot;
            if (index < ids.size() && authorized()) {
                draft = ids.get(index).toString();
                if (input != null) input.setValueWrapper(draft);
                choosing = false;
                initGui();
            }
        });
        button.setEnabled(authorized());
        participantRows.add(button);
    }
    private void nextPage(int rows) {
        if (!authorized()) return;
        int pages = Math.max(1, (participants().size() + rows - 1) / rows);
        page = (page + 1) % pages;
    }
    private List<UUID> participants() {
        if (!authorized()) return List.of();
        var snapshot = backend.snapshot();
        if (snapshot == null) return List.of();
        Set<UUID> ids = new HashSet<>(snapshot.members().keySet());
        ids.addAll(onlinePlayers().keySet());
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }
    private Map<UUID, String> onlinePlayers() {
        Map<UUID, String> online = new HashMap<>();
        if (mc.getConnection() != null) mc.getConnection().getOnlinePlayers().forEach(info ->
            online.put(info.getProfile().id(), info.getProfile().name()));
        return online;
    }
    private boolean authorized() {
        return Objects.equals(original, backend.context()) && backend.administer();
    }
    private boolean compact() { return getScreenHeight() < 250; }
    private int listY() { return getScreenWidth() < 600 ? 178 : 150; }
    private void submit(ProjectRole role) {
        if (!authorized() || input == null) return;
        try {
            String value = input.getValueWrapper().strip(); UUID target = UUID.fromString(value);
            if (!target.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            backend.membership(target, role);
        } catch (IllegalArgumentException invalid) { addMessage(MessageType.ERROR, SharedWorkBackend.text("invalid_uuid")); }
    }
    @Override protected void drawButtons(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        boolean authorized = authorized();
        if (!authorized) {
            draft = ""; page = 0;
            if (input != null) input.setValueWrapper("");
        }
        actions.forEach(button -> button.setEnabled(authorized));
        participantRows.forEach(button -> button.setEnabled(authorized));
        super.drawButtons(ctx, mouseX, mouseY, ticks);
    }
    @Override protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float ticks) {
        super.drawContents(ctx, mouseX, mouseY, ticks);
        boolean authorized = authorized(), compact = compact();
        drawString(ctx, MaterialPresentation.clamp(SharedWorkBackend.text("current_role",
            SharedWorkBackend.text("role." + (backend.role() == null ? "NONE" : backend.role().name()))), getScreenWidth() - 24),
            12, compact ? 18 : 30, 0xFFFFFFFF);
        if (!compact || !choosing) {
            drawString(ctx, MaterialPresentation.clamp(SharedWorkBackend.text("uuid_help"), getScreenWidth() - 24),
                12, compact ? 34 : 46, 0xFFAAAAAA);
        }
        int rows = compact ? choosing ? 5 : 0 : participantRows.size();
        if (authorized && rows > 0) {
            var ids = participants();
            int pages = Math.max(1, (ids.size() + rows - 1) / rows);
            if (page >= pages) page = pages - 1;
            Map<UUID, String> online = onlinePlayers();
            for (int i = 0; i < rows; i++) {
                int index = page * rows + i;
                if (index >= ids.size()) continue;
                UUID id = ids.get(index);
                String label = SharedWorkBackend.text("participant", online.getOrDefault(id, id.toString()),
                    SharedWorkBackend.text("role." + (backend.snapshot().role(id) == null ? "NONE" : backend.snapshot().role(id).name())));
                drawString(ctx, MaterialPresentation.clamp(label, getScreenWidth() - 36), 16,
                    (compact ? 50 : listY()) + i * 20 + 5, 0xFFFFFFFF);
            }
        }
        drawString(ctx, MaterialPresentation.clamp(authorized ? backend.feedback() : SharedWorkBackend.text("join_required"),
            getScreenWidth() - 24), 12, compact ? choosing ? 34 : 130 : getScreenWidth() < 600 ? 154 : 130, 0xFFFFCC55);
    }
    private record Choice(String key, ProjectRole role) {}
}
