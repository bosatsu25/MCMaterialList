package dev.mcmateriallist.core.persistence;

public final class UnsupportedSchemaException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public UnsupportedSchemaException() { super("Unsupported work-state schema or identity rule"); }
}
