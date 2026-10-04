package dev.mcmateriallist.fabric.client.work;

/** Successful upstream publication, invalidated whenever placement counting restarts. */
public interface MaterialReadiness {
    boolean mcmateriallist$ready();
    void mcmateriallist$invalidate();
}
