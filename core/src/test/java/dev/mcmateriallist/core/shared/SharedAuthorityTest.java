package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.work.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mcmateriallist.core.shared.SharedEvents.Status.*;

class SharedAuthorityTest {
    @TempDir Path root;
    final UUID owner = UUID.randomUUID(), viewer = UUID.randomUUID(), stranger = UUID.randomUUID();
    static final MaterialTaskId STONE = new MaterialTaskId("minecraft:stone"), DIRT = new MaterialTaskId("minecraft:dirt");
    static SharedManifest.Definition definition() {
        return definition(10, new RegionDescriptor("original", new Vector3(0, 0, 0), new Vector3(-2, 3, 4)));
    }
    static SharedManifest.Definition definition(long count, RegionDescriptor region) {
        return new SharedManifest.Definition("a".repeat(64), new SharedManifest.Baseline("minecraft:overworld", new Vector3(1, 2, 3),
            SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), Map.of(STONE, count, DIRT, 20L), List.of(region));
    }
    static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(20, TimeUnit.SECONDS); }
    ProjectSnapshot create(SharedProjectService service) throws Exception {
        UUID world = await(service.ready()).worldNamespace();
        var result = await(service.create(owner, world, "shared work", definition())); assertEquals(OK, result.status()); return result.value();
    }
    static SharedCommand command(ProjectSnapshot snapshot, TaskId row, long version, SharedCommand.Intent intent) {
        return new SharedCommand(snapshot.scope(), row, UUID.randomUUID(), version, snapshot.manifest().version(), intent);
    }
    @Test void privateDiscoveryGrantReadOnlyAndRevocationAreDurable() throws Exception {
        ProjectSnapshot initial;
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            initial = create(service); UUID world = initial.scope().worldNamespace();
            assertTrue(await(service.discover(viewer, world)).value().isEmpty());
            assertEquals(DENIED, await(service.join(viewer, initial.scope())).status());
            var grant = await(service.setViewer(owner, initial.scope(), 0, viewer, true)); assertEquals(OK, grant.status());
            assertEquals(1, await(service.discover(viewer, world)).value().size());
            assertEquals(OK, await(service.join(viewer, initial.scope())).status());
            assertEquals(DENIED, await(service.command(viewer, command(initial, STONE, 0, new SharedCommand.Claim()))).result().status());
            assertTrue(service.publishIfAuthorized(viewer, initial.scope(), () -> {}));
            var revoke = await(service.setViewer(owner, initial.scope(), 1, viewer, false)); assertEquals(OK, revoke.status());
            assertTrue(revoke.events().stream().anyMatch(e -> e instanceof SharedEvents.AccessRevoked));
            assertFalse(service.publishIfAuthorized(viewer, initial.scope(), () -> fail("Revoked publication")));
        }
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            await(service.ready()); assertEquals(DENIED, await(service.join(viewer, initial.scope())).status());
            assertEquals(2, await(service.join(owner, initial.scope())).value().projectRevision());
        }
    }
    @Test void independentRowsVersionsIntentReplayAndProgress() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var snapshot = create(service); var region = snapshot.manifest().regions().keySet().iterator().next();
            var done = command(snapshot, STONE, 0, new SharedCommand.SetDone(true));
            var result = await(service.command(owner, done)); assertEquals(OK, result.result().status()); assertEquals(1, result.events().size());
            assertEquals(result.result(), await(service.command(owner, done)).result());
            assertTrue(await(service.command(owner, done)).events().isEmpty());
            assertEquals(OK, await(service.command(owner, command(snapshot, DIRT, 0, new SharedCommand.SetNote("soil")))).result().status());
            assertEquals(CONFLICT, await(service.command(owner, command(snapshot, STONE, 0, new SharedCommand.SetDone(false)))).result().status());
            assertEquals(OK, await(service.command(owner, command(snapshot, region, 0, new SharedCommand.SetDone(true)))).result().status());
            var current = await(service.join(owner, snapshot.scope())).value();
            assertEquals(1, current.materialProgress().completed()); assertEquals(2, current.materialProgress().total());
            assertEquals(1, current.regionProgress().completed()); assertEquals(1, current.regionProgress().total());
            assertEquals(3, current.projectRevision()); assertEquals(0, current.manifest().version());
            assertEquals(owner, current.rows().get(STONE).completedBy());
        }
    }
    @Test void exactRegionReconciliationPreservesOnlyExactIdentityAndArchivesOldWork() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var initial = create(service); RegionTaskId region = initial.manifest().regions().keySet().iterator().next();
            await(service.command(owner, command(initial, region, 0, new SharedCommand.SetDone(true))));
            await(service.command(owner, command(initial, STONE, 0, new SharedCommand.SetDone(true))));
            var same = await(service.updateManifest(owner, initial.scope(), 2, 0, definition(30, definition().regions().getFirst())));
            assertEquals(OK, same.status()); assertTrue(same.value().rows().get(region).done());
            assertTrue(same.value().needsReconfirmation().contains(STONE));
            var changed = await(service.updateManifest(owner, initial.scope(), 3, 1,
                definition(30, new RegionDescriptor("original", new Vector3(1, 0, 0), new Vector3(-2, 3, 4)))));
            assertEquals(OK, changed.status()); assertFalse(changed.value().rows().containsKey(region));
            assertEquals(0, changed.value().regionProgress().completed());
            assertInstanceOf(SharedEvents.ProjectInvalidated.class, changed.events().getFirst());
            var archive = new SharedProjectStore(root).load(initial.scope().worldNamespace(), initial.scope().projectId()).value();
            assertEquals(region, archive.retired().getFirst().row().taskId()); assertTrue(archive.retired().getFirst().row().done());
        }
    }
    @Test void notesStrictUtf8AndBinaryRoundTrip() {
        assertThrows(IllegalArgumentException.class, () -> new SharedCommand.SetNote("界".repeat(1366)));
        assertThrows(IllegalArgumentException.class, () -> new SharedCommand.SetNote("\ud800"));
        var command = new SharedCommand(ProjectScope.create(UUID.randomUUID()), STONE, UUID.randomUUID(), 0, 0, new SharedCommand.SetNote("界".repeat(1365)));
        var message = new SharedMessage.TaskCommand(command); assertEquals(message, SharedProtocol.decode(SharedProtocol.encode(message)));
        byte[] bytes = SharedProtocol.encode(message); assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(Arrays.copyOf(bytes, bytes.length - 1)));
    }
    @Test void saveFailureDoesNotPublishAndRestartReplaysConfirmedResult() throws Exception {
        ProjectSnapshot initial; SharedCommand done; SharedEvents.CommandResult confirmed;
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            initial = create(service); done = command(initial, STONE, 0, new SharedCommand.SetDone(true));
            confirmed = await(service.command(owner, done)).result();
        }
        try (var service = new SharedProjectService(new SharedProjectStore(root, stage -> { throw new java.io.IOException("injected"); }))) {
            await(service.ready()); assertEquals(confirmed, await(service.command(owner, done)).result());
            var failed = await(service.command(owner, command(initial, STONE, 1, new SharedCommand.SetDone(false))));
            assertEquals(SAVE_FAILED, failed.result().status()); assertTrue(failed.events().isEmpty());
            assertTrue(service.committedSnapshot(owner, initial.scope()).orElseThrow().rows().get(STONE).done());
        }
    }
}
