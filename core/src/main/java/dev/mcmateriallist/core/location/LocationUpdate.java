package dev.mcmateriallist.core.location;

/** CHANGED is reported only after a successful save; a null outcome means storage failed first. */
public record LocationUpdate(LocationResult<LocationProfile> storage, LocationOutcome outcome) {}
