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
        try (var server = context.worldBuilder().createServer(properties)) {
          try (var connection = server.connect()) {
            connection.getClientLevel().waitForChunksRender();
            context.waitFor(client -> SharedClientNetworking.state().capabilities() != null, 600);
            var placement = context.computeOnClient(client -> fixture(client.player.blockPosition().above(8)));
            context.waitFor(client -> SchematicWorldHandler.getSchematicWorld().getBlockState(placement.getOrigin()).is(Blocks.STONE));
            context.waitFor(client -> NativeSharedCapture.capture(placement, null, false).ok(), 600);
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
            if (SharedUiRaceChecks.enabled("source")) SharedUiSourceChecks.beforeCreate(context, server, placement, materials, work, localFile);
            capture(context, screen, "phase3-explicit-shared-entry");
            click(context, context.computeOnClient(client -> button(screen, "Share")));
            create(context, "Material project");
            context.waitFor(client -> work.shared().selected() && work.writable(), 600);
            context.runOnClient(client -> check(!work.state(glass).done() && work.dataset().equals(originalLocal), "Creation imported local work or changed local identity"));
            back(context); back(context);
            context.waitForScreen(GuiMaterialList.class);
            if (SharedUiRaceChecks.enabled("source")) SharedUiSourceChecks.loadedEdit(context, server, placement, materials, work, localFile);
            if (SharedUiRaceChecks.enabled("publication")) SharedUiRaceChecks.publication(context, server, screen, work, stone);
            var materialReference = context.computeOnClient(client -> work.shared().reference());
            click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialDoneButton.class)));
            context.waitFor(client -> work.writable() && work.state(stone).done(), 600);
            context.runOnClient(client -> check(work.progress().equals(new Progress(1, 2, 50)), "Shared native progress did not use project denominator"));
            click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialHeadButton.class)));
            context.waitForScreen(MaterialDetailScreen.class);
            context.runOnClient(client -> check(work.writable()
                && !((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Claim")).enabled()
                && ((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Release")).enabled(),
                "Confirmed own assignment exposed incorrect Claim/Release controls"));
            action(context, "Release"); context.waitFor(client -> work.writable() && work.state(stone).assignee() == null, 600);
            int beforeClaim = SharedUiDelays.submissions;
            action(context, "Claim");
            try { context.waitFor(client -> work.writable() && work.state(stone).assignee() != null, 600); }
            catch (AssertionError failure) {
                throw new AssertionError("Claim did not settle: " + context.computeOnClient(client ->
                    "writable=" + work.writable() + ", pending=" + work.pending() + ", feedback=" + work.shared().feedback()
                    + ", transport=" + SharedClientNetworking.state().status() + ", submissions=" + (SharedUiDelays.submissions - beforeClaim)
                    + ", admissionDone=" + (SharedUiDelays.submitted != null && SharedUiDelays.submitted.toCompletableFuture().isDone())
                    + ", assigned=" + (work.state(stone) != null && work.state(stone).assignee() != null)), failure);
            }
            var detail = context.computeOnClient(client -> (GuiBase) client.gui.screen());
            clickField(context, context.computeOnClient(client -> ((SharedUiAccess.MaterialDetail) detail).note()));
            int previousNoteLength = context.computeOnClient(client -> work.state(stone).note().length());
            context.getInput().pressKey(GLFW.GLFW_KEY_END);
            for (int i = 0; i < previousNoteLength; i++) context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
            context.getInput().typeChars("Shared fixture note"); action(context, "Save note");
            context.waitFor(client -> work.writable() && work.state(stone).note().equals("Shared fixture note"), 600);
            action(context, "Release"); context.waitFor(client -> work.writable() && work.state(stone).assignee() == null, 600);
            action(context, "Assign..."); context.waitForScreen(SharedInputScreen.class);
            input(context, context.computeOnClient(client -> client.player.getUUID().toString())); action(context, "Assign...");
            context.waitFor(client -> work.writable() && work.state(stone).assignee() != null, 600);
            action(context, "Clear assignment"); context.waitFor(client -> work.writable() && work.state(stone).assignee() == null, 600);
            var authoritativeBeforeInvalid = server.computeOnServer(SharedServerNetworking::service)
                .join(context.computeOnClient(client -> client.player.getUUID()), materialReference.scope())
                .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value();
            var invalidPlacement = context.computeOnClient(client -> PlacementWorkAdapter.captureRegions(placement).snapshot().placementId());
            var journalBeforeInvalid = new SharedClientStore(config).load(invalidPlacement).value().operations();
            back(context); action(context, "Assign..."); input(context, new UUID(31, 32).toString()); action(context, "Assign...");
            context.runOnClient(client -> check(work.writable() && work.state(stone).assignee() == null
                && SharedWorkBackend.text("target_not_participant").equals(((SharedUiAccess.Input) client.gui.screen()).validation()),
                "Unlisted assignment target was not refused before durable admission"));
            check(authoritativeBeforeInvalid.equals(server.computeOnServer(SharedServerNetworking::service)
                .join(context.computeOnClient(client -> client.player.getUUID()), materialReference.scope())
                .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value()),
                "Refused native assignment changed authoritative rows or revision");
            check(journalBeforeInvalid.equals(new SharedClientStore(config).load(invalidPlacement).value().operations()),
                "Refused native assignment entered the durable client journal");
            back(context); back(context);
            // Real presend save is held without blocking either thread. Local selection must refuse until settled.
            var placementId = context.computeOnClient(client -> PlacementWorkAdapter.captureRegions(placement).snapshot().placementId());
            var store = new SharedClientStore(config);
            var unrelatedUnknowns = store.load(placementId).value().operations().stream()
                .filter(operation -> operation.confirmation() == null && !operation.original().equals(materialReference)).toList();
            SharedUiDelays.admission = new CompletableFuture<>(); SharedUiDelays.result = new CompletableFuture<>();
            SharedUiDelays.admitted = false; SharedUiDelays.executed = false;
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
            for (int i = 0; i < 200 && store.load(placementId).value().operations().stream()
                .anyMatch(operation -> operation.original().equals(materialReference) && operation.confirmation() == null); i++) context.waitTicks(1);
            check(store.load(placementId).value().operations().stream().filter(operation -> operation.original().equals(materialReference))
                .allMatch(operation -> operation.confirmation() != null), "Late actual socket result did not settle original journal");
            check(store.load(placementId).value().operations().containsAll(unrelatedUnknowns), "Late baseline result changed unrelated original unknown operations");
            context.runOnClient(client -> check(work.dataset().equals(originalLocal) && work.state(glass).done() && !work.state(stone).done(), "Late shared result contaminated local rows"));
            action(context, "Find projects"); joinDiscovered(context, materialReference.scope());
            context.waitFor(client -> work.shared().selected() && work.writable(), 600); back(context);
            context.runOnClient(client -> check(work.state(stone).done() && work.state(glass).done(), "Explicit discovery/join did not restore server rows"));
            // Bind region rows to another project without replacing the material project reference.
            context.setScreen(() -> new GuiPlacementConfiguration(placement));
            var regionScreen = context.computeOnClient(client -> (GuiPlacementConfiguration) client.gui.screen());
            var regions = context.computeOnClient(client -> ((RegionWorkScreen) regionScreen).mcmateriallist$session());
            context.waitFor(client -> !regions.pending());
            action(context, "Track regions"); context.waitFor(client -> regions.writable(), 600);
            click(context, context.computeOnClient(client -> regionButton(regionScreen, "Second region", RegionDoneButton.class)));
            context.waitFor(client -> regions.writable() && regions.state(regions.id("Second region")).done(), 600);
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
            // Participant administration uses native controls and the registered socket receiver.
            action(context, "Share"); action(context, "Manage participants...");
            context.runOnClient(client -> check(client.gui.screen().getClass().getSimpleName().equals("SharedMembershipScreen"),
                "Manage participants did not open the native membership form"));
            membershipGeometry(context);
            UUID syntheticViewer = new UUID(4, 7); input(context, syntheticViewer.toString()); action(context, "Grant member");
            context.waitFor(client -> !regions.shared().writable() && regions.shared().snapshot() == null, 600);
            back(context); action(context, "Join / synchronize"); context.waitFor(client -> regions.writable(), 600);
            context.runOnClient(client -> check(regions.shared().snapshot().role(syntheticViewer) == ProjectRole.MEMBER,
                "Native Member grant not saved by authenticated server"));
            action(context, "Manage participants..."); input(context, syntheticViewer.toString()); action(context, "Promote administrator");
            context.waitFor(client -> !regions.writable() && regions.shared().snapshot() == null, 600);
            back(context); action(context, "Join / synchronize"); context.waitFor(client -> regions.writable(), 600);
            context.runOnClient(client -> check(regions.shared().snapshot().role(syntheticViewer) == ProjectRole.ADMINISTRATOR,
                "Native Administrator promotion not saved"));
            action(context, "Manage participants..."); input(context, syntheticViewer.toString()); action(context, "Remove participant");
            context.waitFor(client -> !regions.writable() && regions.shared().snapshot() == null, 600);
            back(context); action(context, "Join / synchronize"); context.waitFor(client -> regions.writable(), 600);
            context.runOnClient(client -> check(regions.shared().snapshot().role(syntheticViewer) == null,
                "Native participant removal not saved"));
            action(context, "Manage participants...");
            assertMembershipNoOp(context, server, regions, context.computeOnClient(client -> client.player.getUUID()),
                "Promote administrator");
            assertMembershipNoOp(context, server, regions, syntheticViewer, "Remove participant");
            context.runOnClient(client -> ((SharedUiAccess.MembershipInput) client.gui.screen()).input()
                .setValueWrapper(client.player.getUUID().toString()));
            var regionScope = context.computeOnClient(client -> regions.shared().reference().scope());
            var beforeLastAdmin = server.computeOnServer(SharedServerNetworking::service)
                .join(context.computeOnClient(client -> client.player.getUUID()), regionScope)
                .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value();
            action(context, "Remove participant");
            context.waitFor(client -> regions.shared().feedback().equals(SharedWorkBackend.text("result.INVALID_INPUT")), 600);
            check(beforeLastAdmin.equals(server.computeOnServer(SharedServerNetworking::service)
                .join(context.computeOnClient(client -> client.player.getUUID()), beforeLastAdmin.scope())
                .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value()),
                "Last Administrator refusal changed authoritative membership or revision");
            back(context); action(context, "Join / synchronize"); context.waitFor(client -> regions.writable(), 600);
            context.runOnClient(client -> check(regions.shared().role() == ProjectRole.ADMINISTRATOR,
                "Native last Administrator removal was accepted")); back(context);
            regionMember(context, server, regionScreen, regions);
            compactMembershipAndRevocation(context, server, regionScreen, regions, config, placementId);
            context.setScreen(() -> screen); context.waitFor(client -> !work.pending());
            context.runOnClient(client -> check(!work.writable(), "A different live region subscription made material binding writable"));
            action(context, "Refresh work"); context.waitFor(client -> work.writable(), 600);
            context.runOnClient(client -> check(work.progress().equals(new Progress(2, 2, 100)), "Returning material focus changed shared denominator"));
            capture(context, screen, "phase3-shared-material-confirmed");
            observations(context, screen, work, placement);
            if (SharedUiRaceChecks.enabled("routing")) SharedUiRaceChecks.restoredRouting(context, screen, placement, config, store, placementId);
            geometry(context, screen, work, regionScreen, regions);
            context.setScreen(() -> screen); action(context, "Refresh work"); context.waitFor(client -> work.writable(), 600);
            if (SharedUiRaceChecks.enabled("admission")) SharedUiRaceChecks.falseAdmission(context, work, store, config, placementId);
            definitionConfirmationContext(context, server, screen, work);
            definitionRepair(context, server, screen, placement, materials, work);
            viewerAndUnavailable(context, server, screen, work, placement, store, placementId);
            check(Arrays.equals(localBytes, Files.readAllBytes(localFile)), "Shared flow rewrote original local file bytes");
            check(Arrays.equals(schematic, Files.readAllBytes(placement.getSchematicFile())), "Shared flow changed source schematic bytes");
          }
          if (SharedUiRaceChecks.enabled("admission")) SharedUiRaceChecks.closedAdmission(context, server);
        }
        SharedSourceLoading.shutdown();
        check(SharedSourceLoading.awaitClosed(10, java.util.concurrent.TimeUnit.SECONDS), "Loaded source worker did not close");
        org.slf4j.LoggerFactory.getLogger("mcmateriallist-shared-ui-test").info("Shared native UI all assertions PASS");
      } catch (Exception failure) { throw new AssertionError("Shared native UI fixture failed", failure); }
    }
    private static void definitionConfirmationContext(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext server,
            GuiMaterialList screen, MaterialWorkSession work) throws Exception {
        var actor = context.computeOnClient(client -> client.player.getUUID());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var original = context.computeOnClient(client -> work.shared().snapshot());
        var created = service.create(actor, original.scope().worldNamespace(), "Definition confirmation fixture",
            original.manifest().definition()).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        check(created.status() == SharedEvents.Status.OK, "Definition confirmation fixture creation failed");
        var second = created.value();
        action(context, "Share"); action(context, "Update shared definition...");
        context.runOnClient(client -> check(hasButton((GuiBase) client.gui.screen(), "Confirm global update"),
            "Definition confirmation did not arm on the original project"));
        action(context, "Find projects");
        joinDiscovered(context, second.scope());
        context.waitFor(client -> work.shared().snapshot() != null && work.shared().snapshot().scope().equals(second.scope())
            && work.writable(), 600);
        context.runOnClient(client -> ((SharedWorkScreen) client.gui.screen()).initGui());
        context.runOnClient(client -> check(hasButton((GuiBase) client.gui.screen(), "Update shared definition...")
            && !hasButton((GuiBase) client.gui.screen(), "Confirm global update"),
            "Definition confirmation from the original project remained armed after joining another project"));
        action(context, "Update shared definition...");
        check(second.equals(service.join(actor, second.scope()).toCompletableFuture()
            .get(10, java.util.concurrent.TimeUnit.SECONDS).value()),
            "First definition click on the second project wrote a global definition");
        var stone = new MaterialTaskId("minecraft:stone");
        check(second.rows().containsKey(stone), "Same-scope confirmation fixture lacks stone row");
        var changed = service.command(actor, new SharedCommand(second.scope(), stone, UUID.randomUUID(),
            second.rows().get(stone).rowVersion(), second.manifest().version(),
            new SharedCommand.SetNote("Confirmation cursor fixture")))
            .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        check(changed.result().status() == SharedEvents.Status.OK,
            "Same-scope confirmation fixture command did not commit");
        server.runOnServer(serverInstance -> SharedServerNetworking.publishEvents(serverInstance, changed.events()));
        context.waitFor(client -> work.shared().snapshot() != null && work.shared().snapshot().scope().equals(second.scope())
            && work.shared().snapshot().projectRevision() == changed.result().projectRevision() && work.writable(), 600);
        context.runOnClient(client -> {
            ((SharedWorkScreen) client.gui.screen()).initGui();
            check(hasButton((GuiBase) client.gui.screen(), "Update shared definition...")
                && !hasButton((GuiBase) client.gui.screen(), "Confirm global update"),
                "Definition confirmation remained armed after same-project cursor change");
        });
        action(context, "Find projects");
        joinDiscovered(context, original.scope());
        context.waitFor(client -> work.shared().snapshot() != null && work.shared().snapshot().scope().equals(original.scope())
            && work.writable(), 600);
        context.runOnClient(client -> {
            ((SharedWorkScreen) client.gui.screen()).initGui();
            check(hasButton((GuiBase) client.gui.screen(), "Update shared definition..."),
                "Second-project definition confirmation remained armed after returning to original project");
        });
        check(original.equals(service.join(actor, original.scope()).toCompletableFuture()
            .get(10, java.util.concurrent.TimeUnit.SECONDS).value()),
            "Cross-project definition confirmation changed the original project");
        back(context);
        context.setScreen(() -> screen);
    }
    private static void definitionRepair(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext server,
            GuiMaterialList screen, SchematicPlacement placement, fi.dy.masa.litematica.materials.MaterialListBase materials,
            MaterialWorkSession work) throws Exception {
        var actor = context.computeOnClient(client -> client.player.getUUID());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var before = context.computeOnClient(client -> work.shared().snapshot());
        byte[] source = Files.readAllBytes(placement.getSchematicFile());
        var origin = placement.getOrigin();
        context.runOnClient(client -> { placement.setOrigin(origin.above(), screen); materials.reCreateMaterialList(); });
        context.waitFor(client -> NativeSharedCapture.capture(placement, materials, true).ok(), 600);
        context.runOnClient(client -> check(!work.writable() && work.shared().canUpdateDefinition(),
            "Old baseline mismatch blocked authorized explicit definition repair"));
        action(context, "Share"); action(context, "Update shared definition..."); action(context, "Confirm global update");
        context.waitFor(client -> work.shared().snapshot() == null && !work.shared().pending(), 600);
        action(context, "Join / synchronize"); context.waitFor(client -> work.writable(), 600);
        var repaired = context.computeOnClient(client -> work.shared().snapshot());
        check(repaired.manifest().version() == before.manifest().version() + 1
            && repaired.manifest().definition().baseline().origin().equals(new Vector3(placement.getOrigin().getX(),
                placement.getOrigin().getY(), placement.getOrigin().getZ()))
            && repaired.manifest().definition().materials().equals(before.manifest().definition().materials())
            && repaired.rows().equals(before.rows()), "Native full definition repair changed task state or lost global counts");
        back(context);
        context.runOnClient(client -> { placement.setOrigin(origin, screen); materials.reCreateMaterialList(); });
        context.waitFor(client -> NativeSharedCapture.capture(placement, materials, true).ok(), 600);
        action(context, "Share"); action(context, "Update shared definition..."); action(context, "Confirm global update");
        context.waitFor(client -> work.shared().snapshot() == null && !work.shared().pending(), 600);
        action(context, "Join / synchronize"); context.waitFor(client -> work.writable(), 600); back(context);
        var restored = context.computeOnClient(client -> work.shared().snapshot());
        check(restored.rows().equals(before.rows()) && restored.manifest().definition().equals(before.manifest().definition()),
            "Restored definition changed task state, material counts or region descriptors");
        context.runOnClient(client -> { placement.toggleSubRegionEnabled("Second region", screen); materials.reCreateMaterialList(); });
        context.waitFor(client -> work.ready() && !NativeSharedCapture.capture(placement, materials, true).ok(), 600);
        context.runOnClient(client -> { check(work.shared().canUpdateDefinition(), "Partial count fixture did not reach native update control"); work.shared().updateDefinition(); });
        context.waitFor(client -> work.shared().feedback().equals(SharedWorkBackend.text("full_recount_required")), 600);
        check(service.join(actor, before.scope()).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value().equals(restored),
            "Partial native count became a shared definition");
        context.runOnClient(client -> { placement.toggleSubRegionEnabled("Second region", screen); materials.reCreateMaterialList(); });
        context.waitFor(client -> NativeSharedCapture.capture(placement, materials, true).ok(), 600);
        check(Arrays.equals(source, Files.readAllBytes(placement.getSchematicFile())), "Definition repair changed saved source bytes");
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
            context.waitFor(client -> !work.pending() && work.shared().feedback().equals(SharedWorkBackend.text("source_reload_required")), 600);
            context.runOnClient(client -> check(work.state(stone).done(), "Changed source bytes bypassed digest verification"));
        } finally { Files.write(source, bytes); }
        SharedUiSourceChecks.reload(context, placement, materials);
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
        var added = screen instanceof SharedWorkScreen || screen instanceof SharedInputScreen || screen instanceof SharedMembershipScreen ? controls
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
    private static void assertMembershipNoOp(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext server,
            RegionWorkSession regions, UUID target, String action) throws Exception {
        UUID actor = context.computeOnClient(client -> client.player.getUUID());
        ProjectScope scope = context.computeOnClient(client -> regions.shared().reference().scope());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var before = service.join(actor, scope).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value();
        Path worldRoot = server.computeOnServer(mc -> mc.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        var store = new dev.mcmateriallist.core.shared.persistence.SharedProjectStore(worldRoot);
        long generation = store.load(scope.worldNamespace(), scope.projectId()).value().generation();
        Path primary = worldRoot.resolve("mcmateriallist-shared").resolve("project-" + scope.projectId() + ".json");
        Path backup = primary.resolveSibling(primary.getFileName() + ".bak");
        byte[] primaryBytes = Files.readAllBytes(primary), backupBytes = Files.exists(backup) ? Files.readAllBytes(backup) : null;
        context.runOnClient(client -> ((SharedUiAccess.MembershipInput) client.gui.screen()).input().setValueWrapper(target.toString()));
        action(context, action);
        context.waitTicks(40);
        var after = service.join(actor, scope).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value();
        check(before.equals(after) && generation == store.load(scope.worldNamespace(), scope.projectId()).value().generation()
            && Arrays.equals(primaryBytes, Files.readAllBytes(primary))
            && Arrays.equals(backupBytes, Files.exists(backup) ? Files.readAllBytes(backup) : null),
            "Native no-op membership changed server revision, generation, or bytes: " + action);
        context.runOnClient(client -> check(regions.writable() && regions.shared().snapshot() != null
            && regions.shared().snapshot().equals(before) && SharedClientNetworking.state().synchronizedState(),
            "Native no-op membership dropped confirmed cache or writable state: " + action));
    }
    private static void membershipGeometry(ClientGameTestContext context) throws Exception {
        var form = context.computeOnClient(client -> (SharedMembershipScreen) client.gui.screen());
        for (String language : List.of("en_us", "ja_jp")) {
            if (language.equals("ja_jp")) {
                var reload = context.computeOnClient(client -> {
                    client.getLanguageManager().setSelected(language); return client.reloadResourcePacks();
                });
                context.waitFor(client -> reload.isDone(), 600); reload.join();
            }
            for (String size : List.of("large", "medium", "small")) {
                int pixelsWide = size.equals("large") ? 1920 : size.equals("medium") ? 1440 : 1280;
                int pixelsHigh = size.equals("large") ? 1080 : size.equals("medium") ? 810 : 720;
                context.getInput().resizeWindow(pixelsWide, pixelsHigh);
                context.runOnClient(client -> { client.options.guiScale().set(size.equals("small") ? 4 : 3); client.resizeGui(); });
                context.setScreen(() -> form); context.waitFor(client -> client.gui.overlay() == null);
                context.runOnClient(client -> geometry(form));
                capture(context, form, "issue6-participants-" + language + "-" + size);
            }
        }
        var english = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected("en_us"); return client.reloadResourcePacks();
        });
        context.waitFor(client -> english.isDone(), 600); english.join();
        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
        context.setScreen(() -> form); context.waitFor(client -> client.gui.overlay() == null);
    }
    private static void regionMember(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext server,
            GuiPlacementConfiguration screen, RegionWorkSession regions) throws Exception {
        var prior = context.computeOnClient(client -> regions.shared().snapshot());
        UUID creator = new UUID(9, 12), actor = context.computeOnClient(client -> client.player.getUUID());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var created = service.create(creator, prior.scope().worldNamespace(), "Region member fixture",
            prior.manifest().definition()).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value();
        var other = created.manifest().regions().entrySet().stream()
            .filter(entry -> entry.getValue().key().equals("First region")).map(Map.Entry::getKey)
            .findFirst().orElseThrow(() -> new AssertionError("Created region fixture omitted First region"));
        var assigned = service.command(creator, new SharedCommand(created.scope(), other, UUID.randomUUID(), 0, 0,
            new SharedCommand.Assign(creator))).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        check(assigned.result().status() == SharedEvents.Status.OK && assigned.result().projectRevision() == 1,
            "Region fixture assignment did not commit in its new project");
        var granted = service.setMembership(creator, created.scope(), 1, actor, ProjectRole.MEMBER)
            .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        check(granted.status() == SharedEvents.Status.OK && granted.value().role(actor) == ProjectRole.MEMBER
            && granted.value().projectRevision() == 2, "Region fixture Member grant did not commit");
        context.setScreen(() -> screen);
        action(context, "Share"); action(context, "Find projects");
        joinDiscovered(context, created.scope());
        context.waitFor(client -> regions.shared().snapshot() != null && regions.shared().snapshot().scope().equals(created.scope())
            && !regions.pending(), 600);
        back(context);
        context.runOnClient(client -> {
            screen.initGui();
            check(!((SharedUiAccess.Button) regionButton(screen, "First region", RegionDoneButton.class)).enabled()
                && ((SharedUiAccess.Button) regionButton(screen, "Second region", RegionDoneButton.class)).enabled(),
                "Region Member other-task Done was enabled or own unassigned Done disabled");
        });
        click(context, context.computeOnClient(client -> regionButton(screen, "Second region", RegionDoneButton.class)));
        context.waitFor(client -> regions.writable() && regions.state(regions.id("Second region")).done()
            && actor.equals(regions.state(regions.id("Second region")).assignee()), 600);
        click(context, context.computeOnClient(client -> regionButton(screen, "Second region", RegionHeadButton.class)));
        context.waitForScreen(RegionDetailScreen.class);
        context.runOnClient(client -> check(((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Release")).enabled()
            && ((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Save note")).enabled()
            && !((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Assign...")).enabled(),
            "Region Member own controls or privileged Assign affordance were wrong"));
        clickField(context, context.computeOnClient(client -> ((SharedUiAccess.RegionDetail) client.gui.screen()).note()));
        context.getInput().typeChars("Region member note"); action(context, "Save note");
        context.waitFor(client -> regions.state(regions.id("Second region")).note().equals("Region member note"), 600);
        action(context, "Release");
        context.waitFor(client -> regions.state(regions.id("Second region")).assignee() == null, 600);
        back(context);
        context.runOnClient(client -> {
            screen.initGui();
            check(!((SharedUiAccess.Button) regionButton(screen, "Second region", RegionDoneButton.class)).enabled(),
                "Region Member could undo completed unassigned row without Claim");
        });
        click(context, context.computeOnClient(client -> regionButton(screen, "First region", RegionHeadButton.class)));
        context.waitForScreen(RegionDetailScreen.class);
        context.runOnClient(client -> check(!((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Save note")).enabled()
            && !((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Release")).enabled()
            && !((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Assign...")).enabled(),
            "Region Member other-task native edits were enabled"));
        back(context);
        action(context, "Share"); action(context, "Find projects");
        joinDiscovered(context, prior.scope());
        context.waitFor(client -> regions.shared().snapshot() != null && regions.shared().snapshot().scope().equals(prior.scope())
            && !regions.pending(), 600);
        back(context);
    }
    private static void compactMembershipAndRevocation(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext server,
            GuiPlacementConfiguration screen, RegionWorkSession regions, Path config,
            dev.mcmateriallist.core.LocalPlacementId placementId) throws Exception {
        var original = context.computeOnClient(client -> regions.shared().snapshot());
        UUID creator = new UUID(10, 1), actor = context.computeOnClient(client -> client.player.getUUID());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var project = service.create(creator, original.scope().worldNamespace(), "Compact participants fixture",
            original.manifest().definition()).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value();
        var granted = service.setMembership(creator, project.scope(), 0, actor, ProjectRole.ADMINISTRATOR)
            .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        check(granted.status() == SharedEvents.Status.OK, "Compact fixture administrator grant failed");
        var roster = granted.value();
        for (int i = 0; i < 6; i++) {
            granted = service.setMembership(creator, project.scope(), roster.projectRevision(), new UUID(10, i + 2), ProjectRole.VIEWER)
                .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            check(granted.status() == SharedEvents.Status.OK, "Compact fixture roster grant failed");
            roster = granted.value();
        }
        var ordered = roster.members().keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList();
        check(ordered.size() == 8, "Compact fixture lacks a second participant page");
        var expectedSecondPage = ordered.get(5);
        var localBefore = new SharedClientStore(config).load(placementId).value().regions().localDataset();
        context.setScreen(() -> screen); action(context, "Share"); action(context, "Find projects");
        joinDiscovered(context, project.scope());
        context.waitFor(client -> regions.shared().snapshot() != null && regions.shared().snapshot().scope().equals(project.scope())
            && regions.shared().role() == ProjectRole.ADMINISTRATOR && !regions.pending(), 600);
        action(context, "Manage participants..."); context.waitForScreen(SharedMembershipScreen.class);
        var form = context.computeOnClient(client -> (SharedMembershipScreen) client.gui.screen());
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(client -> { client.options.guiScale().set(4); client.resizeGui(); });
        context.setScreen(() -> form); context.waitTick();
        context.runOnClient(client -> {
            check(form.getScreenWidth() <= 430 && form.getScreenHeight() < 250,
                "Compact participant fixture did not reach a small logical screen: "
                    + form.getScreenWidth() + "x" + form.getScreenHeight());
            geometry(form);
        });
        boolean chooser = context.computeOnClient(client -> hasButton(form, "Choose participant"));
        capture(context, form, "issue6-participants-compact-en-edit");
        boolean selectedFromSecondPage = false;
        if (chooser) {
            action(context, "Choose participant"); context.waitTick();
            context.runOnClient(client -> geometry(form));
            capture(context, form, "issue6-participants-compact-en-list");
            action(context, "Next page"); context.waitTick();
            context.runOnClient(client -> geometry(form));
            capture(context, form, "issue6-participants-compact-en-list-page-2");
            click(context, context.computeOnClient(client -> ((GuiButtonsAccess) (GuiBase) form).mcmateriallist$buttons().stream()
                .filter(button -> button.getX() == 12 && button.getY() == 50 && button.getWidth() >= form.getScreenWidth() - 30)
                .findFirst().orElseThrow(() -> new AssertionError("Compact second-page participant row missing"))));
            selectedFromSecondPage = context.computeOnClient(client -> expectedSecondPage.toString()
                .equals(((SharedUiAccess.MembershipInput) (GuiBase) form).input().getValueWrapper())
                && hasButton(form, "Choose participant"));
        }
        var japanese = context.computeOnClient(client -> { client.getLanguageManager().setSelected("ja_jp"); return client.reloadResourcePacks(); });
        context.waitFor(client -> japanese.isDone(), 600); japanese.join(); context.setScreen(() -> form);
        context.waitFor(client -> client.gui.overlay() == null);
        context.runOnClient(client -> geometry(form));
        capture(context, form, "issue6-participants-compact-ja-edit");
        if (chooser) {
            action(context, "参加者を選ぶ"); context.waitTick();
            context.runOnClient(client -> geometry(form));
            capture(context, form, "issue6-participants-compact-ja-list");
            action(context, "編集に戻る");
        }
        var english = context.computeOnClient(client -> { client.getLanguageManager().setSelected("en_us"); return client.reloadResourcePacks(); });
        context.waitFor(client -> english.isDone(), 600); english.join();
        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
        context.setScreen(() -> form); context.waitFor(client -> client.gui.overlay() == null);
        click(context, context.computeOnClient(client -> ((GuiButtonsAccess) (GuiBase) form).mcmateriallist$buttons().stream()
            .filter(button -> button.getX() == 12 && button.getY() == 150 && button.getWidth() >= form.getScreenWidth() - 30)
            .findFirst().orElseThrow(() -> new AssertionError("Large participant row missing"))));
        boolean largeSelection = context.computeOnClient(client -> ordered.get(0).toString()
            .equals(((SharedUiAccess.MembershipInput) (GuiBase) form).input().getValueWrapper()));
        check(largeSelection, "Large participant row did not fill the UUID field");
        var removed = service.setMembership(creator, project.scope(), roster.projectRevision(), actor, null)
            .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        check(removed.status() == SharedEvents.Status.OK, "Compact fixture actor revocation failed");
        server.runOnServer(serverInstance -> SharedServerNetworking.publishEvents(serverInstance, removed.events()));
        context.waitFor(client -> SharedClientNetworking.state().status() == SharedClientState.Status.REVOKED
            && regions.shared().snapshot() == null, 600);
        context.waitTick();
        var privateIds = ordered.stream().map(UUID::toString).toList();
        boolean cleared = context.computeOnClient(client -> {
            if (client.gui.screen() != form || !((SharedUiAccess.MembershipInput) (GuiBase) form).input().getValueWrapper().isEmpty()) return false;
            var controls = ((GuiButtonsAccess) (GuiBase) form).mcmateriallist$buttons();
            return controls.stream().noneMatch(button -> privateIds.stream().anyMatch(id ->
                ((SharedUiAccess.Button) button).label().contains(id)
                    || button.getHoverStrings().stream().anyMatch(hover -> hover.contains(id))))
                && !((SharedUiAccess.Button) button(form, "Grant member")).enabled();
        });
        capture(context, form, "issue6-participants-after-revocation");
        check(Objects.equals(localBefore, new SharedClientStore(config).load(placementId).value().regions().localDataset()),
            "Participant revocation changed original Local region binding");
        check(chooser && selectedFromSecondPage && cleared,
            "Compact participant selection/paging or first-render revoked-form privacy failed: chooser=" + chooser
                + ", selected=" + selectedFromSecondPage + ", cleared=" + cleared);
        back(context); action(context, "Find projects");
        joinDiscovered(context, original.scope());
        context.waitFor(client -> regions.shared().snapshot() != null && regions.shared().snapshot().scope().equals(original.scope())
            && !regions.pending(), 600);
        back(context);
    }
    private static boolean hasButton(GuiBase screen, String label) {
        return ((GuiButtonsAccess) screen).mcmateriallist$buttons().stream()
            .anyMatch(button -> label.equals(ChatFormatting.stripFormatting(((SharedUiAccess.Button) button).label())));
    }
    private static void joinDiscovered(ClientGameTestContext context, ProjectScope scope) {
        context.waitFor(client -> SharedClientNetworking.state().projects().stream()
            .anyMatch(project -> project.scope().equals(scope)), 600);
        String label = context.computeOnClient(client -> SharedClientNetworking.state().projects().stream()
            .filter(project -> project.scope().equals(scope)).map(project -> SharedWorkBackend.text("join", project.name()))
            .findFirst().orElseThrow(() -> new AssertionError("Target project is absent from discovery")));
        int discovered = context.computeOnClient(client -> SharedClientNetworking.state().projects().size());
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof SharedWorkScreen, "Project join requires the native management screen");
            ((SharedWorkScreen) client.gui.screen()).initGui();
        });
        // The native list has at least one project per page; the discovered count bounds a full traversal.
        for (int visited = 0; visited < discovered; visited++) {
            if (context.computeOnClient(client -> hasButton((GuiBase) client.gui.screen(), label))) {
                action(context, label);
                return;
            }
            context.runOnClient(client -> check(hasButton((GuiBase) client.gui.screen(), "Next page"),
                "Discovered project is not on this page and native pagination is unavailable"));
            action(context, "Next page");
        }
        throw new AssertionError("Discovered project was not reachable through bounded native pages");
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
        var glass = new MaterialTaskId("minecraft:glass");
        service.command(creator, new SharedCommand(created.scope(), stone, UUID.randomUUID(), 0, 0,
            new SharedCommand.Assign(creator))).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        service.command(creator, new SharedCommand(created.scope(), stone, UUID.randomUUID(), 1, 0,
            new SharedCommand.SetNote("Private viewer fixture note"))).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        service.setMembership(creator, created.scope(), 2, actor, ProjectRole.MEMBER).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        action(context, "Share"); action(context, "Find projects");
        context.waitFor(client -> SharedClientNetworking.state().projects().stream().anyMatch(project -> project.scope().equals(created.scope())), 600);
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(client -> { client.options.guiScale().set(4); client.resizeGui(); ((SharedWorkScreen) client.gui.screen()).initGui(); });
        String viewerJoin = context.computeOnClient(client -> SharedClientNetworking.state().projects().stream()
            .filter(project -> project.scope().equals(created.scope())).map(project -> SharedWorkBackend.text("join", project.name()))
            .findFirst().orElseThrow(() -> new AssertionError("Viewer fixture omitted from discovered projects")));
        context.runOnClient(client -> check(hasButton((GuiBase) client.gui.screen(), "Next page"),
            "Viewer fixture did not exercise native project pagination"));
        if (context.computeOnClient(client -> hasButton((GuiBase) client.gui.screen(), viewerJoin))) action(context, "Next page");
        context.runOnClient(client -> check(!hasButton((GuiBase) client.gui.screen(), viewerJoin),
            "Viewer fixture pagination check did not start away from the target"));
        joinDiscovered(context, created.scope());
        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
        context.waitFor(client -> work.shared().snapshot() != null && work.shared().snapshot().scope().equals(created.scope()) && !work.pending(), 600);
        back(context);
        context.runOnClient(client -> {
            screen.initGui();
            check(!((SharedUiAccess.Button) rowButton(screen, stone, MaterialDoneButton.class)).enabled()
                && ((SharedUiAccess.Button) rowButton(screen, glass, MaterialDoneButton.class)).enabled(),
                "Member wrong-row Done was enabled or own unassigned Done disabled on first native frame");
        });
        click(context, context.computeOnClient(client -> rowButton(screen, glass, MaterialDoneButton.class)));
        context.waitFor(client -> work.writable() && work.state(glass).done() && actor.equals(work.state(glass).assignee()), 600);
        click(context, context.computeOnClient(client -> rowButton(screen, glass, MaterialHeadButton.class)));
        context.waitForScreen(MaterialDetailScreen.class);
        context.runOnClient(client -> check(((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Release")).enabled()
            && ((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Save note")).enabled()
            && !((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Assign...")).enabled(),
            "Member own controls or privileged Assign affordance were wrong"));
        clickField(context, context.computeOnClient(client -> ((SharedUiAccess.MaterialDetail) client.gui.screen()).note()));
        context.getInput().typeChars("Member own note"); action(context, "Save note");
        context.waitFor(client -> work.writable() && work.state(glass).note().equals("Member own note"), 600);
        action(context, "Release"); context.waitFor(client -> work.writable() && work.state(glass).assignee() == null, 600);
        back(context);
        context.runOnClient(client -> {
            screen.initGui();
            check(!((SharedUiAccess.Button) rowButton(screen, glass, MaterialDoneButton.class)).enabled(),
                "Member could undo a completed unassigned row without Claim");
        });
        click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialHeadButton.class)));
        context.waitForScreen(MaterialDetailScreen.class);
        context.runOnClient(client -> check(!((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Save note")).enabled()
            && !((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Release")).enabled()
            && !((SharedUiAccess.Button) button((GuiBase) client.gui.screen(), "Assign...")).enabled(),
            "Member other-task native edits were enabled"));
        back(context);
        long revision = service.join(creator, created.scope()).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value().projectRevision();
        var demoted = service.setMembership(creator, created.scope(), revision, actor, ProjectRole.VIEWER).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        server.runOnServer(serverInstance -> SharedServerNetworking.publishEvents(serverInstance, demoted.events()));
        context.waitFor(client -> !work.pending() && work.shared().snapshot() == null, 600);
        action(context, "Share"); action(context, "Join / synchronize");
        context.waitFor(client -> work.shared().snapshot() != null && work.shared().role() == ProjectRole.VIEWER, 600);
        context.runOnClient(client -> check(!work.writable() && work.shared().feedback().equals(SharedWorkBackend.text("viewer")), "Native Viewer was writable or mislabeled"));
        back(context); click(context, context.computeOnClient(client -> rowButton(screen, stone, MaterialHeadButton.class)));
        context.waitForScreen(MaterialDetailScreen.class);
        context.runOnClient(client -> check(((SharedUiAccess.MaterialDetail) client.gui.screen()).note().getValueWrapper().equals("Private viewer fixture note"), "Viewer snapshot omitted authorized note"));
        revision = service.join(creator, created.scope()).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS).value().projectRevision();
        var removed = service.setMembership(creator, created.scope(), revision, actor, null).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
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
    static void action(ClientGameTestContext context, String label) {
        context.waitTick(); click(context, context.computeOnClient(client -> button((GuiBase) client.gui.screen(), label)));
    }
    static void back(ClientGameTestContext context) { action(context, "Back"); context.waitTick(); }
    static void create(ClientGameTestContext context, String name) {
        action(context, "New project..."); context.waitForScreen(SharedInputScreen.class); input(context, name); action(context, "Create empty work");
    }
    private static void input(ClientGameTestContext context, String text) {
        clickField(context, context.computeOnClient(client -> client.gui.screen() instanceof SharedMembershipScreen
            ? ((SharedUiAccess.MembershipInput) client.gui.screen()).input()
            : ((SharedUiAccess.Input) client.gui.screen()).input())); context.getInput().typeChars(text);
    }
    static ButtonBase rowButton(GuiMaterialList screen, MaterialTaskId id, Class<? extends ButtonBase> type) {
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
    static SchematicPlacement fixture(BlockPos origin) throws java.io.IOException {
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
    static ButtonBase button(GuiBase screen, String label) {
        return ((GuiButtonsAccess) screen).mcmateriallist$buttons().stream()
            .filter(button -> label.equals(ChatFormatting.stripFormatting(((SharedUiAccess.Button) button).label())))
            .findFirst().orElseThrow(() -> new AssertionError("Missing explicit native action: " + label));
    }
    static void click(ClientGameTestContext context, WidgetBase widget) {
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
