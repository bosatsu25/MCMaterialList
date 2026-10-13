package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import dev.mcmateriallist.core.shared.*;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.work.*;
import java.io.*;
import java.util.*;

/** Explicit bounded fields; native objects, names and paths have no persistence role. */
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
        return decode(bytes, false);
    }
    static SharedClientArchive decodeLegacy(byte[] bytes) {
        return decode(bytes, true);
    }
    private static SharedClientArchive decode(byte[] bytes, boolean legacy) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Client shared state capacity");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            long generation = in.readLong(); var placement = new LocalPlacementId(uuid(in)); boolean recovered = bool(in);
            var materials = binding(in, legacy); var regions = binding(in, legacy);
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
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid client shared data"); }
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
    }
    private static SharedClientArchive.Binding binding(DataInputStream in, boolean legacy) throws IOException {
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
        return new SharedClientArchive.Binding(local, shared, cache);
    }
    private static SharedMessage message(byte[] bytes, boolean legacy) {
        return legacy ? SharedProtocol.decodeLegacyPersisted(bytes) : SharedProtocol.decode(bytes);
    }
    private static void reference(DataOutputStream out, SharedClientArchive.Reference ref) throws IOException {
        uuid(out, ref.bindingId()); uuid(out, ref.actor());
        uuid(out, ref.scope().worldNamespace()); uuid(out, ref.scope().projectId());
        uuid(out, ref.scope().materialListId()); uuid(out, ref.scope().regionListId());
    }
    private static SharedClientArchive.Reference reference(DataInputStream in) throws IOException {
        return new SharedClientArchive.Reference(uuid(in), uuid(in), new ProjectScope(uuid(in), uuid(in), uuid(in), uuid(in)));
    }
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
