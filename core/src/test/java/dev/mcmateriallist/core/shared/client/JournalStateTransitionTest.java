package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.work.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * State transition testing ST-J of docs/test-design.md (JSTQB FL v4.0, 4.2.4): one original command in the client journal.
 * States: ABSENT, UNKNOWN (sent, no confirmation), CONFIRMED(status), EVICTED. Events: admit, confirm (the ACK on the original
 * connection), resolve (the answer to an exact replay, #7), a further admission while 64 confirmations are kept.
 * Only definitive statuses (OK, CONFLICT, DENIED, MISSING, INVALID_INPUT) settle through resolve; SAVE_FAILED, UNAVAILABLE,
 * CAPACITY and CLOSED leave the entry UNKNOWN. Existing evidence: SharedReconnectTest (replay paths, definitive predicate),
 * SharedClientServiceTest (presend context, wrong actor/connection), SharedClientUnshareTest (UNAVAILABLE replay).
 */
class JournalStateTransitionTest {
    @TempDir Path root;
    private final LocalPlacementId placement = LocalPlacementId.create();
    private final UUID actor = new UUID(0, 1), connection = new UUID(0, 2);
    private final MaterialTaskId stone = new MaterialTaskId("minecraft:stone");

    private ProjectSnapshot snapshot() {
        var manifest = SharedManifest.initial(new SharedManifest.Definition("9".repeat(64), new SharedManifest.Baseline("minecraft:overworld",
            new Vector3(0, 64, 0), SharedManifest.Baseline.Rotation.NONE, SharedManifest.Baseline.Mirror.NONE), Map.of(stone, 1L), List.of()));
        return new ProjectSnapshot(ProjectScope.create(UUID.randomUUID()), "st-j", actor, Set.of(), 0, manifest, Map.of(stone, TaskState.empty(stone)), Set.of());
    }
    private record Fixture(ProjectSnapshot snapshot, SharedClientArchive.Reference ref) {}
    private Fixture install(List<SharedClientArchive.Operation> operations, ProjectSnapshot snapshot) {
        var ref = new SharedClientArchive.Reference(UUID.randomUUID(), actor, snapshot.scope());
        var archive = new SharedClientArchive(0, placement, new SharedClientArchive.Binding(DatasetId.create(), ref, new SharedClientArchive.Cache(snapshot, 0)),
            new SharedClientArchive.Binding(null, null, null), operations);
        assertEquals(StoreStatus.OK, new SharedClientStore(root).save(archive, -1).status());
        return new Fixture(snapshot, ref);
    }
    private SharedCommand done(ProjectSnapshot snapshot) { return new SharedCommand(snapshot.scope(), stone, UUID.randomUUID(), 0, 0, new SharedCommand.SetDone(true)); }
    private SharedEvents.CommandResult answer(SharedCommand command, SharedEvents.Status status) {
        TaskState row = status == SharedEvents.Status.OK ? new TaskState(stone, actor, true, "", actor, Instant.EPOCH, 1) : null;
        return new SharedEvents.CommandResult(command.scope(), command.operationId(), status, status == SharedEvents.Status.OK ? 1 : 0, row);
    }
    private SharedClientArchive stored() { return new SharedClientStore(root).load(placement).value(); }
    private Path file() { return root.resolve("shared-client").resolve("placement-" + placement + ".json"); }
    private void clean() throws Exception {
        try (var files = Files.walk(root)) { files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(root)).forEach(p -> p.toFile().delete()); }
    }

    /** ST-J-1, ST-J-2: ABSENT --admit--> UNKNOWN --confirm(s)--> CONFIRMED(s) for every status: the original connection's ACK settles. */
    @Test void stJ_confirmOnTheOriginalConnectionSettlesWithAnyStatus() throws Exception {
        for (var status : SharedEvents.Status.values()) {
            clean(); var fixture = install(List.of(), snapshot()); var command = done(fixture.snapshot());
            try (var service = new SharedClientService(root)) {
                assertTrue(service.admit(placement, DatasetKind.MATERIALS, fixture.ref(), actor, connection, command).join().ok());
                assertNull(stored().operations().getFirst().confirmation(), "UNKNOWN after admit");
                var confirmed = service.confirm(placement, actor, connection, answer(command, status)).join();
                assertTrue(confirmed.ok(), status.name());
                assertEquals(status, confirmed.value().operations().getFirst().confirmation().status());
                assertFalse(SharedClientService.hasUnknown(confirmed.value(), actor, fixture.snapshot().scope()), status.name());
            }
        }
    }

    /** ST-J-3, ST-J-4: UNKNOWN --resolve(definitive)--> CONFIRMED; UNKNOWN --resolve(non-definitive)--> UNKNOWN, refused without a write. */
    @Test void stJ_onlyDefinitiveReplayAnswersResolve() throws Exception {
        for (var status : SharedEvents.Status.values()) {
            clean(); var fixture = install(List.of(), snapshot()); var command = done(fixture.snapshot());
            try (var service = new SharedClientService(root)) {
                assertTrue(service.admit(placement, DatasetKind.MATERIALS, fixture.ref(), actor, connection, command).join().ok());
                byte[] before = Files.readAllBytes(file());
                var resolved = service.resolve(placement, actor, answer(command, status)).join();
                if (SharedClientService.definitive(status)) {
                    assertTrue(resolved.ok(), status.name()); assertEquals(status, stored().operations().getFirst().confirmation().status());
                } else {
                    assertEquals(StoreStatus.CONFLICT, resolved.status(), status.name());
                    assertArrayEquals(before, Files.readAllBytes(file()), status.name() + " wrote");
                    assertTrue(SharedClientService.hasUnknown(stored(), actor, fixture.snapshot().scope()), status.name());
                }
            }
        }
    }

    /**
     * ST-J-5..7: CONFIRMED --same answer--> CONFIRMED without a write (idempotent); CONFIRMED --different answer--> refused;
     * ABSENT --confirm/resolve--> refused (an answer for an operation never journaled is not adopted).
     */
    @Test void stJ_confirmedIsFinalAndAbsentAnswersAreRefused() throws Exception {
        var fixture = install(List.of(), snapshot()); var command = done(fixture.snapshot());
        try (var service = new SharedClientService(root)) {
            var stranger = done(fixture.snapshot());
            assertEquals(StoreStatus.CONFLICT, service.confirm(placement, actor, connection, answer(stranger, SharedEvents.Status.OK)).join().status());
            assertEquals(StoreStatus.CONFLICT, service.resolve(placement, actor, answer(stranger, SharedEvents.Status.OK)).join().status());
            assertTrue(service.admit(placement, DatasetKind.MATERIALS, fixture.ref(), actor, connection, command).join().ok());
            assertTrue(service.confirm(placement, actor, connection, answer(command, SharedEvents.Status.OK)).join().ok());
            byte[] settled = Files.readAllBytes(file());
            assertTrue(service.confirm(placement, actor, connection, answer(command, SharedEvents.Status.OK)).join().ok());
            assertTrue(service.resolve(placement, actor, answer(command, SharedEvents.Status.OK)).join().ok());
            assertArrayEquals(settled, Files.readAllBytes(file()), "Repeated identical answer rewrote the journal");
            assertEquals(StoreStatus.CONFLICT, service.confirm(placement, actor, connection, answer(command, SharedEvents.Status.CONFLICT)).join().status());
            assertEquals(StoreStatus.CONFLICT, service.resolve(placement, actor, answer(command, SharedEvents.Status.DENIED)).join().status());
            assertArrayEquals(settled, Files.readAllBytes(file()));
        }
    }

    private List<SharedClientArchive.Operation> confirmed(int count, ProjectSnapshot snapshot) {
        List<SharedClientArchive.Operation> operations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var scope = ProjectScope.create(snapshot.scope().worldNamespace());
            var ref = new SharedClientArchive.Reference(UUID.randomUUID(), actor, scope);
            operations.add(new SharedClientArchive.Operation(DatasetKind.MATERIALS, ref, UUID.randomUUID(),
                new SharedCommand(scope, stone, new UUID(5, i), 0, 0, new SharedCommand.Claim()), new SharedClientArchive.Confirmation(SharedEvents.Status.OK, i)));
        }
        return operations;
    }

    /**
     * ST-J-8 with BVA on SharedClientService.CONFIRMED_OPERATIONS = 64: an admission with 63 kept confirmations evicts nothing;
     * with 64 the oldest confirmed entry goes to EVICTED (FIFO), so the journal keeps 63 confirmed plus the new UNKNOWN entry.
     * An UNKNOWN entry is never evicted (it stays first in the journal while confirmed entries behind it are removed).
     */
    @Test void stJ_evictionOfConfirmedEntriesBoundary() throws Exception {
        for (int kept : new int[]{SharedClientService.CONFIRMED_OPERATIONS - 1, SharedClientService.CONFIRMED_OPERATIONS}) {
            clean(); var snapshot = snapshot(); var old = confirmed(kept, snapshot);
            var fixture = install(old, snapshot); var command = done(snapshot);
            try (var service = new SharedClientService(root)) {
                var after = service.admit(placement, DatasetKind.MATERIALS, fixture.ref(), actor, connection, command).join().value().operations();
                int expectedKept = Math.min(kept, SharedClientService.CONFIRMED_OPERATIONS - 1);
                assertEquals(expectedKept + 1, after.size(), "kept " + kept);
                assertEquals(old.subList(kept - expectedKept, kept), after.subList(0, expectedKept), "FIFO eviction, kept " + kept);
                assertEquals(command, after.getLast().command()); assertNull(after.getLast().confirmation());
            }
        }
        // An UNKNOWN original in front of 64 confirmations survives the eviction of the oldest confirmation.
        clean(); var snapshot = snapshot(); var otherScope = ProjectScope.create(snapshot.scope().worldNamespace());
        var unknownRef = new SharedClientArchive.Reference(UUID.randomUUID(), actor, otherScope);
        var unknown = new SharedClientArchive.Operation(DatasetKind.MATERIALS, unknownRef, UUID.randomUUID(),
            new SharedCommand(otherScope, stone, UUID.randomUUID(), 0, 0, new SharedCommand.Claim()), null);
        List<SharedClientArchive.Operation> journal = new ArrayList<>(); journal.add(unknown); journal.addAll(confirmed(SharedClientService.CONFIRMED_OPERATIONS, snapshot));
        var fixture = install(journal, snapshot);
        try (var service = new SharedClientService(root)) {
            var after = service.admit(placement, DatasetKind.MATERIALS, fixture.ref(), actor, connection, done(snapshot)).join().value().operations();
            assertEquals(unknown, after.getFirst(), "UNKNOWN original evicted");
            assertEquals(journal.get(2), after.get(1), "Oldest confirmation must be the one evicted");
            assertEquals(1 + (SharedClientService.CONFIRMED_OPERATIONS - 1) + 1, after.size());
        }
    }
}
