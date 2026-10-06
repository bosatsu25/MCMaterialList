package dev.mcmateriallist.fabric.client.shared;

/** Published only by a genuine full native recount; direct list replacement clears this proof. */
public interface SharedCountingProvenance {
    void mcmateriallist$beginSharedCount(NativeSharedCapture.CountingStamp stamp);
    NativeSharedCapture.CountingStamp mcmateriallist$sharedCount();
}
