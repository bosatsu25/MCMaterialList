package dev.mcmateriallist.core.stock;

/** Wire order is fixed: append only. Only CHECKED and OBSERVED carry counts; every other status is unknown, never zero. */
public enum LocationStatus {
    /** Read by the server on this scan. */
    CHECKED,
    /** The chunk, or the other half of a double chest, is not loaded. */
    UNLOADED,
    /** Decided before any chunk or block check, so it reveals nothing about the position. */
    UNAUTHORIZED,
    /** No block entity, an ungenerated loot table, or too many distinct items. */
    UNREADABLE,
    /** The block is no longer the registered id. */
    CHANGED,
    /** Folded into an earlier index that reads the same container. */
    DUPLICATE,
    /** Client fallback only. */
    OTHER_DIMENSION,
    /** Client fallback: read from a container the player opened, at a recorded time. */
    OBSERVED;

    public boolean read() { return this == CHECKED || this == OBSERVED; }
}
