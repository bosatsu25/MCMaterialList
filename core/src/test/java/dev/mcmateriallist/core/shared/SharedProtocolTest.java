package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.work.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedProtocolTest {
    private static ProjectSnapshot snapshot(SharedManifest.Definition definition) {
        SharedManifest manifest = SharedManifest.initial(definition); Map<TaskId, TaskState> rows = new HashMap<>();
        manifest.taskIds().forEach(id -> rows.put(id, TaskState.empty(id)));
        return new ProjectSnapshot(ProjectScope.create(UUID.randomUUID()), "shared", UUID.randomUUID(), Set.of(), 0, manifest, rows, Set.of());
    }
    @Test void everyMessageAndEveryIntentHasDeterministicRoundTrip() {
        ProjectSnapshot s = snapshot(SharedAuthorityTest.definition()); UUID request = UUID.randomUUID();
        List<SharedMessage> messages = new ArrayList<>(List.of(
            new SharedMessage.Hello(1), SharedMessage.Capabilities.current(s.scope().worldNamespace()),
            new SharedMessage.ListProjects(request, s.scope().worldNamespace()),
            new SharedMessage.Projects(request, SharedEvents.Status.OK, List.of(new SharedMessage.ProjectSummary(s.scope(), s.name()))),
            new SharedMessage.JoinProject(request, s.scope()), new SharedMessage.CreateProject(request, s.scope().worldNamespace(), s.name(), s.manifest().definition()),
            new SharedMessage.UpdateManifest(request, s.scope(), 0, 0, s.manifest().definition()), new SharedMessage.SetViewer(request, s.scope(), 0, UUID.randomUUID(), true),
            new SharedMessage.Snapshot(request, SharedEvents.Status.OK, s), new SharedMessage.Snapshot(request, SharedEvents.Status.DENIED, null),
            new SharedMessage.CommandResult(new SharedEvents.CommandResult(s.scope(), request, SharedEvents.Status.OK, 0, s.rows().get(SharedAuthorityTest.STONE))),
            new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(s.scope(), 1, 0, s.rows().get(SharedAuthorityTest.STONE), false)),
            new SharedMessage.ProjectInvalidated(new SharedEvents.ProjectInvalidated(s.scope(), 1, 1)), new SharedMessage.AccessRevoked(s.scope())));
        List<SharedCommand.Intent> intents = List.of(new SharedCommand.Claim(), new SharedCommand.Release(), new SharedCommand.Assign(s.creator()),
            new SharedCommand.ClearAssignment(), new SharedCommand.SetDone(true), new SharedCommand.SetNote("\u0000界 😀"));
        for (var intent : intents) messages.add(new SharedMessage.TaskCommand(SharedAuthorityTest.command(s, SharedAuthorityTest.STONE, 0, intent)));
        for (SharedMessage message : messages) {
            byte[] bytes = SharedProtocol.encode(message); assertEquals(message, SharedProtocol.decode(bytes));
            assertArrayEquals(bytes, SharedProtocol.encode(SharedProtocol.decode(bytes)));
            assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(Arrays.copyOf(bytes, bytes.length + 1)));
            assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        }
    }
    @Test void decoderRejectsOversizedCountsLengthsMalformedUtf8AndUnknownVersionsBeforeAllocation() {
        byte[] badCount = ByteBuffer.allocate(26).putInt(SharedProtocol.VERSION).put((byte) 3).putLong(0).putLong(0).put((byte) 0).putInt(Integer.MAX_VALUE).array();
        assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(badCount));
        byte[] invalidVersion = SharedProtocol.encode(new SharedMessage.Hello(1)); invalidVersion[3] = (byte) (SharedProtocol.VERSION + 1);
        assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(invalidVersion));
        byte[] invalidTag = SharedProtocol.encode(new SharedMessage.Hello(1)); invalidTag[4] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(invalidTag));
        ProjectSnapshot s = snapshot(SharedAuthorityTest.definition());
        byte[] command = SharedProtocol.encode(new SharedMessage.TaskCommand(SharedAuthorityTest.command(s, SharedAuthorityTest.STONE, 0, new SharedCommand.SetNote("a"))));
        command[command.length - 1] = (byte) 0xff; assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(command));
        ByteBuffer.wrap(command).putInt(command.length - 5, Integer.MAX_VALUE); assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(command));
        assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(new byte[SharedLimits.MESSAGE_BYTES + 1]));
    }
    @Test void allFourThousandRowsAtLegalStringLimitsFitSnapshotAndFragmentBounds() {
        Map<MaterialTaskId, Long> materials = new HashMap<>(); List<RegionDescriptor> regions = new ArrayList<>();
        for (int i = 0; i < SharedLimits.ROWS_PER_LIST; i++) {
            String suffix = String.format(Locale.ROOT, "%04d", i);
            materials.put(new MaterialTaskId("m:" + "x".repeat(250) + suffix), Long.MAX_VALUE);
            regions.add(new RegionDescriptor("r".repeat(4092) + suffix, new Vector3(i, 0, 0), new Vector3(-1, 1, 1)));
        }
        SharedManifest.Definition definition = new SharedManifest.Definition("b".repeat(64), SharedAuthorityTest.definition().baseline(), materials, regions);
        ProjectSnapshot empty = snapshot(definition); Map<TaskId, TaskState> rows = new HashMap<>();
        for (TaskId id : empty.rows().keySet()) rows.put(id, new TaskState(id, empty.creator(), true, "n".repeat(4096), empty.creator(), Instant.EPOCH, 1));
        ProjectSnapshot full = new ProjectSnapshot(empty.scope(), empty.name(), empty.creator(), Set.of(), 1, empty.manifest(), rows, Set.of());
        byte[] encoded = SharedProtocol.encode(new SharedMessage.Snapshot(UUID.randomUUID(), SharedEvents.Status.OK, full));
        System.out.println("Shared maximal-row fixture encoded bytes: " + encoded.length);
        assertTrue(encoded.length <= SharedLimits.MESSAGE_BYTES);
        assertEquals(full, ((SharedMessage.Snapshot) SharedProtocol.decode(encoded)).snapshot());
        assertTrue(SharedTransfers.split(UUID.randomUUID(), encoded).size() <= SharedLimits.FRAGMENTS);
    }
    @Test void fragmentsAreImmutableBoundedOrderedByIndexAndExpireWithoutExtendingDeadline() {
        byte[] input = new byte[SharedLimits.FRAGMENT_BYTES * 2 + 3]; new Random(7).nextBytes(input);
        List<SharedTransfers.Fragment> fragments = SharedTransfers.split(UUID.randomUUID(), input);
        SharedTransfers transfers = new SharedTransfers();
        for (int i = 2; i >= 0; i--) {
            var fragment = SharedTransfers.decode(SharedTransfers.encode(fragments.get(i)));
            fragment.bytes()[0] ^= 1; var result = transfers.accept(fragment, 100);
            if (i == 0) assertArrayEquals(input, result.orElseThrow()); else assertTrue(result.isEmpty());
        }
        assertEquals(0, transfers.incompleteCount());
        transfers.accept(fragments.getFirst(), 101); transfers.accept(fragments.get(1), 30_000);
        transfers.expire(30_101); assertEquals(0, transfers.incompleteCount());
        assertTrue(transfers.accept(fragments.getLast(), 30_102).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> transfers.accept(fragments.getLast(), 30_103));
        assertEquals(0, transfers.incompleteCount());
        assertThrows(IllegalArgumentException.class, () -> new SharedTransfers.Fragment(UUID.randomUUID(), 0, Integer.MAX_VALUE, Integer.MAX_VALUE, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> SharedTransfers.decode(new byte[SharedTransfers.FRAME_BYTES + 1]));
        for (int i = 0; i < SharedLimits.INCOMPLETE_TRANSFERS; i++) transfers.accept(SharedTransfers.split(UUID.randomUUID(), input).getFirst(), 30_104);
        assertThrows(IllegalArgumentException.class, () -> transfers.accept(SharedTransfers.split(UUID.randomUUID(), input).getFirst(), 30_104));
        assertThrows(IllegalArgumentException.class, () -> transfers.expire(0)); assertEquals(0, transfers.incompleteCount());
    }

    @Test void membershipWirePreservesAllRolesAndRejectsMalformedEntries() {
        var initial = snapshot(SharedAuthorityTest.definition());
        UUID member = new UUID(0, 201), viewer = new UUID(0, 202);
        var s = new ProjectSnapshot(initial.scope(), "roles", initial.creator(),
            Map.of(initial.creator(), ProjectRole.ADMINISTRATOR, member, ProjectRole.MEMBER, viewer, ProjectRole.VIEWER),
            initial.projectRevision(), initial.manifest(), initial.rows(), initial.needsReconfirmation());
        UUID request = new UUID(0, 203);
        byte[] snapshot = SharedProtocol.encode(new SharedMessage.Snapshot(request, SharedEvents.Status.OK, s));
        assertEquals(s, ((SharedMessage.Snapshot) SharedProtocol.decode(snapshot)).snapshot());
        int countAt = 4 + 1 + 16 + 1 + 64 + 4 + 5 + 16;
        byte[] duplicate = snapshot.clone(); System.arraycopy(duplicate, countAt + 4, duplicate, countAt + 4 + 17, 16);
        assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(duplicate));
        byte[] badRole = snapshot.clone(); badRole[countAt + 4 + 16] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(badRole));
        byte[] tooMany = snapshot.clone(); ByteBuffer.wrap(tooMany).putInt(countAt, 21);
        assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(tooMany));
        for (ProjectRole role : Arrays.asList(null, ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMINISTRATOR)) {
            var message = new SharedMessage.SetMembership(request, s.scope(), 0, member, role);
            var bytes = SharedProtocol.encode(message); assertEquals(message, SharedProtocol.decode(bytes));
            assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(Arrays.copyOf(bytes, bytes.length + 1)));
            bytes[bytes.length - (role == null ? 1 : 2)] = 2;
            assertThrows(IllegalArgumentException.class, () -> SharedProtocol.decode(bytes));
        }
    }

}
