package dev.mcmateriallist.core.work;

import java.util.Objects;

/** Identity rule 1: the upstream aggregated row's item registry identifier. */
public record MaterialTaskId(String registryId) implements TaskId {
    public static final int IDENTITY_RULE = 1;
    public MaterialTaskId {
        Objects.requireNonNull(registryId, "registryId");
        if (registryId.length() > 256 || !registryId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid normalized item registry identity");
        }
    }
    @Override public String externalForm() { return registryId; }
}
