package dev.mcmateriallist.fabric.test.mixin;

import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.ITask;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TaskScheduler.class, remap = false)
public interface SchedulerTestAccess {
    @Accessor("tasksToAdd") List<ITask> phase1Queued();
}
