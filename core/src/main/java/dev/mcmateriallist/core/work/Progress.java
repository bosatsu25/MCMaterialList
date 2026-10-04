package dev.mcmateriallist.core.work;

import java.util.Collection;

public record Progress(long completed, long total, int percentage) {
    public static Progress calculate(Collection<TaskState> tracked) {
        long total = tracked.size(); long completed = tracked.stream().filter(TaskState::done).count();
        return new Progress(completed, total, total == 0 ? 0 : (int) (100L * completed / total));
    }
}
