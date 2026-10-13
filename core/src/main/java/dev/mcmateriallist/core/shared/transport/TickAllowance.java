package dev.mcmateriallist.core.shared.transport;

/** Owning tick thread only. Budget includes diagnostics and private-content frames. */
public final class TickAllowance {
    private final int maximum;
    private int used;
    public TickAllowance(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Invalid tick allowance");
        this.maximum = maximum;
    }
    public boolean take() { if (used == maximum) return false; used++; return true; }
    public void reset() { used = 0; }
}
