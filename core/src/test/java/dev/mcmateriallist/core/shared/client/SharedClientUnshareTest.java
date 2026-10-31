package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.*;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.client.SharedClientService.UnshareReason;
import dev.mcmateriallist.core.work.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Issue #11: unshare regions to a detached local copy, local edits, rejoin export, storage schema 3. */
class SharedClientUnshareTest {
    @TempDir Path root;
    private final LocalPlacementId placement = LocalPlacementId.create();
    private final UUID actor = UUID.randomUUID(), connection = UUID.randomUUID();
    private final MaterialTaskId stone = new MaterialTaskId("minecraft:stone");
    private final RegionTaskId tower = RegionTaskId.create(), wall = RegionTaskId.create();
    private final DatasetId localMaterials = DatasetId.create(), localRegions = DatasetId.create();
    private final AtomicReference<SharedClientStore.WriteStage> fail = new AtomicReference<>();
    private final SharedClientStore.WriteHook hook = stage -> { if (stage == fail.get()) throw new IOException("injected " + stage); };

    private SharedManifest manifest() {
        var a = new RegionDescriptor("Tower", new Vector3(0, 0, 0), new Vector3(3, 4, 3));
        var b = new RegionDescriptor("Wall", new Vector3(5, 0, 0), new Vector3(1, 2, 8));
        var definition = new SharedManifest.Definition("c".repeat(64), new SharedManifest.Baseline("minecraft:overworld", new Vector3(0, 64, 0),
            SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), Map.of(stone, 7L), List.of(a, b));
        return new SharedManifest(0, definition, Map.of(tower, a, wall, b));
    }
    private ProjectSnapshot snapshot(ProjectScope scope, long revision, Map<TaskId, TaskState> changed, Set<TaskId> review) {
        var manifest = manifest(); Map<TaskId, TaskState> rows = new HashMap<>();
        manifest.taskIds().forEach(id -> rows.put(id, TaskState.empty(id))); rows.putAll(changed);
        return new ProjectSnapshot(scope, "unshare fixture", actor, Set.of(), revision, manifest, rows, review);
    }
    private final ProjectScope materialScope = ProjectScope.create(UUID.randomUUID()), regionScope = ProjectScope.create(materialScope.worldNamespace());
    private final ProjectSnapshot regions0 = snapshot(regionScope, 0, Map.of(), Set.of());
    private final SharedClientArchive.Reference materialRef = new SharedClientArchive.Reference(UUID.randomUUID(), actor, materialScope),
        regionRef = new SharedClientArchive.Reference(UUID.randomUUID(), actor, regionScope);

    private SharedClientArchive archive(SharedClientArchive.Binding regions, List<SharedClientArchive.Operation> operations, boolean recovered) {
        return new SharedClientArchive(0, placement,
            new SharedClientArchive.Binding(localMaterials, materialRef, new SharedClientArchive.Cache(snapshot(materialScope, 0, Map.of(), Set.of()), 0)),
            regions, operations, recovered);
    }
    private SharedClientArchive shared(List<SharedClientArchive.Operation> operations) {
        return archive(new SharedClientArchive.Binding(localRegions, regionRef, new SharedClientArchive.Cache(regions0, 0)), operations, false);
    }
    private SharedClientArchive install(SharedClientArchive archive) {
        assertEquals(StoreStatus.OK, new SharedClientStore(root).save(archive, -1).status()); return archive;
    }
    private Path file() { return root.resolve("shared-client").resolve("placement-" + placement + ".json"); }
    private Path backup() { return file().resolveSibling(file().getFileName() + ".bak"); }
    private SharedClientArchive stored() { return new SharedClientStore(root).load(placement).value(); }
    private SharedCommand regionCommand(ProjectScope scope) {
        return new SharedCommand(scope, tower, UUID.randomUUID(), 0, 0, new SharedCommand.SetDone(true));
    }
    private SharedClientArchive.Operation operation(SharedClientArchive.Reference ref, SharedClientArchive.Confirmation confirmation) {
        return new SharedClientArchive.Operation(DatasetKind.REGIONS, ref, connection, regionCommand(ref.scope()), confirmation);
    }
    private TaskState done(RegionTaskId id, long version) { return new TaskState(id, actor, true, "", actor, Instant.EPOCH, version); }

    @Test void copyEqualsServerRowsAndManifestWithFreshIdAndOnlyRegionReview() {
        var server = snapshot(regionScope, 4, Map.of(tower, done(tower, 3), stone, new TaskState(stone, actor, false, "m", null, null, 2)), Set.of(wall, stone));
        var id = DatasetId.create(); var copy = RegionDetachment.copy(id, placement, server);
        assertEquals(id, copy.id()); assertEquals(placement, copy.placementId()); assertEquals(0, copy.generation());
        assertEquals(Map.of(tower, new RegionDefinition(tower, server.manifest().regions().get(tower)), wall, new RegionDefinition(wall, server.manifest().regions().get(wall))), copy.definitions());
        assertEquals(Map.of(tower, server.rows().get(tower), wall, server.rows().get(wall)), copy.states());
        assertEquals(Set.of(wall), copy.reviewRequired()); assertTrue(copy.archived().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RegionDetachment.copy(new DatasetId(regionScope.regionListId()), placement, server));
    }

    @Test void bindingInvariantsRejectMixedStatesReusedIdentitiesAndMaterialsOrForeignPlacement() {
        var copy = RegionDetachment.copy(DatasetId.create(), placement, regions0); var detached = new SharedClientArchive.Detached(copy, regionScope);
        assertNull(new SharedClientArchive.Binding(localRegions, null, null).detached());
        assertThrows(IllegalArgumentException.class, () -> new SharedClientArchive.Binding(localRegions, regionRef, null, detached));
        assertThrows(IllegalArgumentException.class, () -> new SharedClientArchive.Binding(copy.id(), null, null, detached));
        assertThrows(IllegalArgumentException.class, () -> new SharedClientArchive.Detached(copy, new ProjectScope(regionScope.worldNamespace(), regionScope.projectId(), regionScope.materialListId(), copy.id().value())));
        var binding = new SharedClientArchive.Binding(localRegions, null, null, detached);
        assertThrows(IllegalArgumentException.class, () -> binding.withCache(new SharedClientArchive.Cache(regions0, 0)));
        assertEquals(binding, binding.withCache(null));
        assertThrows(IllegalArgumentException.class, () -> new SharedClientArchive(0, placement, binding, new SharedClientArchive.Binding(null, null, null), List.of()));
        var foreign = new SharedClientArchive.Detached(RegionDetachment.copy(DatasetId.create(), LocalPlacementId.create(), regions0), regionScope);
        assertThrows(IllegalArgumentException.class, () -> new SharedClientArchive(0, placement, new SharedClientArchive.Binding(null, null, null),
            new SharedClientArchive.Binding(localRegions, null, null, foreign), List.of()));
    }

    @Test void unshareReplacesOnlyRegionsBindingAndLeavesMaterialsJournalAndOriginalRegionsBytes() throws Exception {
        var local = new LocalDatasetStore(root);
        var original = new RegionWorkDataset(localRegions, placement, 0, Map.of(), Map.of(), Map.of(), Set.of());
        assertTrue(local.save(original, -1).ok());
        Path regionsJson = root.resolve("placements").resolve(placement.toString()).resolve("regions.json"); byte[] originalBytes = Files.readAllBytes(regionsJson);
        var unknownMaterial = new SharedClientArchive.Operation(DatasetKind.MATERIALS, materialRef, connection,
            new SharedCommand(materialScope, stone, UUID.randomUUID(), 0, 0, new SharedCommand.Claim()), null);
        var confirmedRegion = operation(regionRef, new SharedClientArchive.Confirmation(SharedEvents.Status.OK, 0));
        var before = install(shared(List.of(unknownMaterial, confirmedRegion)));
        assertTrue(SharedClientService.unshareCheck(before, actor, 0).allowed());
        var id = DatasetId.create();
        try (var service = new SharedClientService(root)) {
            var result = service.unshare(placement, regionRef, 0, actor, id).join(); assertTrue(result.ok());
            var after = result.value();
            assertEquals(before.materials(), after.materials()); assertEquals(before.operations(), after.operations());
            assertFalse(after.recoveredJournalUncertain()); assertEquals(1, after.generation());
            assertEquals(new SharedClientArchive.Binding(localRegions, null, null,
                new SharedClientArchive.Detached(RegionDetachment.copy(id, placement, regions0), regionScope)), after.regions());
            assertEquals(after, stored()); byte[] committed = Files.readAllBytes(file());
            assertTrue(Files.readString(file()).startsWith("{\"schema\":3,"));
            var retry = service.unshare(placement, regionRef, 0, actor, id).join();
            assertTrue(retry.ok()); assertEquals(after, retry.value()); assertArrayEquals(committed, Files.readAllBytes(file()), "Same-ID retry rewrote");
            assertEquals(StoreStatus.CONFLICT, service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().status());
            assertEquals(UnshareReason.NOT_SHARED, SharedClientService.unshareCheck(after, actor, 0).reason());
        }
        assertArrayEquals(originalBytes, Files.readAllBytes(regionsJson)); assertEquals(original, local.load(placement, DatasetKind.REGIONS).dataset());
    }

    @Test void eachPreconditionRefusesWithoutChangingDisk() throws Exception {
        var otherScope = ProjectScope.create(regionScope.worldNamespace());
        var otherRef = new SharedClientArchive.Reference(UUID.randomUUID(), UUID.randomUUID(), otherScope);
        record Case(SharedClientArchive archive, SharedClientArchive.Reference ref, UUID actor, long cursor, UnshareReason reason) {}
        var stranger = UUID.randomUUID();
        var cases = List.of(
            new Case(archive(new SharedClientArchive.Binding(localRegions, null, null), List.of(), false), regionRef, actor, 0, UnshareReason.NOT_SHARED),
            new Case(shared(List.of()), new SharedClientArchive.Reference(UUID.randomUUID(), actor, regionScope), actor, 0, null),
            new Case(shared(List.of()), regionRef, stranger, 0, UnshareReason.ACCOUNT_MISMATCH),
            new Case(archive(new SharedClientArchive.Binding(localRegions, regionRef, null), List.of(), false), regionRef, actor, 0, UnshareReason.JOIN_REQUIRED),
            new Case(shared(List.of()), regionRef, actor, 1, UnshareReason.JOIN_REQUIRED),
            // P3 is strict: another account's and another scope's unknown REGIONS original also blocks.
            new Case(shared(List.of(operation(otherRef, null))), regionRef, actor, 0, UnshareReason.UNRESOLVED_OPERATIONS),
            new Case(archive(shared(List.of()).regions(), List.of(), true), regionRef, actor, 0, UnshareReason.RECOVERED_UNCERTAIN),
            new Case(shared(List.of(operation(regionRef, new SharedClientArchive.Confirmation(SharedEvents.Status.OK, 1)))), regionRef, actor, 0, UnshareReason.REFRESH_REQUIRED));
        try (var service = new SharedClientService(root)) {
            for (var test : cases) {
                Files.deleteIfExists(file()); Files.deleteIfExists(backup()); install(test.archive());
                byte[] bytes = Files.readAllBytes(file());
                if (test.reason() != null) assertEquals(test.reason(), SharedClientService.unshareCheck(test.archive(), test.actor(), test.cursor()).reason());
                assertEquals(StoreStatus.CONFLICT, service.unshare(placement, test.ref(), test.cursor(), test.actor(), DatasetId.create()).join().status(), String.valueOf(test.reason()));
                assertArrayEquals(bytes, Files.readAllBytes(file())); assertFalse(Files.exists(backup()));
            }
        }
        var counted = SharedClientService.unshareCheck(shared(List.of(operation(otherRef, null), operation(regionRef, null))), actor, 0);
        assertEquals(new SharedClientService.UnshareCheck(UnshareReason.UNRESOLVED_OPERATIONS, 2), counted);
        assertEquals("unresolved_operations", counted.reason().key());
    }

    @Test void unknownAndNonDefinitiveReplayBlockAndOkResolutionStillWaitsForTheCacheCursor() {
        install(shared(List.of()));
        var command = regionCommand(regionScope);
        try (var service = new SharedClientService(root)) {
            assertTrue(service.admit(placement, DatasetKind.REGIONS, regionRef, actor, connection, command).join().ok());
            assertEquals(StoreStatus.CONFLICT, service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().status(), "Admit first: P3 must refuse");
            assertEquals(new SharedClientService.UnshareCheck(UnshareReason.UNRESOLVED_OPERATIONS, 1), SharedClientService.unshareCheck(stored(), actor, 0));
            var unavailable = new SharedEvents.CommandResult(regionScope, command.operationId(), SharedEvents.Status.UNAVAILABLE, 0, null);
            assertEquals(StoreStatus.CONFLICT, service.resolve(placement, actor, unavailable).join().status());
            assertEquals(UnshareReason.UNRESOLVED_OPERATIONS, SharedClientService.unshareCheck(stored(), actor, 0).reason());
            assertEquals(StoreStatus.CONFLICT, service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().status());
            var ok = new SharedEvents.CommandResult(regionScope, command.operationId(), SharedEvents.Status.OK, 1, done(tower, 1));
            assertTrue(service.resolve(placement, actor, ok).join().ok());
            assertEquals(UnshareReason.REFRESH_REQUIRED, SharedClientService.unshareCheck(stored(), actor, 0).reason());
            assertEquals(StoreStatus.CONFLICT, service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().status(), "P5: cache predates own OK edit");
            var caught = snapshot(regionScope, 1, Map.of(tower, done(tower, 1)), Set.of());
            assertTrue(service.cache(placement, DatasetKind.REGIONS, regionRef, caught, 1).join().ok());
            var detached = service.unshare(placement, regionRef, 1, actor, DatasetId.create()).join();
            assertTrue(detached.ok()); assertEquals(done(tower, 1), detached.value().regions().detached().dataset().states().get(tower), "Own confirmed edit missing from copy");
        }
    }

    @Test void unshareFirstMakesLaterAdmissionFailItsBindingCheck() {
        install(shared(List.of()));
        try (var service = new SharedClientService(root)) {
            assertTrue(service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().ok());
            var before = stored();
            assertEquals(StoreStatus.CONFLICT, service.admit(placement, DatasetKind.REGIONS, regionRef, actor, connection, regionCommand(regionScope)).join().status());
            assertEquals(before, stored()); assertTrue(stored().operations().isEmpty());
        }
    }

    @Test void injectedFailureAtEitherStageLeavesRegionsSharedUntilRetry() throws Exception {
        for (var stage : List.of(SharedClientStore.WriteStage.BEFORE_BACKUP_REPLACE, SharedClientStore.WriteStage.BEFORE_PRIMARY_REPLACE)) {
            Files.deleteIfExists(file()); Files.deleteIfExists(backup()); fail.set(null);
            var before = install(shared(List.of()));
            // A prior save gives the backup stage something to replace.
            assertTrue(new SharedClientStore(root).save(new SharedClientArchive(1, placement, before.materials(), before.regions(), List.of()), 0).ok());
            byte[] primary = Files.readAllBytes(file()), previous = Files.readAllBytes(backup());
            try (var service = new SharedClientService(root, hook)) {
                fail.set(stage); var id = DatasetId.create();
                assertEquals(StoreStatus.IO_FAILURE, service.unshare(placement, regionRef, 0, actor, id).join().status());
                assertArrayEquals(primary, Files.readAllBytes(file())); assertEquals(regionRef, stored().regions().shared());
                if (stage == SharedClientStore.WriteStage.BEFORE_BACKUP_REPLACE) assertArrayEquals(previous, Files.readAllBytes(backup()));
                fail.set(null);
                var retried = service.unshare(placement, regionRef, 0, actor, id).join();
                assertTrue(retried.ok()); assertEquals(id, stored().regions().detached().dataset().id());
            }
        }
    }

    @Test void backupAndRecoveryKeepTheDetachedCopy() throws Exception {
        install(shared(List.of()));
        try (var service = new SharedClientService(root)) {
            var detached = service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().value().regions().detached();
            var edited = service.updateDetached(placement, detached.dataset().id(), 0, dataset -> dataset.apply(tower, new TaskCommand.Claim(), actor, Instant.EPOCH).dataset()).join();
            assertTrue(edited.ok()); assertEquals(1, edited.value().regions().detached().dataset().generation());
            String payload = com.google.gson.JsonParser.parseString(Files.readString(backup())).getAsJsonObject().get("payload").getAsString();
            assertEquals(detached, SharedClientCodec.decode(Base64.getDecoder().decode(payload)).regions().detached(), "Backup dropped the detached copy");
            Files.writeString(file(), "damaged");
            assertEquals(StoreStatus.RECOVERY_REQUIRED, service.load(placement).join().status());
            var recovered = service.recover(placement).join(); assertTrue(recovered.ok());
            assertEquals(detached, recovered.value().regions().detached()); assertTrue(recovered.value().recoveredJournalUncertain());
            assertNull(recovered.value().materials().cache());
        }
    }

    @Test void localEditsChangeOnlyTheCopyAndIgnoreTheJournal() {
        install(shared(List.of()));
        try (var service = new SharedClientService(root)) {
            var after = service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().value();
            var copy = after.regions().detached().dataset();
            assertTrue(service.admit(placement, DatasetKind.MATERIALS, materialRef, actor, connection,
                new SharedCommand(materialScope, stone, UUID.randomUUID(), 0, 0, new SharedCommand.Claim())).join().ok());
            var marked = service.updateDetached(placement, copy.id(), 0, dataset -> dataset.apply(tower, new TaskCommand.SetDone(true), actor, Instant.EPOCH).dataset()).join();
            assertTrue(marked.ok(), "An unknown journal entry blocked a purely local edit");
            assertTrue(marked.value().regions().detached().dataset().states().get(tower).done());
            assertEquals(after.materials(), marked.value().materials()); assertEquals(regionScope, marked.value().regions().detached().from());
            assertEquals(StoreStatus.CONFLICT, service.updateDetached(placement, copy.id(), 0, dataset -> dataset).join().status(), "Stale generation accepted");
            assertEquals(StoreStatus.CONFLICT, service.updateDetached(placement, DatasetId.create(), 1, dataset -> dataset).join().status());
            var unchanged = service.updateDetached(placement, copy.id(), 1, dataset -> dataset).join();
            assertTrue(unchanged.ok()); assertEquals(marked.value(), unchanged.value());
            assertEquals(StoreStatus.CONFLICT, service.updateDetached(placement, copy.id(), 1,
                dataset -> RegionDetachment.copy(DatasetId.create(), placement, regions0)).join().status());
        }
    }

    @Test void selectLocalCacheAndAdmitCannotDropADetachedCopy() {
        install(shared(List.of()));
        try (var service = new SharedClientService(root)) {
            var detached = service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().value();
            assertEquals(StoreStatus.CONFLICT, service.select(placement, DatasetKind.REGIONS, localRegions, actor, regions0, 0).join().status());
            assertEquals(StoreStatus.CONFLICT, service.local(placement, DatasetKind.REGIONS, localRegions).join().status());
            assertEquals(StoreStatus.CONFLICT, service.cache(placement, DatasetKind.REGIONS, regionRef, regions0, 0).join().status());
            assertTrue(service.clearCaches(placement, null).join().ok());
            assertEquals(detached.regions(), stored().regions());
            assertTrue(service.select(placement, DatasetKind.MATERIALS, localMaterials, actor, snapshot(materialScope, 0, Map.of(), Set.of()), 0).join().ok());
            assertEquals(detached.regions(), stored().regions());
        }
    }

    @Test void rejoinExportsTheLocalEditAndInstallsTheServerSnapshot() throws Exception {
        install(shared(List.of()));
        try (var service = new SharedClientService(root)) {
            var copy = service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().value().regions().detached().dataset();
            var edited = service.updateDetached(placement, copy.id(), 0, dataset -> dataset.apply(wall, new TaskCommand.SetNote("local only"), actor, Instant.EPOCH).dataset())
                .join().value().regions().detached().dataset();
            var before = stored(); var server = snapshot(regionScope, 3, Map.of(tower, done(tower, 2)), Set.of());
            assertEquals(StoreStatus.CONFLICT, service.rejoin(placement, copy.id(), 0, localRegions, actor, server, 3).join().status(), "Stale generation");
            assertEquals(StoreStatus.CONFLICT, service.rejoin(placement, copy.id(), 1, localRegions, actor,
                snapshot(ProjectScope.create(regionScope.worldNamespace()), 3, Map.of(), Set.of()), 3).join().status(), "Other project");
            assertEquals(StoreStatus.CONFLICT, service.rejoin(placement, copy.id(), 1, localRegions, UUID.randomUUID(), server, 3).join().status(), "Unreadable");
            assertEquals(before, stored());
            Path export = root.resolve("shared-client").resolve("detached").resolve("placement-" + placement + "-dataset-" + copy.id() + "-g1.json");
            assertEquals(export, service.detachedExport(placement, edited)); assertFalse(Files.exists(export));
            var rejoined = service.rejoin(placement, copy.id(), 1, localRegions, actor, server, 3).join(); assertTrue(rejoined.ok());
            var regions = rejoined.value().regions();
            assertNull(regions.detached()); assertEquals(localRegions, regions.localDataset());
            assertEquals(actor, regions.shared().actor()); assertEquals(regionScope, regions.shared().scope()); assertNotEquals(regionRef, regions.shared());
            assertEquals(new SharedClientArchive.Cache(server, 3), regions.cache(), "Shared view must be the fresh server snapshot");
            assertEquals(before.materials(), rejoined.value().materials()); assertEquals(before.operations(), rejoined.value().operations());
            assertEquals(edited, new DatasetJsonCodec().decode(Files.readString(export)));
            assertEquals("local only", ((RegionWorkDataset) new DatasetJsonCodec().decode(Files.readString(export))).states().get(wall).note());
            assertEquals(rejoined.value(), stored());
            assertEquals(StoreStatus.CONFLICT, service.rejoin(placement, copy.id(), 1, localRegions, actor, server, 3).join().status());
        }
    }

    @Test void rejoinFailureAtEachStageLeavesTheCopyDetachedAndRetryRewritesTheSameExport() throws Exception {
        for (var stage : SharedClientStore.WriteStage.values()) {
            try (var files = Files.walk(root)) { files.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(root)).forEach(path -> path.toFile().delete()); }
            fail.set(null); install(shared(List.of()));
            try (var service = new SharedClientService(root, hook)) {
                var copy = service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().value().regions().detached().dataset();
                var before = stored(); byte[] primary = Files.readAllBytes(file()); Path export = service.detachedExport(placement, copy);
                var server = snapshot(regionScope, 2, Map.of(wall, done(wall, 1)), Set.of());
                fail.set(stage);
                assertEquals(StoreStatus.IO_FAILURE, service.rejoin(placement, copy.id(), 0, localRegions, actor, server, 2).join().status(), stage.name());
                assertArrayEquals(primary, Files.readAllBytes(file())); assertEquals(before, stored());
                assertEquals(stage != SharedClientStore.WriteStage.BEFORE_EXPORT_REPLACE, Files.exists(export), stage.name());
                fail.set(null);
                var retried = service.rejoin(placement, copy.id(), 0, localRegions, actor, server, 2).join();
                assertTrue(retried.ok(), stage.name()); assertEquals(new SharedClientArchive.Cache(server, 2), retried.value().regions().cache());
                assertEquals(copy, new DatasetJsonCodec().decode(Files.readString(export)));
                try (var leftovers = Files.list(export.getParent())) { assertEquals(List.of(export), leftovers.toList(), "Temporary export left behind"); }
            }
        }
    }

    @Test void version2FileIsReadAndUpgradedWhileFutureSchemaIsRefusedAndKept() throws Exception {
        UUID owner = new UUID(0, 42); var fixture = new LocalPlacementId(new UUID(0, 40));
        Path client = root.resolve("shared-client").resolve("placement-" + fixture + ".json"); Files.createDirectories(client.getParent());
        try (var input = getClass().getResourceAsStream("/shared-v2/client.json")) { assertNotNull(input); Files.copy(input, client); }
        byte[] v2 = Files.readAllBytes(client); assertTrue(Files.readString(client).startsWith("{\"schema\":2,"));
        var loaded = new SharedClientStore(root).load(fixture); assertEquals(StoreStatus.OK, loaded.status());
        var archive = loaded.value(); assertArrayEquals(v2, Files.readAllBytes(client));
        assertEquals(new SharedClientArchive.Reference(new UUID(0, 47), owner, archive.materials().shared().scope()), archive.regions().shared());
        assertEquals(6, archive.regions().cache().cursor()); assertNull(archive.regions().detached());
        assertEquals(new SharedClientArchive.Confirmation(SharedEvents.Status.OK, 6), archive.operations().getFirst().confirmation());
        try (var service = new SharedClientService(root)) {
            var id = DatasetId.create();
            var detached = service.unshare(fixture, archive.regions().shared(), 6, owner, id).join(); assertTrue(detached.ok());
            var region = new RegionTaskId(new UUID(0, 48));
            assertEquals(Set.of(region), detached.value().regions().detached().dataset().reviewRequired());
            assertEquals("v2 region note", detached.value().regions().detached().dataset().states().get(region).note());
            assertTrue(Files.readString(client).startsWith("{\"schema\":3,")); assertEquals(detached.value(), new SharedClientStore(root).load(fixture).value());
            String future = Files.readString(client).replace("\"schema\":3", "\"schema\":4"); Files.writeString(client, future);
            byte[] backup = Files.readAllBytes(client.resolveSibling(client.getFileName() + ".bak"));
            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, service.load(fixture).join().status());
            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, service.updateDetached(fixture, id, 0, dataset -> dataset.acknowledge(region)).join().status());
            assertEquals(StoreStatus.UNSUPPORTED_SCHEMA, service.recover(fixture).join().status());
            assertEquals(future, Files.readString(client)); assertArrayEquals(backup, Files.readAllBytes(client.resolveSibling(client.getFileName() + ".bak")));
        }
    }

    @Test void schema3PayloadCannotBeReadAsVersion2() throws Exception {
        install(shared(List.of()));
        try (var service = new SharedClientService(root)) { assertTrue(service.unshare(placement, regionRef, 0, actor, DatasetId.create()).join().ok()); }
        Files.writeString(file(), Files.readString(file()).replace("\"schema\":3", "\"schema\":2"));
        // The primary is unreadable as version 2, so only the (also version-3) backup could recover it.
        var masquerade = new SharedClientStore(root).load(placement);
        assertEquals(StoreStatus.RECOVERY_REQUIRED, masquerade.status()); assertNull(masquerade.value());
    }
}
