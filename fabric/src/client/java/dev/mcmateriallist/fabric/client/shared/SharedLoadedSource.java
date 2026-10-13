package dev.mcmateriallist.fabric.client.shared;

import dev.mcmateriallist.core.shared.client.SchematicSourceDigest;

/** One bounded local association on the actual native object, reset only by a successful native reload. */
public interface SharedLoadedSource {
    record Proof(SchematicSourceDigest.Stamp file, Object instance, long loadGeneration, long editRevision,
        boolean modifiedAtLoad, java.util.concurrent.CompletableFuture<String> digest) {
        public String completedDigest() { return digest.getNow(null); }
    }
    Proof mcmateriallist$loadedSource();
}
