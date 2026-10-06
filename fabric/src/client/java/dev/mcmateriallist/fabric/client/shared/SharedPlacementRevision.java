package dev.mcmateriallist.fabric.client.shared;

/** Native placement edits invalidate the provenance of an in-flight complete material count. */
public interface SharedPlacementRevision { long mcmateriallist$sharedRevision(); }
