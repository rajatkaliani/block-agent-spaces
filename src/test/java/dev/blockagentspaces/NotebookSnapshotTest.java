package dev.blockagentspaces;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.AgentMessage;
import dev.blockagentspaces.model.AgentState;
import dev.blockagentspaces.service.AgentCommunicationService;
import dev.blockagentspaces.service.WorldState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotebookSnapshotTest {
    @Test void snapshotKeepsLongIdsAndReplyBodiesWithinBridgeLimits() {
        WorldState state = new WorldState();
        String id = "a".repeat(128);
        Agent agent = new Agent(id, "n".repeat(256), AgentState.WORKING, "t".repeat(128), "", "w".repeat(256), "b".repeat(256), "", "", "d".repeat(4000), List.of(), Instant.EPOCH);
        state.putAgent(agent);
        state.addMessage(new AgentMessage("reply", id, AgentCommunicationService.MINECRAFT_PLAYER, "r".repeat(4000), Instant.EPOCH));
        AgentCommunicationService communication = new AgentCommunicationService(state);
        assertTrue(communication.findAgent(id).isPresent());
        var snapshot = AgentInteractionHandler.notebookSnapshot(communication, agent);
        assertEquals(id, snapshot.agentId());
        assertEquals(128, snapshot.name().length());
        assertEquals(128, snapshot.workspace().length());
        assertEquals(512, snapshot.detail().length());
        assertEquals(4000, snapshot.conversation().getFirst().body().length());
        assertFalse(snapshot.conversation().getFirst().outgoing());
    }
}
