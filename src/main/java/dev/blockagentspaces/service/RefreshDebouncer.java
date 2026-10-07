package dev.blockagentspaces.service;

import java.util.concurrent.atomic.AtomicLong;

/** Coalesces cross-thread bridge events before a server-thread world reconciliation. */
public final class RefreshDebouncer {
    private final int quietTicks;
    private final AtomicLong requestedRevision = new AtomicLong();
    private long observedRevision;
    private long deliveredRevision;
    private int stableTicks;

    public RefreshDebouncer(int quietTicks) {
        if (quietTicks < 1) throw new IllegalArgumentException("quietTicks must be positive");
        this.quietTicks = quietTicks;
    }

    /** Safe to call from the HTTP bridge's virtual-thread executor. */
    public void request() { requestedRevision.incrementAndGet(); }

    /** Call only from the server tick. Returns true once an update burst has gone quiet. */
    public boolean ready() {
        long requested = requestedRevision.get();
        if (requested == deliveredRevision) return false;
        if (requested != observedRevision) {
            observedRevision = requested;
            stableTicks = 0;
            return false;
        }
        if (++stableTicks < quietTicks) return false;
        deliveredRevision = observedRevision;
        return true;
    }
}
