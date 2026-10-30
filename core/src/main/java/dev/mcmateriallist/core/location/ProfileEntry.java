package dev.mcmateriallist.core.location;

import dev.mcmateriallist.core.persistence.StoreStatus;

/** Unreadable profiles are listed with their status instead of being hidden. */
public record ProfileEntry(LocationProfileId id, StoreStatus status, LocationProfile profile) {}
