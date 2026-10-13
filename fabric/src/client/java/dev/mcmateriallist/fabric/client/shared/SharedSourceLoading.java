package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.shared.client.LoadedSourceDigests;
import dev.mcmateriallist.core.shared.client.SchematicSourceDigest;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;

/** Scoped observation of only the raw stream used by a native schematic load. */
public final class SharedSourceLoading {
    private static final LoadedSourceDigests DIGESTS = new LoadedSourceDigests();
    private static final ThreadLocal<Candidate> PENDING = new ThreadLocal<>(), ACTIVE = new ThreadLocal<>();
    public static final class Candidate {
        private final SchematicSourceDigest.Stamp stamp;
        private LoadedSourceDigests.Observation stream;
        private Candidate(SchematicSourceDigest.Stamp stamp) { this.stamp = stamp; }
    }
    private SharedSourceLoading() {}
    public static Candidate begin(SchematicSourceDigest.Stamp stamp) {
        Candidate previous = PENDING.get();
        if (previous != null && previous.stream != null) previous.stream.invalidate();
        var candidate = new Candidate(stamp); PENDING.set(candidate); return candidate;
    }
    public static CompoundTag read(Path path) {
        Candidate candidate = PENDING.get(); PENDING.remove();
        if (candidate != null) ACTIVE.set(candidate);
        try { return NbtUtils.readNbtFromFile(path); }
        finally { ACTIVE.remove(); }
    }
    public static InputStream observe(Path path, InputStream input) {
        Candidate candidate = ACTIVE.get();
        if (candidate == null || candidate.stamp == null
            || !candidate.stamp.file().equals(path.toAbsolutePath().normalize())) return input;
        // MaLiLib retries uncompressed NBT after the gzip attempt. Only the final stream can prove the load.
        if (candidate.stream != null) candidate.stream.invalidate();
        candidate.stream = DIGESTS.observe(input);
        return candidate.stream == null ? input : candidate.stream;
    }
    public static CompletableFuture<String> finish(Candidate candidate, boolean success) {
        PENDING.remove();
        if (candidate == null || candidate.stream == null) return CompletableFuture.completedFuture(null);
        var stream = candidate.stream; candidate.stream = null;
        if (!success) stream.invalidate();
        // Also closes on an unexpected upstream exit; the parser has returned ownership now.
        try { stream.close(); } catch (IOException failure) { stream.invalidate(); }
        return stream.digest();
    }
    public static void shutdown() { DIGESTS.close(); }
    public static boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException { return DIGESTS.awaitIdle(timeout, unit); }
    public static boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException { return DIGESTS.awaitClosed(timeout, unit); }
}
