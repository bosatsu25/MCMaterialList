package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.client.*;
import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.core.work.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.mcmateriallist.core.shared.SharedAuthorityTest.*;
import static dev.mcmateriallist.core.shared.SharedEvents.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Issue #7: concurrent edits, exactly-once replay, delayed/lost ACKs and reconnect resolution against the real authority. */
class SharedReconnectTest {
    @TempDir Path root;
    final UUID owner = new UUID(0, 1), first = new UUID(0, 2), second = new UUID(0, 3);
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return now; }
    }
    SharedProjectService server() { return new SharedProjectService(new SharedProjectStore(root.resolve("server"))); }
    ProjectSnapshot create(SharedProjectService service) throws Exception {
        var created = await(service.create(owner, await(service.ready()).worldNamespace(), "reconnect", definition()));
        assertEquals(OK, created.status()); return created.value();
    }
    ProjectSnapshot grant(SharedProjectService service, ProjectSnapshot current, UUID target) throws Exception {
        var granted = await(service.setMembership(owner, current.scope(), current.projectRevision(), target, ProjectRole.MEMBER));
        assertEquals(OK, granted.status()); return granted.value();
    }
    /** A client save unit whose material binding caches the given authority snapshot, as after a confirmed join. */
    SharedClientArchive.Reference bind(LocalPlacementId placement, ProjectSnapshot snapshot) {
        var reference = new SharedClientArchive.Reference(UUID.randomUUID(), owner, snapshot.scope());
        var archive = new SharedClientArchive(0, placement,
            new SharedClientArchive.Binding(DatasetId.create(), reference, new SharedClientArchive.Cache(snapshot, snapshot.projectRevision())),
            new SharedClientArchive.Binding(DatasetId.create(), null, null), List.of());
        assertEquals(StoreStatus.OK, new SharedClientStore(root.resolve("client")).save(archive, -1).status());
        return reference;
    }
    /** A replacement transport lifetime: only a fresh fixed-revision snapshot re-enables it. */
    static SharedClientState reconnect(SharedProjectService service, UUID actor, ProjectScope scope) throws Exception {
        var state = new SharedClientState(); state.joining(scope);
        state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), OK, await(service.join(actor, scope)).value()));
        assertTrue(state.synchronizedState()); assertEquals(1, state.installations());
        return state;
    }

    @Test void concurrentClaimsHaveOneWinnerAndTheLoserLearnsTheAssigneeFromAuthority() throws Exception {
        try (var service = server()) {
            var initial = grant(service, grant(service, create(service), first), second);
            var loserView = new SharedClientState(); loserView.joining(initial.scope());
            loserView.receive(new SharedMessage.Snapshot(UUID.randomUUID(), OK, initial));
            for (TaskId row : List.<TaskId>of(STONE, initial.manifest().regions().keySet().iterator().next())) {
                // Both are admitted before either completes; the serial queue applies whichever it accepted first.
                var a = service.command(first, command(initial, row, 0, new SharedCommand.Claim()));
                var b = service.command(second, command(initial, row, 0, new SharedCommand.Claim()));
                var ra = await(a); var rb = await(b);
                var winner = ra.result().status() == OK ? first : second;
                var won = winner.equals(first) ? ra : rb; var lost = winner.equals(first) ? rb : ra;
                assertEquals(OK, won.result().status()); assertEquals(1, won.events().size());
                // Issue #6 contract: the row now belongs to someone else, so the late Member claim is refused
                // before version checks, without a durable outcome or private row in the reply.
                assertEquals(DENIED, lost.result().status(), "Both concurrent claims succeeded");
                assertNull(lost.result().row()); assertTrue(lost.events().isEmpty());
                // The loser's subscription receives the winner's committed delta, which carries the current assignee.
                loserView.receive(new SharedMessage.ProjectDelta((SharedEvents.ProjectDelta) won.events().getFirst()));
                loserView.receive(new SharedMessage.CommandResult(lost.result()));
                assertTrue(loserView.synchronizedState()); assertEquals(winner, loserView.snapshot().rows().get(row).assignee());
                var current = await(service.join(owner, initial.scope())).value().rows().get(row);
                assertEquals(winner, current.assignee()); assertEquals(1, current.rowVersion());
                assertEquals(current, loserView.snapshot().rows().get(row));
            }
            var stale = new SharedCommand(initial.scope(), DIRT, UUID.randomUUID(), 0, 1, new SharedCommand.SetNote("stale definition"));
            var before = await(service.join(owner, initial.scope())).value();
            assertEquals(CONFLICT, await(service.command(owner, stale)).result().status());
            assertEquals(before, await(service.join(owner, initial.scope())).value(), "Stale manifest command changed authority");
        }
    }

    @Test void completeUndoThenDelayedOldCompleteCannotRestoreStaleCompletion() throws Exception {
        try (var service = server()) {
            var initial = create(service);
            var done = command(initial, STONE, 0, new SharedCommand.SetDone(true));
            var completed = await(service.command(owner, done)); assertEquals(OK, completed.result().status());
            var undone = await(service.command(owner, command(initial, STONE, 1, new SharedCommand.SetDone(false))));
            assertEquals(OK, undone.result().status()); assertFalse(undone.result().row().done());
            // The same delayed packet: the retained original answer, no second execution.
            var duplicate = await(service.command(owner, done));
            assertEquals(completed.result(), duplicate.result()); assertTrue(duplicate.events().isEmpty());
            // An older complete under a fresh operation ID: rejected against the latest row version.
            var delayed = await(service.command(owner, command(initial, STONE, 0, new SharedCommand.SetDone(true))));
            assertEquals(CONFLICT, delayed.result().status()); assertTrue(delayed.events().isEmpty());
            var authority = await(service.join(owner, initial.scope())).value();
            assertFalse(authority.rows().get(STONE).done(), "Delayed old completion restored stale state");
            assertEquals(2, authority.rows().get(STONE).rowVersion()); assertEquals(2, authority.projectRevision());
            // A client already at the undone row ignores both the replayed ACK and the superseded delta.
            var state = new SharedClientState(); state.joining(initial.scope());
            state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), OK, authority));
            state.receive(new SharedMessage.CommandResult(duplicate.result()));
            state.receive(new SharedMessage.ProjectDelta((SharedEvents.ProjectDelta) completed.events().getFirst()));
            assertTrue(state.synchronizedState()); assertEquals(2, state.cursor());
            assertEquals(authority.rows().get(STONE), state.snapshot().rows().get(STONE), "Stale ACK or delta regressed the client row");
        }
    }

    @Test void duplicateExecutesOnceAcrossRestartAndExpiredReplayOnlyConflicts() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ProjectSnapshot initial; SharedCommand done; SharedEvents.CommandResult original;
        try (var service = new SharedProjectService(new SharedProjectStore(root), clock)) {
            initial = create(service); done = command(initial, STONE, 0, new SharedCommand.SetDone(true));
            var executed = await(service.command(owner, done)); original = executed.result();
            assertEquals(OK, original.status()); assertEquals(1, executed.events().size());
        }
        try (var service = new SharedProjectService(new SharedProjectStore(root), clock)) {
            await(service.ready());
            var replayed = await(service.command(owner, done));
            assertEquals(original, replayed.result()); assertTrue(replayed.events().isEmpty(), "Restart replay published a second change");
            assertEquals(1, await(service.join(owner, initial.scope())).value().projectRevision());
        }
        clock.advance(Duration.ofHours(24));
        try (var service = new SharedProjectService(new SharedProjectStore(root), clock)) {
            await(service.ready());
            // Retention expired: the exact replay is a new request whose expected row version is now stale.
            var expired = await(service.command(owner, done));
            assertEquals(CONFLICT, expired.result().status()); assertTrue(expired.events().isEmpty());
            var authority = await(service.join(owner, initial.scope())).value();
            assertEquals(1, authority.projectRevision()); assertEquals(1, authority.rows().get(STONE).rowVersion());
            assertEquals(original.row(), authority.rows().get(STONE), "Expired replay executed again");
        }
    }

    @Test void lostAckSettlesThroughExactReplayOnReplacementConnection() throws Exception {
        var placement = LocalPlacementId.create();
        try (var service = server(); var client = new SharedClientService(root.resolve("client"))) {
            var initial = create(service); var reference = bind(placement, initial);
            var done = command(initial, STONE, 0, new SharedCommand.SetDone(true));
            UUID lost = UUID.randomUUID(), replacement = UUID.randomUUID();
            assertTrue(client.admit(placement, DatasetKind.MATERIALS, reference, owner, lost, done).join().ok());
            var delivered = await(service.command(owner, done)); assertEquals(OK, delivered.result().status());
            // The ACK never reaches the client; the original connection closes.
            var state = reconnect(service, owner, initial.scope());
            assertTrue(state.snapshot().rows().get(STONE).done(), "Fresh authority omitted the delivered command");
            var archive = client.load(placement).join().value();
            assertEquals(List.of(done), SharedClientService.unknown(archive, owner, initial.scope()).stream()
                .map(SharedClientArchive.Operation::command).toList());
            var replayed = await(service.command(owner, done));
            assertEquals(delivered.result(), replayed.result()); assertTrue(replayed.events().isEmpty(), "Replay executed twice");
            state.receive(new SharedMessage.CommandResult(replayed.result()));
            assertEquals(1, state.cursor()); assertEquals(delivered.result().row(), state.snapshot().rows().get(STONE));
            // The original-connection path stays exact; replay settlement is a separate, policy-bound path.
            assertEquals(StoreStatus.CONFLICT, client.confirm(placement, owner, replacement, replayed.result()).join().status());
            var refusal = new SharedEvents.CommandResult(initial.scope(), done.operationId(), CAPACITY, 0, null);
            assertEquals(StoreStatus.CONFLICT, client.resolve(placement, owner, refusal).join().status(),
                "A transient refusal of the replay settled the original");
            assertEquals(StoreStatus.CONFLICT, client.resolve(placement, first, replayed.result()).join().status(),
                "Another account settled the original");
            assertTrue(SharedClientService.hasUnknown(client.load(placement).join().value(), owner, initial.scope()));
            var settled = client.resolve(placement, owner, replayed.result()).join();
            assertTrue(settled.ok()); assertFalse(SharedClientService.hasUnknown(settled.value(), owner, initial.scope()));
            var operation = settled.value().operations().getFirst();
            assertEquals(OK, operation.confirmation().status()); assertEquals(lost, operation.connection(), "Replay remapped the original context");
            assertEquals(archive.materials(), settled.value().materials(), "Replay settlement changed the binding or cache");
            assertEquals(settled.value(), client.resolve(placement, owner, replayed.result()).join().value(), "Repeated settlement rewrote the journal");
            assertEquals(StoreStatus.CONFLICT, client.resolve(placement, owner, new SharedEvents.CommandResult(initial.scope(), done.operationId(),
                CONFLICT, 1, replayed.result().row())).join().status(), "A different answer rewrote a settled original");
            assertEquals(1, await(service.join(owner, initial.scope())).value().projectRevision());
        }
    }

    @Test void undeliveredCommandExecutesOnceOnReplayAndStaleReplayOnlyConflicts() throws Exception {
        var placement = LocalPlacementId.create();
        try (var service = server(); var client = new SharedClientService(root.resolve("client"))) {
            var initial = create(service); var reference = bind(placement, initial);
            var claim = command(initial, STONE, 0, new SharedCommand.Claim());
            assertTrue(client.admit(placement, DatasetKind.MATERIALS, reference, owner, UUID.randomUUID(), claim).join().ok());
            // Disconnected before the command left the client.
            var state = reconnect(service, owner, initial.scope());
            assertNull(state.snapshot().rows().get(STONE).assignee());
            var executed = await(service.command(owner, claim));
            assertEquals(OK, executed.result().status()); assertEquals(1, executed.events().size());
            var duplicate = await(service.command(owner, claim));
            assertEquals(executed.result(), duplicate.result()); assertTrue(duplicate.events().isEmpty());
            assertTrue(client.resolve(placement, owner, executed.result()).join().ok());

            var note = command(initial, DIRT, 0, new SharedCommand.SetNote("owner intent"));
            assertTrue(client.admit(placement, DatasetKind.MATERIALS, reference, owner, UUID.randomUUID(), note).join().ok());
            var current = grant(service, await(service.join(owner, initial.scope())).value(), first);
            assertEquals(OK, await(service.command(first, command(current, DIRT, 0, new SharedCommand.Claim()))).result().status());
            var before = await(service.join(owner, initial.scope())).value();
            var stale = await(service.command(owner, note));
            assertEquals(CONFLICT, stale.result().status()); assertTrue(stale.events().isEmpty());
            assertEquals(first, stale.result().row().assignee());
            assertEquals(before, await(service.join(owner, initial.scope())).value(), "Stale replay overwrote another member's work");
            var settled = client.resolve(placement, owner, stale.result()).join();
            assertTrue(settled.ok()); assertFalse(SharedClientService.hasUnknown(settled.value(), owner, initial.scope()));
            assertEquals(CONFLICT, settled.value().operations().getLast().confirmation().status());
        }
    }

    @Test void onlyFreshlyInstalledSnapshotsMakeReplayDue() throws Exception {
        try (var service = server()) {
            var initial = create(service);
            var state = new SharedClientState(); state.joining(initial.scope());
            assertEquals(0, state.installations());
            state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), OK, initial)); assertEquals(1, state.installations());
            var row = new TaskState(STONE, null, false, "gap", null, null, 1);
            state.receive(new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(initial.scope(), 2, 0, row, false)));
            assertEquals(SharedClientState.Status.RESYNC_REQUIRED, state.status(), "Missing delta did not force resync");
            assertEquals(1, state.installations());
            state.joining(initial.scope());
            state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), OK, initial));
            assertFalse(state.synchronizedState(), "Older snapshot satisfied the gap fence");
            assertEquals(1, state.installations());
            var done = await(service.command(owner, command(initial, STONE, 0, new SharedCommand.SetDone(true))));
            var newer = await(service.command(owner, command(initial, DIRT, 0, new SharedCommand.SetNote("newer"))));
            assertEquals(OK, done.result().status()); assertEquals(OK, newer.result().status());
            state.joining(initial.scope());
            state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), OK, await(service.join(owner, initial.scope())).value()));
            assertTrue(state.synchronizedState()); assertEquals(2, state.installations()); assertEquals(2, state.cursor());
        }
    }

    @Test void onlyAuthoritativeReplayAnswersAreDefinitive() {
        for (var status : SharedEvents.Status.values())
            assertEquals(Set.of(OK, CONFLICT, DENIED, MISSING, INVALID_INPUT).contains(status), SharedClientService.definitive(status), status.name());
    }
}
