package dev.mcmateriallist.core.shared.protocol;

import dev.mcmateriallist.core.shared.*;
import java.util.*;

/** Protocol v2. Identity of the requester never appears in request fields. */
public sealed interface SharedMessage {
    record Hello(int protocolVersion) implements SharedMessage {}
    record Capabilities(UUID worldNamespace, int protocolVersion, int materialRows, int regionRows,
                        int participants, int messageBytes, int fragmentBytes) implements SharedMessage {
        public Capabilities { Objects.requireNonNull(worldNamespace);
            if (protocolVersion < 1 || materialRows < 1 || materialRows > SharedLimits.ROWS_PER_LIST
                || regionRows < 1 || regionRows > SharedLimits.ROWS_PER_LIST || participants < 1 || participants > SharedLimits.PARTICIPANTS
                || messageBytes < 1 || messageBytes > SharedLimits.MESSAGE_BYTES || fragmentBytes < 1 || fragmentBytes > SharedLimits.FRAGMENT_BYTES)
                throw new IllegalArgumentException("Invalid capabilities"); }
        public static Capabilities current(UUID world) { return new Capabilities(world, SharedProtocol.VERSION, SharedLimits.ROWS_PER_LIST,
            SharedLimits.ROWS_PER_LIST, SharedLimits.PARTICIPANTS, SharedLimits.MESSAGE_BYTES, SharedLimits.FRAGMENT_BYTES); }
    }
    record ListProjects(UUID requestId, UUID worldNamespace) implements SharedMessage {
        public ListProjects { Objects.requireNonNull(requestId); Objects.requireNonNull(worldNamespace); }
    }
    record ProjectSummary(ProjectScope scope, String name) {
        public ProjectSummary { Objects.requireNonNull(scope);
            if (name == null || name.isBlank() || !dev.mcmateriallist.core.work.TextLimits.valid(name, 256))
                throw new IllegalArgumentException("Invalid project name"); }
    }
    record Projects(UUID requestId, SharedEvents.Status status, List<ProjectSummary> projects) implements SharedMessage {
        public Projects { Objects.requireNonNull(requestId); Objects.requireNonNull(status); projects = List.copyOf(projects);
            SharedLimits.count(projects.size(), SharedLimits.PROJECTS);
            if (status != SharedEvents.Status.OK && !projects.isEmpty()) throw new IllegalArgumentException("Unexpected project contents"); }
    }
    record JoinProject(UUID requestId, ProjectScope scope) implements SharedMessage {
        public JoinProject { Objects.requireNonNull(requestId); Objects.requireNonNull(scope); }
    }
    record CreateProject(UUID requestId, UUID worldNamespace, String name, SharedManifest.Definition definition) implements SharedMessage {
        public CreateProject { Objects.requireNonNull(requestId); Objects.requireNonNull(worldNamespace); Objects.requireNonNull(definition);
            if (name == null || name.isBlank() || !dev.mcmateriallist.core.work.TextLimits.valid(name, 256)) throw new IllegalArgumentException("Invalid name"); }
    }
    record UpdateManifest(UUID requestId, ProjectScope scope, long expectedProjectRevision,
                          long expectedManifestVersion, SharedManifest.Definition definition) implements SharedMessage {
        public UpdateManifest { Objects.requireNonNull(requestId); Objects.requireNonNull(scope); Objects.requireNonNull(definition);
            if (expectedProjectRevision < 0 || expectedManifestVersion < 0) throw new IllegalArgumentException("Invalid version"); }
    }
    record SetViewer(UUID requestId, ProjectScope scope, long expectedProjectRevision, UUID target, boolean granted) implements SharedMessage {
        public SetViewer { Objects.requireNonNull(requestId); Objects.requireNonNull(scope); Objects.requireNonNull(target);
            if (expectedProjectRevision < 0) throw new IllegalArgumentException("Invalid version"); }
    }
    record SetMembership(UUID requestId, ProjectScope scope, long expectedProjectRevision, UUID target, ProjectRole role) implements SharedMessage {
        public SetMembership { Objects.requireNonNull(requestId); Objects.requireNonNull(scope); Objects.requireNonNull(target);
            if (expectedProjectRevision < 0) throw new IllegalArgumentException("Invalid version"); }
    }
    /** Correlates create/join/admin responses; only OK carries content. */
    record Snapshot(UUID requestId, SharedEvents.Status status, ProjectSnapshot snapshot) implements SharedMessage {
        public Snapshot { Objects.requireNonNull(requestId); Objects.requireNonNull(status);
            if ((status == SharedEvents.Status.OK) != (snapshot != null)) throw new IllegalArgumentException("Invalid snapshot reply"); }
    }
    record TaskCommand(SharedCommand command) implements SharedMessage { public TaskCommand { Objects.requireNonNull(command); } }
    record CommandResult(SharedEvents.CommandResult result) implements SharedMessage { public CommandResult { Objects.requireNonNull(result); } }
    record ProjectDelta(SharedEvents.ProjectDelta delta) implements SharedMessage { public ProjectDelta { Objects.requireNonNull(delta); } }
    record ProjectInvalidated(SharedEvents.ProjectInvalidated invalidated) implements SharedMessage { public ProjectInvalidated { Objects.requireNonNull(invalidated); } }
    /** The recipient is the connection; no private contents or participant IDs on the wire. */
    record AccessRevoked(ProjectScope scope) implements SharedMessage { public AccessRevoked { Objects.requireNonNull(scope); } }
}
