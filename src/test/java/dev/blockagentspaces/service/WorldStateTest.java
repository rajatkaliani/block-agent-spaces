package dev.blockagentspaces.service;

import dev.blockagentspaces.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorldStateTest {
    @Test void capturesAnAgentUpdateAndEvent() {
        WorldState state = new WorldState();
        state.putAgent(new Agent("a1", "Builder", AgentState.WORKING, "t1", "t1", "workspace", "ticket/t1", "reviewing", "pending", "Writing code", List.of("n1"), Instant.now()));
        assertEquals(1, state.agents().size());
        assertEquals("agent.updated", state.eventsAfter(0).getFirst().type());
    }
    @Test void emitsMonotonicallyOrderedEventsAndTracksAcknowledgements() {
        WorldState state = new WorldState();
        state.putTask(new Task("t1", "First", "", "open", Instant.now()));
        state.putTask(new Task("t2", "Second", "", "open", Instant.now()));
        var events = state.eventsAfter(0);
        assertEquals(1L, events.getFirst().sequence());
        assertEquals(2L, events.getLast().sequence());
        state.acknowledge("demo", 2);
        assertEquals(2L, state.acknowledgementFor("demo"));
        assertTrue(state.eventsAfter(2).isEmpty());
    }
    @Test void exampleWorkspaceConnectsAgentAndGraph() {
        WorldState state = new WorldState();
        state.seedExample();
        assertFalse(state.agents().isEmpty());
        assertFalse(state.nodes().isEmpty());
        assertFalse(state.edges().isEmpty());
    }
    @Test void publishesPresentationChangesForServerThreadReconciliation() {
        WorldState state = new WorldState();
        AtomicReference<String> eventType = new AtomicReference<>();
        state.addListener(event -> eventType.set(event.type()));
        state.setBridgeAvailable(true);
        assertEquals("presentation.updated", eventType.get());
        assertEquals("DEMO DATA • BRIDGE READY", state.presentationStatus());
        state.markExternalUpdate();
        assertEquals("LIVE LOCAL UPDATES", state.presentationStatus());
    }
}
