package dev.mcmateriallist.core.stock.protocol;

import dev.mcmateriallist.core.location.ContainerBlocks;
import dev.mcmateriallist.core.location.ContainerPosition;
import dev.mcmateriallist.core.location.LocationLimits;
import dev.mcmateriallist.core.location.LocationProfileId;
import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.stock.LocationReport;
import dev.mcmateriallist.core.stock.StockGrant;
import dev.mcmateriallist.core.stock.StockLimits;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Messages of the mcmateriallist:stock_v1 channel. Every request carries a requestId that its replies echo; the actor is never in a payload. */
public sealed interface StockMessage {
    UUID requestId();

    /** Client to server. binding is the profile's ProjectScope (null when unbound); positions are the profile generation's positions. */
    record ScanRequest(UUID requestId, LocationProfileId profileId, long profileGeneration, ProjectScope binding, String dimension,
        List<ContainerPosition> positions) implements StockMessage {
        public ScanRequest {
            Objects.requireNonNull(requestId); Objects.requireNonNull(profileId);
            if (profileGeneration < 0) throw new IllegalArgumentException("Negative generation");
            positions = validPositions(dimension, positions);
        }
    }
    record Cancel(UUID requestId) implements StockMessage { public Cancel { Objects.requireNonNull(requestId); } }
    /** Server to client, one frame per location as soon as it is produced. */
    record Report(UUID requestId, LocationReport report) implements StockMessage {
        public Report { Objects.requireNonNull(requestId); Objects.requireNonNull(report); }
    }
    enum Outcome { COMPLETE, CANCELLED }
    /** maxContainersPerTick and maxNanosPerTick are tick-wide measurements over the ticks that served this scan. */
    record Done(UUID requestId, Outcome outcome, int reports, int checked, int maxContainersPerTick, long maxNanosPerTick) implements StockMessage {
        public Done {
            Objects.requireNonNull(requestId); Objects.requireNonNull(outcome);
            if (reports < 0 || reports > StockLimits.MAX_POSITIONS || checked < 0 || checked > reports || maxContainersPerTick < 0 || maxNanosPerTick < 0)
                throw new IllegalArgumentException("Invalid scan summary");
        }
    }
    enum RefusalReason { COOLDOWN, CAPACITY, BUSY, INVALID }
    record Refused(UUID requestId, RefusalReason reason) implements StockMessage {
        public Refused { Objects.requireNonNull(requestId); Objects.requireNonNull(reason); }
    }
    /** An empty position list grants nothing and only lists the dimension's grants. */
    record GrantRequest(UUID requestId, ProjectScope scope, String dimension, List<ContainerPosition> positions) implements StockMessage {
        public GrantRequest { Objects.requireNonNull(requestId); Objects.requireNonNull(scope); positions = validPositions(dimension, positions); }
    }
    record RevokeRequest(UUID requestId, ProjectScope scope, String dimension, List<ContainerPosition> positions) implements StockMessage {
        public RevokeRequest { Objects.requireNonNull(requestId); Objects.requireNonNull(scope); positions = validPositions(dimension, positions); }
    }
    /** Wire order is fixed: append only. */
    enum GrantStatus { OK, UNAUTHORIZED, INVALID, CAPACITY, BUSY, CONFLICT, FAILED }
    /** The project's grants in the request's dimension after the change; empty unless OK. */
    record GrantReply(UUID requestId, GrantStatus status, String dimension, List<StockGrant> grants) implements StockMessage {
        public GrantReply {
            Objects.requireNonNull(requestId); Objects.requireNonNull(status);
            if (!LocationLimits.namespacedId(dimension)) throw new IllegalArgumentException("Invalid dimension");
            grants = List.copyOf(grants);
            if (grants.size() > StockLimits.MAX_GRANTS || status != GrantStatus.OK && !grants.isEmpty()
                || grants.stream().anyMatch(g -> !g.dimension().equals(dimension))) throw new IllegalArgumentException("Invalid grant reply");
        }
    }

    private static List<ContainerPosition> validPositions(String dimension, List<ContainerPosition> positions) {
        if (!LocationLimits.namespacedId(dimension)) throw new IllegalArgumentException("Invalid dimension");
        List<ContainerPosition> copy = List.copyOf(positions);
        if (copy.size() > StockLimits.MAX_POSITIONS || copy.stream().anyMatch(p -> !ContainerBlocks.supported(p.blockId())))
            throw new IllegalArgumentException("Invalid positions");
        return copy;
    }
}
