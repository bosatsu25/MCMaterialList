package dev.mcmateriallist.core.shared;

import dev.mcmateriallist.core.work.*;
import java.time.Instant;
import java.util.*;

/** One atomic persistence unit; operation results are never written separately from rows. */
public record ProjectArchive(long generation, ProjectSnapshot snapshot, List<Operation> operations, List<Retired> retired) {
    public record Operation(UUID actor, SharedCommand command, SharedEvents.CommandResult result, Instant recordedAt) {
        public Operation { Objects.requireNonNull(actor); Objects.requireNonNull(command); Objects.requireNonNull(result); Objects.requireNonNull(recordedAt);
            if (!command.scope().equals(result.scope()) || !command.operationId().equals(result.operationId())
                || (result.row() != null && !command.taskId().equals(result.row().taskId())))
                throw new IllegalArgumentException("Inconsistent operation"); }
    }
    public record Retired(long manifestVersion, TaskState row, RegionDescriptor region, Long materialCount) {
        public Retired { Objects.requireNonNull(row);
            if (manifestVersion < 0 || (row.taskId() instanceof MaterialTaskId) != (materialCount != null)
                || (row.taskId() instanceof RegionTaskId) != (region != null) || (materialCount != null && materialCount < 0))
                throw new IllegalArgumentException("Invalid archived row"); }
    }
    public ProjectArchive {
        if (generation < 0) throw new IllegalArgumentException("Invalid generation");
        Objects.requireNonNull(snapshot);
        SharedLimits.count(operations.size(), SharedLimits.OPERATIONS); SharedLimits.count(retired.size(), SharedLimits.RETIRED_ROWS);
        operations = List.copyOf(operations); retired = List.copyOf(retired);
        Set<String> seen = new HashSet<>();
        for (Operation op : operations) if (!op.command().scope().equals(snapshot.scope())
            || op.result().projectRevision() > snapshot.projectRevision()
            || !seen.add(op.actor() + ":" + op.command().operationId())) throw new IllegalArgumentException("Invalid operation archive");
    }
}
