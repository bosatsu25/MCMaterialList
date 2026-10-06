package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.fabric.client.shared.SharedClientNetworking;
import dev.mcmateriallist.fabric.client.shared.*;
import dev.mcmateriallist.fabric.client.material.*;
import dev.mcmateriallist.fabric.client.region.*;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import net.fabricmc.loader.api.FabricLoader;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import java.util.concurrent.CompletableFuture;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.fabric.shared.SharedPayload;
import dev.mcmateriallist.fabric.test.mixin.SharedUiAccess;
import dev.mcmateriallist.fabric.client.mixin.GuiButtonsAccess;
import dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

/** A synthetic offline test account on one actual socket, not external two-client acceptance. */
public final class SharedUiClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
      try {
        Properties properties = new Properties();
        properties.setProperty("level-name", "shared-ui-owned-world"); properties.setProperty("online-mode", "false");
        properties.setProperty("server-ip", "localhost"); properties.setProperty("server-port", Integer.toString(availablePort()));
        try (var server = context.worldBuilder().createServer(properties); var connection = server.connect()) {
            connection.getClientLevel().waitForChunksRender();
            context.waitFor(client -> SharedClientNetworking.state().capabilities() != null, 600);
            var placement = context.computeOnClient(client -> fixture(client.player.blockPosition().above(8)));
            context.waitFor(client -> SchematicWorldHandler.getSchematicWorld().getBlockState(placement.getOrigin()).is(Blocks.STONE));
            var materials = context.computeOnClient(client -> placement.getMaterialList());
            context.waitFor(client -> materials.getMaterialsAll().size() == 2
                && PlacementWorkAdapter.capture(placement, materials).status() == PlacementWorkAdapter.Status.OK, 600);
            byte[] schematic = Files.readAllBytes(placement.getSchematicFile());
            context.getInput().resizeWindow(1920, 1080);
            context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
            context.setScreen(() -> new GuiMaterialList(materials));
            var screen = context.computeOnClient(client -> (GuiMaterialList) client.gui.screen());
            var work = context.computeOnClient(client -> ((MaterialWorkScreen) screen).mcmateriallist$session());
            context.waitFor(client -> !work.pending() && work.status() == StoreStatus.MISSING);
            click(context, context.computeOnClient(client -> button(screen, "Track materials")));
            context.waitFor(client -> work.writable());
            var stone = new MaterialTaskId("minecraft:stone"); var glass = new MaterialTaskId("minecraft:glass");
            click(context, context.computeOnClient(client -> rowButton(screen, glass, MaterialDoneButton.class)));
            context.waitFor(client -> work.writable() && work.state(glass).done());
            var originalLocal = context.computeOnClient(client -> work.dataset());
            Path config = FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist");
            Path localFile = config.resolve("placements").resolve(placement.getHashId().toString()).resolve("materials.json");
            byte[] localBytes = Files.readAllBytes(localFile);
            capture(context, screen, "phase3-explicit-shared-entry");
            click(context, context.computeOnClient(client -> button(screen, "Share")));
            create(context, "Material project");
            context.waitFor(client -> work.shared().selected() && work.writable(), 600);
            var materialReference = context.computeOnClient(client -> work.shared().reference());
            context.runOnClient(client -> check(!work.state(glass).done() && work.dataset().equals(originalLocal), "Creation imported local work or changed local identity"));
            back(context); back(context);
            context.waitForScreen(GuiMaterialList.class);
            click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
            context.waitFor(client -> work.writable() && work.state(stone).done(), 600);
            context.runOnClient(client -> check(work.progress().equals(new Progress(1, 2, 50)), "Shared native progress did not use project denominator"));
            click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialHeadButton.class)));
            context.waitForScreen(MaterialDetailScreen.class);
            action(context, "Claim"); context.waitFor(client -> work.writable() && work.state(stone).assignee() != null, 600);
            var detail = context.computeOnClient(client -> (GuiBase) client.gui.screen());
            clickField(context, context.computeOnClient(client -> ((SharedUiAccess.MaterialDetail) detail).note()));
            context.getInput().typeChars("Shared fixture note"); action(context, "Save note");
            context.waitFor(client -> work.writable() && work.state(stone).note().equals("Shared fixture note"), 600);
            action(context, "Release"); context.waitFor(client -> work.writable() && work.state(stone).assignee() == null, 600);
            action(context, "Assign..."); context.waitForScreen(SharedInputScreen.class);
            input(context, context.computeOnClient(client -> client.player.getUUID().toString())); action(context, "Assign...");
            context.waitFor(client -> work.writable() && work.state(stone).assignee() != null, 600);
            action(context, "Clear assignment"); context.waitFor(client -> work.writable() && work.state(stone).assignee() == null, 600);
            back(context); action(context, "Assign..."); input(context, new UUID(31, 32).toString()); action(context, "Assign...");
            context.waitFor(client -> work.writable() && work.shared().feedback().equals(SharedWorkBackend.text("result.INVALID_INPUT")), 600);
            context.runOnClient(client -> check(work.state(stone).assignee() == null, "Rejected assignment optimistically changed native row"));
            back(context); back(context);
            // Real presend save is held without blocking either thread. Local selection must refuse until settled.
            SharedUiDelays.admission = new CompletableFuture<>(); SharedUiDelays.result = new CompletableFuture<>();
            click(context, context.computeOnClient(client -> rowButton(screen, glass, MaterialDoneButton.class)));
            context.waitFor(client -> SharedUiDelays.admitted, 600);
            context.runOnClient(client -> {
                work.shared().local();
                check(work.shared().reference().equals(materialReference) && work.pending(), "Local switch escaped delayed original admission");
            });
            SharedUiDelays.admission.complete(null); SharedUiDelays.admission = null;
            context.waitFor(client -> SharedUiDelays.executed && !work.pending(), 600);
            click(context, context.computeOnClient(client -> button(screen, "Share"))); action(context, "Use local work");
            context.waitFor(client -> !work.shared().selected() && !work.pending(), 600);
            SharedUiDelays.result.complete(null); SharedUiDelays.result = null;
            var placementId = context.computeOnClient(client -> PlacementWorkAdapter.captureRegions(placement).snapshot().placementId());
            var store = new SharedClientStore(config);
            for (int i = 0; i < 200 && store.load(placementId).value().operations().stream().anyMatch(operation -> operation.confirmation() == null); i++) context.waitTicks(1);
            check(store.load(placementId).value().operations().stream().allMatch(operation -> operation.confirmation() != null), "Late actual socket result did not settle original journal");
            context.runOnClient(client -> check(work.dataset().equals(originalLocal) && work.state(glass).done() && !work.state(stone).done(), "Late shared result contaminated local rows"));
            action(context, "Find projects"); context.waitFor(client -> !SharedClientNetworking.state().projects().isEmpty(), 600);
            action(context, "Join: Material project"); context.waitFor(client -> work.shared().selected() && work.writable(), 600); back(context);
            context.runOnClient(client -> check(work.state(stone).done() && work.state(glass).done(), "Explicit discovery/join did not restore server rows"));
            // Bind region rows to another project without replacing the material project reference.
            context.setScreen(() -> new GuiPlacementConfiguration(placement));
            var regionScreen = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
            var regions = context.computeOnClient(client -> ((RegionWorkScreen) regionScreen).mcmateriallist$session());
            context.waitFor(client -> !regions.pending());
            action(context, "Share"); create(context, "Region project");
            context.waitFor(client -> regions.shared().selected() && regions.writable(), 600);
            var archive = store.load(placementId).value();
            check(archive.materials().shared().scope().equals(materialReference.scope())
                && !archive.regions().shared().scope().equals(materialReference.scope()), "Material/region bindings were coupled");
            back(context); back(context);
            var regionId = context.computeOnClient(client -> regions.id("First region"));
            click(context, context.computeOnClient(client -> regionButton(regionScreen, "First region", RegionDoneButton.class)));
            context.waitFor(client -> regions.writable() && regions.state(regionId).done(), 600);
            context.runOnClient(client -> check(regions.progress().equals(new Progress(1, 2, 50)), "Shared region native progress uses wrong definition"));
            // Permission input goes through real registered admin payload and invalidates the current view.
            action(context, "Share"); action(context, "Viewer access...");
            UUID syntheticViewer = new UUID(4, 7); input(context, syntheticViewer.toString()); action(context, "Grant viewer");
            context.waitFor(client -> !regions.shared().writable() && regions.shared().snapshot() == null, 600);
            back(context); action(context, "Join / synchronize"); context.waitFor(client -> regions.writable(), 600);
            context.runOnClient(client -> check(regions.shared().snapshot().viewers().contains(syntheticViewer), "Native viewer grant not saved by server"));
            action(context, "Viewer access..."); input(context, syntheticViewer.toString()); action(context, "Remove viewer");
            context.waitFor(client -> !regions.writable() && regions.shared().snapshot() == null, 600);
            back(context); action(context, "Join / synchronize"); context.waitFor(client -> regions.writable(), 600);
            context.runOnClient(client -> check(!regions.shared().snapshot().viewers().contains(syntheticViewer), "Native viewer removal not saved by server")); back(context);
            context.setScreen(() -> screen); context.waitFor(client -> !work.pending());
            context.runOnClient(client -> check(!work.writable(), "A different live region subscription made material binding writable"));
            action(context, "Refresh work"); context.waitFor(client -> work.writable(), 600);
            context.runOnClient(client -> check(work.progress().equals(new Progress(2, 2, 100)), "Returning material focus changed shared denominator"));
            capture(context, screen, "phase3-shared-material-confirmed");
            observations(context, screen, work, placement);
            geometry(context, screen, work, regionScreen, regions);
            context.setScreen(() -> screen); action(context, "Refresh work"); context.waitFor(client -> work.writable(), 600);
            viewerAndUnavailable(context, server, screen, work, placement, store, placementId);
            check(Arrays.equals(localBytes, Files.readAllBytes(localFile)), "Shared flow rewrote original local file bytes");
            check(Arrays.equals(schematic, Files.readAllBytes(placement.getSchematicFile())), "Shared flow changed source schematic bytes");
        }
        org.slf4j.LoggerFactory.getLogger("mcmateriallist-shared-ui-test").info("Shared native UI all assertions PASS");
      } catch (Exception failure) { throw new AssertionError("Shared native UI fixture failed", failure); }
    }
    private static void observations(ClientGameTestContext context, GuiMaterialList screen, MaterialWorkSession work,
            SchematicPlacement placement) throws java.io.IOException {
        var materials = screen.getMaterialList(); var stone = new MaterialTaskId("minecraft:stone");
        var before = context.computeOnClient(client -> work.shared().snapshot());
        action(context, "Hide Done: OFF");
        context.runOnClient(client -> check(((SharedUiAccess.ListScreen) screen).list().getCurrentEntries().isEmpty()
            && work.progress().total() == 2, "Hide Done rewrote shared denominator"));
        action(context, "Hide Done: ON");
        var ignored = context.computeOnClient(client -> rowButton(screen, stone, fi.dy.masa.malilib.gui.button.ButtonGeneric.class));
        // The first native generic child is upstream Ignore, before the injected controls.
        context.runOnClient(client -> check("Ignore".equals(((SharedUiAccess.Button) ignored).label()), "Fixture did not identify native Ignore"));
        click(context, ignored); action(context, "Clear ignored");
        var multiplier = context.computeOnClient(client -> ((SharedUiAccess.Screen) screen).fields().getFirst().textField());
        clickSingleField(context, multiplier); context.getInput().pressKey(GLFW.GLFW_KEY_END); context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE); context.getInput().typeChars("3");
        context.waitFor(client -> materials.getMultiplier() == 3);
        action(context, "Hide available: OFF"); action(context, "Hide available: ON");
        context.runOnClient(client -> {
            check(work.shared().snapshot().manifest().equals(before.manifest()) && work.progress().total() == 2,
                "Personal multiplier/Ignore/available filter rewrote shared definitions");
            placement.toggleSubRegionEnabled("Second region", screen);
        });
        action(context, "Refresh");
        context.waitFor(client -> work.ready() && materials.getMaterialsAll().size() == 1, 600);
        action(context, "Refresh work"); context.waitFor(client -> work.writable(), 600);
        context.runOnClient(client -> check(work.progress().total() == 2 && work.shared().snapshot().manifest().equals(before.manifest()),
            "Partial personal recount rewrote global material/region definitions"));
        // Restoring flags cannot launder a prior partial count into a full manifest.
        context.runOnClient(client -> placement.toggleSubRegionEnabled("Second region", screen));
        action(context, "Share"); create(context, "Rejected partial project");
        context.waitFor(client -> work.shared().feedback().equals(SharedWorkBackend.text("full_recount_required")), 600);
        context.runOnClient(client -> check(work.shared().snapshot().scope().equals(before.scope()), "Partial creation silently changed selected project"));
        back(context); back(context); action(context, "Refresh");
        context.waitFor(client -> work.ready() && materials.getMaterialsAll().size() == 2, 600);
        context.runOnClient(client -> check(NativeSharedCapture.capture(placement, materials, true).ok(), "Fresh full native recount remained unavailable"));
        // A changed baseline blocks native buttons; returning to the original does not rewrite definitions.
        var origin = context.computeOnClient(client -> placement.getOrigin());
        context.runOnClient(client -> { placement.setOrigin(origin.above(), screen); check(!work.writable(), "Changed placement baseline remained writable"); });
        context.waitTick(); click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
        context.runOnClient(client -> { check(work.state(stone).done(), "Disabled baseline control changed shared work"); placement.setOrigin(origin, screen); });
        // Test-owned source mutation must be detected before any actual command admission, then restored.
        Path source = placement.getSchematicFile(); byte[] bytes = Files.readAllBytes(source);
        Files.write(source, new byte[]{1}, StandardOpenOption.APPEND);
        try {
            click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
            context.waitFor(client -> !work.pending() && work.shared().feedback().equals(SharedWorkBackend.text("baseline_mismatch")), 600);
            context.runOnClient(client -> check(work.state(stone).done(), "Changed source bytes bypassed digest verification"));
        } finally { Files.write(source, bytes); }
        action(context, "Refresh"); context.waitFor(client -> work.ready() && materials.getMaterialsAll().size() == 2, 600);
        action(context, "Refresh work"); context.waitFor(client -> work.writable(), 600);
        context.runOnClient(client -> check(work.shared().snapshot().manifest().equals(before.manifest()) && work.shared().snapshot().projectRevision() == before.projectRevision(),
            "Observations or refused commands changed server authority"));
    }
    private static void geometry(ClientGameTestContext context, GuiMaterialList materialScreen, MaterialWorkSession material,
            GuiPlacementConfiguration regionScreen, RegionWorkSession region) throws Exception {
        for (int scale : new int[]{1, 2, 3, 4}) {
            context.runOnClient(client -> { client.options.guiScale().set(scale); client.resizeGui(); });
            for (GuiBase screen : List.of(materialScreen, regionScreen)) {
                context.setScreen(() -> screen); context.waitTick(); context.runOnClient(client -> geometry(screen));
                action(context, "Share"); context.waitForScreen(SharedWorkScreen.class);
                context.runOnClient(client -> geometry((GuiBase) client.gui.screen())); back(context);
                capture(context, screen, "phase3-" + (screen == materialScreen ? "materials" : "regions") + "-scale-" + scale);
            }
        }
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(client -> { client.options.guiScale().set(4); client.resizeGui(); });
        for (GuiBase screen : List.of(materialScreen, regionScreen)) {
            context.setScreen(() -> screen); context.waitTick(); context.runOnClient(client -> geometry(screen));
            action(context, "Share"); context.waitForScreen(SharedWorkScreen.class); context.runOnClient(client -> geometry((GuiBase) client.gui.screen()));
            capture(context, context.computeOnClient(client -> (GuiBase) client.gui.screen()), "phase3-management-narrow-" + (screen == materialScreen ? "materials" : "regions")); back(context);
        }
        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
        var reload = context.computeOnClient(client -> { client.getLanguageManager().setSelected("ja_jp"); return client.reloadResourcePacks(); });
        context.waitFor(client -> reload.isDone(), 600); reload.join();
        for (GuiBase screen : List.of(materialScreen, regionScreen)) {
            context.setScreen(() -> screen); context.waitFor(client -> client.gui.overlay() == null); context.runOnClient(client -> geometry(screen));
            action(context, "共有"); context.waitForScreen(SharedWorkScreen.class);
            context.runOnClient(client -> geometry((GuiBase) client.gui.screen()));
            capture(context, context.computeOnClient(client -> (GuiBase) client.gui.screen()), "phase3-management-ja-" + (screen == materialScreen ? "materials" : "regions")); action(context, "戻る");
        }
        var english = context.computeOnClient(client -> { client.getLanguageManager().setSelected("en_us"); return client.reloadResourcePacks(); });
        context.waitFor(client -> english.isDone(), 600); english.join(); context.setScreen(() -> materialScreen);
        context.waitFor(client -> client.gui.overlay() == null);
    }
    private static void geometry(GuiBase screen) {
        var controls = ((GuiButtonsAccess) screen).mcmateriallist$buttons();
        var added = screen instanceof SharedWorkScreen || screen instanceof SharedInputScreen ? controls
            : controls.stream().filter(button -> button.getHoverStrings().contains(SharedWorkBackend.text("title"))).toList();
        check(!added.isEmpty(), "No shared controls in geometry check");
        for (var control : added) {
            check(control.getX() >= 0 && control.getY() >= 0 && control.getX() + control.getWidth() <= screen.getScreenWidth()
                && control.getY() + control.getHeight() <= screen.getScreenHeight(), "Shared control outside native screen");
            for (var other : controls) if (control != other) check(!(control.getX() < other.getX() + other.getWidth()
                && control.getX() + control.getWidth() > other.getX() && control.getY() < other.getY() + other.getHeight()
                && control.getY() + control.getHeight() > other.getY()), "Shared control overlaps native action: "
                    + screen.getClass().getSimpleName() + " " + screen.getScreenWidth() + "x" + screen.getScreenHeight()
                    + " shared=" + rectangle(control) + " other=" + rectangle(other));
        }
    }
    private static String rectangle(ButtonBase button) {
        return ChatFormatting.stripFormatting(((SharedUiAccess.Button) button).label()) + "[" + button.getX() + ","
            + button.getY() + "," + button.getWidth() + "," + button.getHeight() + "]";
    }
    private static void viewerAndUnavailable(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext server,
            GuiMaterialList screen, MaterialWorkSession work, SchematicPlacement placement, SharedClientStore store,
            dev.mcmateriallist.core.LocalPlacementId placementId) throws Exception {
        var prior = context.computeOnClient(client -> work.shared().snapshot());
        UUID creator = new UUID(9, 11), actor = context.computeOnClient(client -> client.player.getUUID());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var created = service.create(creator, prior.scope().worldNamespace(), "Viewer fixture project", prior.manifest().definition()).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value();
        var stone = new MaterialTaskId("minecraft:stone");
        service.command(creator, new SharedCommand(created.scope(), stone, UUID.randomUUID(), 0, 0,
            new SharedCommand.SetNote("Private viewer fixture note"))).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        service.setViewer(creator, created.scope(), 1, actor, true).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        action(context, "Share"); action(context, "Find projects");
        context.waitFor(client -> SharedClientNetworking.state().projects().stream().anyMatch(project -> project.scope().equals(created.scope())), 600);
        action(context, "Join: Viewer fixture project");
        context.waitFor(client -> work.shared().snapshot() != null && work.shared().snapshot().scope().equals(created.scope()) && !work.pending(), 600);
        context.runOnClient(client -> check(!work.writable() && work.shared().feedback().equals(SharedWorkBackend.text("viewer")), "Native Viewer was writable or mislabeled"));
        back(context); click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialHeadButton.class)));
        context.waitForScreen(MaterialDetailScreen.class);
        context.runOnClient(client -> check(((SharedUiAccess.MaterialDetail) client.gui.screen()).note().getValueWrapper().equals("Private viewer fixture note"), "Viewer snapshot omitted authorized note"));
        var removed = service.setViewer(creator, created.scope(), 2, actor, false).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        server.runOnServer(serverInstance -> SharedServerNetworking.publishEvents(serverInstance, removed.events()));
        context.waitFor(client -> SharedClientNetworking.state().status() == SharedClientState.Status.REVOKED && !work.pending(), 600);
        context.waitTick();
        context.runOnClient(client -> check(work.shared().snapshot() == null && work.state(stone) == null && !work.writable()
            && ((SharedUiAccess.MaterialDetail) client.gui.screen()).note().getValueWrapper().isEmpty(), "Access removal leaked cached rows or the detail draft"));
        check(store.load(placementId).value().materials().cache() == null, "Access removal retained private primary cache");
        capture(context, context.computeOnClient(client -> (GuiBase) client.gui.screen()), "phase3-viewer-access-removed"); back(context);
        // Real registered control path reports incompatibility, while Local remains an explicit usable option.
        context.runOnClient(client -> {
            for (var frame : SharedTransfers.split(UUID.randomUUID(), SharedProtocol.encode(new SharedMessage.Hello(SharedProtocol.VERSION + 1))))
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new SharedPayload(frame));
        });
        context.waitFor(client -> SharedClientNetworking.state().status() == SharedClientState.Status.UNSUPPORTED, 600);
        action(context, "Share"); context.waitTick();
        context.runOnClient(client -> check(!((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "New project...")).enabled(), "Unsupported server left native shared create enabled"));
        action(context, "Use local work"); context.waitFor(client -> !work.shared().selected() && work.writable(), 600); back(context);
    }
    private static void capture(ClientGameTestContext context, GuiBase screen, String name) {
        context.runOnClient(client -> {
            client.gui.toastManager().clear(); client.gui.hud.getChat().clearMessages(true);
            ((SharedUiAccess.Messages) ((SharedUiAccess.Screen) screen).messages()).messages().clear();
            ((SharedUiAccess.Messages) SharedUiAccess.InGameMessages.messages()).messages().clear();
        });
        context.getInput().setCursorPos(0, 0); context.waitTicks(2); context.takeScreenshot(name);
    }
    private static void clickSingleField(ClientGameTestContext context, fi.dy.masa.malilib.gui.GuiTextFieldGeneric field) {
        double[] cursor = context.computeOnClient(client -> new double[]{
            (field.getX() + 8.0) * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
            (field.getY() + 8.0) * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void action(ClientGameTestContext context, String label) {
        context.waitTick(); click(context, context.computeOnClient(client -> button((GuiBase) client.gui.screen(), label)));
    }
    private static void back(ClientGameTestContext context) { action(context, "Back"); context.waitTick(); }
    private static void create(ClientGameTestContext context, String name) {
        action(context, "New project..."); context.waitForScreen(SharedInputScreen.class); input(context, name); action(context, "Create empty work");
    }
    private static void input(ClientGameTestContext context, String text) {
        clickField(context, context.computeOnClient(client -> ((SharedUiAccess.Input) client.gui.screen()).input())); context.getInput().typeChars(text);
    }
    private static ButtonBase rowButton(GuiMaterialList screen, MaterialTaskId id, Class<? extends ButtonBase> type) {
        var row = ((SharedUiAccess.Rows) ((SharedUiAccess.ListScreen) screen).list()).rows().stream()
            .filter(candidate -> candidate.getEntry() instanceof MaterialListEntry entry
                && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString().equals(id.registryId()))
            .findFirst().orElseThrow(() -> new AssertionError("Missing native material row"));
        return ((SharedUiAccess.Children) row).children().stream().filter(type::isInstance).map(widget -> (ButtonBase) widget).findFirst().orElseThrow();
    }
    private static ButtonBase regionButton(GuiPlacementConfiguration screen, String name, Class<? extends ButtonBase> type) {
        var row = ((SharedUiAccess.Rows) ((SharedUiAccess.ListScreen) screen).list()).rows().stream()
            .filter(candidate -> candidate.getEntry() instanceof fi.dy.masa.litematica.schematic.placement.SubRegionPlacement region && region.getName().equals(name))
            .findFirst().orElseThrow(() -> new AssertionError("Missing native region row"));
        return ((SharedUiAccess.Children) row).children().stream().filter(type::isInstance).map(widget -> (ButtonBase) widget).findFirst().orElseThrow();
    }
    private static int availablePort() throws java.io.IOException {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }
    private static SchematicPlacement fixture(BlockPos origin) throws java.io.IOException {
        Path base = DataManager.getSchematicsBaseDirectory(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "shared-ui-fixture-");
        var selection = new AreaSelection();
        selection.addSubRegionBox(new Box(BlockPos.ZERO, new BlockPos(1, 0, 0), "First region"), false);
        selection.addSubRegionBox(new Box(new BlockPos(3, 0, 0), new BlockPos(3, 0, 0), "Second region"), false);
        var schematic = LitematicaSchematic.createEmptySchematic(selection, "Synthetic shared UI fixture");
        schematic.getSubRegionContainer("First region").set(0, 0, 0, Blocks.STONE.defaultBlockState());
        schematic.getSubRegionContainer("First region").set(1, 0, 0, Blocks.STONE.defaultBlockState());
        schematic.getSubRegionContainer("Second region").set(0, 0, 0, Blocks.GLASS.defaultBlockState());
        check(schematic.writeToFile(directory, "fixture", false), "Could not create owned synthetic schematic fixture");
        var placement = SchematicPlacement.createFor(LitematicaSchematic.createFromFile(directory, "fixture.litematic"), origin,
            "Synthetic shared placement", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false); return placement;
    }
    private static ButtonBase button(GuiBase screen, String label) {
        return ((GuiButtonsAccess) screen).mcmateriallist$buttons().stream()
            .filter(button -> label.equals(ChatFormatting.stripFormatting(((SharedUiAccess.Button) button).label())))
            .findFirst().orElseThrow(() -> new AssertionError("Missing explicit native action: " + label));
    }
    private static void click(ClientGameTestContext context, WidgetBase widget) {
        double[] cursor = context.computeOnClient(client -> new double[]{
            (widget.getX() + widget.getWidth() / 2.0) * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
            (widget.getY() + widget.getHeight() / 2.0) * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void clickField(ClientGameTestContext context, fi.dy.masa.malilib.gui.GuiTextFieldMultiLine field) {
        double[] cursor = context.computeOnClient(client -> new double[]{
            (field.getX() + 8.0) * client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth(),
            (field.getY() + 8.0) * client.getWindow().getScreenHeight() / client.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(cursor[0], cursor[1]); context.waitTick(); context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
