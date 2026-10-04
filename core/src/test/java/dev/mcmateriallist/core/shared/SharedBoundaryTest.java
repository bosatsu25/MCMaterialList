package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.work.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.mcmateriallist.core.shared.SharedAuthorityTest.*;
import static dev.mcmateriallist.core.shared.SharedEvents.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedBoundaryTest {
    @TempDir Path root;
    private final UUID owner = UUID.randomUUID(), viewer = UUID.randomUUID();
    private ProjectSnapshot create(SharedProjectService service) throws Exception {
        return await(service.create(owner, await(service.ready()).worldNamespace(), "project", definition())).value();
    }
    @Test void assignmentsRequireParticipantsReleaseCannotClearOthersAndClearIsExplicit() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = create(service);
            assertEquals(INVALID_INPUT, await(service.command(owner, command(s, STONE, 0, new SharedCommand.Assign(viewer)))).result().status());
            await(service.setViewer(owner, s.scope(), 0, viewer, true));
            assertEquals(OK, await(service.command(owner, command(s, STONE, 0, new SharedCommand.Assign(viewer)))).result().status());
            assertEquals(CONFLICT, await(service.command(owner, command(s, STONE, 1, new SharedCommand.Release()))).result().status());
            var done = await(service.command(owner, command(s, STONE, 1, new SharedCommand.SetDone(true)))).result().row();
            assertEquals(viewer, done.assignee()); assertEquals(owner, done.completedBy());
            var clear = await(service.command(owner, command(s, STONE, 2, new SharedCommand.ClearAssignment()))).result().row();
            assertNull(clear.assignee()); assertTrue(clear.done());
            var undone = await(service.command(owner, command(s, STONE, 3, new SharedCommand.SetDone(false)))).result().row();
            assertFalse(undone.done()); assertNull(undone.completedBy()); assertNull(undone.completedAt());
            assertThrows(NullPointerException.class, () -> new SharedCommand.Assign(null));
        }
    }
    @Test void wrongWorldListProjectAndManifestCannotCrossScopesOrLeakRows() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var first = create(service); var second = create(service);
            await(service.command(owner, command(first, STONE, 0, new SharedCommand.SetDone(true))));
            assertFalse(await(service.join(owner, second.scope())).value().rows().get(STONE).done());
            ProjectScope wrong = new ProjectScope(first.scope().worldNamespace(), first.scope().projectId(), second.scope().materialListId(), first.scope().regionListId());
            assertEquals(DENIED, await(service.join(owner, wrong)).status());
            var denied = await(service.command(owner, new SharedCommand(wrong, STONE, UUID.randomUUID(), 1, 0, new SharedCommand.SetDone(false))));
            assertEquals(DENIED, denied.result().status()); assertNull(denied.result().row());
            var stale = new SharedCommand(first.scope(), STONE, UUID.randomUUID(), 1, 99, new SharedCommand.SetDone(false));
            assertEquals(CONFLICT, await(service.command(owner, stale)).result().status());
            assertEquals(DENIED, await(service.discover(owner, UUID.randomUUID())).status());
            assertEquals(DENIED, await(service.setViewer(viewer, first.scope(), 1, viewer, true)).status());
            assertEquals(INVALID_INPUT, await(service.setViewer(owner, first.scope(), 1, owner, false)).status());
        }
    }
    @Test void observationsFiltersAndCountsNeverEnterTheAuthorityAndIdsSurviveRestart() throws Exception {
        ProjectSnapshot expected;
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            expected = create(service);
            // Different Litematica observations stay in consumer-owned values; no observation mutation API exists.
            record Observation(long total, long missing, long available, boolean ignored) {}
            Observation a = new Observation(10, 9, 1, false), b = new Observation(100, 0, 100, true);
            assertNotEquals(a, b);
            assertEquals(expected, await(service.join(owner, expected.scope())).value());
            assertEquals(2, expected.materialProgress().total()); assertEquals(1, expected.regionProgress().total());
        }
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            await(service.ready()); assertEquals(expected, await(service.join(owner, expected.scope())).value());
        }
    }
    @Test void completedDefinitionChangeNeedsExplicitReconfirmationAndDoesNotResetDone() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = create(service); await(service.command(owner, command(s, STONE, 0, new SharedCommand.SetDone(true))));
            var updated = await(service.updateManifest(owner, s.scope(), 1, 0, definition(11, definition().regions().getFirst()))).value();
            assertTrue(updated.rows().get(STONE).done()); assertTrue(updated.needsReconfirmation().contains(STONE));
            var confirmed = await(service.command(owner, command(updated, STONE, 1, new SharedCommand.SetDone(true))));
            assertEquals(2, confirmed.result().row().rowVersion());
            assertFalse(((SharedEvents.ProjectDelta) confirmed.events().getFirst()).needsReconfirmation());
            assertFalse(await(service.join(owner, s.scope())).value().needsReconfirmation().contains(STONE));
        }
    }
    @Test void operationIdentityMismatchRetentionAndAccessBeforeReplay() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC); ProjectSnapshot s;
        SharedCommand first; SharedEvents.CommandResult confirmed;
        try (var service = new SharedProjectService(new SharedProjectStore(root), clock)) {
            s = create(service); first = command(s, STONE, 0, new SharedCommand.Claim()); confirmed = await(service.command(owner, first)).result();
            var changedContent = new SharedCommand(s.scope(), STONE, first.operationId(), 0, 0, new SharedCommand.SetDone(true));
            assertEquals(INVALID_INPUT, await(service.command(owner, changedContent)).result().status());
            assertEquals(DENIED, await(service.command(viewer, first)).result().status());
        }
        try (var service = new SharedProjectService(new SharedProjectStore(root), Clock.offset(clock, Duration.ofHours(23)))) {
            await(service.ready()); assertEquals(confirmed, await(service.command(owner, first)).result());
        }
        try (var service = new SharedProjectService(new SharedProjectStore(root), Clock.offset(clock, Duration.ofHours(24)))) {
            await(service.ready()); assertEquals(CONFLICT, await(service.command(owner, first)).result().status());
        }
        // Seed a historical result for a now read-only actor to assert authority precedes lookup.
        var store = new SharedProjectStore(root); var archive = store.load(s.scope().worldNamespace(), s.scope().projectId()).value();
        var viewerCommand = command(s, DIRT, 0, new SharedCommand.Claim());
        var op = new ProjectArchive.Operation(viewer, viewerCommand,
            new SharedEvents.CommandResult(s.scope(), viewerCommand.operationId(), OK, 0, s.rows().get(DIRT)), clock.instant());
        var snapshot = new ProjectSnapshot(s.scope(), s.name(), owner, Set.of(viewer), archive.snapshot().projectRevision(), s.manifest(), archive.snapshot().rows(), Set.of());
        assertEquals(StoreStatus.OK, store.save(new ProjectArchive(archive.generation() + 1, snapshot, List.of(op), List.of()), archive.generation()).status());
        try (var service = new SharedProjectService(store, clock)) {
            await(service.ready()); var denied = await(service.command(viewer, viewerCommand)).result();
            assertEquals(DENIED, denied.status()); assertNull(denied.row());
        }
    }
    @Test void durableOperationCountIsBoundedAndOldestResultIsEvicted() throws Exception {
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC); ProjectSnapshot s;
        try (var service = new SharedProjectService(new SharedProjectStore(root), clock)) { s = create(service); }
        List<ProjectArchive.Operation> ops = new ArrayList<>();
        for (int i = 0; i < SharedLimits.OPERATIONS; i++) {
            SharedCommand command = command(s, STONE, 0, new SharedCommand.SetDone(false));
            ops.add(new ProjectArchive.Operation(owner, command, new SharedEvents.CommandResult(s.scope(), command.operationId(), OK, 0, s.rows().get(STONE)), clock.instant()));
        }
        var store = new SharedProjectStore(root); assertEquals(StoreStatus.OK, store.save(new ProjectArchive(1, s, ops, List.of()), 0).status());
        try (var service = new SharedProjectService(store, clock)) {
            await(service.ready()); assertEquals(OK, await(service.command(owner, command(s, DIRT, 0, new SharedCommand.SetNote("new")))).result().status());
        }
        var archive = store.load(s.scope().worldNamespace(), s.scope().projectId()).value(); assertEquals(SharedLimits.OPERATIONS, archive.operations().size());
        assertFalse(archive.operations().stream().anyMatch(op -> op.command().operationId().equals(ops.getFirst().command().operationId())));
    }
    @Test void publicationGateUsesLatestCommittedMembershipAndDoesNotSwallowCallbackFailure() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = create(service); await(service.setViewer(owner, s.scope(), 0, viewer, true));
            assertThrows(IllegalStateException.class, () -> service.publishIfAuthorized(viewer, s.scope(), () -> { throw new IllegalStateException("test"); }));
            AtomicBoolean sent = new AtomicBoolean(); var captured = await(service.join(viewer, s.scope())).value();
            await(service.setViewer(owner, s.scope(), 1, viewer, false));
            assertFalse(service.publishIfAuthorized(viewer, captured.scope(), () -> sent.set(true))); assertFalse(sent.get());
            service.publishDiscovery(viewer, summaries -> assertTrue(summaries.isEmpty()));
            assertTrue(service.committedSnapshot(viewer, s.scope()).isEmpty());
        }
    }
    @Test void queueBackpressureAndShutdownDrainAllAcceptedWork() throws Exception {
        CountDownLatch saving = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean armed = new AtomicBoolean();
        var store = new SharedProjectStore(root, stage -> {
            if (armed.get() && stage == SharedProjectStore.WriteStage.BEFORE_PRIMARY_REPLACE) {
                saving.countDown(); try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new java.io.IOException("test interrupted"); }
            }
        });
        var service = new SharedProjectService(store);
        try {
            var s = create(service); armed.set(true); var command = service.command(owner, command(s, STONE, 0, new SharedCommand.SetDone(true)));
            assertTrue(saving.await(5, TimeUnit.SECONDS));
            List<CompletionStage<SharedEvents.Reply<ProjectSnapshot>>> queued = new ArrayList<>();
            for (int i = 0; i < SharedLimits.QUEUED_WORK; i++) queued.add(service.join(owner, s.scope()));
            assertEquals(CAPACITY, await(service.join(owner, s.scope())).status());
            var closed = service.shutdown(); assertEquals(CLOSED, await(service.join(owner, s.scope())).status());
            assertFalse(closed.toCompletableFuture().isDone()); release.countDown();
            assertEquals(OK, await(command).result().status()); for (var work : queued) assertTrue(await(work).value().rows().get(STONE).done());
            await(closed); assertFalse(service.publishIfAuthorized(owner, s.scope(), () -> fail("closed publication")));
        } finally { release.countDown(); service.close(); }
    }
    @Test void sameRowConcurrentRequestsUseLatestStateInsideTheQueue() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = create(service);
            var a = service.command(owner, command(s, STONE, 0, new SharedCommand.Claim()));
            var b = service.command(owner, command(s, STONE, 0, new SharedCommand.SetDone(true)));
            assertEquals(OK, await(a).result().status()); assertEquals(CONFLICT, await(b).result().status());
            assertFalse(await(service.join(owner, s.scope())).value().rows().get(STONE).done());
        }
    }
    @Test void participantsAndSubmittedDefinitionCountsHaveExplicitAdmissionLimits() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = create(service);
            for (int i = 0; i < SharedLimits.PARTICIPANTS - 1; i++)
                assertEquals(OK, await(service.setViewer(owner, s.scope(), i, UUID.randomUUID(), true)).status());
            assertEquals(CAPACITY, await(service.setViewer(owner, s.scope(), SharedLimits.PARTICIPANTS - 1, UUID.randomUUID(), true)).status());
        }
        Map<MaterialTaskId, Long> tooMany = new HashMap<>();
        for (int i = 0; i <= SharedLimits.ROWS_PER_LIST; i++) tooMany.put(new MaterialTaskId("m:item" + i), 1L);
        assertThrows(IllegalArgumentException.class, () -> new SharedManifest.Definition("a".repeat(64), definition().baseline(), tooMany, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SharedManifest.Definition("not a digest", definition().baseline(), Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SharedManifest.Definition("a".repeat(64), definition().baseline(), Map.of(STONE, -1L), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SharedManifest.Definition("a".repeat(64), definition().baseline(), Map.of(),
            List.of(definition().regions().getFirst(), definition().regions().getFirst())));
    }
    @Test void aggregateDefinitionRequestBudgetRejectsBeforeCountQueueFills() throws Exception {
        CountDownLatch saving = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean armed = new AtomicBoolean();
        var store = new SharedProjectStore(root, stage -> {
            if (armed.get() && stage == SharedProjectStore.WriteStage.BEFORE_PRIMARY_REPLACE) {
                saving.countDown(); try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new java.io.IOException("test interrupted"); }
            }
        });
        var service = new SharedProjectService(store);
        try {
            var s = create(service); Map<MaterialTaskId, Long> materials = new HashMap<>(); List<RegionDescriptor> regions = new ArrayList<>();
            for (int i = 0; i < SharedLimits.ROWS_PER_LIST; i++) {
                materials.put(new MaterialTaskId("m:item" + i), 1L);
                regions.add(new RegionDescriptor("r" + i, new Vector3(i, 0, 0), new Vector3(1, 1, 1)));
            }
            var large = new SharedManifest.Definition("a".repeat(64), definition().baseline(), materials, regions);
            armed.set(true); var active = service.command(owner, command(s, STONE, 0, new SharedCommand.SetDone(true)));
            assertTrue(saving.await(5, TimeUnit.SECONDS));
            List<CompletionStage<SharedEvents.Reply<ProjectSnapshot>>> queued = new ArrayList<>();
            for (int i = 0; i < 3; i++) queued.add(service.updateManifest(owner, s.scope(), 999, 0, large));
            assertEquals(CAPACITY, await(service.updateManifest(owner, s.scope(), 999, 0, large)).status());
            release.countDown(); assertEquals(OK, await(active).result().status());
            for (var work : queued) assertEquals(CONFLICT, await(work).status());
        } finally { release.countDown(); service.close(); }
    }
}
