package dev.blockagentspaces.world;

/** Pure ownership rule shared by first builds and later reconciliations. */
public final class ManagedBlockOwnership {
    private ManagedBlockOwnership() { }

    /** A known managed block may change only when it is exactly as last recorded; air is also a player edit. */
    public static boolean mayReplace(boolean wasManaged, boolean matchesRecordedState, boolean isAir) {
        return wasManaged ? matchesRecordedState : isAir;
    }
}
