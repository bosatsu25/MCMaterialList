package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.shared.persistence.*;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.work.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedLegacyMigrationTest {
    @TempDir Path root;
    static UUID id(long n) { return new UUID(0, n); }
    Path install(String resource, String target) throws Exception {
        Path path = root.resolve(target); Files.createDirectories(path.getParent());
        try (var input = getClass().getResourceAsStream("/shared-v1/" + resource)) {
            assertNotNull(input); Files.copy(input, path);
        }
        return path;
    }
    @Test void legacyPrimaryAndBackupPreserveIdentityAndJournalWithoutOpenWrite() throws Exception {
        Path world = install("world.json", "mcmateriallist-shared/world.json");
        Path project = install("project.json", "mcmateriallist-shared/project-" + id(4) + ".json");
        Path client = install("client.json", "shared-client/placement-" + id(18) + ".json");
        byte[] worldBytes = Files.readAllBytes(world), projectBytes = Files.readAllBytes(project), clientBytes = Files.readAllBytes(client);
        var store = new SharedProjectStore(root); var clients = new SharedClientStore(root);
        assertEquals(id(1), store.openWorld().value().namespace());
        var a = store.load(id(1), id(4)).value(); assertNotNull(a); assertEquals(13, a.generation());
        assertEquals(9, a.snapshot().projectRevision()); assertEquals(4, a.snapshot().manifest().version());
        assertEquals(1, a.operations().size()); assertEquals(1, a.retired().size());
        assertEquals(new ProjectScope(id(1), id(4), id(5), id(6)), a.snapshot().scope());
        assertEquals(Map.of(id(2), ProjectRole.ADMINISTRATOR, id(3), ProjectRole.VIEWER), a.snapshot().members());
        var stone = new MaterialTaskId("minecraft:stone"); var region = new RegionTaskId(id(8));
        var instant = java.time.Instant.parse("2026-10-13T00:00:00Z");
        assertEquals(new TaskState(stone, id(2), true, "synthetic note", id(2), instant, 7), a.snapshot().rows().get(stone));
        assertEquals(new TaskState(region, null, true, "region note", id(2), instant, 3), a.snapshot().rows().get(region));
        assertEquals(Set.of(stone), a.snapshot().needsReconfirmation()); assertFalse(a.operations().getFirst().memberAllowed());
        assertEquals(id(9), a.operations().getFirst().command().operationId());
        assertEquals("retired", a.retired().getFirst().row().note()); assertEquals(5, a.retired().getFirst().row().rowVersion());
        var c = clients.load(new LocalPlacementId(id(18))).value(); assertNotNull(c); assertEquals(21, c.generation());
        assertEquals(2, c.operations().size()); assertNull(c.operations().getFirst().confirmation());
        assertNotEquals(c.materials().shared().scope(), c.regions().shared().scope());
        assertEquals(new DatasetId(id(19)), c.materials().localDataset()); assertEquals(new DatasetId(id(20)), c.regions().localDataset());
        assertEquals(id(2), c.materials().shared().actor()); assertEquals(id(10), c.materials().shared().bindingId());
        assertEquals(a.snapshot(), c.materials().cache().snapshot()); assertEquals(9, c.materials().cache().cursor());
        assertEquals(id(12), c.operations().getFirst().command().operationId());
        assertEquals(new SharedCommand.SetNote("original unknown"), c.operations().getFirst().command().intent());
        assertEquals(new SharedClientArchive.Confirmation(SharedEvents.Status.CONFLICT, 9), c.operations().get(1).confirmation());
        assertArrayEquals(worldBytes, Files.readAllBytes(world)); assertArrayEquals(projectBytes, Files.readAllBytes(project)); assertArrayEquals(clientBytes, Files.readAllBytes(client));
        var next = new ProjectArchive(14, a.snapshot(), a.operations(), a.retired());
        assertTrue(store.save(next, 13).ok());
        assertTrue(Files.readString(project).startsWith("{\"schema\":2,"), "A successful CAS must upgrade the project envelope");
        assertArrayEquals(projectBytes, Files.readAllBytes(project.resolveSibling(project.getFileName() + ".bak")));
        assertEquals(next, new SharedProjectStore(root).load(id(1), id(4)).value());
        byte[] upgraded = Files.readAllBytes(project);
        assertEquals(dev.mcmateriallist.core.persistence.StoreStatus.CONFLICT, store.save(next, 13).status());
        assertArrayEquals(upgraded, Files.readAllBytes(project));
        var nextClient = new SharedClientArchive(22, c.placement(), c.materials(), c.regions(), c.operations());
        assertTrue(clients.save(nextClient, 21).ok()); assertTrue(Files.readString(client).startsWith("{\"schema\":2,"));
        assertEquals(nextClient, new SharedClientStore(root).load(c.placement()).value());
        assertArrayEquals(worldBytes, Files.readAllBytes(world));
    }
    @Test void legacyWireAcceptsOnlyHelloNegotiationWhilePersistenceMigrates() {
        byte[] helloV1 = {0, 0, 0, 1, 0, 0, 0, 0, 1};
        assertEquals(new SharedMessage.Hello(1), SharedProtocol.decode(helloV1));
        byte[] applicationV1 = SharedProtocol.encode(new SharedMessage.ListProjects(id(30), id(31)));
        applicationV1[3] = 1;
        assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(applicationV1));
        assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decodeLegacyPersisted(helloV1));
    }

    @Test void legacyPayloadCannotMasqueradeAsCurrentSchema() throws Exception {
        Path project = install("project.json", "mcmateriallist-shared/project-" + id(4) + ".json");
        Path client = install("client.json", "shared-client/placement-" + id(18) + ".json");
        Files.writeString(project, Files.readString(project).replace("\"schema\":1", "\"schema\":2"));
        Files.writeString(client, Files.readString(client).replace("\"schema\":1", "\"schema\":2"));
        assertEquals(dev.mcmateriallist.core.persistence.StoreStatus.CORRUPT, new SharedProjectStore(root).load(id(1), id(4)).status());
        assertEquals(dev.mcmateriallist.core.persistence.StoreStatus.CORRUPT, new SharedClientStore(root).load(new LocalPlacementId(id(18))).status());
    }

    @Test void legacyRecoveryQuarantinesJournalAndPreservesDamagedBytes() throws Exception {
        install("world.json", "mcmateriallist-shared/world.json");
        Path project = install("project.json", "mcmateriallist-shared/project-" + id(4) + ".json.bak");
        Path client = install("client.json", "shared-client/placement-" + id(18) + ".json.bak");
        Path projectPrimary = project.resolveSibling("project-" + id(4) + ".json");
        Path clientPrimary = client.resolveSibling("placement-" + id(18) + ".json");
        Files.writeString(projectPrimary, "damaged project"); Files.writeString(clientPrimary, "damaged journal");
        var store = new SharedProjectStore(root); var clients = new SharedClientStore(root); var placement = new LocalPlacementId(id(18));
        assertEquals(dev.mcmateriallist.core.persistence.StoreStatus.RECOVERY_REQUIRED, store.load(id(1), id(4)).status());
        assertEquals(dev.mcmateriallist.core.persistence.StoreStatus.RECOVERY_REQUIRED, clients.load(placement).status());
        assertNull(clients.load(placement).value());
        byte[] oldProject = Files.readAllBytes(project), oldClient = Files.readAllBytes(client);
        var recoveredProject = store.recover(id(1), id(4)); assertTrue(recoveredProject.ok()); assertEquals(13, recoveredProject.value().generation());
        var recovered = clients.recover(placement); assertTrue(recovered.ok());
        assertTrue(recovered.value().recoveredJournalUncertain()); assertNull(recovered.value().materials().cache()); assertNull(recovered.value().regions().cache());
        assertEquals(2, recovered.value().operations().size()); assertNull(recovered.value().operations().getFirst().confirmation());
        assertEquals(id(12), recovered.value().operations().getFirst().command().operationId());
        assertEquals(new SharedCommand.SetNote("original unknown"), recovered.value().operations().getFirst().command().intent());
        assertEquals(id(21), recovered.value().operations().getFirst().connection());
        assertEquals(recovered.value(), new SharedClientStore(root).load(placement).value());
        assertArrayEquals(oldProject, Files.readAllBytes(project)); assertArrayEquals(oldClient, Files.readAllBytes(client));
        for (Path directory : List.of(project.getParent(), client.getParent())) try (var files = Files.list(directory)) {
            var retained = files.filter(path -> path.toString().endsWith(".retained")).toList(); assertEquals(1, retained.size());
            assertEquals(directory.equals(project.getParent()) ? "damaged project" : "damaged journal", Files.readString(retained.getFirst()));
        }
    }
    @Test void legacyOperationNeverInventsMemberPermissionAfterDemotion() throws Exception {
        install("world.json", "mcmateriallist-shared/world.json");
        Path project = install("project.json", "mcmateriallist-shared/project-" + id(4) + ".json");
        var store = new SharedProjectStore(root); var archive = store.load(id(1), id(4)).value();
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-13T00:30:00Z"), java.time.ZoneOffset.UTC);
        try (var service = new SharedProjectService(store, clock)) {
            SharedAuthorityTest.await(service.ready());
            var op = archive.operations().getFirst();
            assertEquals(op.result(), SharedAuthorityTest.await(service.command(id(2), op.command())).result());
            var grant = SharedAuthorityTest.await(service.setMembership(id(2), archive.snapshot().scope(), 9, id(3), ProjectRole.ADMINISTRATOR));
            assertEquals(SharedEvents.Status.OK, grant.status());
            var demote = SharedAuthorityTest.await(service.setMembership(id(3), archive.snapshot().scope(), 10, id(2), ProjectRole.MEMBER));
            assertEquals(SharedEvents.Status.OK, demote.status()); byte[] bytes = Files.readAllBytes(project);
            assertEquals(SharedEvents.Status.DENIED, SharedAuthorityTest.await(service.command(id(2), op.command())).result().status());
            assertArrayEquals(bytes, Files.readAllBytes(project));
        }
    }

}
