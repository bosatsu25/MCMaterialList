package dev.mcmateriallist.core.shared;

import java.util.Objects;
import java.util.UUID;

/** Both independent list identities travel with every project operation. */
public record ProjectScope(UUID worldNamespace, UUID projectId, UUID materialListId, UUID regionListId) {
    public ProjectScope {
        Objects.requireNonNull(worldNamespace); Objects.requireNonNull(projectId);
        Objects.requireNonNull(materialListId); Objects.requireNonNull(regionListId);
        if (materialListId.equals(regionListId)) throw new IllegalArgumentException("Distinct lists required");
    }
    public static ProjectScope create(UUID world) {
        return new ProjectScope(world, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
