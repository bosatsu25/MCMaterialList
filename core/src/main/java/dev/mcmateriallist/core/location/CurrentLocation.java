package dev.mcmateriallist.core.location;

/** A missing or unreadable selected profile is reported as such, never replaced by another profile. profile is null when nothing is selected. */
public record CurrentLocation(LocationResult<LocationSelection> selection, LocationResult<LocationProfile> profile) {}
