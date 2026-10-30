package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.persistence.StoreStatus;

/** profile is the target profile's storage status, or null when clearing or not evaluated. */
public record SelectionUpdate(LocationResult<LocationSelection> selection, LocationOutcome outcome, StoreStatus profile) {}
