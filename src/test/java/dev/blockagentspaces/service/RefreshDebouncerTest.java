package dev.blockagentspaces.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RefreshDebouncerTest {
    @Test void waitsForAQuietPeriodAndCoalescesBursts() {
        RefreshDebouncer debouncer = new RefreshDebouncer(3);
        debouncer.request();
        assertFalse(debouncer.ready());
        assertFalse(debouncer.ready());
        debouncer.request();
        assertFalse(debouncer.ready());
        assertFalse(debouncer.ready());
        assertFalse(debouncer.ready());
        assertTrue(debouncer.ready());
        assertFalse(debouncer.ready());
    }

    @Test void rejectsANonPositiveQuietPeriod() {
        assertThrows(IllegalArgumentException.class, () -> new RefreshDebouncer(0));
    }
}
