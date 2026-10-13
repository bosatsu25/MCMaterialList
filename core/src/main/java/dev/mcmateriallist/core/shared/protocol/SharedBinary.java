package dev.mcmateriallist.core.shared.protocol;

import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.work.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Explicit deterministic binary fields. No reflection, Java serialization, or polymorphic JSON. */
public final class SharedBinary {
    private SharedBinary() {}
    @FunctionalInterface interface Writer { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface interface Reader<T> { T read(DataInputStream in) throws IOException; }
    static byte[] encode(int limit, Writer writer) {
        try {
            LimitedBytes bytes = new LimitedBytes(limit);
            try (DataOutputStream out = new DataOutputStream(bytes)) { writer.write(out); }
            return bytes.toByteArray();
        } catch (IOException exception) { throw new IllegalArgumentException("Cannot encode shared data"); }
    }
    static <T> T decode(byte[] bytes, int limit, Reader<T> reader) {
        Objects.requireNonNull(bytes); SharedLimits.count(bytes.length, limit);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            T value = reader.read(in);
            if (in.available() != 0) throw new IllegalArgumentException("Trailing shared bytes");
            return value;
        } catch (IOException | java.time.DateTimeException exception) { throw new IllegalArgumentException("Invalid shared data"); }
    }
    private static final class LimitedBytes extends ByteArrayOutputStream {
        private final int limit;
        LimitedBytes(int limit) { this.limit = limit; }
        private void check(int size) { if (size < 0 || size > limit - count) throw new IllegalArgumentException("Shared byte capacity exceeded"); }
        @Override public synchronized void write(int value) { check(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) { check(length); super.write(bytes, offset, length); }
    }
    static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    static void scope(DataOutputStream out, ProjectScope value) throws IOException {
        uuid(out, value.worldNamespace()); uuid(out, value.projectId()); uuid(out, value.materialListId()); uuid(out, value.regionListId());
    }
    static ProjectScope scope(DataInputStream in) throws IOException { return new ProjectScope(uuid(in), uuid(in), uuid(in), uuid(in)); }
    static void text(DataOutputStream out, String value, int max) throws IOException {
        if (!TextLimits.valid(value, max)) throw new IllegalArgumentException("Invalid shared text");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    static String text(DataInputStream in, int max) throws IOException {
        int length = count(in, max); if (length > in.available()) throw new EOFException();
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
    }
    static int count(DataInputStream in, int max) throws IOException { int n = in.readInt(); SharedLimits.count(n, max); return n; }
    static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte(); if (value > 1) throw new IllegalArgumentException("Invalid boolean"); return value == 1;
    }
    static <E> E enumeration(DataInputStream in, E[] values) throws IOException {
        int n = in.readUnsignedByte(); if (n >= values.length) throw new IllegalArgumentException("Invalid enum"); return values[n];
    }
    private static void vector(DataOutputStream out, Vector3 v) throws IOException { out.writeInt(v.x()); out.writeInt(v.y()); out.writeInt(v.z()); }
    private static Vector3 vector(DataInputStream in) throws IOException { return new Vector3(in.readInt(), in.readInt(), in.readInt()); }
    private static void region(DataOutputStream out, RegionDescriptor r) throws IOException { text(out, r.key(), 4096); vector(out, r.relativeOrigin()); vector(out, r.size()); }
    private static RegionDescriptor region(DataInputStream in) throws IOException { return new RegionDescriptor(text(in, 4096), vector(in), vector(in)); }
    static void definition(DataOutputStream out, SharedManifest.Definition d) throws IOException {
        text(out, d.schematicDigest(), 64); text(out, d.baseline().dimension(), 256); vector(out, d.baseline().origin());
        out.writeByte(d.baseline().rotation().ordinal()); out.writeByte(d.baseline().mirror().ordinal());
        List<MaterialTaskId> ids = d.materials().keySet().stream().sorted(Comparator.comparing(MaterialTaskId::registryId)).toList();
        out.writeInt(ids.size()); for (MaterialTaskId id : ids) { text(out, id.registryId(), 256); out.writeLong(d.materials().get(id)); }
        out.writeInt(d.regions().size()); for (RegionDescriptor descriptor : d.regions()) region(out, descriptor);
    }
    static SharedManifest.Definition definition(DataInputStream in) throws IOException {
        String digest = text(in, 64);
        SharedManifest.Baseline baseline = new SharedManifest.Baseline(text(in, 256), vector(in),
            enumeration(in, SharedManifest.Baseline.Rotation.values()), enumeration(in, SharedManifest.Baseline.Mirror.values()));
        Map<MaterialTaskId, Long> materials = new HashMap<>();
        int n = count(in, SharedLimits.ROWS_PER_LIST);
        for (int i = 0; i < n; i++) if (materials.put(new MaterialTaskId(text(in, 256)), in.readLong()) != null) throw new IllegalArgumentException("Duplicate material");
        List<RegionDescriptor> regions = new ArrayList<>(); n = count(in, SharedLimits.ROWS_PER_LIST);
        for (int i = 0; i < n; i++) regions.add(region(in));
        return new SharedManifest.Definition(digest, baseline, materials, regions);
    }
    private static void manifest(DataOutputStream out, SharedManifest value) throws IOException {
        out.writeLong(value.version()); definition(out, value.definition());
        // Definition order fixes the mapping order without depending on map iteration.
        Map<RegionDescriptor, RegionTaskId> reverse = new HashMap<>(); value.regions().forEach((id, r) -> reverse.put(r, id));
        for (RegionDescriptor descriptor : value.definition().regions()) uuid(out, reverse.get(descriptor).value());
    }
    private static SharedManifest manifest(DataInputStream in) throws IOException {
        long version = in.readLong(); SharedManifest.Definition definition = definition(in);
        Map<RegionTaskId, RegionDescriptor> regions = new HashMap<>();
        for (RegionDescriptor descriptor : definition.regions()) if (regions.put(new RegionTaskId(uuid(in)), descriptor) != null)
            throw new IllegalArgumentException("Duplicate region");
        return new SharedManifest(version, definition, regions);
    }
    static void taskId(DataOutputStream out, TaskId id) throws IOException {
        if (id instanceof MaterialTaskId material) { out.writeByte(0); text(out, material.registryId(), 256); }
        else if (id instanceof RegionTaskId region) { out.writeByte(1); uuid(out, region.value()); }
        else throw new IllegalArgumentException("Unsupported task ID");
    }
    static TaskId taskId(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) { case 0 -> new MaterialTaskId(text(in, 256)); case 1 -> new RegionTaskId(uuid(in));
            default -> throw new IllegalArgumentException("Invalid task kind"); };
    }
    static void row(DataOutputStream out, TaskState r) throws IOException {
        taskId(out, r.taskId()); out.writeBoolean(r.assignee() != null); if (r.assignee() != null) uuid(out, r.assignee());
        out.writeBoolean(r.done()); text(out, r.note(), TextLimits.NOTE_BYTES);
        if (r.done()) { uuid(out, r.completedBy()); instant(out, r.completedAt()); } out.writeLong(r.rowVersion());
    }
    static TaskState row(DataInputStream in) throws IOException {
        TaskId id = taskId(in); UUID assignee = bool(in) ? uuid(in) : null; boolean done = bool(in); String note = text(in, TextLimits.NOTE_BYTES);
        UUID by = done ? uuid(in) : null; Instant at = done ? instant(in) : null; return new TaskState(id, assignee, done, note, by, at, in.readLong());
    }
    private static void instant(DataOutputStream out, Instant t) throws IOException { out.writeLong(t.getEpochSecond()); out.writeInt(t.getNano()); }
    private static Instant instant(DataInputStream in) throws IOException {
        long seconds = in.readLong(); int nanos = in.readInt();
        if (nanos < 0 || nanos > 999_999_999) throw new IllegalArgumentException("Invalid instant"); return Instant.ofEpochSecond(seconds, nanos);
    }
    static void snapshot(DataOutputStream out, ProjectSnapshot s) throws IOException {
        scope(out, s.scope()); text(out, s.name(), 256); uuid(out, s.creator()); out.writeInt(s.members().size());
        for (UUID member : s.members().keySet().stream().sorted().toList()) { uuid(out, member); out.writeByte(s.members().get(member).ordinal()); }
        out.writeLong(s.projectRevision()); manifest(out, s.manifest());
        List<TaskId> ids = ordered(s.rows().keySet()); out.writeInt(ids.size());
        for (TaskId id : ids) { row(out, s.rows().get(id)); out.writeBoolean(s.needsReconfirmation().contains(id)); }
    }
    static ProjectSnapshot snapshot(DataInputStream in) throws IOException {
        return snapshot(in, false);
    }
    static ProjectSnapshot legacySnapshot(DataInputStream in) throws IOException {
        return snapshot(in, true);
    }
    private static ProjectSnapshot snapshot(DataInputStream in, boolean legacy) throws IOException {
        ProjectScope scope = scope(in); String name = text(in, 256); UUID creator = uuid(in);
        Map<UUID, ProjectRole> members = new HashMap<>();
        if (legacy) members.put(creator, ProjectRole.ADMINISTRATOR);
        int n = count(in, legacy ? SharedLimits.PARTICIPANTS - 1 : SharedLimits.PARTICIPANTS);
        for (int i = 0; i < n; i++) {
            UUID member = uuid(in); ProjectRole role = legacy ? ProjectRole.VIEWER : enumeration(in, ProjectRole.values());
            if (members.put(member, role) != null) throw new IllegalArgumentException("Duplicate participant");
        }
        long revision = in.readLong(); SharedManifest manifest = manifest(in);
        Map<TaskId, TaskState> rows = new HashMap<>(); Set<TaskId> recheck = new HashSet<>(); n = count(in, 2 * SharedLimits.ROWS_PER_LIST);
        for (int i = 0; i < n; i++) { TaskState row = row(in);
            if (rows.put(row.taskId(), row) != null) throw new IllegalArgumentException("Duplicate row"); if (bool(in)) recheck.add(row.taskId()); }
        return new ProjectSnapshot(scope, name, creator, members, revision, manifest, rows, recheck);
    }
    private static List<TaskId> ordered(Set<TaskId> ids) {
        return ids.stream().sorted(Comparator.comparing((TaskId id) -> id instanceof MaterialTaskId ? 0 : 1).thenComparing(TaskId::externalForm)).toList();
    }
    static void command(DataOutputStream out, SharedCommand c) throws IOException {
        scope(out, c.scope()); taskId(out, c.taskId()); uuid(out, c.operationId()); out.writeLong(c.expectedRowVersion()); out.writeLong(c.expectedManifestVersion());
        switch (c.intent()) {
            case SharedCommand.Claim ignored -> out.writeByte(0);
            case SharedCommand.Release ignored -> out.writeByte(1);
            case SharedCommand.Assign assign -> { out.writeByte(2); uuid(out, assign.target()); }
            case SharedCommand.ClearAssignment ignored -> out.writeByte(3);
            case SharedCommand.SetDone done -> { out.writeByte(4); out.writeBoolean(done.done()); }
            case SharedCommand.SetNote note -> { out.writeByte(5); text(out, note.note(), TextLimits.NOTE_BYTES); }
        }
    }
    static SharedCommand command(DataInputStream in) throws IOException {
        ProjectScope scope = scope(in); TaskId id = taskId(in); UUID operation = uuid(in); long row = in.readLong(), manifest = in.readLong();
        SharedCommand.Intent intent = switch (in.readUnsignedByte()) {
            case 0 -> new SharedCommand.Claim(); case 1 -> new SharedCommand.Release(); case 2 -> new SharedCommand.Assign(uuid(in));
            case 3 -> new SharedCommand.ClearAssignment(); case 4 -> new SharedCommand.SetDone(bool(in)); case 5 -> new SharedCommand.SetNote(text(in, TextLimits.NOTE_BYTES));
            default -> throw new IllegalArgumentException("Invalid intent"); };
        return new SharedCommand(scope, id, operation, row, manifest, intent);
    }
    static void result(DataOutputStream out, SharedEvents.CommandResult r) throws IOException {
        scope(out, r.scope()); uuid(out, r.operationId()); out.writeByte(r.status().ordinal()); out.writeLong(r.projectRevision());
        out.writeBoolean(r.row() != null); if (r.row() != null) row(out, r.row());
    }
    static SharedEvents.CommandResult result(DataInputStream in) throws IOException {
        return new SharedEvents.CommandResult(scope(in), uuid(in), enumeration(in, SharedEvents.Status.values()), in.readLong(), bool(in) ? row(in) : null);
    }
    public static byte[] encodeArchive(ProjectArchive a) {
        return encode(SharedLimits.ARCHIVE_BYTES, out -> {
            out.writeLong(a.generation()); snapshot(out, a.snapshot()); out.writeInt(a.operations().size());
            for (ProjectArchive.Operation op : a.operations()) {
                uuid(out, op.actor()); command(out, op.command()); result(out, op.result()); instant(out, op.recordedAt()); out.writeBoolean(op.memberAllowed());
            }
            out.writeInt(a.retired().size()); for (ProjectArchive.Retired retired : a.retired()) {
                out.writeLong(retired.manifestVersion()); row(out, retired.row());
                if (retired.region() != null) region(out, retired.region()); else out.writeLong(retired.materialCount());
            }
        });
    }
    public static ProjectArchive decodeArchive(byte[] bytes) {
        return decodeArchive(bytes, false);
    }
    /** Persistence-only schema-1 migration; never used by the network decoder. */
    public static ProjectArchive decodeLegacyArchive(byte[] bytes) {
        return decodeArchive(bytes, true);
    }
    private static ProjectArchive decodeArchive(byte[] bytes, boolean legacy) {
        return decode(bytes, SharedLimits.ARCHIVE_BYTES, in -> {
            long generation = in.readLong(); ProjectSnapshot snapshot = snapshot(in, legacy);
            List<ProjectArchive.Operation> ops = new ArrayList<>(); int n = count(in, SharedLimits.OPERATIONS);
            for (int i = 0; i < n; i++) ops.add(new ProjectArchive.Operation(uuid(in), command(in), result(in), instant(in), !legacy && bool(in)));
            List<ProjectArchive.Retired> retired = new ArrayList<>(); n = count(in, SharedLimits.RETIRED_ROWS);
            for (int i = 0; i < n; i++) { long version = in.readLong(); TaskState row = row(in);
                retired.add(new ProjectArchive.Retired(version, row, row.taskId() instanceof RegionTaskId ? region(in) : null,
                    row.taskId() instanceof MaterialTaskId ? in.readLong() : null)); }
            return new ProjectArchive(generation, snapshot, ops, retired);
        });
    }
}
