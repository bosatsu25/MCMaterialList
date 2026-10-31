package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.persistence.DatasetJsonCodec;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.work.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Explicit bounded fields; native objects, names and paths have no persistence role. Envelope schema 1 holds legacy
 * protocol blobs, 2 current blobs, 3 adds a per-binding detached region copy (DatasetJsonCodec JSON and source scope).
 * I1 keeps at most one large blob (cache or detached copy) per binding slot, so MAX_BYTES is unchanged.
 */
final class SharedClientCodec {
    static final int MAX_BYTES = 2 * SharedLimits.MESSAGE_BYTES + 1024 * 1024;
    private static final UUID CACHE_REQUEST = new UUID(0, 0);
    private SharedClientCodec() {}
    static byte[] encode(SharedClientArchive archive) {
        LimitedBytes buffer = new LimitedBytes();
        try (var out = new DataOutputStream(buffer)) {
            out.writeLong(archive.generation()); uuid(out, archive.placement().value()); out.writeBoolean(archive.recoveredJournalUncertain());
            binding(out, archive.materials()); binding(out, archive.regions());
            out.writeInt(archive.operations().size());
            for (var operation : archive.operations()) {
                out.writeByte(operation.kind() == DatasetKind.MATERIALS ? 0 : 1);
                reference(out, operation.original()); uuid(out, operation.connection());
                blob(out, SharedProtocol.encode(new SharedMessage.TaskCommand(operation.command())));
                out.writeBoolean(operation.confirmation() != null);
                if (operation.confirmation() != null) {
                    out.writeByte(operation.confirmation().status().ordinal()); out.writeLong(operation.confirmation().projectRevision());
                }
            }
        } catch (IOException invalid) { throw new IllegalArgumentException("Cannot encode client shared state"); }
        return buffer.toByteArray();
    }
    static SharedClientArchive decode(byte[] bytes) {
        return decode(bytes, SharedClientStore.SCHEMA);
    }
    static SharedClientArchive decodeLegacy(byte[] bytes) {
        return decode(bytes, 1);
    }
    static SharedClientArchive decode(byte[] bytes, int schema) {
        if (schema < 1 || schema > SharedClientStore.SCHEMA) throw new IllegalArgumentException("Unsupported client shared schema");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Client shared state capacity");
        boolean legacy = schema == 1, detachable = schema >= 3;
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            long generation = in.readLong(); var placement = new LocalPlacementId(uuid(in)); boolean recovered = bool(in);
            var materials = binding(in, legacy, detachable); var regions = binding(in, legacy, detachable);
            int count = in.readInt();
            if (count < 0 || count > SharedClientArchive.MAX_OPERATIONS) throw new IllegalArgumentException("Client operation capacity");
            List<SharedClientArchive.Operation> operations = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int tag = in.readUnsignedByte();
                if (tag > 1) throw new IllegalArgumentException("Invalid binding kind");
                var kind = tag == 0 ? DatasetKind.MATERIALS : DatasetKind.REGIONS;
                var original = reference(in); var connection = uuid(in);
                if (!(message(blob(in), legacy) instanceof SharedMessage.TaskCommand message))
                    throw new IllegalArgumentException("Invalid journal command");
                SharedClientArchive.Confirmation confirmation = null;
                if (bool(in)) {
                    int status = in.readUnsignedByte();
                    if (status >= SharedEvents.Status.values().length) throw new IllegalArgumentException("Invalid confirmation status");
                    confirmation = new SharedClientArchive.Confirmation(SharedEvents.Status.values()[status], in.readLong());
                }
                operations.add(new SharedClientArchive.Operation(kind, original, connection, message.command(), confirmation));
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing client shared data");
            return new SharedClientArchive(generation, placement, materials, regions, operations, recovered);
        } catch (IOException | java.time.DateTimeException | ArithmeticException invalid) { throw new IllegalArgumentException("Invalid client shared data"); }
    }
    private static void binding(DataOutputStream out, SharedClientArchive.Binding binding) throws IOException {
        out.writeBoolean(binding.localDataset() != null);
        if (binding.localDataset() != null) uuid(out, binding.localDataset().value());
        out.writeBoolean(binding.shared() != null);
        if (binding.shared() != null) reference(out, binding.shared());
        out.writeBoolean(binding.cache() != null);
        if (binding.cache() != null) {
            out.writeLong(binding.cache().cursor());
            blob(out, SharedProtocol.encode(new SharedMessage.Snapshot(CACHE_REQUEST, SharedEvents.Status.OK, binding.cache().snapshot())));
        }
        out.writeBoolean(binding.detached() != null);
        if (binding.detached() != null) {
            scope(out, binding.detached().from());
            byte[] json = new DatasetJsonCodec().encode(binding.detached().dataset()).getBytes(StandardCharsets.UTF_8);
            if (json.length > SharedLimits.MESSAGE_BYTES) throw new IllegalArgumentException("Detached copy capacity");
            blob(out, json);
        }
    }
    private static SharedClientArchive.Binding binding(DataInputStream in, boolean legacy, boolean detachable) throws IOException {
        DatasetId local = bool(in) ? new DatasetId(uuid(in)) : null;
        var shared = bool(in) ? reference(in) : null;
        SharedClientArchive.Cache cache = null;
        if (bool(in)) {
            long cursor = in.readLong();
            if (!(message(blob(in), legacy) instanceof SharedMessage.Snapshot snapshot)
                || snapshot.status() != SharedEvents.Status.OK || !snapshot.requestId().equals(CACHE_REQUEST))
                throw new IllegalArgumentException("Invalid confirmed cache");
            cache = new SharedClientArchive.Cache(snapshot.snapshot(), cursor);
        }
        SharedClientArchive.Detached detached = null;
        if (detachable && bool(in)) {
            var from = scope(in);
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(blob(in))).toString();
            if (!(new DatasetJsonCodec().decode(json) instanceof RegionWorkDataset dataset)) throw new IllegalArgumentException("Detached copy is not region work");
            detached = new SharedClientArchive.Detached(dataset, from);
        }
        return new SharedClientArchive.Binding(local, shared, cache, detached);
    }
    private static SharedMessage message(byte[] bytes, boolean legacy) {
        return legacy ? SharedProtocol.decodeLegacyPersisted(bytes) : SharedProtocol.decode(bytes);
    }
    private static void reference(DataOutputStream out, SharedClientArchive.Reference ref) throws IOException {
        uuid(out, ref.bindingId()); uuid(out, ref.actor()); scope(out, ref.scope());
    }
    private static SharedClientArchive.Reference reference(DataInputStream in) throws IOException {
        return new SharedClientArchive.Reference(uuid(in), uuid(in), scope(in));
    }
    private static void scope(DataOutputStream out, ProjectScope scope) throws IOException {
        uuid(out, scope.worldNamespace()); uuid(out, scope.projectId()); uuid(out, scope.materialListId()); uuid(out, scope.regionListId());
    }
    private static ProjectScope scope(DataInputStream in) throws IOException { return new ProjectScope(uuid(in), uuid(in), uuid(in), uuid(in)); }
    private static void blob(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] blob(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > SharedLimits.MESSAGE_BYTES || length > in.available()) throw new IllegalArgumentException("Invalid client message size");
        return in.readNBytes(length);
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte(); if (value > 1) throw new IllegalArgumentException("Invalid client boolean"); return value == 1;
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static final class LimitedBytes extends ByteArrayOutputStream {
        private void check(int length) { if (length < 0 || length > MAX_BYTES - count) throw new IllegalArgumentException("Client state capacity"); }
        @Override public synchronized void write(int value) { check(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) { check(length); super.write(bytes, offset, length); }
    }
}
