package dev.mcmateriallist.core.shared.protocol;

import dev.mcmateriallist.core.shared.*;
import java.util.*;
import static dev.mcmateriallist.core.shared.protocol.SharedBinary.*;

/** Big-endian, protocol version followed by a message tag; decoder rejects trailing bytes. */
public final class SharedProtocol {
    public static final int VERSION = 2;
    private SharedProtocol() {}
    /** Only negotiation uses the historical envelope; all application messages remain schema 2. */
    public static byte[] encodeNegotiationHello() {
        return SharedBinary.encode(9, out -> { out.writeInt(1); out.writeByte(0); out.writeInt(VERSION); });
    }
    public static byte[] encode(SharedMessage message) {
        Objects.requireNonNull(message);
        return SharedBinary.encode(SharedLimits.MESSAGE_BYTES, out -> {
            out.writeInt(VERSION);
            switch (message) {
                case SharedMessage.SetMembership m -> { out.writeByte(14); uuid(out, m.requestId()); scope(out, m.scope());
                    out.writeLong(m.expectedProjectRevision()); uuid(out, m.target()); out.writeBoolean(m.role() != null);
                    if (m.role() != null) out.writeByte(m.role().ordinal()); }
                case SharedMessage.Hello m -> { out.writeByte(0); out.writeInt(m.protocolVersion()); }
                case SharedMessage.Capabilities m -> { out.writeByte(1); uuid(out, m.worldNamespace()); out.writeInt(m.protocolVersion());
                    out.writeInt(m.materialRows()); out.writeInt(m.regionRows()); out.writeInt(m.participants()); out.writeInt(m.messageBytes()); out.writeInt(m.fragmentBytes()); }
                case SharedMessage.ListProjects m -> { out.writeByte(2); uuid(out, m.requestId()); uuid(out, m.worldNamespace()); }
                case SharedMessage.Projects m -> { out.writeByte(3); uuid(out, m.requestId()); out.writeByte(m.status().ordinal()); out.writeInt(m.projects().size());
                    for (SharedMessage.ProjectSummary project : m.projects()) { scope(out, project.scope()); text(out, project.name(), 256); } }
                case SharedMessage.JoinProject m -> { out.writeByte(4); uuid(out, m.requestId()); scope(out, m.scope()); }
                case SharedMessage.CreateProject m -> { out.writeByte(5); uuid(out, m.requestId()); uuid(out, m.worldNamespace()); text(out, m.name(), 256); definition(out, m.definition()); }
                case SharedMessage.UpdateManifest m -> { out.writeByte(6); uuid(out, m.requestId()); scope(out, m.scope());
                    out.writeLong(m.expectedProjectRevision()); out.writeLong(m.expectedManifestVersion()); definition(out, m.definition()); }
                case SharedMessage.SetViewer m -> { out.writeByte(7); uuid(out, m.requestId()); scope(out, m.scope());
                    out.writeLong(m.expectedProjectRevision()); uuid(out, m.target()); out.writeBoolean(m.granted()); }
                case SharedMessage.Snapshot m -> { out.writeByte(8); uuid(out, m.requestId()); out.writeByte(m.status().ordinal());
                    if (m.snapshot() != null) snapshot(out, m.snapshot()); }
                case SharedMessage.TaskCommand m -> { out.writeByte(9); command(out, m.command()); }
                case SharedMessage.CommandResult m -> { out.writeByte(10); result(out, m.result()); }
                case SharedMessage.ProjectDelta m -> { out.writeByte(11); scope(out, m.delta().scope()); out.writeLong(m.delta().projectRevision());
                    out.writeLong(m.delta().manifestVersion()); row(out, m.delta().row()); out.writeBoolean(m.delta().needsReconfirmation()); }
                case SharedMessage.ProjectInvalidated m -> { out.writeByte(12); scope(out, m.invalidated().scope());
                    out.writeLong(m.invalidated().projectRevision()); out.writeLong(m.invalidated().manifestVersion()); }
                case SharedMessage.AccessRevoked m -> { out.writeByte(13); scope(out, m.scope()); }
            }
        });
    }
    public static SharedMessage decode(byte[] bytes) {
        return SharedBinary.decode(bytes, SharedLimits.MESSAGE_BYTES, in -> {
            int envelope = in.readInt();
            if (envelope == 1) {
                if (bytes.length != 9 || in.readUnsignedByte() != 0)
                    throw new IllegalArgumentException("Unsupported legacy message");
                return new SharedMessage.Hello(in.readInt());
            }
            if (envelope != VERSION) throw new IllegalArgumentException("Unsupported shared protocol");
            return switch (in.readUnsignedByte()) {
                case 0 -> new SharedMessage.Hello(in.readInt());
                case 1 -> new SharedMessage.Capabilities(uuid(in), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
                case 2 -> new SharedMessage.ListProjects(uuid(in), uuid(in));
                case 3 -> {
                    UUID request = uuid(in); SharedEvents.Status status = enumeration(in, SharedEvents.Status.values());
                    int n = count(in, SharedLimits.PROJECTS); List<SharedMessage.ProjectSummary> projects = new ArrayList<>();
                    for (int i = 0; i < n; i++) projects.add(new SharedMessage.ProjectSummary(scope(in), text(in, 256)));
                    yield new SharedMessage.Projects(request, status, projects);
                }
                case 4 -> new SharedMessage.JoinProject(uuid(in), scope(in));
                case 5 -> new SharedMessage.CreateProject(uuid(in), uuid(in), text(in, 256), definition(in));
                case 6 -> new SharedMessage.UpdateManifest(uuid(in), scope(in), in.readLong(), in.readLong(), definition(in));
                case 7 -> new SharedMessage.SetViewer(uuid(in), scope(in), in.readLong(), uuid(in), bool(in));
                case 8 -> {
                    UUID request = uuid(in); SharedEvents.Status status = enumeration(in, SharedEvents.Status.values());
                    yield new SharedMessage.Snapshot(request, status, status == SharedEvents.Status.OK ? snapshot(in) : null);
                }
                case 9 -> new SharedMessage.TaskCommand(command(in));
                case 10 -> new SharedMessage.CommandResult(result(in));
                case 11 -> new SharedMessage.ProjectDelta(new SharedEvents.ProjectDelta(scope(in), in.readLong(), in.readLong(), row(in), bool(in)));
                case 12 -> new SharedMessage.ProjectInvalidated(new SharedEvents.ProjectInvalidated(scope(in), in.readLong(), in.readLong()));
                case 13 -> new SharedMessage.AccessRevoked(scope(in));
                case 14 -> new SharedMessage.SetMembership(uuid(in), scope(in), in.readLong(), uuid(in), bool(in) ? enumeration(in, ProjectRole.values()) : null);
                default -> throw new IllegalArgumentException("Unknown shared message");
            };
        });
    }
    /** Only the two embedded blob types in schema-1 client saves; not a wire compatibility decoder. */
    public static SharedMessage decodeLegacyPersisted(byte[] bytes) {
        return SharedBinary.decode(bytes, SharedLimits.MESSAGE_BYTES, in -> {
            if (in.readInt() != 1) throw new IllegalArgumentException("Invalid legacy persistence version");
            return switch (in.readUnsignedByte()) {
                case 8 -> {
                    UUID request = uuid(in); SharedEvents.Status status = enumeration(in, SharedEvents.Status.values());
                    yield new SharedMessage.Snapshot(request, status, status == SharedEvents.Status.OK ? legacySnapshot(in) : null);
                }
                case 9 -> new SharedMessage.TaskCommand(command(in));
                default -> throw new IllegalArgumentException("Unsupported legacy persisted message");
            };
        });
    }
}
