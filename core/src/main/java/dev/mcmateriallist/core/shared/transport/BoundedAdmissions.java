package dev.mcmateriallist.core.shared.transport;

import java.util.Objects;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/** Bounded asynchronous admissions, rejected when their owning connection closes. */
public final class BoundedAdmissions<T> {
    private final ArrayBlockingQueue<T> queue;
    private final Consumer<T> reject;
    private volatile boolean open = true;
    public BoundedAdmissions(int capacity, Consumer<T> reject) {
        queue = new ArrayBlockingQueue<>(capacity); this.reject = Objects.requireNonNull(reject);
    }
    public boolean open() { return open; }
    public boolean offer(T admission) {
        synchronized (this) {
            if (open && queue.offer(admission)) return true;
        }
        reject.accept(admission); return false;
    }
    public T poll() { return queue.poll(); }
    public void close() {
        var rejected = new ArrayList<T>();
        synchronized (this) {
            open = false;
            queue.drainTo(rejected);
        }
        // Completion callbacks run outside the admission monitor.
        rejected.forEach(reject);
    }
}
