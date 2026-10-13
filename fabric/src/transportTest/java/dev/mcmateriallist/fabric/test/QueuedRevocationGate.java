package dev.mcmateriallist.fabric.test;

import dev.mcmateriallist.core.shared.ProjectScope;
import dev.mcmateriallist.core.shared.transport.BoundedOutgoing;

/** Test-only pause after a real first server fragment, before future queue polls. */
public final class QueuedRevocationGate {
    private static BoundedOutgoing target;
    private static ProjectScope scope;
    private static boolean first, held, revoked;
    private static int afterRevocation;
    private QueuedRevocationGate() {}
    public static synchronized void begin(BoundedOutgoing outgoing, ProjectScope selected) {
        if (target != null) throw new AssertionError("Revocation queue barrier occupied");
        target = outgoing; scope = selected; first = held = revoked = false; afterRevocation = 0;
    }
    public static synchronized boolean block(BoundedOutgoing outgoing) {
        return outgoing == target && first && held;
    }
    public static synchronized void observed(BoundedOutgoing outgoing, BoundedOutgoing.Frame frame) {
        if (outgoing != target || frame == null || !scope.equals(frame.scope())) return;
        if (revoked) afterRevocation++;
        if (!first) {
            if (frame.fragment().index() != 0 || frame.fragment().count() < 2)
                throw new AssertionError("Revocation fixture did not queue multiple real private fragments");
            first = held = true;
        }
    }
    public static synchronized boolean held() { return first && held; }
    public static synchronized void releaseAfterRevocation() { revoked = true; held = false; }
    public static synchronized void finish() {
        if (!first || !revoked || afterRevocation != 0) throw new AssertionError("Private fragment sent after membership revocation");
        target = null; scope = null; first = held = revoked = false;
    }
}
