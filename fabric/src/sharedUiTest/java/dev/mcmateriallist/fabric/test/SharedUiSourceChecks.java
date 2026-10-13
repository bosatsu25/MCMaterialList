package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.work.*;
import dev.mcmateriallist.fabric.client.material.MaterialWorkSession;
import dev.mcmateriallist.fabric.client.shared.*;
import dev.mcmateriallist.fabric.shared.SharedServerNetworking;
import dev.mcmateriallist.fabric.test.mixin.SharedUiSchematicEdit;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.world.level.block.Blocks;

/** Source identity regressions use the real loaded native object, count, edit body, file and socket. */
final class SharedUiSourceChecks {
    private SharedUiSourceChecks() {}
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void beforeCreate(ClientGameTestContext context, TestDedicatedServerContext server, SchematicPlacement placement,
            MaterialListBase materials, MaterialWorkSession work, Path local) throws Exception {
        byte[] bytes = Files.readAllBytes(placement.getSchematicFile()), localBytes = Files.readAllBytes(local);
        var actor = context.computeOnClient(client -> client.player.getUUID());
        var world = context.computeOnClient(client -> SharedClientNetworking.state().capabilities().worldNamespace());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var projects = service.discover(actor, world).toCompletableFuture().get(10, TimeUnit.SECONDS);
        replace(context, placement);
        byte[] replacement = Files.readAllBytes(placement.getSchematicFile());
        check(!Arrays.equals(bytes, replacement), "Replacement fixture did not change the saved bytes");
        try {
            context.runOnClient(client -> {
                check(placement.getSchematic().getSubRegionContainer("First region").get(0, 0, 0).is(Blocks.STONE), "Replacement changed the loaded instance");
                check(!NativeSharedCapture.capture(placement, materials, true).ok(),
                    "Disk replacement before first Create adopted a different file for the old loaded count");
                work.shared().create("Refused source replacement");
                check(!work.shared().pending() && !work.shared().selected(), "Replaced source admitted first Create");
                materials.reCreateMaterialList();
            });
            context.waitFor(client -> work.ready(), 600);
            context.runOnClient(client -> {
                check(!NativeSharedCapture.capture(placement, materials, true).ok(), "Recount laundered stale loaded contents onto replacement bytes");
                work.shared().create("Refused stale recount");
            });
            check(projects.equals(service.discover(actor, world).toCompletableFuture().get(10, TimeUnit.SECONDS)), "Refused Create changed authority");
            check(Arrays.equals(replacement, Files.readAllBytes(placement.getSchematicFile())), "Refusal changed replacement source bytes");
            check(Arrays.equals(localBytes, Files.readAllBytes(local)), "Refusal changed preserved local data");
        } finally { Files.write(placement.getSchematicFile(), bytes); }
        reload(context, placement, materials);
    }
    static void loadedEdit(ClientGameTestContext context, TestDedicatedServerContext server, SchematicPlacement placement,
            MaterialListBase materials, MaterialWorkSession work, Path local) throws Exception {
        byte[] bytes = Files.readAllBytes(placement.getSchematicFile()), localBytes = Files.readAllBytes(local);
        var before = context.computeOnClient(client -> work.shared().snapshot());
        int submissions = SharedUiDelays.submissions;
        context.runOnClient(client -> {
            edit(placement);
            check(!NativeSharedCapture.capture(placement, materials, false).ok() && !work.writable(),
                "Native same-geometry unsaved edit remained eligible for shared commands");
            work.shared().command(new MaterialTaskId("minecraft:stone"), new TaskCommand.SetDone(true));
            work.shared().create("Refused unsaved design");
            work.shared().join(before.scope());
        });
        check(submissions == SharedUiDelays.submissions, "Unsaved edit reached durable command admission");
        check(Arrays.equals(bytes, Files.readAllBytes(placement.getSchematicFile())), "Native unsaved edit wrote source bytes");
        reload(context, placement, materials); synchronize(context, work);
        // Upstream Reload retains its old true flag. A second real edit must still invalidate the new proof.
        context.runOnClient(client -> { check(placement.getSchematic().getMetadata().wasModifiedSinceSaved(), "Pinned reload flag precondition changed"); edit(placement); check(!work.writable(), "Second edit with already-true modified flag escaped the edit epoch"); });
        reload(context, placement, materials); synchronize(context, work);
        for (boolean disk : new boolean[]{false, true}) {
            heldCount(context, placement, materials, disk, bytes);
            synchronize(context, work);
            heldDigest(context, placement, materials, work, disk, bytes);
            synchronize(context, work);
        }
        var actor = context.computeOnClient(client -> client.player.getUUID());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var after = service.join(actor, before.scope()).toCompletableFuture().get(10, TimeUnit.SECONDS);
        check(after.value().equals(before), "Source refusal changed server rows, manifest or revision");
        check(Arrays.equals(localBytes, Files.readAllBytes(local)) && Arrays.equals(bytes, Files.readAllBytes(placement.getSchematicFile())),
            "Source guard changed local work or original source bytes");
        // Keep intentional durable unknowns out of the original baseline project's binding.
        context.runOnClient(client -> work.shared().local());
        context.waitFor(client -> !work.shared().selected() && !work.shared().pending(), 600);
        context.runOnClient(client -> work.shared().create("Source admission fixture"));
        context.waitFor(client -> work.shared().selected() && work.writable(), 600);
        for (boolean disk : new boolean[]{false, true}) heldAdmission(context, server, placement, materials, work, disk, bytes, local);
        context.runOnClient(client -> work.shared().local());
        context.waitFor(client -> !work.shared().selected() && !work.shared().pending(), 600);
        context.runOnClient(client -> work.shared().join(before.scope()));
        context.waitFor(client -> work.shared().selected() && work.shared().snapshot() != null
            && work.writable() && work.shared().snapshot().scope().equals(before.scope()), 600);
    }
    private static void heldAdmission(ClientGameTestContext context, TestDedicatedServerContext server, SchematicPlacement placement,
            MaterialListBase materials, MaterialWorkSession work, boolean disk, byte[] original, Path local) throws Exception {
        var snapshot = context.computeOnClient(client -> work.shared().snapshot());
        var reference = context.computeOnClient(client -> work.shared().reference());
        var actor = context.computeOnClient(client -> client.player.getUUID());
        var service = server.computeOnServer(SharedServerNetworking::service);
        var id = context.computeOnClient(client -> dev.mcmateriallist.fabric.client.work.PlacementWorkAdapter.captureRegions(placement).snapshot().placementId());
        var store = new SharedClientStore(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mcmateriallist"));
        byte[] localBytes = Files.readAllBytes(local);
        var gate = new CompletableFuture<Void>(); SharedUiDelays.admission = gate; SharedUiDelays.admitted = false;
        SharedClientArchive.Operation admitted;
        try {
            context.runOnClient(client -> work.shared().command(new MaterialTaskId("minecraft:stone"), new TaskCommand.SetDone(true)));
            context.waitFor(client -> SharedUiDelays.admitted, 600);
            var exactCommand = SharedUiDelays.command;
            admitted = store.load(id).value().operations().stream()
                .filter(operation -> operation.command().operationId().equals(exactCommand.operationId())).findFirst().orElseThrow();
            check(admitted.confirmation() == null && admitted.original().equals(reference)
                && admitted.command().equals(exactCommand) && admitted.kind() == DatasetKind.MATERIALS,
                "Held source fixture did not identify its exact newly persisted command and binding");
            if (disk) replace(context, placement); else context.runOnClient(client -> edit(placement));
            context.runOnClient(client -> gate.complete(null));
            context.waitFor(client -> SharedUiDelays.submitted.toCompletableFuture().isDone() && !work.shared().pending(), 600);
            check(!SharedUiDelays.submitted.toCompletableFuture().join(), "Changed loaded source during durable admission was sent");
            check(store.load(id).value().operations().contains(admitted), "Source refusal cancelled the original durable unknown operation");
            check(service.join(actor, snapshot.scope()).toCompletableFuture().get(10, TimeUnit.SECONDS).value().equals(snapshot),
                "Source mutation during admission changed authority");
        } finally {
            SharedUiDelays.admission = null; context.runOnClient(client -> gate.complete(null));
            if (disk) Files.write(placement.getSchematicFile(), original);
        }
        reload(context, placement, materials);
        context.runOnClient(client -> work.shared().local());
        context.waitFor(client -> !work.shared().selected() && !work.shared().pending(), 600);
        check(store.load(id).value().operations().contains(admitted), "Local fallback remapped original unknown operation");
        context.runOnClient(client -> work.shared().create("Reloaded clean source"));
        context.waitFor(client -> work.shared().selected() && work.writable(), 600);
        check(Arrays.equals(localBytes, Files.readAllBytes(local)) && Arrays.equals(original, Files.readAllBytes(placement.getSchematicFile())),
            "Recovered source workflow rewrote original source or local data");
    }
    private static void heldCount(ClientGameTestContext context, SchematicPlacement placement, MaterialListBase materials,
            boolean disk, byte[] original) throws Exception {
        SharedUiDelays.publishCount = null; SharedUiDelays.holdCount = true;
        try {
            context.runOnClient(client -> materials.reCreateMaterialList());
            context.waitFor(client -> SharedUiDelays.publishCount != null, 600);
            if (disk) replace(context, placement); else context.runOnClient(client -> edit(placement));
            context.runOnClient(client -> {
                SharedUiDelays.publishCount.run(); SharedUiDelays.publishCount = null;
                check(!NativeSharedCapture.capture(placement, materials, true).ok(), "Changed source became a full count at held native publication");
            });
        } finally {
            SharedUiDelays.holdCount = false;
            context.runOnClient(client -> { if (SharedUiDelays.publishCount != null) { SharedUiDelays.publishCount.run(); SharedUiDelays.publishCount = null; } });
            if (disk) Files.write(placement.getSchematicFile(), original);
        }
        reload(context, placement, materials);
    }
    private static void heldDigest(ClientGameTestContext context, SchematicPlacement placement, MaterialListBase materials,
            MaterialWorkSession work, boolean disk, byte[] original) throws Exception {
        var gate = new CompletableFuture<Void>(); SharedUiDelays.digest = gate; SharedUiDelays.digestHeld = false;
        int submissions = SharedUiDelays.submissions;
        try {
            context.runOnClient(client -> work.shared().command(new MaterialTaskId("minecraft:stone"), new TaskCommand.SetDone(true)));
            context.waitFor(client -> SharedUiDelays.digestHeld, 600);
            if (disk) replace(context, placement); else context.runOnClient(client -> edit(placement));
            context.runOnClient(client -> gate.complete(null));
            context.waitFor(client -> !work.shared().pending(), 600);
            check(submissions == SharedUiDelays.submissions, "Changed source during held hash reached command admission");
        } finally {
            SharedUiDelays.digest = null; context.runOnClient(client -> gate.complete(null));
            if (disk) Files.write(placement.getSchematicFile(), original);
        }
        reload(context, placement, materials);
    }
    static void reload(ClientGameTestContext context, SchematicPlacement placement, MaterialListBase materials) {
        context.runOnClient(client -> {
            check(placement.getSchematic().readFromFile(), "Native clean source reload failed");
            DataManager.getSchematicPlacementManager().markAllPlacementsOfSchematicForRebuild(placement.getSchematic());
        });
        context.waitFor(client -> SchematicWorldHandler.getSchematicWorld().getBlockState(placement.getOrigin()).is(Blocks.STONE), 600);
        context.waitFor(client -> NativeSharedCapture.capture(placement, null, false).ok(), 600);
        context.runOnClient(client -> materials.reCreateMaterialList());
        context.waitFor(client -> NativeSharedCapture.capture(placement, materials, true).ok(), 600);
    }
    private static void synchronize(ClientGameTestContext context, MaterialWorkSession work) {
        context.runOnClient(client -> work.shared().refresh());
        context.waitFor(client -> work.writable(), 600);
    }
    private static void edit(SchematicPlacement placement) {
        check(SharedUiSchematicEdit.edit(placement.getOrigin(), Blocks.DIRT.defaultBlockState())
            && placement.getSchematic().getSubRegionContainer("First region").get(0, 0, 0).is(Blocks.DIRT)
            && placement.getSchematic().getMetadata().wasModifiedSinceSaved(), "Native edit fixture did not mutate the loaded source");
    }
    private static void replace(ClientGameTestContext context, SchematicPlacement placement) throws Exception {
        Path source = placement.getSchematicFile(), directory = source.getParent();
        context.runOnClient(client -> {
            var replacement = LitematicaSchematic.createFromFile(directory, source.getFileName().toString());
            check(replacement != null && replacement.getAreaSizes().equals(placement.getSchematic().getAreaSizes())
                && replacement.getAreaPositions().equals(placement.getSchematic().getAreaPositions()), "Replacement geometry precondition failed");
            replacement.getSubRegionContainer("First region").set(0, 0, 0, Blocks.DIRT.defaultBlockState());
            check(replacement.writeToFile(directory, "replacement", true), "Could not save actual replacement fixture");
        });
        Files.move(directory.resolve("replacement.litematic"), source, StandardCopyOption.REPLACE_EXISTING);
    }
}
