package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.shared.ProjectScope;
import java.util.Objects;
import java.util.UUID;

/** Only mod-enabled servers and singleplayer have a world namespace; there is no address-based identity. */
public record WorldIdentity(UUID namespace) {
    public enum Kind { SERVER_NAMESPACE }
    public WorldIdentity { Objects.requireNonNull(namespace, "namespace"); }
    public static WorldIdentity of(ProjectScope scope) { return new WorldIdentity(scope.worldNamespace()); }
    public Kind kind() { return Kind.SERVER_NAMESPACE; }
    @Override public String toString() { return namespace.toString(); }
}
