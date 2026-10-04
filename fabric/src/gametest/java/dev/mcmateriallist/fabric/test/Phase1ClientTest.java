package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.persistence.*;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import dev.mcmateriallist.fabric.test.mixin.CountTaskTestAccess;
import dev.mcmateriallist.fabric.test.mixin.SchedulerTestAccess;
import dev.mcmateriallist.fabric.test.mixin.GuiBaseTestAccess;
import dev.mcmateriallist.fabric.test.mixin.ButtonTestAccess;
import dev.mcmateriallist.fabric.test.mixin.MessageRendererTestAccess;
import dev.mcmateriallist.fabric.test.mixin.MessageTestAccess;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.time.Instant;
import java.util.UUID;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Real aggregated rows/regions, local store and a fresh-process restoration. */
public final class Phase1ClientTest implements FabricClientGameTest {
    private static final Instant TIME = Instant.parse("2026-10-04T00:00:00Z");
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = FixtureWorlds.create(context, "Phase 1")) {
            world.getClientLevel().waitForChunksRender();
            Path root = Path.of(System.getProperty("mcmateriallist.phase0.fixture")).resolve("phase1");
            Files.createDirectories(root);
            if (Boolean.getBoolean("mcmateriallist.phase0.restore")) { restore(context, root); return; }
            SchematicPlacement placement = context.computeOnClient(mc -> fixture(root, mc.player.blockPosition()));
            byte[] bytes = Files.readAllBytes(placement.getSchematicFile());
            awaitSchematic(context, placement);
            context.runOnClient(mc -> check(placement.getSubRegionBoxes(fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).size() == 3, "Expected three enabled upstream counting boxes"));
            context.runOnClient(mc -> verifyReadiness(placement));
            var observation = context.computeOnClient(mc -> counting(placement));
            MaterialListPlacement materials = observation.materials();
            awaitMaterials(context, placement, observation);
            WorkSnapshot snapshot = context.computeOnClient(mc -> PlacementWorkAdapter.capture(placement, materials).snapshot());
            check(snapshot != null && snapshot.materials().size() == 3 && snapshot.regions().size() == 3, "Real adapter snapshot failed");
            context.setScreen(() -> new GuiMaterialList(materials));
            verifyDiagnostic(context, "phase1-material-diagnostic");
            var cached = context.computeOnClient(mc -> placement.getMaterialList());
            context.waitFor(mc -> cached.getMaterialsAll().size() == 3 && PlacementWorkAdapter.capture(placement, cached).status() == PlacementWorkAdapter.Status.OK);
            context.setScreen(() -> new GuiPlacementConfiguration(placement));
            verifyDiagnostic(context, "phase1-region-diagnostic");
            context.setScreen(() -> null);
            UUID actor = UUID.randomUUID(); MaterialTaskId stone = new MaterialTaskId("minecraft:stone");
            try (var service = new LocalWorkService(root.resolve("data"))) {
                var fresh = service.refresh(snapshot).get(); check(fresh.materials().ok() && fresh.regions().ok(), "Initial real work save failed");
                RegionWorkDataset regions = (RegionWorkDataset) fresh.regions().dataset();
                RegionTaskId second = regions.definitions().values().stream().filter(def -> def.descriptor().key().equals("Region 2")).findFirst().orElseThrow().id();
                check(service.update(snapshot.placementId(), stone, new TaskCommand.SetDone(true), actor, TIME).get().storage().ok(), "Material completion save failed");
                check(service.update(snapshot.placementId(), stone, new TaskCommand.SetNote("石材の収集完了"), actor, TIME).get().storage().ok(), "Material note save failed");
                check(service.update(snapshot.placementId(), second, new TaskCommand.SetDone(true), actor, TIME).get().storage().ok(), "Region completion save failed");
                check(service.update(snapshot.placementId(), second, new TaskCommand.SetNote("領域2の建築完了"), actor, TIME).get().storage().ok(), "Region note save failed");
                var filtered = context.computeOnClient(mc -> {
                    materials.ignoreEntry(materials.getMaterialsAll().getFirst()); materials.setHideAvailable(true);
                    materials.setSortCriteria(fi.dy.masa.litematica.materials.MaterialListBase.SortCriteria.NAME);
                    materials.getMaterialsFiltered(true);
                    return PlacementWorkAdapter.capture(placement, materials).snapshot();
                });
                var after = service.refresh(filtered).get();
                check(after.materials().dataset().progress().total() == 3, "Ignore/filter changed tracked progress");
                check(((MaterialWorkDataset) after.materials().dataset()).definitions().equals(((MaterialWorkDataset) fresh.materials().dataset()).definitions()), "Work mutation changed upstream material counts");
                verifyStates(after, actor);
                var saved = context.computeOnClient(mc -> {
                    var manager = DataManager.getSchematicPlacementManager(); var json = manager.toJson();
                    check(manager.getAllSchematicsPlacements().size() == 1, "Phase 1 fixture contains unrelated placements");
                    json.addProperty("expectedPlacementCount", manager.getAllSchematicsPlacements().size()); return json;
                });
                saved.addProperty("expectedActor", actor.toString());
                saved.addProperty("expectedPlacementId", snapshot.placementId().toString());
                Files.writeString(root.resolve("placement.json"), saved.toString());
                Files.write(root.resolve("schematic-baseline.bin"), bytes);
                Files.writeString(root.resolve("expected-materials.json"), new DatasetJsonCodec().encode(after.materials().dataset()));
                Files.writeString(root.resolve("expected-regions.json"), new DatasetJsonCodec().encode(after.regions().dataset()));
            }
            context.runOnClient(mc -> check(placement.isEnabled() && placement.isRenderingEnabled() && placement.getRelativeSubRegionPlacement("Region 2").isEnabled() && placement.getRelativeSubRegionPlacement("Region 2").isRenderingEnabled(), "Work completion changed placement state"));
            check(Arrays.equals(bytes, Files.readAllBytes(placement.getSchematicFile())), "Work persistence modified schematic");
        } catch (IOException | ExecutionException exception) { throw new AssertionError("Phase 1 integration failed", exception); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError("Phase 1 integration interrupted", exception); }
    }
    private static SchematicPlacement fixture(Path root, BlockPos origin) throws java.io.IOException {
        Path directory = Files.createTempDirectory(root, "schematic-"); AreaSelection selection = new AreaSelection();
        for (int i = 0; i < 3; i++) selection.addSubRegionBox(new Box(new BlockPos(i * 3, 0, 0), new BlockPos(i * 3 + 1, 1, 1), "Region " + (i + 1)), false);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(selection, "Phase1Test");
        schematic.getSubRegionContainer("Region 1").set(0, 0, 0, Blocks.STONE.defaultBlockState());
        schematic.getSubRegionContainer("Region 2").set(0, 0, 0, Blocks.GLASS.defaultBlockState());
        schematic.getSubRegionContainer("Region 3").set(0, 0, 0, Blocks.DIRT.defaultBlockState());
        check(schematic.writeToFile(directory, "fixture", false), "Phase 1 fixture write failed");
        SchematicPlacement placement = SchematicPlacement.createFor(LitematicaSchematic.createFromFile(directory, "fixture.litematic"), origin, "Phase 1 fixture", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false); return placement;
    }
    private static void restore(ClientGameTestContext context, Path root) throws IOException, ExecutionException, InterruptedException {
        var json = JsonParser.parseString(Files.readString(root.resolve("placement.json"))).getAsJsonObject();
        UUID actor = UUID.fromString(json.get("expectedActor").getAsString());
        SchematicPlacement placement = context.computeOnClient(mc -> {
            var manager = DataManager.getSchematicPlacementManager(); manager.loadFromJson(json);
            check(manager.getAllSchematicsPlacements().size() == json.get("expectedPlacementCount").getAsInt(), "Phase 1 restart placement count wrong");
            var expected = dev.mcmateriallist.core.LocalPlacementId.parse(json.get("expectedPlacementId").getAsString());
            return manager.getAllSchematicsPlacements().stream().filter(candidate -> candidate.getHashId().equals(expected.value())).findFirst().orElseThrow();
        });
        // Definitions are captured after the newly restored schematic reaches the schematic world.
        awaitSchematic(context, placement);
        var observation = context.computeOnClient(mc -> counting(placement));
        MaterialListPlacement materials = observation.materials();
        awaitMaterials(context, placement, observation);
        WorkSnapshot snapshot = context.computeOnClient(mc -> PlacementWorkAdapter.capture(placement, materials).snapshot());
        try (var service = new LocalWorkService(root.resolve("data"))) {
            var restored = service.refresh(snapshot).get(); verifyStates(restored, actor);
            DatasetJsonCodec codec = new DatasetJsonCodec();
            check(codec.decode(Files.readString(root.resolve("expected-materials.json"))).equals(restored.materials().dataset()), "Material dataset identity/state changed after restart");
            check(codec.decode(Files.readString(root.resolve("expected-regions.json"))).equals(restored.regions().dataset()), "Region dataset identity/state changed after restart");
        }
        check(Arrays.equals(Files.readAllBytes(root.resolve("schematic-baseline.bin")), Files.readAllBytes(placement.getSchematicFile())), "Restart modified schematic bytes");
    }
    private static void verifyStates(RefreshResult result, UUID actor) {
        check(result.materials().ok() && result.regions().ok(), "Real work reload failed");
        MaterialWorkDataset materials = (MaterialWorkDataset) result.materials().dataset(); RegionWorkDataset regions = (RegionWorkDataset) result.regions().dataset();
        TaskState stone = materials.states().get(new MaterialTaskId("minecraft:stone"));
        check(stone.done() && stone.note().equals("石材の収集完了") && actor.equals(stone.assignee()) && TIME.equals(stone.completedAt()) && actor.equals(stone.completedBy()) && stone.rowVersion() == 2, "Material state drift");
        check(materials.progress().completed() == 1 && materials.progress().total() == 3, "Material progress drift");
        materials.states().forEach((id, state) -> { if (!id.equals(stone.taskId())) check(state.equals(TaskState.empty(id)), "Another material state changed"); });
        for (var def : regions.definitions().values()) {
            TaskState state = regions.states().get(def.id()); boolean second = def.descriptor().key().equals("Region 2");
            check(state.done() == second, "Region progress attached to wrong row");
            if (second) check(state.note().equals("領域2の建築完了") && actor.equals(state.assignee()) && actor.equals(state.completedBy()) && TIME.equals(state.completedAt()) && state.rowVersion() == 2, "Region metadata drift");
            else check(state.equals(TaskState.empty(def.id())), "Another region state changed");
        }
        check(regions.progress().completed() == 1 && regions.progress().total() == 3, "Region progress drift");
    }
    private static void awaitMaterials(ClientGameTestContext context, SchematicPlacement placement, Counting observation) {
        var materials = observation.materials();
        try { context.waitFor(mc -> materials.getMaterialsAll().size() == 3 && PlacementWorkAdapter.capture(placement, materials).status() == PlacementWorkAdapter.Status.OK); }
        catch (AssertionError failure) {
            String diagnostic = context.computeOnClient(mc -> "rows=" + materials.getMaterialsAll().size() + "; sameWorld=" + (observation.task().phase1World() == SchematicWorldHandler.getSchematicWorld()) + "; adapter=" + PlacementWorkAdapter.capture(placement, materials).status());
            throw new AssertionError("Material capture did not become ready: " + diagnostic, failure);
        }
    }
    private static void awaitSchematic(ClientGameTestContext context, SchematicPlacement placement) {
        context.waitFor(mc -> {
            var schematic = SchematicWorldHandler.getSchematicWorld(); var origin = placement.getOrigin();
            return schematic.getBlockState(origin).is(Blocks.STONE) && schematic.getBlockState(origin.offset(3, 0, 0)).is(Blocks.GLASS) && schematic.getBlockState(origin.offset(6, 0, 0)).is(Blocks.DIRT);
        });
    }
    private record Counting(MaterialListPlacement materials, CountTaskTestAccess task) {}
    private static void verifyDiagnostic(ClientGameTestContext context, String screenshot) {
        GuiBase screen = context.computeOnClient(mc -> (GuiBase) mc.gui.screen());
        var button = context.computeOnClient(mc -> ((GuiBaseTestAccess) screen).phase0Buttons().stream().filter(candidate -> "MCMaterialList".equals(ChatFormatting.stripFormatting(((ButtonTestAccess) candidate).phase0Text()))).findFirst().orElseThrow());
        double[] cursor = context.computeOnClient(mc -> new double[]{
            (button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
            (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.getInput().holdShift(); context.waitTick();
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT); context.getInput().releaseShift();
        context.waitFor(mc -> {
            var renderer = ((GuiBaseTestAccess) screen).phase0MessageRenderer();
            return ((MessageRendererTestAccess) renderer).phase0Messages().stream().anyMatch(message -> {
                String text = ChatFormatting.stripFormatting(String.join(" ", ((MessageTestAccess) message).phase0Lines()));
                return text.contains("MCMaterialList Phase 1") && text.contains("materials") && text.contains("regions") && text.contains("0/3");
            });
        });
        context.takeScreenshot(screenshot);
    }
    private static void verifyReadiness(SchematicPlacement placement) {
        var list = new MaterialListPlacement(placement, false);
        check(PlacementWorkAdapter.capture(placement, list).status() == PlacementWorkAdapter.Status.MATERIALS_NOT_READY, "Uninitialized list claimed ready");
        list.reCreateMaterialList();
        check(PlacementWorkAdapter.capture(placement, list).status() == PlacementWorkAdapter.Status.MATERIALS_BUSY, "Queued count claimed ready");
        var scheduler = TaskScheduler.getInstanceClient();
        var ownTask = ((SchedulerTestAccess) scheduler).phase1Queued().getLast();
        scheduler.runTasks(); // Admit the queued task, without executing its first count tick.
        check(scheduler.removeTask(ownTask), "Test count cancellation failed");
        check(PlacementWorkAdapter.capture(placement, list).status() == PlacementWorkAdapter.Status.MATERIALS_NOT_READY, "Canceled calculation claimed ready");
        var unregistered = SchematicPlacement.createFor(placement.getSchematic(), placement.getOrigin(), "Unregistered fixture", true, true);
        check(PlacementWorkAdapter.capture(unregistered, new MaterialListPlacement(unregistered, false)).status() == PlacementWorkAdapter.Status.UNREGISTERED, "Unregistered placement accepted");
    }
    private static Counting counting(SchematicPlacement placement) {
        var materials = new MaterialListPlacement(placement, true);
        var queued = ((SchedulerTestAccess) TaskScheduler.getInstanceClient()).phase1Queued();
        var task = (CountTaskTestAccess) queued.getLast();
        check(task.phase1World() == SchematicWorldHandler.getSchematicWorld(), "Count task captured a stale schematic world");
        for (int x : new int[]{0, 3, 6}) {
            var pos = placement.getOrigin().offset(x, 0, 0);
            check(task.phase1Boxes().values().stream().anyMatch(box -> pos.getX() >= box.minX() && pos.getX() <= box.maxX() && pos.getY() >= box.minY() && pos.getY() <= box.maxY() && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ()), "Upstream count boxes exclude a fixture block");
        }
        return new Counting(materials, task);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
