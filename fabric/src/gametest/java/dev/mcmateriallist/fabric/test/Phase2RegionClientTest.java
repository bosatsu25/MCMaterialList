package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.fabric.test.mixin.*;
import dev.mcmateriallist.fabric.client.region.*;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.core.persistence.*;
import dev.mcmateriallist.core.work.*;
import fi.dy.masa.litematica.gui.widgets.WidgetPlacementSubRegion;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;
import java.util.Arrays;
import java.util.List;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;

/** Real 62-region fixture; all names and identities are explicit synthetic test data. */
public final class Phase2RegionClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = FixtureWorlds.create(context, "Phase 2B")) {
            world.getClientLevel().waitForChunksRender();
            Path root = Path.of(System.getProperty("mcmateriallist.phase0.fixture")).resolve("phase2-region");
            Files.createDirectories(root);
            if (Boolean.getBoolean("mcmateriallist.phase0.restore")) { restore(context, root); return; }
            var placement = context.computeOnClient(mc -> fixture(root, mc.player.blockPosition().above(5)));
            context.getInput().resizeWindow(1920, 1080);
            context.runOnClient(mc -> { mc.options.guiScale().set(3); mc.resizeGui(); });
            context.setScreen(() -> new GuiPlacementConfiguration(placement));
            var screen = context.computeOnClient(mc -> (GuiPlacementConfiguration) mc.gui.screen());
            var work = context.computeOnClient(mc -> session(screen));
            context.waitFor(mc -> !work.pending() && work.status() == StoreStatus.MISSING);
            Path primary = primary(placement);
            byte[] schematicBytes = Files.readAllBytes(placement.getSchematicFile());
            check(!Files.exists(primary) && !Files.exists(primary.resolveSibling("materials.json")), "Opening region UI wrote work");
            context.runOnClient(mc -> check(PlacementWorkAdapter.captureRegions(placement).status() == PlacementWorkAdapter.Status.OK, "Region capture incorrectly requires materials"));
            click(context, context.computeOnClient(mc -> button(screen, "Track regions"))); idle(context, work);
            context.runOnClient(mc -> {
                var list = (ListTestAccess) ((GuiListTestAccess) screen).phase0List();
                check(mc.getWindow().getGuiScaledWidth() == 640 && mc.getWindow().getGuiScaledHeight() == 360, "Region baseline must be 1920x1080 GUI3");
                check(list.phase0Y() == 62, "Region work toolbar still consumes a baseline list row");
                check(button(screen, "Hide Done: OFF").getY() == 44 && button(screen, "Show Info: OFF").getY() == 44, "Region filters do not share the baseline header");
                UiParityEvidence.controls(screen);
                var ordered = ((GuiListTestAccess) screen).phase0List().getCurrentEntries();
                for (int i = 0; i < 11; i++) check(ordered.get(i) instanceof fi.dy.masa.litematica.schematic.placement.SubRegionPlacement region && region.getName().equals("Region " + (i + 1)), "Reference natural region order differs");
            });
            var delivered = new java.util.concurrent.atomic.AtomicBoolean();
            var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
            context.runOnClient(mc -> net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (!armed.compareAndSet(true, false)) return;
                ((RegionSessionTestAccess) (Object) work).phase2Await(java.util.concurrent.CompletableFuture.completedFuture(new StoreResult(StoreStatus.OK, work.dataset())), result -> delivered.set(true));
                check(!delivered.get(), "Region callback reentered initGui");
            }));
            context.waitFor(mc -> delivered.get() && !work.pending());
            context.runOnClient(mc -> check(((GuiBaseTestAccess) screen).phase0Buttons().stream().filter(candidate -> "Refresh work".equals(ChatFormatting.stripFormatting(((ButtonTestAccess) candidate).phase0Text()))).count() == 1, "Region callback duplicated toolbar"));
            check(!Files.exists(primary.resolveSibling("materials.json")), "Region tracking initialized materials");
            for (int region : new int[]{2, 6, 8, 9, 11}) {
                click(context, context.computeOnClient(mc -> rowButton(screen, "Region " + region, RegionDoneButton.class))); idle(context, work);
                check(work.state(work.id("Region " + region)).done(), "Clicked completion missed identity");
            }
            check(work.dataset().progress().equals(new Progress(5, 62, 8)), "Reference progress differs");
            check(placement.getAllSubRegionsPlacements().stream().allMatch(p -> p.isEnabled()), "Completion changed enabled flags");
            UiParityEvidence.capture(context, screen, "phase2-regions-5-of-62");
            UiParityEvidence.color(context, "phase2-regions-5-of-62", 120, 343, 220, 9, 0xFF5555);
            UiParityEvidence.color(context, "phase2-regions-5-of-62", 120, 343, 40, 9, 0xFFFFFF);
            verifyReferenceMarks(context, screen);
            click(context, context.computeOnClient(mc -> rowButton(screen, "Region 1", RegionHeadButton.class)));
            context.waitForScreen(RegionDetailScreen.class);
            var detail = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
            click(context, context.computeOnClient(mc -> button(detail, "Claim"))); idle(context, work);
            clickNote(context, context.computeOnClient(mc -> ((RegionDetailTestAccess) detail).phase2Note()));
            context.getInput().typeChars("建築担当"); context.getInput().pressKey(GLFW.GLFW_KEY_ENTER); context.getInput().typeChars("屋根を確認");
            click(context, context.computeOnClient(mc -> button(detail, "Claim"))); idle(context, work);
            String draft = context.computeOnClient(mc -> ((RegionDetailTestAccess) detail).phase2Note().getValueWrapper());
            check(work.state(work.id("Region 1")).note().isEmpty(), "Claim implicitly saved note draft");
            check(draft.equals("建築担当\n屋根を確認"), "Claim discarded note draft; observed UTF-8 bytes=" + draft.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            click(context, context.computeOnClient(mc -> button(detail, "Save note"))); idle(context, work);
            check(work.state(work.id("Region 1")).note().equals("建築担当\n屋根を確認"), "Japanese multiline note differs");
            click(context, context.computeOnClient(mc -> button(detail, "Release"))); idle(context, work);
            check(work.state(work.id("Region 1")).assignee() == null && !work.state(work.id("Region 1")).note().isEmpty(), "Release changed note");
            click(context, context.computeOnClient(mc -> button(detail, "Back")));
            click(context, context.computeOnClient(mc -> rowButton(screen, "Region 2", RegionDoneButton.class))); idle(context, work);
            check(!work.state(work.id("Region 2")).done() && work.state(work.id("Region 2")).completedAt() == null, "Undo retained completion metadata");
            click(context, context.computeOnClient(mc -> rowButton(screen, "Region 2", RegionDoneButton.class))); idle(context, work);
            click(context, context.computeOnClient(mc -> button(screen, "Hide Done: OFF")));
            check(((GuiListTestAccess) screen).phase0List().getCurrentEntries().size() == 57 && work.dataset().progress().equals(new Progress(5, 62, 8)), "Hide Done changed denominator");
            click(context, context.computeOnClient(mc -> button(screen, "Hide Done: ON")));
            var beforeScroll = work.dataset();
            UiParityEvidence.scroll(context, screen, -100);
            check(context.computeOnClient(mc -> ((GuiListTestAccess) screen).phase0List().getScrollbar().getValue()) > 0, "Real region scrolling did not move the view");
            click(context, context.computeOnClient(mc -> rowButton(screen, "Region 62", RegionHeadButton.class))); context.waitForScreen(RegionDetailScreen.class);
            check(context.computeOnClient(mc -> ((RegionDetailTestAccess) mc.gui.screen()).phase2Id()).equals(work.id("Region 62")), "Scrolled region head resolved wrong identity");
            click(context, context.computeOnClient(mc -> button((GuiBase) mc.gui.screen(), "Back")));
            UiParityEvidence.scroll(context, screen, 100);
            check(work.dataset().equals(beforeScroll), "Region scrolling/details changed exact state");
            click(context, context.computeOnClient(mc -> button(screen, "Show Info: OFF")));
            context.runOnClient(mc -> {
                var rows = ((ListTestAccess) ((GuiListTestAccess) screen).phase0List()).phase0Rows();
                for (int i = 0; i < rows.size(); i++) {
                    check(rows.get(i).getHeight() == 40, "Expanded row height differs");
                    if (i > 0) check(rows.get(i).getY() == rows.get(i - 1).getY() + 40, "Expanded row hit geometry differs");
                }
            });
            click(context, context.computeOnClient(mc -> rowButton(screen, "Region 3", RegionHeadButton.class)));
            context.waitForScreen(RegionDetailScreen.class);
            check(context.computeOnClient(mc -> ((RegionDetailTestAccess) mc.gui.screen()).phase2Id()).equals(work.id("Region 3")), "Expanded head resolved wrong row");
            click(context, context.computeOnClient(mc -> button((GuiBase) mc.gui.screen(), "Back")));
            context.takeScreenshot("phase2-regions-show-info");
            click(context, context.computeOnClient(mc -> button(screen, "Show Info: ON")));
            verifyAdditional(context, screen, work, placement, primary);
            check(Arrays.equals(schematicBytes, Files.readAllBytes(placement.getSchematicFile())), "Region UI changed schematic bytes");
            var saved = context.computeOnClient(mc -> {
                var manager = DataManager.getSchematicPlacementManager();
                check(manager.getAllSchematicsPlacements().size() == 1, "Unrelated region fixture placement");
                return manager.toJson();
            });
            saved.addProperty("expectedPlacement", placement.getHashId().toString());
            Files.writeString(root.resolve("placement.json"), saved.toString());
            Files.writeString(root.resolve("expected-regions.json"), new DatasetJsonCodec().encode(work.dataset()));
            Files.write(root.resolve("schematic-baseline.bin"), schematicBytes);
            byte[] persisted = Files.readAllBytes(primary);
            context.setScreen(() -> new GuiPlacementConfiguration(placement));
            var reopened = context.computeOnClient(mc -> session((GuiPlacementConfiguration) mc.gui.screen())); idle(context, reopened);
            check(work.dataset().equals(reopened.dataset()) && Arrays.equals(persisted, Files.readAllBytes(primary)), "Region reopen changed storage");
        } catch (java.io.IOException failure) { throw new AssertionError("Region fixture failed", failure); }
    }
    private static void verifyReferenceMarks(ClientGameTestContext context, GuiPlacementConfiguration screen) throws java.io.IOException {
        Path screenshot;
        try (var files = Files.list(FabricLoader.getInstance().getGameDir().resolve("screenshots"))) {
            screenshot = files.filter(file -> file.getFileName().toString().endsWith("phase2-regions-5-of-62.png")).findFirst().orElseThrow();
        }
        var image = javax.imageio.ImageIO.read(screenshot.toFile());
        for (int number : new int[]{2, 6, 8, 9, 11}) {
            for (boolean head : new boolean[]{true, false}) {
                int[] bounds = context.computeOnClient(mc -> {
                    var control = rowButton(screen, "Region " + number, head ? RegionHeadButton.class : RegionDoneButton.class);
                    double scaleX = image.getWidth() / (double) mc.getWindow().getGuiScaledWidth();
                    double scaleY = image.getHeight() / (double) mc.getWindow().getGuiScaledHeight();
                    return new int[]{(int) (control.getX() * scaleX), (int) (control.getY() * scaleY), (int) ((control.getX() + control.getWidth()) * scaleX), (int) ((control.getY() + control.getHeight()) * scaleY)};
                });
                int expected = head ? 0x22DD22 : 0xEE2222;
                boolean found = false;
                for (int x = bounds[0]; x < bounds[2]; x++) for (int y = bounds[1]; y < bounds[3]; y++) if ((image.getRGB(x, y) & 0xFFFFFF) == expected) found = true;
                check(found, head ? "Completed head overlay missing from actual pixels" : "Red undo action missing from actual pixels");
            }
        }
    }
    private static void verifyAdditional(ClientGameTestContext context, GuiPlacementConfiguration screen, RegionWorkSession work, SchematicPlacement placement, Path primary) throws java.io.IOException {
        var materials = context.computeOnClient(mc -> new fi.dy.masa.litematica.materials.MaterialListPlacement(placement, true));
        context.waitFor(mc -> PlacementWorkAdapter.capture(placement, materials).status() == PlacementWorkAdapter.Status.OK);
        var materialWrite = context.computeOnClient(mc -> dev.mcmateriallist.fabric.client.MCMaterialListClient.work().refreshMaterials(PlacementWorkAdapter.capture(placement, materials).snapshot()));
        context.waitFor(mc -> materialWrite.isDone());
        check(materialWrite.join().storage().ok(), "Independent material fixture failed");
        byte[] materialBytes = Files.readAllBytes(primary.resolveSibling("materials.json"));
        click(context, context.computeOnClient(mc -> rowLabel(screen, "Region 2", "Configure")));
        context.waitForScreen(fi.dy.masa.litematica.gui.GuiSubRegionConfiguration.class);
        var configuration = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
        click(context, context.computeOnClient(mc -> buttonContaining(configuration, "Rotation")));
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitForScreen(GuiPlacementConfiguration.class);
        check(placement.getRelativeSubRegionPlacement("Region 2").isRegionPlacementModifiedFromDefault(), "Configure did not modify region");
        click(context, context.computeOnClient(mc -> rowButton(screen, "Region 2", RegionHeadButton.class)));
        context.waitForScreen(RegionDetailScreen.class);
        var detail = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
        clickNote(context, context.computeOnClient(mc -> ((RegionDetailTestAccess) detail).phase2Note()));
        context.getInput().typeChars("変更と完了を確認");
        click(context, context.computeOnClient(mc -> button(detail, "Save note"))); idle(context, work);
        click(context, context.computeOnClient(mc -> button(detail, "Back"))); context.waitTick();
        String modified = context.computeOnClient(mc -> fi.dy.masa.malilib.util.StringUtils.translate("litematica.hud.schematic_placement.hover_info.placement_sub_region_modified"));
        context.runOnClient(mc -> {
            var notices = notices(screen, "Region 2");
            check(notices.size() == 1 && notices.getFirst().getHoverStrings().stream().anyMatch(value -> value.contains(modified) && value.contains("変更と完了を確認")), "AC07 combined notice missing standard/note reasons");
            check(((NativeNoticeTestAccess) notices.getFirst()).phase2Icon() == fi.dy.masa.litematica.gui.Icons.NOTICE_EXCLAMATION_11, "Region combined notice does not use native bubble");
            check(work.state(work.id("Region 2")).done(), "AC07 notice changed done");
        });
        UiParityEvidence.capture(context, screen, "phase2-regions-notice-coexist");
        click(context, context.computeOnClient(mc -> notices(screen, "Region 2").getFirst()));
        context.waitForScreen(RegionDetailScreen.class);
        var note = context.computeOnClient(mc -> ((RegionDetailTestAccess) mc.gui.screen()).phase2Note());
        clickNote(context, note);
        int noteCharacters = context.computeOnClient(mc -> note.getValueWrapper().length());
        context.getInput().pressKey(GLFW.GLFW_KEY_END);
        for (int i = 0; i < noteCharacters; i++) context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
        check(context.computeOnClient(mc -> ((RegionDetailTestAccess) mc.gui.screen()).phase2Note().getValueWrapper()).isEmpty(), "Real note deletion input did not empty the editor");
        click(context, context.computeOnClient(mc -> button((GuiBase) mc.gui.screen(), "Save note"))); idle(context, work);
        click(context, context.computeOnClient(mc -> button((GuiBase) mc.gui.screen(), "Back"))); context.waitTick();
        context.runOnClient(mc -> {
            check(work.state(work.id("Region 2")).note().isEmpty(), "AC07 explicit deletion did not persist empty note");
            check(notices(screen, "Region 2").size() == 1, "AC07 deletion removed or duplicated notice");
            check(notices(screen, "Region 2").getFirst().getHoverStrings().contains(modified), "AC07 deletion removed standard explanation");
        });
        UiParityEvidence.capture(context, screen, "phase2-regions-notice-after-note-delete");
        UiParityEvidence.notice(context, "phase2-regions-notice-after-note-delete", context.computeOnClient(mc -> notices(screen, "Region 2").getFirst()));
        var beforeTransforms = work.dataset();
        for (String label : List.of("Rotation", "Mirror")) {
            click(context, context.computeOnClient(mc -> buttonContaining(screen, label)));
        }
        var origin = placement.getOrigin();
        click(context, context.computeOnClient(mc -> ((GuiBaseTestAccess) screen).phase0Buttons().stream().filter(button -> button.getX() == screen.getScreenWidth() - 43 && button.getY() == 100).findFirst().orElseThrow()));
        check(placement.getOrigin().equals(origin.offset(1, 0, 0)), "Upstream origin nudge failed");
        click(context, context.computeOnClient(mc -> buttonContaining(screen, "All OFF")));
        check(placement.getAllSubRegionsPlacements().stream().noneMatch(p -> p.isEnabled()) && work.dataset().progress().equals(new Progress(5, 62, 8)), "All OFF changed denominator");
        click(context, context.computeOnClient(mc -> buttonContaining(screen, "All ON")));
        click(context, context.computeOnClient(mc -> rowContaining(screen, "Region 1", "Placement:")));
        check(!placement.getRelativeSubRegionPlacement("Region 1").isEnabled() && work.dataset().equals(beforeTransforms), "Placement toggle changed work");
        click(context, context.computeOnClient(mc -> rowContaining(screen, "Region 1", "Placement:")));
        click(context, context.computeOnClient(mc -> button(screen, "Refresh work"))); idle(context, work);
        check(work.dataset().equals(beforeTransforms), "Transforms changed original identity");
        // Actual search input retains natural upstream Region 1, 2, ... ordering.
        var search = context.computeOnClient(mc -> ((GuiListTestAccess) screen).phase0List().getSearchBarWidget());
        click(context, search); context.getInput().typeChars("Region 62");
        context.waitFor(mc -> ((GuiListTestAccess) screen).phase0List().getCurrentEntries().size() == 1);
        check(work.dataset().progress().equals(new Progress(5, 62, 8)), "Search changed denominator");
        for (int scale : new int[]{4, 3}) {
            context.runOnClient(mc -> { mc.options.guiScale().set(scale); mc.resizeGui(); }); context.waitTick();
            context.runOnClient(mc -> {
                UiParityEvidence.controls(screen);
                var filtered = ((GuiListTestAccess) screen).phase0List();
                check(filtered.getSearchBarWidget().isSearchOpen() && filtered.getSearchBarWidget().getFilter().equals("region 62")
                    && ((dev.mcmateriallist.fabric.client.mixin.SearchBoxAccess) filtered.getSearchBarWidget()).mcmateriallist$searchBox().getTextWrapper().equals("Region 62")
                    && filtered.getCurrentEntries().size() == 1 && work.dataset().equals(beforeTransforms), "Region layout recreation lost native search/state");
            });
        }
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(mc -> ((GuiListTestAccess) screen).phase0List().getCurrentEntries().size() == 62);
        context.runOnClient(mc -> { var list = ((GuiListTestAccess) screen).phase0List(); list.getScrollbar().setValue(0); list.refreshEntries(); });
        var oldId = work.id("Region 1");
        BlockPos original = placement.getSchematic().getSubRegionPosition("Region 1");
        context.runOnClient(mc -> ((RegionSchematicTestAccess) placement.getSchematic()).phase2Origins().put("Region 1", original.offset(0, 0, 1)));
        click(context, context.computeOnClient(mc -> button(screen, "Refresh work"))); idle(context, work);
        var nextId = work.id("Region 1");
        check(!oldId.equals(nextId) && work.review(nextId) && work.state(nextId).equals(TaskState.empty(nextId)) && work.dataset().archived().get(oldId).state().note().equals("建築担当\n屋根を確認"), "Definition mismatch transferred old work");
        click(context, context.computeOnClient(mc -> rowButton(screen, "Region 1", RegionDoneButton.class))); context.waitTick();
        check(!work.state(nextId).done() && !work.pending(), "Pending review allowed completion");
        click(context, context.computeOnClient(mc -> button(screen, "Archived work (1)"))); context.waitForScreen(RegionArchiveScreen.class);
        context.takeScreenshot("phase2-regions-archive");
        click(context, context.computeOnClient(mc -> button((GuiBase) mc.gui.screen(), "Back")));
        click(context, context.computeOnClient(mc -> rowButton(screen, "Region 1", RegionHeadButton.class))); context.waitForScreen(RegionDetailScreen.class);
        context.takeScreenshot("phase2-regions-definition-review");
        click(context, context.computeOnClient(mc -> button((GuiBase) mc.gui.screen(), "Accept new definition"))); idle(context, work);
        check(!work.review(nextId) && work.state(nextId).equals(TaskState.empty(nextId)) && work.dataset().archived().containsKey(oldId), "Acceptance transferred or destroyed archived work");
        click(context, context.computeOnClient(mc -> button((GuiBase) mc.gui.screen(), "Back")));
        context.runOnClient(mc -> ((RegionSchematicTestAccess) placement.getSchematic()).phase2Origins().put("Region 1", original));
        click(context, context.computeOnClient(mc -> button(screen, "Refresh work"))); idle(context, work);
        check(work.id("Region 1").equals(oldId) && work.state(oldId).note().equals("建築担当\n屋根を確認"), "Exact archived definition failed to restore");
        context.runOnClient(mc -> ((RegionSchematicTestAccess) placement.getSchematic()).phase2Origins().put("Region 1", null));
        context.runOnClient(mc -> check(!work.ready() && PlacementWorkAdapter.captureRegions(placement).status() == PlacementWorkAdapter.Status.INVALID_DEFINITION, "Invalid original definition remained writable"));
        context.runOnClient(mc -> work.command(oldId, new TaskCommand.SetDone(true)));
        check(!work.pending() && !work.state(oldId).done(), "Invalid definition accepted a command");
        context.runOnClient(mc -> ((RegionSchematicTestAccess) placement.getSchematic()).phase2Origins().put("Region 1", original));
        verifyScaledNames(context, screen, placement);
        var previous = work.dataset();
        byte[] damaged = "{ damaged region".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(primary, damaged);
        click(context, context.computeOnClient(mc -> rowButton(screen, "Region 3", RegionDoneButton.class)));
        context.waitFor(mc -> !work.pending() && work.status() == StoreStatus.RECOVERY_REQUIRED);
        check(work.dataset().equals(previous) && !work.state(work.id("Region 3")).done() && Arrays.equals(damaged, Files.readAllBytes(primary)), "Failed region write published completion or overwrote damaged data");
        context.setScreen(() -> new GuiPlacementConfiguration(placement));
        var recovery = context.computeOnClient(mc -> session((GuiPlacementConfiguration) mc.gui.screen()));
        context.waitFor(mc -> !recovery.pending() && recovery.status() == StoreStatus.RECOVERY_REQUIRED);
        context.runOnClient(mc -> UiParityEvidence.controls((GuiBase) mc.gui.screen()));
        check(recovery.dataset() == null && !recovery.writable(), "Region backup was presented as current");
        context.setScreen(() -> screen);
        click(context, context.computeOnClient(mc -> button(screen, "Recover backup")));
        check(Arrays.equals(damaged, Files.readAllBytes(primary)), "First recovery click changed region storage");
        click(context, context.computeOnClient(mc -> button(screen, "Confirm recovery"))); idle(context, work);
        click(context, context.computeOnClient(mc -> button(screen, "Refresh work"))); idle(context, work);
        check(work.dataset().progress().equals(new Progress(5, 62, 8)) && work.id("Region 1").equals(oldId), "Explicit region recovery/refresh changed progress or identity");
        context.takeScreenshot("phase2-regions-explicit-recovery");
        var recovered = work.dataset();
        byte[] secondDamage = "{ second region incident".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(primary, secondDamage);
        click(context, context.computeOnClient(mc -> button(screen, "Refresh work")));
        context.waitFor(mc -> !work.pending() && work.status() == StoreStatus.RECOVERY_REQUIRED);
        check(work.dataset().equals(recovered), "Second region incident changed confirmed state");
        click(context, context.computeOnClient(mc -> ((GuiBaseTestAccess) screen).phase0Buttons().stream()
            .filter(candidate -> List.of("Recover backup", "Confirm recovery").contains(ChatFormatting.stripFormatting(((ButtonTestAccess) candidate).phase0Text())))
            .findFirst().orElseThrow()));
        context.waitFor(mc -> !work.pending());
        check(Arrays.equals(secondDamage, Files.readAllBytes(primary)), "Second region recovery first click changed damaged primary without fresh confirmation");
        click(context, context.computeOnClient(mc -> button(screen, "Confirm recovery"))); idle(context, work);
        click(context, context.computeOnClient(mc -> button(screen, "Refresh work"))); idle(context, work);
        check(work.dataset().equals(recovered), "Second region recovery changed exact state");
        context.takeScreenshot("phase2-regions-second-recovery");
        check(Arrays.equals(materialBytes, Files.readAllBytes(primary.resolveSibling("materials.json"))), "Region operations rewrote material snapshot");
        Files.write(primary.getParent().resolveSibling("region-material-baseline.bin"), materialBytes);
    }
    private static List<ButtonBase> notices(GuiPlacementConfiguration screen, String key) { return ((WidgetContainerTestAccess) row(screen, key)).phase0Children().stream().filter(widget -> widget instanceof dev.mcmateriallist.fabric.client.work.WorkNoticeButton && ((ButtonTestAccess) widget).phase2Enabled()).map(widget -> (ButtonBase) widget).toList(); }
    private static ButtonBase rowLabel(GuiPlacementConfiguration screen, String key, String label) { return ((WidgetContainerTestAccess) row(screen, key)).phase0Children().stream().filter(widget -> widget instanceof ButtonBase button && label.equals(ChatFormatting.stripFormatting(((ButtonTestAccess) button).phase0Text()))).map(widget -> (ButtonBase) widget).findFirst().orElseThrow(); }
    private static ButtonBase rowContaining(GuiPlacementConfiguration screen, String key, String label) { return ((WidgetContainerTestAccess) row(screen, key)).phase0Children().stream().filter(widget -> widget instanceof ButtonBase button && ChatFormatting.stripFormatting(((ButtonTestAccess) button).phase0Text()).contains(label)).map(widget -> (ButtonBase) widget).findFirst().orElseThrow(); }
    private static ButtonBase buttonContaining(GuiBase screen, String label) { return ((GuiBaseTestAccess) screen).phase0Buttons().stream().filter(button -> ChatFormatting.stripFormatting(((ButtonTestAccess) button).phase0Text()).contains(label)).findFirst().orElseThrow(() -> new AssertionError("Missing upstream button: " + label)); }
    private static void verifyScaledNames(ClientGameTestContext context, GuiPlacementConfiguration screen, SchematicPlacement placement) throws java.io.IOException {
        String key = "非常に長い日本語の領域名でも担当と完了と既存の操作が重ならない".repeat(5);
        var longPlacement = context.computeOnClient(mc -> {
            Path root = Path.of(System.getProperty("mcmateriallist.phase0.fixture")).resolve("phase2-region");
            var selection = new AreaSelection(); selection.addSubRegionBox(new Box(BlockPos.ZERO, new BlockPos(0, 1, 0), key), false);
            var schematic = LitematicaSchematic.createEmptySchematic(selection, "SyntheticJapaneseRegion");
            var path = Files.createTempDirectory(root, "long-name-");
            check(schematic.writeToFile(path, "fixture", false), "Long-name fixture save failed");
            var fixture = SchematicPlacement.createFor(LitematicaSchematic.createFromFile(path, "fixture.litematic"), placement.getOrigin(), "Japanese name fixture", true, true);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(fixture, false); return fixture;
        });
        context.setScreen(() -> new GuiPlacementConfiguration(longPlacement));
        var longScreen = context.computeOnClient(mc -> (GuiPlacementConfiguration) mc.gui.screen());
        var longWork = context.computeOnClient(mc -> session(longScreen));
        context.waitFor(mc -> !longWork.pending() && longWork.status() == StoreStatus.MISSING);
        click(context, context.computeOnClient(mc -> button(longScreen, "Track regions"))); idle(context, longWork);
        var offline = java.util.UUID.fromString("12345678-1234-4234-8234-123456789abc");
        context.runOnClient(mc -> longWork.command(longWork.id(key), new TaskCommand.Assign(offline))); idle(context, longWork);
        click(context, context.computeOnClient(mc -> rowButton(longScreen, key, RegionHeadButton.class)));
        context.waitForScreen(RegionDetailScreen.class); context.waitTick();
        context.runOnClient(mc -> {
            var detail = (GuiBase) mc.gui.screen();
            check(!((ButtonTestAccess) button(detail, "Claim")).phase2Enabled() && !((ButtonTestAccess) button(detail, "Release")).phase2Enabled(), "Unavailable other assignee allowed ownership actions");
            check(((ButtonTestAccess) button(detail, "Save note")).phase2Enabled(), "Other assignee blocked independent note editing");
        });
        click(context, context.computeOnClient(mc -> button((GuiBase) mc.gui.screen(), "Back")));
        for (int scale : new int[]{1, 2, 3, 4}) {
            context.runOnClient(mc -> { mc.options.guiScale().set(scale); mc.resizeGui(); }); context.waitTick();
            context.runOnClient(mc -> {
                var row = row(longScreen, key);
                var head = rowButton(longScreen, key, RegionHeadButton.class);
                var done = rowButton(longScreen, key, RegionDoneButton.class);
                var configure = rowLabel(longScreen, key, "Configure");
                var toggle = rowContaining(longScreen, key, "Placement:");
                check(head.getWidth() == 16 && head.getX() + 16 <= done.getX() && done.getX() + done.getWidth() <= configure.getX() && configure.getX() + configure.getWidth() <= toggle.getX() && toggle.getX() + toggle.getWidth() <= row.getX() + row.getWidth(), "Scaled fallback/name row controls overlap");
                var toolbar = ((GuiBaseTestAccess) longScreen).phase0Buttons().stream().filter(control -> control.getHoverStrings().stream().anyMatch(List.of("Refresh work", "Hide Done: OFF", "Show Info: OFF")::contains)).toList();
                check(toolbar.size() == 3, "Scaled region toolbar control missing");
                UiParityEvidence.controls(longScreen);
                for (var control : toolbar) check(control.getY() == (scale <= 3 ? 44 : 64), "Scaled toolbar did not choose its compact/narrow position");
                for (var control : toolbar) check(control.getX() >= 0 && control.getX() + control.getWidth() <= longScreen.getScreenWidth() - 140, "Scaled toolbar overlaps upstream sidebar");
            });
            click(context, context.computeOnClient(mc -> rowButton(longScreen, key, RegionDoneButton.class))); idle(context, longWork);
            check(longWork.state(longWork.id(key)).done() == (scale % 2 == 1), "Scaled completion click missed long name");
            check(workUnchanged(screen, new Progress(5, 62, 8)), "Another placement mixed work");
            UiParityEvidence.capture(context, longScreen, "phase2-regions-japanese-name-scale-" + scale);
        }
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(mc -> { mc.options.guiScale().set(4); mc.resizeGui(); }); context.waitTick();
        context.runOnClient(mc -> {
            var head = rowButton(longScreen, key, RegionHeadButton.class);
            var done = rowButton(longScreen, key, RegionDoneButton.class);
            var configure = rowLabel(longScreen, key, "Configure");
            check(head.getX() + head.getWidth() <= done.getX() && done.getX() + done.getWidth() <= configure.getX(), "Narrow GUI overlaps work/upstream controls");
            UiParityEvidence.controls(longScreen);
            check(((ListTestAccess) ((GuiListTestAccess) longScreen).phase0List()).phase0Y() + ((ListTestAccess) ((GuiListTestAccess) longScreen).phase0List()).phase0Height() <= longScreen.getScreenHeight() - 62, "Narrow list occupies the reserved readable progress line");
        });
        click(context, context.computeOnClient(mc -> rowButton(longScreen, key, RegionDoneButton.class))); idle(context, longWork);
        check(longWork.state(longWork.id(key)).done(), "Narrow long-name completion click missed");
        UiParityEvidence.capture(context, longScreen, "phase2-regions-narrow-japanese-name");
        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(mc -> {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(longPlacement);
            mc.options.guiScale().set(3); mc.resizeGui();
        });
        context.setScreen(() -> screen);
        context.runOnClient(mc -> { var list = ((GuiListTestAccess) screen).phase0List(); list.getScrollbar().setValue(0); list.refreshEntries(); });
        var beforeLanguage = session(screen).dataset();
        UiParityEvidence.language(context, screen, "ja_jp");
        context.runOnClient(mc -> {
            UiParityEvidence.controls(screen);
            check(RegionWorkSession.text("show_info", "OFF").equals("補足情報: OFF"), "Native Japanese region translation unavailable");
        });
        UiParityEvidence.capture(context, screen, "phase2-regions-japanese-ui");
        check(session(screen).dataset().equals(beforeLanguage), "Native language reload changed region IDs/state");
        UiParityEvidence.language(context, screen, "en_us");
    }
    private static boolean workUnchanged(GuiPlacementConfiguration screen, Progress progress) { return session(screen).dataset().progress().equals(progress); }
    private static RegionWorkSession session(GuiPlacementConfiguration screen) { return ((RegionWorkScreen) screen).mcmateriallist$session(); }
    private static Path primary(SchematicPlacement placement) { return FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist/placements").resolve(placement.getHashId().toString()).resolve("regions.json"); }
    private static void idle(ClientGameTestContext context, RegionWorkSession work) { context.waitFor(mc -> !work.pending() && work.status() == StoreStatus.OK); }
    private static WidgetPlacementSubRegion row(GuiPlacementConfiguration screen, String key) {
        return ((ListTestAccess) ((GuiListTestAccess) screen).phase0List()).phase0Rows().stream().filter(candidate -> candidate instanceof WidgetPlacementSubRegion && candidate.getEntry() instanceof fi.dy.masa.litematica.schematic.placement.SubRegionPlacement entry && key.equals(entry.getName())).map(candidate -> (WidgetPlacementSubRegion) candidate).findFirst().orElseThrow(() -> new AssertionError("Region row not visible: " + key));
    }
    private static ButtonBase rowButton(GuiPlacementConfiguration screen, String key, Class<? extends ButtonBase> type) { return ((WidgetContainerTestAccess) row(screen, key)).phase0Children().stream().filter(type::isInstance).map(widget -> (ButtonBase) widget).findFirst().orElseThrow(() -> new AssertionError("Missing region action")); }
    private static void click(ClientGameTestContext context, WidgetBase button) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void clickNote(ClientGameTestContext context, fi.dy.masa.malilib.gui.GuiTextFieldMultiLine note) {
        double[] cursor = context.computeOnClient(mc -> new double[]{(note.getX() + 8.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(), (note.getY() + 8.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void restore(ClientGameTestContext context, Path root) throws java.io.IOException {
        var json = com.google.gson.JsonParser.parseString(Files.readString(root.resolve("placement.json"))).getAsJsonObject();
        var placement = context.computeOnClient(mc -> {
            var manager = DataManager.getSchematicPlacementManager(); manager.loadFromJson(json);
            check(manager.getAllSchematicsPlacements().size() == 1, "Unrelated restored region fixture");
            return manager.getAllSchematicsPlacements().stream().filter(p -> p.getHashId().toString().equals(json.get("expectedPlacement").getAsString())).findFirst().orElseThrow();
        });
        byte[] persisted = Files.readAllBytes(primary(placement));
        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
        context.setScreen(() -> new GuiPlacementConfiguration(placement));
        var work = context.computeOnClient(mc -> session((GuiPlacementConfiguration) mc.gui.screen())); idle(context, work);
        check(work.dataset().equals(new DatasetJsonCodec().decode(Files.readString(root.resolve("expected-regions.json")))), "Restart changed exact region identity/state");
        check(work.dataset().progress().equals(new Progress(5, 62, 8)), "Restart region progress differs");
        check(placement.getAllSubRegionsPlacements().stream().allMatch(p -> p.isEnabled()), "Restart changed enabled flags");
        check(Arrays.equals(persisted, Files.readAllBytes(primary(placement))), "Restart opening wrote region work");
        check(Arrays.equals(Files.readAllBytes(primary(placement).getParent().resolveSibling("region-material-baseline.bin")), Files.readAllBytes(primary(placement).resolveSibling("materials.json"))), "Restart changed independent material bytes");
        check(Arrays.equals(Files.readAllBytes(root.resolve("schematic-baseline.bin")), Files.readAllBytes(placement.getSchematicFile())), "Restart changed schematic");
        context.takeScreenshot("phase2-regions-restart");
    }
    private static ButtonBase button(GuiBase screen, String label) {
        return ((GuiBaseTestAccess) screen).phase0Buttons().stream().filter(button -> label.equals(ChatFormatting.stripFormatting(((ButtonTestAccess) button).phase0Text()))).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label));
    }
    private static SchematicPlacement fixture(Path root, BlockPos origin) throws java.io.IOException {
        var directory = Files.createTempDirectory(root, "schematic-");
        var selection = new AreaSelection();
        for (int i = 1; i <= 62; i++) selection.addSubRegionBox(new Box(new BlockPos(i - 1, 0, 0), new BlockPos(i - 1, 1, 0), "Region " + i), false);
        var schematic = LitematicaSchematic.createEmptySchematic(selection, "Phase2RegionTest");
        if (!schematic.writeToFile(directory, "fixture", false)) throw new AssertionError("Region schematic write failed");
        var placement = SchematicPlacement.createFor(LitematicaSchematic.createFromFile(directory, "fixture.litematic"), origin, "Phase 2 regions", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }
}
