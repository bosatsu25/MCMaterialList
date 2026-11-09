package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.Perf5A;
import dev.mcmateriallist.core.persistence.StoreStatus;
import dev.mcmateriallist.core.shared.SharedEvents.Outcome;
import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.work.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.mcmateriallist.core.shared.SharedAuthorityTest.await;
import static dev.mcmateriallist.core.shared.SharedEvents.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5A load profile (design §13): 2000 materials + 2000 regions + 20 participants through the real codec, authority queue
 * and durable store. Hard assertions are limits, consistency and bounded refusal; timings are printed with the PERF5A marker
 * and only checked against generous ceilings, because they depend on the host disk (two fsynced replaces per command) and CPU.
 */
class Phase5ASharedLoadTest {
    @TempDir Path root;
    private final Phase5AProfile profile = new Phase5AProfile(5);

    @Test void payloadSizesAndCodecTimesForTheInitialProfile() throws Exception {
        UUID world = UUID.randomUUID();
        ProjectSnapshot snapshot = profile.progressed(ProjectScope.create(world), 9);
        var message = new SharedMessage.Snapshot(UUID.randomUUID(), OK, snapshot);
        byte[][] encoded = new byte[1][];
        var encode = Perf5A.measure(3, 15, () -> encoded[0] = SharedProtocol.encode(message));
        byte[] bytes = encoded[0];
        var decode = Perf5A.measure(3, 15, () -> SharedProtocol.decode(bytes));
        int fragments = SharedTransfers.split(UUID.randomUUID(), bytes).size();
        assertTrue(bytes.length <= SharedLimits.MESSAGE_BYTES && fragments <= SharedLimits.FRAGMENTS);
        assertEquals(message, SharedProtocol.decode(bytes));
        // A join storm: every participant receives its own snapshot; the server sends 4 frames per tick in total.
        int stormFrames = Phase5AProfile.PARTICIPANTS * fragments;
        int stormTicks = (stormFrames + 3) / 4;
        Perf5A.record("shared-snapshot-payload", "materials", Phase5AProfile.MATERIALS, "regions", Phase5AProfile.REGIONS,
            "participants", Phase5AProfile.PARTICIPANTS, "bytes", bytes.length, "kib", Perf5A.kib(bytes.length), "fragments", fragments,
            "messageLimitBytes", SharedLimits.MESSAGE_BYTES, "headroomFactor", String.format(Locale.ROOT, "%.1f", SharedLimits.MESSAGE_BYTES / (double) bytes.length),
            "encode", encode, "decode", decode);
        Perf5A.record("shared-join-storm-model", "joins", Phase5AProfile.PARTICIPANTS, "frames", stormFrames, "framesPerTick", 4,
            "minTicks", stormTicks, "minSeconds", String.format(Locale.ROOT, "%.2f", stormTicks / 20.0), "outboundKiB", Perf5A.kib((long) bytes.length * Phase5AProfile.PARTICIPANTS));

        // Ordinary operations send one changed row, never the snapshot: every one fits a single 60 KiB fragment, even with a maximal note.
        RegionTaskId region = snapshot.manifest().regions().keySet().stream().sorted(Comparator.comparing(RegionTaskId::externalForm)).findFirst().orElseThrow();
        TaskState noted = new TaskState(region, profile.owner, true, "n".repeat(TextLimits.NOTE_BYTES), profile.owner, Instant.EPOCH, 7);
        UUID operation = UUID.randomUUID();
        Map<String, SharedMessage> perOperation = new LinkedHashMap<>();
        perOperation.put("command-setdone", new SharedMessage.TaskCommand(new SharedCommand(snapshot.scope(), region, operation, 6, 0, new SharedCommand.SetDone(true))));
        perOperation.put("command-setnote-4096", new SharedMessage.TaskCommand(new SharedCommand(snapshot.scope(), region, operation, 6, 0, new SharedCommand.SetNote("n".repeat(TextLimits.NOTE_BYTES)))));
        perOperation.put("result-4096-note", new SharedMessage.CommandResult(new SharedEvents.CommandResult(snapshot.scope(), operation, OK, 1235, noted)));
        perOperation.put("delta-4096-note", new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(snapshot.scope(), 1235, 0, noted, false)));
        perOperation.put("invalidated", new SharedMessage.ProjectInvalidated(new SharedEvents.ProjectInvalidated(snapshot.scope(), 1236, 1)));
        for (var entry : perOperation.entrySet()) {
            byte[] value = SharedProtocol.encode(entry.getValue());
            assertEquals(1, SharedTransfers.split(UUID.randomUUID(), value).size(), entry.getKey() + " must fit one fragment");
            var roundTrip = Perf5A.measure(2_000, 5_000, () -> SharedProtocol.decode(SharedProtocol.encode(entry.getValue())));
            Perf5A.record("shared-operation-payload", "message", entry.getKey(), "bytes", value.length, "encodeDecode", roundTrip);
        }
        byte[] create = SharedProtocol.encode(new SharedMessage.CreateProject(UUID.randomUUID(), world, "Phase 5A load profile", profile.definition));
        Perf5A.record("shared-create-request-payload", "bytes", create.length, "kib", Perf5A.kib(create.length),
            "fragments", SharedTransfers.split(UUID.randomUUID(), create).size());
    }

    @Test void authorityUnderAverageBurstAndJoinLoad() throws Exception {
        var traces = new ConcurrentLinkedQueue<SharedDiagnostics.Trace>();
        ProjectScope scope; UUID world; Set<UUID> burstIds = new HashSet<>(); Set<TaskId> burstRows = new HashSet<>();
        try (var service = new SharedProjectService(new SharedProjectStore(root), traces::add)) {
            world = await(service.ready()).worldNamespace();
            long start = System.nanoTime();
            var created = await(service.create(profile.owner, world, "Phase 5A load profile", profile.definition));
            long createNanos = System.nanoTime() - start;
            assertEquals(OK, created.status()); scope = created.value().scope();
            long[] membership = new long[Phase5AProfile.PARTICIPANTS - 1]; long revision = 0;
            for (int i = 1; i < Phase5AProfile.PARTICIPANTS; i++) {
                UUID participant = profile.participants.get(i);
                start = System.nanoTime();
                assertEquals(OK, await(service.setMembership(profile.owner, scope, revision++, participant, profile.members().get(participant))).status());
                membership[i - 1] = System.nanoTime() - start;
            }
            Path file = root.resolve("mcmateriallist-shared").resolve("project-" + scope.projectId() + ".json");
            long createdFileBytes = Files.size(file);
            ProjectSnapshot current = await(service.join(profile.owner, scope)).value();
            assertEquals(Phase5AProfile.PARTICIPANTS, current.members().size());
            List<MaterialTaskId> materials = current.manifest().definition().materials().keySet().stream()
                .sorted(Comparator.comparing(MaterialTaskId::externalForm)).toList();
            List<RegionTaskId> regions = current.manifest().regions().keySet().stream().sorted(Comparator.comparing(RegionTaskId::externalForm)).toList();
            List<UUID> writers = profile.writers();

            // Average: one operation at a time from rotating participants (completion, claim, administrator note).
            long[] sequential = new long[120];
            for (int i = 0; i < sequential.length; i++) {
                UUID actor = writers.get(1 + i % (writers.size() - 1)); SharedCommand command;
                switch (i % 3) {
                    case 0 -> command = new SharedCommand(scope, materials.get(i), UUID.randomUUID(), 0, 0, new SharedCommand.SetDone(true));
                    case 1 -> command = new SharedCommand(scope, regions.get(i), UUID.randomUUID(), 0, 0, new SharedCommand.Claim());
                    default -> { actor = profile.owner; command = new SharedCommand(scope, regions.get(i), UUID.randomUUID(), 0, 0, new SharedCommand.SetNote("屋根の確認 #" + i)); }
                }
                start = System.nanoTime();
                assertEquals(OK, await(service.command(actor, command)).result().status());
                sequential[i] = System.nanoTime() - start;
            }
            long averageFileBytes = Files.size(file);

            // Burst: 18 writers x 10 completions arrive at once, then all 18 race to claim one region: the first (the administrator) wins and members are refused because the row is no longer unassigned.
            List<CompletableFuture<Outcome>> burst = new ArrayList<>(); long[] submitted = new long[198]; long[] latency = new long[198];
            long burstStart = System.nanoTime();
            for (int k = 0; k < 10; k++) for (int w = 0; w < writers.size(); w++) {
                var command = new SharedCommand(scope, materials.get(200 + w * 10 + k), UUID.randomUUID(), 0, 0, new SharedCommand.SetDone(true));
                burstIds.add(command.operationId()); burstRows.add(command.taskId());
                int index = burst.size(); submitted[index] = System.nanoTime();
                burst.add(service.command(writers.get(w), command).toCompletableFuture().whenComplete((outcome, failure) -> latency[index] = System.nanoTime() - submitted[index]));
            }
            RegionTaskId contested = regions.get(500);
            for (UUID writer : writers) {
                var command = new SharedCommand(scope, contested, UUID.randomUUID(), 0, 0, new SharedCommand.Claim());
                burstIds.add(command.operationId());
                int index = burst.size(); submitted[index] = System.nanoTime();
                burst.add(service.command(writer, command).toCompletableFuture().whenComplete((outcome, failure) -> latency[index] = System.nanoTime() - submitted[index]));
            }
            CompletableFuture.allOf(burst.toArray(CompletableFuture[]::new)).get(300, TimeUnit.SECONDS);
            long burstNanos = System.nanoTime() - burstStart;
            Map<SharedEvents.Status, Long> statuses = new EnumMap<>(SharedEvents.Status.class);
            for (var future : burst) statuses.merge(future.get().result().status(), 1L, Long::sum);
            assertEquals(Map.of(OK, 181L, DENIED, 17L), statuses, "Burst must neither refuse nor corrupt: " + statuses);
            long maxQueued = traces.stream().filter(t -> burstIds.contains(t.operationId())).mapToLong(SharedDiagnostics.Trace::queuedNanos).max().orElseThrow();
            long[] executed = traces.stream().filter(t -> burstIds.contains(t.operationId())).mapToLong(SharedDiagnostics.Trace::durationNanos).toArray();

            // Join storm: every participant (re)joins at once.
            List<CompletableFuture<SharedEvents.Reply<ProjectSnapshot>>> joins = new ArrayList<>(); long[] joinLatency = new long[Phase5AProfile.PARTICIPANTS];
            for (int i = 0; i < Phase5AProfile.PARTICIPANTS; i++) {
                int index = i; long submittedAt = System.nanoTime();
                joins.add(service.join(profile.participants.get(i), scope).toCompletableFuture().whenComplete((reply, failure) -> joinLatency[index] = System.nanoTime() - submittedAt));
            }
            CompletableFuture.allOf(joins.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
            for (var join : joins) assertEquals(OK, join.get().status());

            Perf5A.record("shared-authority-create", "createMs", Perf5A.ms(createNanos), "membership", Perf5A.Stats.of(membership),
                "projectFileBytes", createdFileBytes, "projectFileKiB", Perf5A.kib(createdFileBytes));
            Perf5A.record("shared-authority-average", "commands", sequential.length, "latency", Perf5A.Stats.of(sequential),
                "projectFileKiB", Perf5A.kib(averageFileBytes));
            Perf5A.record("shared-authority-burst", "commands", burst.size(), "drainMs", Perf5A.ms(burstNanos), "latency", Perf5A.Stats.of(latency),
                "execution", Perf5A.Stats.of(executed), "maxQueuedMs", Perf5A.ms(maxQueued), "ok", statuses.get(OK), "lostClaimRace", statuses.get(DENIED),
                "queueCapacity", SharedLimits.QUEUED_WORK);
            Perf5A.record("shared-authority-join-storm", "joins", joins.size(), "latency", Perf5A.Stats.of(joinLatency));
            // Generous ceilings only: an ordinary operation must not take a whole minute even on a slow shared CI disk.
            assertTrue(Perf5A.Stats.of(sequential).p50Nanos() < 60_000_000_000L);
        }
        // The durable result is complete and consistent after the burst: every accepted completion is stored exactly once.
        var stored = new SharedProjectStore(root).load(world, scope.projectId());
        assertEquals(StoreStatus.OK, stored.status());
        ProjectSnapshot finalSnapshot = stored.value().snapshot();
        for (TaskId row : burstRows) assertTrue(finalSnapshot.rows().get(row).done() && finalSnapshot.rows().get(row).rowVersion() == 1);
        assertEquals(120 + 181, stored.value().operations().size(), "Refused claims record no operation");
        assertEquals(1, finalSnapshot.rows().values().stream().filter(row -> row.taskId() instanceof RegionTaskId && row.assignee() != null
            && row.rowVersion() == 1 && !row.done() && row.note().isEmpty() && finalSnapshot.manifest().regions().keySet().stream().sorted(Comparator.comparing(RegionTaskId::externalForm)).toList().indexOf(row.taskId()) == 500).count());
    }

    @Test void overloadIsRefusedImmediatelyAndLeavesNoCorruption() throws Exception {
        var release = new CountDownLatch(1); var blocked = new CountDownLatch(1); var armed = new AtomicBoolean();
        SharedProjectStore.WriteHook hook = stage -> {
            if (stage == SharedProjectStore.WriteStage.BEFORE_PRIMARY_REPLACE && armed.compareAndSet(true, false)) {
                blocked.countDown();
                try { if (!release.await(60, TimeUnit.SECONDS)) throw new IOException("test latch timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            }
        };
        UUID owner = UUID.randomUUID(); ProjectSnapshot s; UUID world;
        try (var service = new SharedProjectService(new SharedProjectStore(root, hook))) {
            world = await(service.ready()).worldNamespace();
            s = await(service.create(owner, world, "overload", SharedAuthorityTest.definition())).value();
            armed.set(true);
            var first = service.command(owner, SharedAuthorityTest.command(s, SharedAuthorityTest.STONE, 0, new SharedCommand.SetDone(true))).toCompletableFuture();
            assertTrue(blocked.await(20, TimeUnit.SECONDS), "worker did not reach the held write");
            List<CompletableFuture<Outcome>> queued = new ArrayList<>(); List<Long> refusal = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                long start = System.nanoTime();
                var future = service.command(owner, SharedAuthorityTest.command(s, SharedAuthorityTest.DIRT, 0, new SharedCommand.SetNote("n" + i))).toCompletableFuture();
                long elapsed = System.nanoTime() - start;
                if (future.isDone()) { assertEquals(CAPACITY, future.get().result().status()); refusal.add(elapsed); }
                else queued.add(future);
            }
            assertEquals(SharedLimits.QUEUED_WORK, queued.size(), "Exactly the bounded queue is admitted while the worker is busy");
            assertEquals(300 - SharedLimits.QUEUED_WORK, refusal.size());
            long drainStart = System.nanoTime(); release.countDown();
            assertEquals(OK, first.get(60, TimeUnit.SECONDS).result().status());
            CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)).get(300, TimeUnit.SECONDS);
            long drainNanos = System.nanoTime() - drainStart;
            long ok = 0, conflict = 0;
            for (var future : queued) { var status = future.get().result().status(); if (status == OK) ok++; else if (status == CONFLICT) conflict++; else fail("Unexpected " + status); }
            assertEquals(1, ok); assertEquals(SharedLimits.QUEUED_WORK - 1, conflict);
            Perf5A.record("shared-authority-overload", "submitted", 301, "queueCapacity", SharedLimits.QUEUED_WORK, "refused", refusal.size(),
                "refusalReturn", Perf5A.Stats.of(refusal.stream().mapToLong(Long::longValue).toArray()), "drainMs", Perf5A.ms(drainNanos));
        }
        var stored = new SharedProjectStore(root).load(world, s.scope().projectId());
        assertEquals(StoreStatus.OK, stored.status());
        assertEquals(1 + SharedLimits.QUEUED_WORK, stored.value().operations().size());
        assertEquals("n0", stored.value().snapshot().rows().get(SharedAuthorityTest.DIRT).note());
    }

    @Test void fullRetentionArchiveStorageCostAndEvictedReplaySafety() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC);
        var store = new SharedProjectStore(root);
        UUID world = store.openWorld().value().namespace();
        ProjectScope scope = ProjectScope.create(world);
        ProjectSnapshot base = profile.progressed(scope, 21);
        MaterialTaskId probe = base.manifest().definition().materials().keySet().stream().sorted(Comparator.comparing(MaterialTaskId::externalForm)).findFirst().orElseThrow();
        TaskState probeRow = new TaskState(probe, null, false, "probe", null, null, 5);
        Map<TaskId, TaskState> rows = new HashMap<>(base.rows()); rows.put(probe, probeRow);
        ProjectSnapshot snapshot = new ProjectSnapshot(scope, base.name(), base.creator(), base.members(), base.projectRevision(), base.manifest(), rows, Set.of());
        // The oldest retained operation produced the probe row (version 4 -> 5); 4095 newer operations follow it.
        SharedCommand oldest = new SharedCommand(scope, probe, UUID.randomUUID(), 4, 0, new SharedCommand.SetNote("probe"));
        List<ProjectArchive.Operation> operations = new ArrayList<>();
        operations.add(new ProjectArchive.Operation(profile.owner, oldest, new SharedEvents.CommandResult(scope, oldest.operationId(), OK, 1, probeRow), clock.instant()));
        List<TaskId> ids = snapshot.rows().keySet().stream().sorted(Comparator.comparing(TaskId::externalForm)).toList();
        Random random = new Random(3);
        for (int i = 1; i < SharedLimits.OPERATIONS; i++) {
            TaskId id = ids.get(random.nextInt(ids.size()));
            SharedCommand.Intent intent = switch (i % 3) {
                case 0 -> new SharedCommand.SetDone(true);
                case 1 -> new SharedCommand.Claim();
                default -> new SharedCommand.SetNote("確認済み / checked " + i);
            };
            SharedCommand command = new SharedCommand(scope, id, UUID.randomUUID(), 0, 0, intent);
            operations.add(new ProjectArchive.Operation(profile.owner, command,
                new SharedEvents.CommandResult(scope, command.operationId(), i % 7 == 0 ? CONFLICT : OK, Math.min(i, snapshot.projectRevision()), snapshot.rows().get(id)), clock.instant()));
        }
        ProjectArchive archive = new ProjectArchive(0, snapshot, operations, List.of());
        byte[][] holder = new byte[1][];
        var encode = Perf5A.measure(2, 8, () -> holder[0] = SharedBinary.encodeArchive(archive));
        var decode = Perf5A.measure(2, 8, () -> SharedBinary.decodeArchive(holder[0]));
        assertTrue(holder[0].length <= SharedLimits.ARCHIVE_BYTES);
        long emptyOpsBytes = SharedBinary.encodeArchive(new ProjectArchive(0, snapshot, List.of(), List.of())).length;
        assertEquals(StoreStatus.OK, store.save(archive, -1).status());
        Path file = root.resolve("mcmateriallist-shared").resolve("project-" + scope.projectId() + ".json");
        long fileBytes = Files.size(file);
        var load = Perf5A.measure(2, 8, () -> assertTrue(store.load(world, scope.projectId()).ok()));
        var traces = new ConcurrentLinkedQueue<SharedDiagnostics.Trace>();
        long[] latency = new long[10];
        try (var service = new SharedProjectService(store, clock, traces::add)) {
            await(service.ready());
            List<TaskId> open = ids.stream().filter(id -> !id.equals(probe) && !snapshot.rows().get(id).done() && snapshot.rows().get(id).assignee() == null).limit(latency.length).toList();
            for (int i = 0; i < latency.length; i++) {
                TaskState row = snapshot.rows().get(open.get(i));
                long start = System.nanoTime();
                assertEquals(OK, await(service.command(profile.owner, new SharedCommand(scope, row.taskId(), UUID.randomUUID(), row.rowVersion(), 0, new SharedCommand.SetDone(true)))).result().status());
                latency[i] = System.nanoTime() - start;
            }
            // The oldest result was evicted by the first new operation. Its exact replay must be a definitive CONFLICT, not a second application.
            var replay = await(service.command(profile.owner, oldest));
            assertEquals(CONFLICT, replay.result().status());
            assertEquals(probeRow, await(service.join(profile.owner, scope)).value().rows().get(probe));
        }
        assertEquals(SharedLimits.OPERATIONS, store.load(world, scope.projectId()).value().operations().size());
        long[] execution = traces.stream().filter(t -> t.operation() == SharedDiagnostics.Operation.COMMAND).mapToLong(SharedDiagnostics.Trace::durationNanos).toArray();
        Perf5A.record("shared-archive-full-retention", "operations", SharedLimits.OPERATIONS, "archiveBytes", holder[0].length, "archiveKiB", Perf5A.kib(holder[0].length),
            "withoutOperationsKiB", Perf5A.kib(emptyOpsBytes), "archiveLimitBytes", SharedLimits.ARCHIVE_BYTES, "fileKiB", Perf5A.kib(fileBytes),
            "encode", encode, "decode", decode, "storeLoad", load);
        Perf5A.record("shared-authority-full-retention-command", "commands", latency.length, "latency", Perf5A.Stats.of(latency), "execution", Perf5A.Stats.of(execution));
        // How long 4096 retained results last for 20 participants at a sustained per-participant operation rate.
        for (double perParticipantSeconds : new double[] {1, 3, 10, 30})
            Perf5A.record("shared-retention-window-model", "participants", Phase5AProfile.PARTICIPANTS, "secondsBetweenOpsPerParticipant", perParticipantSeconds,
                "windowMinutes", String.format(Locale.ROOT, "%.1f", SharedLimits.OPERATIONS * perParticipantSeconds / Phase5AProfile.PARTICIPANTS / 60.0),
                "timeCapHours", SharedLimits.RETENTION_SECONDS / 3600);
    }
}
