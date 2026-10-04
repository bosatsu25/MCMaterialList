package dev.mcmateriallist.core.shared.transport;

import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.protocol.SharedMessage;
import dev.mcmateriallist.core.work.*;
import java.util.*;

/** Receiving-thread state; no local binding or offline mutation queue. */
public final class SharedClientState {
    public static final int BUFFERED_DELTAS = 64;
    public enum Status { UNAVAILABLE, NEGOTIATING, AVAILABLE, JOINING, SYNCHRONIZED, RESYNC_REQUIRED, REVOKED, DISCONNECTED, UNSUPPORTED, CAPACITY }
    private Status status = Status.UNAVAILABLE;
    private ProjectScope scope;
    private ProjectSnapshot snapshot;
    private long cursor = -1;
    private long minimumRevision, minimumManifest;
    private final List<SharedEvents.ProjectDelta> buffered = new ArrayList<>();
    private SharedMessage.Capabilities capabilities;
    private List<SharedMessage.ProjectSummary> projects = List.of();
    public void negotiating() { status = Status.NEGOTIATING; capabilities = null; }
    public void unsupported() { buffered.clear(); capabilities = null; status = Status.UNSUPPORTED; }
    public void unavailable() { buffered.clear(); capabilities = null; status = Status.UNAVAILABLE; }
    public void capacity() { buffered.clear(); status = Status.CAPACITY; }
    public void joining(ProjectScope next) {
        Objects.requireNonNull(next);
        if (!next.equals(scope)) { minimumRevision = 0; minimumManifest = 0; }
        scope = next; snapshot = null; cursor = -1; buffered.clear(); status = Status.JOINING;
    }
    public void receive(SharedMessage message) {
        if (message instanceof SharedMessage.Capabilities value) {
            capabilities = value;
            status = value.protocolVersion() == 1 ? Status.AVAILABLE : Status.UNSUPPORTED;
        } else if (message instanceof SharedMessage.Projects value) {
            projects = value.status() == SharedEvents.Status.OK ? value.projects() : List.of();
        } else if (message instanceof SharedMessage.Snapshot value) {
            if (status != Status.JOINING) return;
            if (value.status() != SharedEvents.Status.OK) { failed(); return; }
            if (!accepts(value.snapshot())) { if (value.snapshot().scope().equals(scope)) failed(); return; }
            snapshot = value.snapshot(); cursor = snapshot.projectRevision(); status = Status.SYNCHRONIZED;
            fence(cursor, snapshot.manifest().version());
            List<SharedEvents.ProjectDelta> pending = List.copyOf(buffered); buffered.clear();
            for (var delta : pending) delta(delta);
        } else if (message instanceof SharedMessage.ProjectDelta value) {
            if (!value.delta().scope().equals(scope)) return;
            if (status == Status.JOINING) {
                if (buffered.size() == BUFFERED_DELTAS) failed(); else buffered.add(value.delta());
            } else if (status == Status.SYNCHRONIZED) delta(value.delta());
        } else if (message instanceof SharedMessage.CommandResult value) {
            var result = value.result();
            if (status == Status.SYNCHRONIZED && snapshot != null && result.scope().equals(scope) && result.row() != null) adopt(result.row(), null);
        } else if (message instanceof SharedMessage.ProjectInvalidated value && value.invalidated().scope().equals(scope)) {
            fence(value.invalidated().projectRevision(), value.invalidated().manifestVersion());
            failed();
        } else if (message instanceof SharedMessage.AccessRevoked value && value.scope().equals(scope)) {
            snapshot = null; buffered.clear(); projects = List.of(); status = Status.REVOKED;
        }
    }
    private void fence(long revision, long manifest) {
        minimumRevision = Math.max(minimumRevision, revision); minimumManifest = Math.max(minimumManifest, manifest);
    }
    public boolean accepts(ProjectSnapshot candidate) {
        return candidate.scope().equals(scope) && candidate.projectRevision() >= minimumRevision
            && candidate.manifest().version() >= minimumManifest;
    }
    private void delta(SharedEvents.ProjectDelta delta) {
        if (delta.projectRevision() <= cursor) return;
        fence(delta.projectRevision(), delta.manifestVersion());
        if (delta.projectRevision() != cursor + 1 || delta.manifestVersion() != snapshot.manifest().version()
            || !snapshot.rows().containsKey(delta.row().taskId())) { failed(); return; }
        adopt(delta.row(), delta.needsReconfirmation()); cursor = delta.projectRevision();
        snapshot = new ProjectSnapshot(scope, snapshot.name(), snapshot.creator(), snapshot.viewers(), cursor,
            snapshot.manifest(), snapshot.rows(), snapshot.needsReconfirmation());
    }
    private void adopt(TaskState row, Boolean reconfirmation) {
        TaskState current = snapshot.rows().get(row.taskId());
        if (current == null || row.rowVersion() < current.rowVersion()) return;
        Map<TaskId, TaskState> rows = new HashMap<>(snapshot.rows());
        if (row.rowVersion() > current.rowVersion()) rows.put(row.taskId(), row);
        Set<TaskId> markers = new HashSet<>(snapshot.needsReconfirmation());
        if (reconfirmation != null) { if (reconfirmation) markers.add(row.taskId()); else markers.remove(row.taskId()); }
        snapshot = new ProjectSnapshot(scope, snapshot.name(), snapshot.creator(), snapshot.viewers(), snapshot.projectRevision(),
            snapshot.manifest(), rows, markers);
    }
    public void failed() { buffered.clear(); status = Status.RESYNC_REQUIRED; }
    public void disconnected() { buffered.clear(); capabilities = null; projects = List.of(); status = Status.DISCONNECTED; }
    public long cursor() { return cursor; }
    public ProjectScope scope() { return scope; }
    public boolean synchronizedState() { return status == Status.SYNCHRONIZED; }
    public ProjectSnapshot snapshot() { return snapshot; }
    public Status status() { return status; }
    public SharedMessage.Capabilities capabilities() { return capabilities; }
    public List<SharedMessage.ProjectSummary> projects() { return projects; }
}
