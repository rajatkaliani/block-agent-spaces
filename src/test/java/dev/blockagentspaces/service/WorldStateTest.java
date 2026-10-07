package dev.blockagentspaces.service;

import dev.blockagentspaces.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorldStateTest {
    @Test void capturesAnAgentUpdateAndEvent() {
        WorldState state = new WorldState();
        state.putAgent(new Agent("a1", "Builder", AgentState.WORKING, "t1", "Writing code", List.of("n1"), Instant.now()));
        assertEquals(1, state.agents().size());
        assertTrue(state.events().getFirst().contains("agent.updated"));
    }
    @Test void exampleWorkspaceConnectsAgentAndGraph() {
        WorldState state = new WorldState();
        state.seedExample();
        assertFalse(state.agents().isEmpty());
        assertFalse(state.nodes().isEmpty());
        assertFalse(state.edges().isEmpty());
    }
}
