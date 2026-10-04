package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.test.mixin.ButtonTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiBaseTestAccess;
import dev.mcmateriallist.fabric.test.mixin.*;
import dev.mcmateriallist.fabric.client.material.*;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.core.persistence.*;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.DyeColor;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.List;

/** Real upstream-counted nineteen-material acceptance fixture. */
public final class Phase2ClientTest implements FabricClientGameTest {
    private static final Block[] BLOCKS = {Blocks.BLACKSTONE, Blocks.CONCRETE.pick(DyeColor.BLACK), Blocks.DEEPSLATE_BRICKS,
        Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.MUD,
        Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_DEEPSLATE_TILES, Blocks.CONCRETE.pick(DyeColor.GRAY),
        Blocks.CHISELED_DEEPSLATE, Blocks.WOOL.pick(DyeColor.GRAY), Blocks.STONE, Blocks.GLASS, Blocks.DIRT,
        Blocks.COBBLESTONE, Blocks.SANDSTONE, Blocks.GRANITE, Blocks.ANDESITE, Blocks.DIORITE};
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = FixtureWorlds.create(context, "Phase 2A")) {
            world.getClientLevel().waitForChunksRender();
            Path root = Path.of(System.getProperty("mcmateriallist.phase0.fixture")).resolve("phase2");
            Files.createDirectories(root);
            if (Boolean.getBoolean("mcmateriallist.phase0.restore")) { restore(context, root); return; }
            SchematicPlacement placement = context.computeOnClient(mc -> fixture(root, mc.player.blockPosition().above(5)));
            context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld().getBlockState(placement.getOrigin()).is(BLOCKS[0])
                && SchematicWorldHandler.getSchematicWorld().getBlockState(placement.getOrigin().offset(18, 0, 0)).is(BLOCKS[18]));
            var materials = context.computeOnClient(mc -> new MaterialListPlacement(placement, true));
            context.waitFor(mc -> materials.getMaterialsAll().size() == 19 && PlacementWorkAdapter.capture(placement, materials).status() == PlacementWorkAdapter.Status.OK);
            byte[] schematicBytes = Files.readAllBytes(placement.getSchematicFile());
            context.getInput().resizeWindow(1920, 1080);
            context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
            context.setScreen(() -> new GuiMaterialList(materials));
            GuiMaterialList screen = context.computeOnClient(mc -> (GuiMaterialList) mc.gui.screen());
            MaterialWorkSession session = context.computeOnClient(mc -> session(screen));
            context.waitFor(mc -> !session.pending() && session.status() == StoreStatus.MISSING);
            Path primary = primary(placement);
            check(!Files.exists(primary), "Opening untracked work wrote a snapshot");
            click(context, context.computeOnClient(mc -> button(screen, "Track materials")));
            idle(context, session);
            check(session.dataset().progress().total() == 19, "Tracking denominator differs from all upstream rows");
            var delivered = new java.util.concurrent.atomic.AtomicBoolean();
            var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
            context.runOnClient(mc -> {
                // A tick callback is outside the event loop's doRunTask, where execute() can inline.
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
                    if (!armed.compareAndSet(true, false)) return;
                    ((MaterialSessionTestAccess) (Object) session).phase2Await(java.util.concurrent.CompletableFuture.completedFuture(new StoreResult(StoreStatus.OK, session.dataset())), result -> delivered.set(true));
                    check(!delivered.get(), "Already-completed I/O callback reentered screen initialization");
                });
            });
            context.waitFor(mc -> delivered.get() && !session.pending());
            context.runOnClient(mc -> check(((GuiBaseTestAccess) screen).phase0Buttons().stream().filter(candidate -> "Refresh work".equals(ChatFormatting.stripFormatting(((ButtonTestAccess) candidate).phase0Text()))).count() == 1, "Asynchronous load duplicated toolbar"));
            context.runOnClient(mc -> {
                sortByCount(screen, materials);
                check(materials.getMaterialsAll().stream().allMatch(row -> row.getCountMissing() == row.getCountTotal() && row.getCountAvailable() == 0), "Fixture counts differ from Missing=Total, Available=0");
            });
            for (String key : List.of("deepslate_bricks", "cracked_polished_blackstone_bricks", "chiseled_deepslate", "gray_wool")) {
                var id = id(key);
                click(context, context.computeOnClient(mc -> rowButton(screen, id, MaterialDoneButton.class)));
                idle(context, session);
                check(session.state(id).done() && session.state(id).completedBy() != null, "Completion did not persist on the clicked registry identity");
            }
            check(session.dataset().progress().equals(new Progress(4, 19, 21)), "Reference progress is not 4/19 (21%)");
            var blackstone = id("blackstone");
            click(context, context.computeOnClient(mc -> rowButton(screen, blackstone, MaterialHeadButton.class)));
            context.waitForScreen(MaterialDetailScreen.class);
            GuiBase detail = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
            click(context, context.computeOnClient(mc -> button(detail, "Claim"))); idle(context, session);
            check(session.state(blackstone).assignee() != null && !session.state(blackstone).done(), "Claim implicitly completed work");
            clickNote(context, context.computeOnClient(mc -> ((MaterialDetailTestAccess) detail).phase2Note()));
            context.getInput().typeChars("採掘担当");
            context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
            context.getInput().typeChars("倉庫に保管");
            click(context, context.computeOnClient(mc -> button(detail, "Save note"))); idle(context, session);
            check(session.state(blackstone).note().equals("採掘担当\n倉庫に保管"), "Multiline UTF-8 note was not saved");
            click(context, context.computeOnClient(mc -> button(detail, "Release"))); idle(context, session);
            check(session.state(blackstone).assignee() == null && !session.state(blackstone).note().isEmpty(), "Release changed independent completion/note state");
            click(context, context.computeOnClient(mc -> button(detail, "Back")));
            context.waitForScreen(GuiMaterialList.class);
            context.runOnClient(mc -> check(row(screen, blackstone).getHeight() == 22, "Collapsed row height changed"));
            context.takeScreenshot("phase2-materials-4-of-19");
            click(context, context.computeOnClient(mc -> button(screen, "Show Info: OFF")));
            context.runOnClient(mc -> {
                var rows = ((ListTestAccess) ((GuiListTestAccess) screen).phase0List()).phase0Rows();
                for (int i = 1; i < rows.size(); i++) check(rows.get(i).getHeight() == 40 && rows.get(i).getY() == rows.get(i - 1).getY() + rows.get(i - 1).getHeight(), "Show Info row geometry: height=" + rows.get(i).getHeight() + ", delta=" + (rows.get(i).getY() - rows.get(i - 1).getY()) + ", previousHeight=" + rows.get(i - 1).getHeight());
            });
            // An expanded row still resolves the correct head and details rather than a row index.
            click(context, context.computeOnClient(mc -> rowButton(screen, id("deepslate_bricks"), MaterialHeadButton.class)));
            context.waitForScreen(MaterialDetailScreen.class);
            GuiBase doneDetail = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
            click(context, context.computeOnClient(mc -> button(doneDetail, "Release"))); idle(context, session);
            check(session.state(id("deepslate_bricks")).done() && session.state(id("deepslate_bricks")).assignee() == null && session.state(id("deepslate_bricks")).completedBy() != null, "Release erased completion metadata");
            click(context, context.computeOnClient(mc -> button(doneDetail, "Back")));
            context.takeScreenshot("phase2-materials-show-info");
            click(context, context.computeOnClient(mc -> button(screen, "Show Info: ON")));
            click(context, context.computeOnClient(mc -> button(screen, "Hide Done: OFF")));
            context.runOnClient(mc -> check(((GuiListTestAccess) screen).phase0List().getCurrentEntries().size() == 15 && session.dataset().progress().equals(new Progress(4, 19, 21)), "Hide Done changed denominator or did not filter completed rows"));
            click(context, context.computeOnClient(mc -> button(screen, "Hide Done: ON")));
            var header = context.computeOnClient(mc -> ((ListTestAccess) ((GuiListTestAccess) screen).phase0List()).phase0Rows().getFirst());
            int[] sortPoint = context.computeOnClient(mc -> new int[]{((MaterialRowTestAccess) header).phase2Column(0) + 8, header.getY() + 10});
            clickAt(context, sortPoint[0], sortPoint[1]);
            context.runOnClient(mc -> check(materials.getSortCriteria() == MaterialListBase.SortCriteria.NAME && session.dataset().progress().equals(new Progress(4, 19, 21)), "Patched header click did not sort by name or altered progress"));
            var search = context.computeOnClient(mc -> ((GuiListTestAccess) screen).phase0List().getSearchBarWidget());
            int[] searchPoint = context.computeOnClient(mc -> new int[]{search.getX() + search.getWidth() - 7, search.getY() + 7});
            clickAt(context, searchPoint[0], searchPoint[1]);
            context.getInput().typeChars("minecraft:deepslate_bricks");
            context.waitFor(mc -> ((GuiListTestAccess) screen).phase0List().getCurrentEntries().size() == 1);
            check(session.dataset().progress().equals(new Progress(4, 19, 21)), "Search altered progress denominator");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(mc -> ((GuiListTestAccess) screen).phase0List().getCurrentEntries().size() == 19);
            context.runOnClient(mc -> {
                materials.ignoreEntry(materials.getMaterialsAll().getFirst()); materials.setHideAvailable(true);
                materials.setSortCriteria(MaterialListBase.SortCriteria.NAME); ((GuiListTestAccess) screen).phase0List().refreshEntries();
            });
            check(session.dataset().progress().equals(new Progress(4, 19, 21)), "Ignore/filter/sort changed work progress");
            context.runOnClient(mc -> { materials.clearIgnored(); materials.setHideAvailable(false); sortByCount(screen, materials); });
            click(context, context.computeOnClient(mc -> rowButton(screen, id("deepslate_bricks"), MaterialDoneButton.class))); idle(context, session);
            check(!session.state(id("deepslate_bricks")).done() && session.state(id("deepslate_bricks")).completedAt() == null && session.state(id("deepslate_bricks")).assignee() == null, "Undo changed owner or retained completion metadata");
            click(context, context.computeOnClient(mc -> rowButton(screen, id("deepslate_bricks"), MaterialDoneButton.class))); idle(context, session);
            var beforeRefresh = session.dataset().states();
            click(context, context.computeOnClient(mc -> button(screen, "Refresh")));
            context.waitFor(mc -> !session.pending() && session.ready() && session.dataset().states().equals(beforeRefresh));
            check(session.dataset().progress().equals(new Progress(4, 19, 21)), "Upstream Refresh reset progress");
            verifyAdditional(context, screen, materials, session, primary);
            context.runOnClient(mc -> check(placement.isEnabled() && placement.isRenderingEnabled(), "Completion disabled placement"));
            check(!Files.exists(primary.resolveSibling("regions.json")), "Material screen created region work");
            check(Arrays.equals(schematicBytes, Files.readAllBytes(placement.getSchematicFile())), "Material UI modified .litematic bytes");
            var saved = context.computeOnClient(mc -> {
                var manager = DataManager.getSchematicPlacementManager();
                check(manager.getAllSchematicsPlacements().size() == 1, "Phase 2 fixture contains unrelated placements");
                return manager.toJson();
            });
            saved.addProperty("expectedPlacement", placement.getHashId().toString());
            Files.writeString(root.resolve("placement.json"), saved.toString());
            var current = context.computeOnClient(mc -> session((GuiMaterialList) mc.gui.screen())); idle(context, current);
            Files.writeString(root.resolve("expected-materials.json"), new DatasetJsonCodec().encode(current.dataset()));
            Files.write(root.resolve("schematic-baseline.bin"), schematicBytes);
            long generation = current.dataset().generation();
            var finalStates = current.dataset().states();
            context.setScreen(() -> new GuiMaterialList(materials));
            var reopened = context.computeOnClient(mc -> session((GuiMaterialList) mc.gui.screen())); idle(context, reopened);
            check(reopened.dataset().generation() == generation && reopened.dataset().states().equals(finalStates), "Reopening wrote/reset work");
        } catch (java.io.IOException failure) { throw new AssertionError("Phase 2 fixture failed", failure); }
    }
    private static MaterialTaskId id(String key) { return new MaterialTaskId("minecraft:" + key); }
    private static void sortByCount(GuiMaterialList screen, MaterialListPlacement materials) {
        var list = ((GuiListTestAccess) screen).phase0List();
        materials.setSortCriteria(MaterialListBase.SortCriteria.COUNT_TOTAL); list.refreshEntries();
        if (!(list.getCurrentEntries().getFirst() instanceof MaterialListEntry first) || !first.getStack().is(BLOCKS[0].asItem())) {
            materials.setSortCriteria(MaterialListBase.SortCriteria.COUNT_TOTAL); list.refreshEntries();
        }
        list.getScrollbar().setValue(0); list.refreshEntries();
    }
    private static void verifyAdditional(ClientGameTestContext context, GuiMaterialList screen, MaterialListPlacement materials, MaterialWorkSession work, Path primary) throws java.io.IOException {
        var changedId = id("deepslate_bricks");
        var original = context.computeOnClient(mc -> materials.getMaterialsAll());
        var states = work.dataset().states();
        context.runOnClient(mc -> materials.setMaterialListEntries(original.stream().map(entry -> entry.getStack().is(BLOCKS[2].asItem())
            ? new MaterialListEntry(entry.getStack(), entry.getCountTotal() + 1, entry.getCountMissing() + 1, entry.getCountMismatched(), entry.getCountAvailable()) : entry).toList()));
        context.waitFor(mc -> !work.pending() && work.dataset().definitions().get(changedId).total() == 18);
        check(work.changed(changedId) && work.dataset().states().equals(states), "Definition change lost state or notice");
        context.waitTick();
        context.runOnClient(mc -> check(((WidgetContainerTestAccess) row(screen, changedId)).phase0Children().stream().anyMatch(widget -> widget instanceof ButtonBase button && "!".equals(ChatFormatting.stripFormatting(((ButtonTestAccess) button).phase0Text()))), "Definition notice disappeared with Show Info OFF"));
        context.runOnClient(mc -> materials.setMaterialListEntries(original.stream().filter(entry -> !entry.getStack().is(BLOCKS[18].asItem())).toList()));
        context.waitFor(mc -> !work.pending() && work.dataset().archived().containsKey(id("diorite")));
        click(context, context.computeOnClient(mc -> button(screen, "Archived work (1)")));
        context.waitForScreen(MaterialArchiveScreen.class);
        var archive = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
        click(context, context.computeOnClient(mc -> button(archive, "Back")));
        context.runOnClient(mc -> materials.setMaterialListEntries(original));
        context.waitFor(mc -> !work.pending() && work.dataset().archived().isEmpty() && work.dataset().definitions().size() == 19);
        check(work.dataset().states().equals(states), "Archived definition restoration lost identity/state");
        var offline = java.util.UUID.randomUUID();
        var actor = context.computeOnClient(mc -> mc.player.getUUID());
        context.runOnClient(mc -> work.command(id("blackstone"), new TaskCommand.Assign(offline))); idle(context, work);
        click(context, context.computeOnClient(mc -> rowButton(screen, id("blackstone"), MaterialHeadButton.class)));
        context.waitForScreen(MaterialDetailScreen.class);
        var detail = context.computeOnClient(mc -> (GuiBase) mc.gui.screen()); context.waitTick();
        context.runOnClient(mc -> {
            check(!((ButtonTestAccess) button(detail, "Claim")).phase2Enabled() && !((ButtonTestAccess) button(detail, "Release")).phase2Enabled(), "Other-owner Claim/Release appeared enabled");
            check(!button(detail, "Claim").getHoverStrings().isEmpty(), "Disabled ownership action has no explanation");
        });
        context.runOnClient(mc -> work.command(id("blackstone"), new TaskCommand.Assign(actor))); idle(context, work);
        click(context, context.computeOnClient(mc -> button(detail, "Release"))); idle(context, work);
        click(context, context.computeOnClient(mc -> button(detail, "Back")));
        // Exercise retained real handlers and inspect actual upstream exports.
        Path exports = FabricLoader.getInstance().getConfigDir().resolve("litematica");
        long beforeText = countFiles(exports, "material_list", ".txt");
        click(context, context.computeOnClient(mc -> button(screen, "Write to file")));
        check(countFiles(exports, "material_list", ".txt") > beforeText, "Upstream text export did not write a file");
        long beforeRaw = countFiles(exports, "raw_material_list_simplified", ".json");
        click(context, context.computeOnClient(mc -> button(screen, "Raw Materials")));
        check(countFiles(exports, "raw_material_list_simplified", ".json") > beforeRaw, "Upstream raw export did not write a file");
        click(context, context.computeOnClient(mc -> button(screen, "Clear cache")));
        check(work.dataset().progress().equals(new Progress(4, 19, 21)), "Cache/export operations changed progress");
        // Long Japanese display text is test data; it does not identify a material.
        context.runOnClient(mc -> {
            original.stream().filter(entry -> entry.getStack().is(BLOCKS[0].asItem())).findFirst().orElseThrow().getStack().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("非常に長い日本語の素材名を表示しても担当と完了操作が重ならない".repeat(5)));
            fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry.setMaxNameLength(materials.getMaterialsAll(), materials.getMultiplier());
        });
        for (int scale : new int[]{1, 2, 3, 4}) {
            context.runOnClient(mc -> { mc.options.guiScale().set(scale); mc.resizeGui(); });
            context.waitTick();
            context.runOnClient(mc -> {
                for (var candidate : ((ListTestAccess) ((GuiListTestAccess) screen).phase0List()).phase0Rows()) {
                    if (candidate.getEntry() == null) continue;
                    var controls = ((WidgetContainerTestAccess) candidate).phase0Children();
                    var head = controls.stream().filter(MaterialHeadButton.class::isInstance).findFirst().orElseThrow();
                    var done = controls.stream().filter(MaterialDoneButton.class::isInstance).findFirst().orElseThrow();
                    check(head.getWidth() == 16 && head.getX() + 16 <= done.getX() && done.getX() + done.getWidth() <= candidate.getX() + candidate.getWidth(), "Skin fallback/long name/scale overlaps actions");
                }
                var controls = ((GuiBaseTestAccess) screen).phase0Buttons();
                for (var control : controls) if (control.getY() == 44) check(control.getX() >= 0 && control.getX() + control.getWidth() <= screen.getScreenWidth(), "Work toolbar outside scaled screen");
            });
            context.takeScreenshot("phase2-japanese-name-scale-" + scale);
        }
        context.runOnClient(mc -> { original.forEach(entry -> entry.getStack().remove(net.minecraft.core.component.DataComponents.CUSTOM_NAME)); mc.options.guiScale().set(2); mc.resizeGui(); sortByCount(screen, materials); });
        // An I/O failure must retain the last confirmed view, then require explicit recovery.
        var previous = work.dataset();
        byte[] damaged = "{ damaged".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(primary, damaged);
        click(context, context.computeOnClient(mc -> rowButton(screen, id("black_concrete"), MaterialDoneButton.class)));
        context.waitFor(mc -> !work.pending() && work.status() == StoreStatus.RECOVERY_REQUIRED);
        check(work.dataset().equals(previous) && !work.state(id("black_concrete")).done(), "Failed write optimistically published completion");
        check(Arrays.equals(damaged, Files.readAllBytes(primary)), "Failed write overwrote damaged snapshot");
        context.setScreen(() -> new GuiMaterialList(materials));
        var recoveryScreen = context.computeOnClient(mc -> (GuiMaterialList) mc.gui.screen());
        var recovery = context.computeOnClient(mc -> session(recoveryScreen));
        context.waitFor(mc -> !recovery.pending() && recovery.status() == StoreStatus.RECOVERY_REQUIRED);
        check(recovery.dataset() == null && !recovery.writable(), "Backup candidate was published as current work");
        click(context, context.computeOnClient(mc -> button(recoveryScreen, "Recover backup")));
        check(Arrays.equals(damaged, Files.readAllBytes(primary)), "First recovery click changed storage without confirmation");
        click(context, context.computeOnClient(mc -> button(recoveryScreen, "Confirm recovery"))); idle(context, recovery);
        check(recovery.dataset().progress().equals(new Progress(4, 19, 21)), "Explicit backup recovery lost reference progress");
        context.takeScreenshot("phase2-explicit-recovery");
    }
    private static long countFiles(Path directory, String prefix, String suffix) throws java.io.IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (var files = Files.list(directory)) { return files.filter(file -> file.getFileName().toString().startsWith(prefix) && file.getFileName().toString().endsWith(suffix)).count(); }
    }
    private static MaterialWorkSession session(GuiMaterialList screen) { return ((MaterialWorkScreen) screen).mcmateriallist$session(); }
    private static Path primary(SchematicPlacement placement) { return FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist/placements").resolve(placement.getHashId().toString()).resolve("materials.json"); }
    private static void idle(ClientGameTestContext context, MaterialWorkSession session) { context.waitFor(mc -> !session.pending() && session.status() == StoreStatus.OK); }
    private static ButtonBase button(GuiBase screen, String label) { return ((GuiBaseTestAccess) screen).phase0Buttons().stream().filter(button -> label.equals(ChatFormatting.stripFormatting(((ButtonTestAccess) button).phase0Text()))).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label)); }
    private static WidgetMaterialListEntry row(GuiMaterialList screen, MaterialTaskId id) {
        return ((ListTestAccess) ((GuiListTestAccess) screen).phase0List()).phase0Rows().stream()
            .filter(candidate -> candidate instanceof WidgetMaterialListEntry && candidate.getEntry() instanceof MaterialListEntry entry
                && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString().equals(id.registryId()))
            .map(candidate -> (WidgetMaterialListEntry) candidate).findFirst().orElseThrow(() -> new AssertionError("Expected material row not visible"));
    }
    private static ButtonBase rowButton(GuiMaterialList screen, MaterialTaskId id, Class<? extends ButtonBase> type) {
        return ((WidgetContainerTestAccess) row(screen, id)).phase0Children().stream().filter(type::isInstance).map(widget -> (ButtonBase) widget).findFirst().orElseThrow(() -> new AssertionError("Missing material control"));
    }
    private static void click(ClientGameTestContext context, WidgetBase button) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void clickAt(ClientGameTestContext context, int x, int y) {
        double[] cursor = context.computeOnClient(mc -> new double[]{x * (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), y * (double) mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void clickNote(ClientGameTestContext context, fi.dy.masa.malilib.gui.GuiTextFieldMultiLine field) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(field.getX() + 8.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (field.getY() + 8.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void restore(ClientGameTestContext context, Path root) throws java.io.IOException {
        var json = JsonParser.parseString(Files.readString(root.resolve("placement.json"))).getAsJsonObject();
        var placement = context.computeOnClient(mc -> {
            var manager = DataManager.getSchematicPlacementManager(); manager.loadFromJson(json);
            check(manager.getAllSchematicsPlacements().size() == 1, "Phase 2 restart contains unrelated placements");
            return manager.getAllSchematicsPlacements().stream().filter(candidate -> candidate.getHashId().toString().equals(json.get("expectedPlacement").getAsString())).findFirst().orElseThrow();
        });
        context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld().getBlockState(placement.getOrigin()).is(BLOCKS[0]));
        var materials = context.computeOnClient(mc -> new MaterialListPlacement(placement, true));
        context.waitFor(mc -> materials.getMaterialsAll().size() == 19 && PlacementWorkAdapter.capture(placement, materials).status() == PlacementWorkAdapter.Status.OK);
        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
        byte[] persisted = Files.readAllBytes(primary(placement));
        context.setScreen(() -> new GuiMaterialList(materials));
        var session = context.computeOnClient(mc -> session((GuiMaterialList) mc.gui.screen())); idle(context, session);
        check(new DatasetJsonCodec().decode(Files.readString(root.resolve("expected-materials.json"))).equals(session.dataset()), "Fresh process did not restore exact durable identity/state");
        check(session.dataset().progress().equals(new Progress(4, 19, 21)), "Fresh process progress differs");
        check(Arrays.equals(persisted, Files.readAllBytes(primary(placement))), "Opening restored work rewrote snapshot");
        check(Arrays.equals(Files.readAllBytes(root.resolve("schematic-baseline.bin")), Files.readAllBytes(placement.getSchematicFile())), "Restart modified schematic");
        context.takeScreenshot("phase2-materials-restart");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static SchematicPlacement fixture(Path root, BlockPos origin) throws java.io.IOException {
        var directory = Files.createTempDirectory(root, "schematic-");
        var selection = new AreaSelection();
        selection.addSubRegionBox(new Box(BlockPos.ZERO, new BlockPos(18, 18, 0), "Materials"), false);
        var schematic = LitematicaSchematic.createEmptySchematic(selection, "Phase2Test");
        var container = schematic.getSubRegionContainer("Materials");
        for (int x = 0; x < BLOCKS.length; x++) for (int y = 0; y < 19 - x; y++) container.set(x, y, 0, BLOCKS[x].defaultBlockState());
        if (!schematic.writeToFile(directory, "fixture", false)) throw new AssertionError("Phase 2 schematic write failed");
        var placement = SchematicPlacement.createFor(LitematicaSchematic.createFromFile(directory, "fixture.litematic"), origin, "Phase 2 materials", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }
}
