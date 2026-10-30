package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.persistence.StoreStatus;

/** A RECOVERY_REQUIRED candidate is never writable until explicit recover(). */
public record LocationResult<T>(StoreStatus status, T value) {
    public static <T> LocationResult<T> failure(StoreStatus status) { return new LocationResult<>(status, null); }
    public boolean ok() { return status == StoreStatus.OK; }
}
