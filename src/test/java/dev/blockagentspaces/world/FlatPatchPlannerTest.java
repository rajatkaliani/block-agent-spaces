package dev.blockagentspaces.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FlatPatchPlannerTest {
    @Test void plansAnInstallationCenteredOnAFlatClearPatch() {
        TestSurface surface = new TestSurface(72);

        FlatPatchPlanner.Result result = FlatPatchPlanner.plan(surface, 100, -40);

        assertTrue(result.isSuitable());
        assertEquals(85, result.site().minX());
        assertEquals(73, result.site().buildY());
        assertEquals(-45, result.site().minZ());
    }

    @Test void explainsWhenThePatchIsNotLevel() {
        TestSurface surface = new TestSurface(72);
        surface.heights.put(new Column(104, -38), 73);

        FlatPatchPlanner.Result result = FlatPatchPlanner.plan(surface, 100, -40);

        assertFalse(result.isSuitable());
        assertTrue(result.problem().contains("not level"));
        assertTrue(result.problem().contains("relative +4, +2"));
    }

    @Test void explainsUnsafeGroundAndBlockedClearance() {
        TestSurface unsafe = new TestSurface(72);
        unsafe.unsafe.add(new Column(99, -40));
        assertTrue(FlatPatchPlanner.plan(unsafe, 100, -40).problem().contains("not solid and safe"));

        TestSurface blocked = new TestSurface(72);
        blocked.blocked.add(new Block(101, 75, -39));
        FlatPatchPlanner.Result result = FlatPatchPlanner.plan(blocked, 100, -40);
        assertTrue(result.problem().contains("build volume is blocked"));
        assertTrue(result.problem().contains("relative +1, +1"));
    }

    private static final class TestSurface implements FlatPatchPlanner.Surface {
        private final int defaultGroundY;
        private final java.util.Map<Column, Integer> heights = new java.util.HashMap<>();
        private final Set<Column> unsafe = new HashSet<>();
        private final Set<Block> blocked = new HashSet<>();

        private TestSurface(int defaultGroundY) { this.defaultGroundY = defaultGroundY; }
        @Override public int groundY(int x, int z) { return heights.getOrDefault(new Column(x, z), defaultGroundY); }
        @Override public boolean hasSafeGround(int x, int y, int z) { return !unsafe.contains(new Column(x, z)); }
        @Override public boolean isClear(int x, int y, int z) { return !blocked.contains(new Block(x, y, z)); }
    }

    private record Column(int x, int z) { }
    private record Block(int x, int y, int z) { }
}
