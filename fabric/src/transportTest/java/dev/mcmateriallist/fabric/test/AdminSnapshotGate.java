package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.shared.protocol.*;
import dev.mcmateriallist.core.shared.transport.TransportBudget;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** At most one genuine invalidation callback and one genuine final frame, never fabricated content. */
public final class AdminSnapshotGate {
    public interface WireControl {
        boolean wireWork(Runnable job);
        TransportBudget inboundBudget();
    }
    private static ProjectScope scope;
    private static UUID transfer;
    private static WireControl wire;
    private static Runnable notification, lastFrame;
    private static boolean notificationArrived, notificationDecoded, decided, notificationFirst;
    private AdminSnapshotGate() {}
    public static synchronized void begin(ProjectScope value) {
        if (scope != null) throw new AssertionError("Admin snapshot test gate occupied");
        scope = value; transfer = null; wire = null; notification = null; lastFrame = null;
        notificationArrived = false; notificationDecoded = false; decided = false; notificationFirst = false;
    }
    public static synchronized boolean frame(WireControl control, SharedTransfers.Fragment fragment, Runnable callback) {
        if (scope == null) return false;
        if (fragment.count() == 1) {
            var message = SharedProtocol.decode(fragment.bytes());
            if (message instanceof SharedMessage.ProjectInvalidated invalidated && invalidated.invalidated().scope().equals(scope))
                notificationArrived = true;
        } else if (!decided) {
            if (fragment.count() <= 4 || fragment.index() != 0) throw new AssertionError("Admin snapshot did not span tick send budget");
            transfer = fragment.transferId(); wire = control; decided = true; notificationFirst = notificationArrived;
        }
        if (notificationFirst && fragment.transferId().equals(transfer) && fragment.index() == fragment.count() - 1) {
            if (lastFrame != null) throw new AssertionError("Duplicate held admin frame");
            lastFrame = callback; return true;
        }
        return false;
    }
    public static synchronized boolean decoded(SharedMessage message, Runnable callback) {
        if (scope == null || !(message instanceof SharedMessage.ProjectInvalidated invalidated)
            || !invalidated.invalidated().scope().equals(scope)) return false;
        notificationDecoded = true;
        if (!notificationArrived || (decided && !notificationFirst)) return false;
        if (notification != null) throw new AssertionError("Duplicate held admin notification");
        notification = callback; return true;
    }
    public static synchronized boolean ready() { return decided && (!notificationFirst || notification != null && lastFrame != null); }
    public static synchronized boolean notificationFirst() { return notificationFirst; }
    public static synchronized boolean notified() { return notificationDecoded; }
    public static CompletableFuture<Long> workerUsage() {
        WireControl control;
        synchronized (AdminSnapshotGate.class) { control = wire; }
        if (control == null) throw new AssertionError("Admin snapshot wire observer missing");
        var result = new CompletableFuture<Long>();
        if (!control.wireWork(() -> result.complete(control.inboundBudget().used())))
            throw new AssertionError("Admin snapshot test worker barrier capacity");
        return result;
    }
    public static void releaseNotification() {
        Runnable value;
        synchronized (AdminSnapshotGate.class) { value = notification; notification = null; }
        if (value == null) throw new AssertionError("Actual admin invalidation not held");
        value.run();
    }
    public static void releaseLastFrame() {
        Runnable value;
        synchronized (AdminSnapshotGate.class) { value = lastFrame; lastFrame = null; }
        if (value == null) throw new AssertionError("Actual admin final frame not held");
        value.run();
    }
    public static synchronized void finish() {
        if (notification != null || lastFrame != null) throw new AssertionError("Admin snapshot test callback retained");
        scope = null; transfer = null; wire = null;
    }
}
