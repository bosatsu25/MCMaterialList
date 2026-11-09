package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.shared.persistence.SharedProjectStore;
import dev.mcmateriallist.core.work.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.mcmateriallist.core.shared.SharedAuthorityTest.*;
import static dev.mcmateriallist.core.shared.SharedEvents.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 5A, design §13: traces correlate operations and never contain note bodies, names, counts or player identities. */
class SharedDiagnosticsTest {
    @TempDir Path root;
    private static final String NOTE = "PRIVATE-NOTE-BODY 秘密の補足 do-not-log";
    private static final String NAME = "Secret project name";
    private final UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), outsider = UUID.randomUUID();

    @Test void tracesCorrelateEveryOperationAndExcludePrivateContent() throws Exception {
        var traces = new ConcurrentLinkedQueue<SharedDiagnostics.Trace>();
        var region = new RegionDescriptor("Secret tower key", new Vector3(0, 0, 0), new Vector3(-2, 3, 4));
        var definition = definition(424242, region);
        ProjectSnapshot created;
        SharedCommand note, assign;
        try (var service = new SharedProjectService(new SharedProjectStore(root), traces::add)) {
            UUID world = await(service.ready()).worldNamespace();
            created = await(service.create(owner, world, NAME, definition)).value();
            assertEquals(OK, await(service.setMembership(owner, created.scope(), 0, member, ProjectRole.MEMBER)).status());
            await(service.discover(owner, world));
            await(service.join(member, created.scope()));
            await(service.join(outsider, created.scope()));
            ProjectSnapshot current = await(service.join(owner, created.scope())).value();
            assertEquals(OK, await(service.command(member, command(current, STONE, 0, new SharedCommand.Claim()))).result().status());
            note = command(current, STONE, 1, new SharedCommand.SetNote(NOTE));
            assertEquals(OK, await(service.command(member, note)).result().status());
            assign = command(current, DIRT, 0, new SharedCommand.Assign(member));
            assertEquals(OK, await(service.command(owner, assign)).result().status());
            assertEquals(CONFLICT, await(service.command(owner, command(current, STONE, 0, new SharedCommand.SetDone(true)))).result().status());
            await(service.updateManifest(owner, created.scope(), current.projectRevision() + 3, 0, definition(7, region)));
        }
        List<String> lines = traces.stream().map(SharedDiagnostics.Trace::format).toList();
        lines.forEach(line -> System.out.println("PERF5A trace-sample " + line));
        assertEquals(11, lines.size(), "One trace per executed operation: " + lines);
        for (String line : lines) {
            assertTrue(line.startsWith(SharedDiagnostics.PREFIX + " op="), line);
            for (String forbidden : List.of(NOTE, "PRIVATE-NOTE", "秘密", NAME, "Secret", "424242", owner.toString(), member.toString(), outsider.toString()))
                assertFalse(line.contains(forbidden), () -> "Trace leaked private content '" + forbidden + "': " + line);
            for (String key : List.of(" project=", " task=", " operation=", " intent=", " result=", " revision=", " rowVersion=", " queuedMicros=", " durationMicros="))
                assertTrue(line.contains(key), () -> "Trace lacks " + key + ": " + line);
        }
        var noteTrace = traces.stream().filter(t -> note.operationId().equals(t.operationId())).findFirst().orElseThrow();
        assertEquals(SharedDiagnostics.Operation.COMMAND, noteTrace.operation());
        assertEquals(created.scope().projectId(), noteTrace.projectId());
        assertEquals(STONE, noteTrace.taskId());
        assertEquals("SetNote", noteTrace.intent());
        assertEquals(OK, noteTrace.result());
        assertTrue(noteTrace.revision() > 0 && noteTrace.rowVersion() == 2 && noteTrace.durationNanos() >= 0 && noteTrace.queuedNanos() >= 0);
        assertTrue(noteTrace.routine());
        var assignTrace = traces.stream().filter(t -> assign.operationId().equals(t.operationId())).findFirst().orElseThrow();
        assertEquals("Assign", assignTrace.intent());
        assertTrue(traces.stream().anyMatch(t -> t.operation() == SharedDiagnostics.Operation.COMMAND && t.result() == CONFLICT && !t.routine()));
        assertTrue(traces.stream().anyMatch(t -> t.operation() == SharedDiagnostics.Operation.JOIN && t.result() == DENIED && !t.routine()));
        assertTrue(traces.stream().anyMatch(t -> t.operation() == SharedDiagnostics.Operation.MEMBERSHIP && t.intent().equals("Membership(MEMBER)")));
        assertTrue(traces.stream().anyMatch(t -> t.operation() == SharedDiagnostics.Operation.MANIFEST && t.intent().equals("Manifest(materials=2,regions=1)") && t.manifestVersion() == 1));
        assertTrue(traces.stream().anyMatch(t -> t.operation() == SharedDiagnostics.Operation.CREATE && t.intent().equals("Create(materials=2,regions=1)")
            && created.scope().projectId().equals(t.projectId()) && t.revision() == 0));
    }

    @Test void refusedAdmissionIsTracedAndAThrowingSinkNeverChangesResults() throws Exception {
        var traces = new ConcurrentLinkedQueue<SharedDiagnostics.Trace>();
        SharedDiagnostics.Sink sink = trace -> { traces.add(trace); throw new IllegalStateException("broken sink"); };
        ProjectSnapshot created;
        var service = new SharedProjectService(new SharedProjectStore(root), sink);
        try {
            UUID world = await(service.ready()).worldNamespace();
            created = await(service.create(owner, world, NAME, definition())).value();
            assertEquals(OK, await(service.command(owner, command(created, STONE, 0, new SharedCommand.SetDone(true)))).result().status());
        } finally { service.close(); }
        var closed = await(service.command(owner, command(created, DIRT, 0, new SharedCommand.SetNote(NOTE))));
        assertEquals(CLOSED, closed.result().status());
        var last = traces.stream().reduce((a, b) -> b).orElseThrow();
        assertEquals(CLOSED, last.result());
        assertEquals("SetNote", last.intent());
        assertFalse(last.format().contains(NOTE));
    }

    @Test void intentNamesNeverIncludeArguments() {
        UUID target = UUID.randomUUID();
        assertEquals("Assign", SharedDiagnostics.intent(new SharedCommand.Assign(target)));
        assertEquals("SetNote", SharedDiagnostics.intent(new SharedCommand.SetNote(NOTE)));
        assertEquals("SetDone(false)", SharedDiagnostics.intent(new SharedCommand.SetDone(false)));
        var trace = new SharedDiagnostics.Trace(SharedDiagnostics.Operation.COMMAND, null, null, null, "Claim", null, -1, -1, -1, 0, 0);
        assertEquals(SharedDiagnostics.PREFIX + " op=command project=- task=- operation=- intent=Claim result=FAILED revision=-1 rowVersion=-1 manifest=-1 queuedMicros=0 durationMicros=0",
            trace.format());
        assertFalse(trace.routine());
    }
}
