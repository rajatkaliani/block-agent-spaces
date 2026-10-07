package dev.blockagentspaces.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManagedBlockOwnershipTest {
    @Test void onlyBuildsIntoAirForANewFootprint() {
        assertTrue(ManagedBlockOwnership.mayReplace(false, false, true));
        assertFalse(ManagedBlockOwnership.mayReplace(false, false, false));
    }

    @Test void protectsAnyManagedBlockChangeIncludingAir() {
        assertTrue(ManagedBlockOwnership.mayReplace(true, true, false));
        assertFalse(ManagedBlockOwnership.mayReplace(true, false, false));
        assertFalse(ManagedBlockOwnership.mayReplace(true, false, true));
    }
}
