package dev.blockagentspaces.service;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.AgentState;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentCommunicationServiceTest {
    @Test void resolvesAnAgentByDisplayNameAndQueuesMinecraftMessage() {
        WorldState state = new WorldState();
        state.putAgent(new Agent("builder", "Builder", AgentState.WORKING, "task-1", "Building", List.of("project"), Instant.now()));
        AgentCommunicationService service = new AgentCommunicationService(state);
        var result = service.sendFromMinecraft("Builder", "Please share progress.");
        assertTrue(result.sent());
        assertEquals("builder", result.message().to());
        assertEquals("minecraft-player", result.message().from());
        assertEquals(1, state.messages().size());
    }
    @Test void rejectsUnknownAgentAndOversizedMessage() {
        AgentCommunicationService service = new AgentCommunicationService(new WorldState());
        assertFalse(service.sendFromMinecraft("missing", "hello").sent());
        WorldState state = new WorldState();
        state.putAgent(new Agent("builder", "Builder", AgentState.IDLE, "", "", List.of(), Instant.now()));
        assertFalse(new AgentCommunicationService(state).sendFromMinecraft("builder", "x".repeat(401)).sent());
    }
}
