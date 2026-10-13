package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import dev.mcmateriallist.core.shared.transport.SharedClientState;
import dev.mcmateriallist.core.work.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.mcmateriallist.core.shared.SharedAuthorityTest.*;
import static dev.mcmateriallist.core.shared.SharedEvents.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedPermissionsTest {
    @TempDir Path root;
    final UUID owner = new UUID(0, 1), member = new UUID(0, 2), viewer = new UUID(0, 3);
    ProjectSnapshot create(SharedProjectService service) throws Exception {
        return await(service.create(owner, await(service.ready()).worldNamespace(), "permissions", definition())).value();
    }
    Path file(ProjectSnapshot snapshot) { return root.resolve("mcmateriallist-shared/project-" + snapshot.scope().projectId() + ".json"); }

    @Test void memberPolicyAllowsOwnWorkButNeverSomeoneElses() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var initial = create(service);
            var s = new ProjectSnapshot(initial.scope(), initial.name(), owner,
                Map.of(owner, ProjectRole.ADMINISTRATOR, member, ProjectRole.MEMBER, viewer, ProjectRole.VIEWER),
                initial.projectRevision(), initial.manifest(), initial.rows(), initial.needsReconfirmation());
            for (TaskId id : both(s)) {
                var own = new TaskState(id, member, false, "", null, null, 0);
                var other = new TaskState(id, owner, false, "", null, null, 0);
                assertTrue(ProjectPermissions.allows(s, member, own, new SharedCommand.SetNote("own")));
                assertFalse(ProjectPermissions.allows(s, member, other, new SharedCommand.SetNote("other")));
                assertTrue(ProjectPermissions.allows(s, member, TaskState.empty(id), new SharedCommand.SetDone(true)));
                assertFalse(ProjectPermissions.allows(s, viewer, own, new SharedCommand.Claim()));
            }
        }
    }

    @Test void deniedAndInvalidRequestsPreserveDurableBytes() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var snapshot = create(service);
            byte[] before = Files.readAllBytes(file(snapshot));
            assertEquals(DENIED, await(service.command(viewer, command(snapshot, STONE, 0, new SharedCommand.Claim()))).result().status());
            assertArrayEquals(before, Files.readAllBytes(file(snapshot)));
            assertEquals(INVALID_INPUT, await(service.command(owner, command(snapshot, STONE, 0, new SharedCommand.Assign(viewer)))).result().status());
            assertArrayEquals(before, Files.readAllBytes(file(snapshot)), "Invalid assignment must not persist a journal generation");
            for (TaskId id : both(snapshot)) {
                assertEquals(INVALID_INPUT, await(service.command(owner, command(snapshot, id, 0, new SharedCommand.Assign(viewer)))).result().status());
                var wrong = new ProjectScope(snapshot.scope().worldNamespace(), snapshot.scope().projectId(), UUID.randomUUID(), snapshot.scope().regionListId());
                assertEquals(DENIED, await(service.command(owner, new SharedCommand(wrong, id, UUID.randomUUID(), 0, 0, new SharedCommand.Claim()))).result().status());
            }
            assertEquals(INVALID_INPUT, await(service.command(owner, command(snapshot, new MaterialTaskId("minecraft:unknown"), 0, new SharedCommand.Claim()))).result().status());
            assertThrows(IllegalArgumentException.class, () -> new SharedCommand.SetNote("界".repeat(1366)));
            assertThrows(IllegalArgumentException.class, () -> new SharedCommand.SetNote("\ud800"));
            assertThrows(IllegalArgumentException.class, () -> command(snapshot, STONE, -1, new SharedCommand.Claim()));
            assertArrayEquals(before, Files.readAllBytes(file(snapshot)));
        }
    }

    ProjectSnapshot membership(SharedProjectService service, UUID actor, ProjectSnapshot snapshot, UUID target, ProjectRole role) throws Exception {
        var result = await(service.setMembership(actor, snapshot.scope(), snapshot.projectRevision(), target, role));
        assertEquals(OK, result.status()); return result.value();
    }
    ProjectSnapshot current(SharedProjectService service, ProjectSnapshot snapshot) throws Exception {
        return await(service.join(owner, snapshot.scope())).value();
    }
    SharedEvents.Outcome act(SharedProjectService service, UUID actor, ProjectSnapshot snapshot, TaskId id, SharedCommand.Intent intent) throws Exception {
        var s = current(service, snapshot);
        return await(service.command(actor, command(s, id, s.rows().get(id).rowVersion(), intent)));
    }
    List<TaskId> both(ProjectSnapshot snapshot) { return List.of(STONE, snapshot.manifest().regions().keySet().iterator().next()); }

    @Test void viewerCannotMutate() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = membership(service, owner, create(service), viewer, ProjectRole.VIEWER);
            byte[] before = Files.readAllBytes(file(s));
            for (TaskId id : both(s)) for (var intent : List.of(new SharedCommand.Claim(), new SharedCommand.Release(), new SharedCommand.SetDone(true), new SharedCommand.SetNote("x"), new SharedCommand.Assign(owner), new SharedCommand.ClearAssignment())) {
                var reply = act(service, viewer, s, id, intent); assertEquals(DENIED, reply.result().status()); assertNull(reply.result().row());
            }
            assertEquals(DENIED, await(service.setMembership(viewer, s.scope(), s.projectRevision(), viewer, ProjectRole.ADMINISTRATOR)).status());
            assertEquals(DENIED, await(service.updateManifest(viewer, s.scope(), s.projectRevision(), s.manifest().version(), definition())).status());
            assertArrayEquals(before, Files.readAllBytes(file(s))); assertTrue(s.canRead(viewer)); assertFalse(s.canWrite(viewer));
        }
    }
    @Test void memberOwnOtherAndUnassignedMatrix() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = membership(service, owner, create(service), member, ProjectRole.MEMBER);
            for (TaskId id : both(s)) {
                for (var intent : List.of(new SharedCommand.Release(), new SharedCommand.SetDone(false), new SharedCommand.SetNote("x"), new SharedCommand.Assign(member), new SharedCommand.ClearAssignment()))
                    assertEquals(DENIED, act(service, member, s, id, intent).result().status());
                assertEquals(OK, act(service, member, s, id, new SharedCommand.Claim()).result().status());
                assertEquals(OK, act(service, member, s, id, new SharedCommand.SetNote("界😀")).result().status());
                assertEquals(OK, act(service, member, s, id, new SharedCommand.SetDone(true)).result().status());
                assertEquals(OK, act(service, member, s, id, new SharedCommand.SetDone(false)).result().status());
                assertEquals(OK, act(service, owner, s, id, new SharedCommand.Assign(owner)).result().status());
                byte[] before = Files.readAllBytes(file(s));
                for (var intent : List.of(new SharedCommand.Claim(), new SharedCommand.Release(), new SharedCommand.SetDone(true), new SharedCommand.SetNote("x"), new SharedCommand.Assign(member), new SharedCommand.ClearAssignment()))
                    assertEquals(DENIED, act(service, member, s, id, intent).result().status());
                assertArrayEquals(before, Files.readAllBytes(file(s)));
                assertEquals(ProjectRole.MEMBER, current(service, s).role(member));
            }
        }
    }
    @Test void unassignedMemberDoneClaimsAndCompletesOnce() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = membership(service, owner, create(service), member, ProjectRole.MEMBER);
            for (TaskId id : both(s)) {
                var before = new SharedProjectStore(root).load(s.scope().worldNamespace(), s.scope().projectId()).value();
                var command = command(current(service, s), id, 0, new SharedCommand.SetDone(true));
                var done = await(service.command(member, command));
                assertEquals(OK, done.result().status()); assertEquals(member, done.result().row().assignee());
                assertEquals(member, done.result().row().completedBy()); assertEquals(1, done.result().row().rowVersion()); assertTrue(done.result().row().done());
                var after = new SharedProjectStore(root).load(s.scope().worldNamespace(), s.scope().projectId()).value();
                assertEquals(before.generation()+1, after.generation()); assertEquals(before.snapshot().projectRevision()+1, after.snapshot().projectRevision());
                assertEquals(before.snapshot().manifest(), after.snapshot().manifest());
                assertEquals(done.result(), await(service.command(member, command)).result());
                assertEquals(after, new SharedProjectStore(root).load(s.scope().worldNamespace(), s.scope().projectId()).value());
            }
        }
    }
    @Test void releasePreservesManualCompletionAndNote() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = membership(service, owner, create(service), member, ProjectRole.MEMBER);
            for (TaskId id : both(s)) {
                assertEquals(OK, act(service, member, s, id, new SharedCommand.SetDone(true)).result().status());
                var noteReply = act(service, member, s, id, new SharedCommand.SetNote("manual")); assertEquals(OK, noteReply.result().status());
                var noted = noteReply.result().row(); assertNotNull(noted);
                var releaseReply = act(service, member, s, id, new SharedCommand.Release()); assertEquals(OK, releaseReply.result().status());
                var released = releaseReply.result().row(); assertNotNull(released);
                assertNull(released.assignee()); assertTrue(released.done()); assertEquals(noted.note(), released.note());
                assertEquals(noted.completedBy(), released.completedBy()); assertEquals(noted.completedAt(), released.completedAt());
                assertEquals(noted.rowVersion()+1, released.rowVersion());
                for (var intent : List.of(new SharedCommand.SetDone(true), new SharedCommand.SetDone(false), new SharedCommand.SetNote("denied")))
                    assertEquals(DENIED, act(service, member, s, id, intent).result().status());
                assertEquals(OK, act(service, member, s, id, new SharedCommand.Claim()).result().status());
                assertEquals(OK, act(service, member, s, id, new SharedCommand.SetDone(false)).result().status());
            }
        }
    }
    @Test void currentAdministratorCanDemoteOrRemoveHistoricalCreator() throws Exception {
        ProjectSnapshot s;
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            s = membership(service, owner, create(service), member, ProjectRole.ADMINISTRATOR);
            s = membership(service, member, s, owner, ProjectRole.MEMBER);
            assertEquals(owner, s.creator()); assertFalse(s.canAdminister(owner)); assertTrue(s.canAdminister(member));
            assertEquals(DENIED, await(service.setMembership(owner, s.scope(), s.projectRevision(), viewer, ProjectRole.VIEWER)).status());
            s = membership(service, member, s, owner, null);
            assertFalse(s.canRead(owner)); assertEquals(DENIED, await(service.join(owner, s.scope())).status());
            byte[] bytes = Files.readAllBytes(file(s));
            assertEquals(INVALID_INPUT, await(service.setMembership(member, s.scope(), s.projectRevision(), member, null)).status());
            assertArrayEquals(bytes, Files.readAllBytes(file(s)));
        }
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            await(service.ready()); var restored = await(service.join(member, s.scope())).value();
            assertEquals(s, restored); assertFalse(restored.canRead(owner));
        }
    }
    @Test void lastAdministratorCannotBeRemoved() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = create(service); byte[] before = Files.readAllBytes(file(s));
            for (ProjectRole role : Arrays.asList(null, ProjectRole.VIEWER, ProjectRole.MEMBER))
                assertEquals(INVALID_INPUT, await(service.setMembership(owner, s.scope(), s.projectRevision(), owner, role)).status());
            assertArrayEquals(before, Files.readAllBytes(file(s)));
            assertEquals(INVALID_INPUT, await(service.setMembership(owner, s.scope(), -1, member, ProjectRole.MEMBER)).status());
            assertArrayEquals(before, Files.readAllBytes(file(s)));
        }
    }
    @Test void currentRoleGuardsHistoricalPrivilegedReplay() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = membership(service, owner, create(service), member, ProjectRole.ADMINISTRATOR);
            for (TaskId id : both(s)) {
                var assign = command(current(service, s), id, 0, new SharedCommand.Assign(owner));
                assertEquals(OK, await(service.command(member, assign)).result().status());
                var other = command(current(service, s), id, 1, new SharedCommand.SetNote("admin other"));
                assertEquals(OK, await(service.command(member, other)).result().status());
                s = membership(service, owner, current(service, s), member, ProjectRole.MEMBER);
                byte[] bytes = Files.readAllBytes(file(s));
                assertEquals(DENIED, await(service.command(member, assign)).result().status());
                assertEquals(DENIED, await(service.command(member, other)).result().status());
                assertArrayEquals(bytes, Files.readAllBytes(file(s)));
                s = membership(service, owner, current(service, s), member, ProjectRole.ADMINISTRATOR);
            }
            s = membership(service, owner, current(service, s), member, ProjectRole.VIEWER);
            assertEquals(DENIED, act(service, member, s, STONE, new SharedCommand.SetNote("viewer")).result().status());
        }
    }
    @Test void legitimateOwnClaimReleaseReplayKeepsOriginalOutcome() throws Exception {
        ProjectSnapshot s; var commands = new ArrayList<SharedCommand>(); var results = new ArrayList<SharedEvents.CommandResult>();
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            s = membership(service, owner, create(service), member, ProjectRole.ADMINISTRATOR);
            for (TaskId id : both(s)) {
                var claim = command(current(service, s), id, 0, new SharedCommand.Claim());
                commands.add(claim); results.add(await(service.command(member, claim)).result());
                var note = command(current(service, s), id, 1, new SharedCommand.SetNote("own admin note"));
                commands.add(note); results.add(await(service.command(member, note)).result());
                var release = command(current(service, s), id, 2, new SharedCommand.Release());
                commands.add(release); results.add(await(service.command(member, release)).result());
            }
            s = membership(service, owner, current(service, s), member, ProjectRole.MEMBER);
        }
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            await(service.ready()); byte[] before = Files.readAllBytes(file(s));
            for (int i=0; i<commands.size(); i++) {
                var replay = await(service.command(member, commands.get(i)));
                assertEquals(results.get(i), replay.result()); assertTrue(replay.events().isEmpty());
            }
            assertArrayEquals(before, Files.readAllBytes(file(s)));
            s = membership(service, owner, current(service, s), member, ProjectRole.VIEWER);
            byte[] viewerBytes = Files.readAllBytes(file(s));
            for (var command : commands) assertEquals(DENIED, await(service.command(member, command)).result().status());
            assertArrayEquals(viewerBytes, Files.readAllBytes(file(s)));
            s = membership(service, owner, current(service, s), member, null);
            for (var command : commands) assertEquals(DENIED, await(service.command(member, command)).result().status());
        }
    }


    @Test void membershipSurvivesManifestAckDeltaAndParticipantCapacity() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root))) {
            var s = membership(service, owner, create(service), member, ProjectRole.MEMBER);
            s = membership(service, owner, s, viewer, ProjectRole.VIEWER);
            var update = await(service.updateManifest(owner, s.scope(), s.projectRevision(), s.manifest().version(), definition(30, definition().regions().getFirst())));
            assertEquals(OK, update.status()); s = update.value(); assertEquals(ProjectRole.MEMBER, s.role(member));
            var state = new SharedClientState(); state.joining(s.scope());
            state.receive(new SharedMessage.Snapshot(UUID.randomUUID(), OK, s));
            var changed = new TaskState(STONE, member, false, "confirmed", null, null, 1);
            state.receive(new SharedMessage.CommandResult(new SharedEvents.CommandResult(s.scope(), UUID.randomUUID(), OK, s.projectRevision()+1, changed)));
            assertEquals(s.members(), state.snapshot().members());
            state.receive(new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(s.scope(), s.projectRevision()+1, s.manifest().version(), changed, false)));
            assertEquals(s.members(), state.snapshot().members()); assertTrue(state.synchronizedState());
            for (int i=3; i<20; i++) s = membership(service, owner, s, new UUID(0, 100+i), ProjectRole.MEMBER);
            assertEquals(20, s.members().size()); byte[] bytes = Files.readAllBytes(file(s));
            assertEquals(CAPACITY, await(service.setMembership(owner, s.scope(), s.projectRevision(), new UUID(0, 200), ProjectRole.VIEWER)).status());
            assertArrayEquals(bytes, Files.readAllBytes(file(s)));
            assertEquals(ProjectRole.MEMBER, new SharedProjectStore(root).load(s.scope().worldNamespace(), s.scope().projectId()).value().snapshot().role(member));
        }
    }

    @Test void clientAdmissionUsesSameRowPolicyBeforeJournaling() throws Exception {
        try (var service = new SharedProjectService(new SharedProjectStore(root));
             var client = new dev.mcmateriallist.core.shared.client.SharedClientService(root)) {
            var s = membership(service, owner, create(service), member, ProjectRole.MEMBER);
            assertEquals(OK, act(service, owner, s, STONE, new SharedCommand.Assign(owner)).result().status()); s = current(service, s);
            var placement = dev.mcmateriallist.core.LocalPlacementId.create();
            var selected = await(client.select(placement, DatasetKind.MATERIALS, DatasetId.create(), member, s, s.projectRevision()));
            assertTrue(selected.ok()); var reference = selected.value().materials().shared();
            Path clientFile = root.resolve("shared-client/placement-" + placement + ".json"); byte[] bytes = Files.readAllBytes(clientFile);
            var refused = await(client.admit(placement, DatasetKind.MATERIALS, reference, member, UUID.randomUUID(), command(s, STONE, 1, new SharedCommand.SetNote("forbidden"))));
            assertEquals(dev.mcmateriallist.core.persistence.StoreStatus.CONFLICT, refused.status()); assertArrayEquals(bytes, Files.readAllBytes(clientFile));
            var admitted = await(client.admit(placement, DatasetKind.MATERIALS, reference, member, UUID.randomUUID(), command(s, DIRT, 0, new SharedCommand.SetDone(true))));
            assertTrue(admitted.ok()); assertEquals(1, admitted.value().operations().size());
            assertNull(admitted.value().operations().getFirst().confirmation());
        }
    }

}
