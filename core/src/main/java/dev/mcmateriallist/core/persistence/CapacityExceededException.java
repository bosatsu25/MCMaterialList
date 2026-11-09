package dev.mcmateriallist.core.persistence;

/**
 * A well-formed value that is larger than an explicit persistence limit (Phase 5A). Stores report it as the bounded refusal
 * {@link StoreStatus#TOO_LARGE} and keep the previous file, instead of CORRUPT. It remains an IllegalArgumentException so
 * existing callers that only distinguish valid from invalid input keep their behaviour. The message never carries user data.
 */
public final class CapacityExceededException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public CapacityExceededException(String limit) { super(limit); }
}
