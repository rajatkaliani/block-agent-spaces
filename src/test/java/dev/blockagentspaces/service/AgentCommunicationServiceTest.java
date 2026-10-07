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
        state.putAgent(new Agent("builder", "Builder", AgentState.WORKING, "task-1", "ticket-1", "workspace", "ticket-1/build", "reviewing", "pending", "Building", List.of("project"), Instant.now()));
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
        state.putAgent(new Agent("builder", "Builder", AgentState.IDLE, "", "", "", "", "not_requested", "pending", "", List.of(), Instant.now()));
        assertFalse(new AgentCommunicationService(state).sendFromMinecraft("builder", "x".repeat(401)).sent());
    }

    @Test void notebookThreadKeepsPlayerMessagesOutgoingAndFiltersOtherAgents() {
        WorldState state = new WorldState();
        state.putAgent(new Agent("goat", "Goat", AgentState.IDLE, "", "", "", "", "", "", "", List.of(), Instant.now()));
        state.putAgent(new Agent("builder", "Builder", AgentState.IDLE, "", "", "", "", "", "", "", List.of(), Instant.now()));
        state.addMessage(new dev.blockagentspaces.model.AgentMessage("player-goat", "minecraft-player", "goat", "Please triage this.", Instant.ofEpochSecond(1)));
        state.addMessage(new dev.blockagentspaces.model.AgentMessage("goat-player", "goat", "minecraft-player", "I recorded it for triage.", Instant.ofEpochSecond(2)));
        state.addMessage(new dev.blockagentspaces.model.AgentMessage("builder-player", "builder", "minecraft-player", "Unrelated developer update.", Instant.ofEpochSecond(3)));
        state.addMessage(new dev.blockagentspaces.model.AgentMessage("goat-builder", "goat", "builder", "Private delegation.", Instant.ofEpochSecond(4)));

        var thread = new AgentCommunicationService(state).conversationFor("goat");

        assertEquals(2, thread.size());
        assertEquals(AgentCommunicationService.Direction.OUTGOING, thread.getFirst().direction());
        assertEquals("Please triage this.", thread.getFirst().message().body());
        assertEquals(AgentCommunicationService.Direction.INCOMING, thread.get(1).direction());
        assertEquals("I recorded it for triage.", thread.get(1).message().body());
    }
}
