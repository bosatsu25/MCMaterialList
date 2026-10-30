package dev.mcmateriallist.core.stock;

import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.shared.ProjectRole;
import dev.mcmateriallist.core.shared.ProjectScope;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * R1-R3 as pure decisions over request data only, so an answer never depends on the world. Fabric supplies the admin bit
 * (StockAuthority.serverAdmin) and the committed role (SharedProjectService.committedSnapshot); the actor always comes from the connection.
 */
public final class StockPolicy {
    public static final ProjectRole MINIMUM_ROLE = ProjectRole.MEMBER;
    /** The actor's committed role in exactly this scope, or empty when the actor cannot read it. */
    @FunctionalInterface public interface Roles { Optional<ProjectRole> role(UUID actor, ProjectScope scope); }
    private StockPolicy() {}

    /** R1: an admin reads anything. R2: otherwise MEMBER or higher in the bound project, and only granted positions of that project. */
    public static boolean allows(boolean serverAdmin, ProjectRole role, StockGrants grants, String dimension, ContainerPosition position) {
        if (serverAdmin) return true;
        return role != null && role.compareTo(MINIMUM_ROLE) >= 0 && grants != null && grants.grants(dimension, position);
    }
    /** Per-index decisions for a whole request, made before any probe call. */
    public static boolean[] decide(boolean serverAdmin, ProjectRole role, StockGrants grants, String dimension, List<ContainerPosition> positions) {
        boolean[] allowed = new boolean[positions.size()];
        for (int i = 0; i < allowed.length; i++) allowed[i] = allows(serverAdmin, role, grants, dimension, positions.get(i));
        return allowed;
    }
    /** Granting and revoking need both a server admin and the project's ADMINISTRATOR. */
    public static boolean mayGrant(boolean serverAdmin, ProjectRole role) { return serverAdmin && role == ProjectRole.ADMINISTRATOR; }
    /**
     * The live check a StockScan evaluates before every container: admin status, role and grants are read again each time, so a
     * deop, demotion or revoke takes effect mid-scan. grants maps a projectId to its committed registry (null when none is loaded).
     */
    public static StockScan.Access live(BooleanSupplier serverAdmin, UUID actor, ProjectScope binding, Roles roles, Function<UUID, StockGrants> grants) {
        Objects.requireNonNull(serverAdmin); Objects.requireNonNull(actor); Objects.requireNonNull(roles); Objects.requireNonNull(grants);
        return (dimension, position) -> {
            if (serverAdmin.getAsBoolean()) return true;
            if (binding == null) return false;
            StockGrants registry = grants.apply(binding.projectId());
            return registry != null && registry.projectId().equals(binding.projectId())
                && allows(false, roles.role(actor, binding).orElse(null), registry, dimension, position);
        };
    }
}
