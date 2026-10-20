package dev.blockagentspaces.graph;

import dev.blockagentspaces.model.GraphEdge;
import dev.blockagentspaces.model.GraphNode;
import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.AgentState;
import dev.blockagentspaces.network.GraphPayloads;
import dev.blockagentspaces.service.WorldState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GraphSnapshotTest {
    @Test void snapshotConnectsGraphToPublishedAgents() {
        WorldState state = new WorldState();
        state.seedExample();
        GraphPayloads.Open graph = GraphSnapshot.from(state, "fabric");
        assertEquals("fabric", graph.selected());
        assertEquals("builder", graph.nodes().stream().filter(n -> n.id().equals("fabric")).findFirst().orElseThrow().agentId());
        assertTrue(graph.edges().stream().anyMatch(e -> e.source().equals("project") && e.target().equals("fabric")));
        assertEquals("goat", graph.agents().getFirst().id());
        assertEquals(3, graph.agents().size());
        assertFalse(graph.truncated());
    }

    @Test void capsGraphAndRetainsSelectedNodeWithoutDanglingEdges() {
        WorldState state = new WorldState();
        for (int i = 0; i < 140; i++) state.putNode(new GraphNode(String.format("n%03d", i), "node", "note", Map.of()));
        state.putNode(new GraphNode("x".repeat(129), "oversized id", "note", Map.of()));
        state.putEdge(new GraphEdge("visible", "n139", "n000", "relates"));
        state.putEdge(new GraphEdge("hidden", "n138", "missing", "relates"));
        GraphPayloads.Open graph = GraphSnapshot.from(state, "n139");
        assertEquals(GraphPayloads.MAX_NODES, graph.nodes().size());
        assertTrue(graph.truncated());
        assertEquals("n139", graph.selected());
        assertTrue(graph.nodes().stream().anyMatch(n -> n.id().equals("n139")));
        assertEquals(1, graph.edges().size());
    }

    @Test void selectedNodeBringsItsConnectedNeighborhoodIntoAFullSnapshot() {
        WorldState state = new WorldState();
        for (int i = 0; i < 140; i++) state.putNode(new GraphNode(String.format("a%03d", i), "unrelated", "note", Map.of()));
        state.putNode(new GraphNode("z-focus", "focus", "ticket", Map.of()));
        state.putNode(new GraphNode("z-linked", "linked", "file", Map.of()));
        state.putEdge(new GraphEdge("focus-link", "z-focus", "z-linked", "touches"));

        GraphPayloads.Open graph = GraphSnapshot.from(state, "z-focus");

        assertEquals("z-focus", graph.selected());
        assertTrue(graph.nodes().stream().anyMatch(n -> n.id().equals("z-linked")));
        assertTrue(graph.edges().stream().anyMatch(e -> e.source().equals("z-focus") && e.target().equals("z-linked")));
    }

    @Test void directoryIncludesGoatBeforeWorkersAndHonorsItsPacketBound() {
        WorldState state = new WorldState();
        for (int i = 0; i < 130; i++) state.putAgent(new Agent(String.format("worker-%03d", i), "Worker", AgentState.IDLE,
            "", "", "", "", "", "", "", List.of(), Instant.EPOCH));
        state.putAgent(new Agent("goat", "Goat", AgentState.WORKING, "triage", "", "", "", "", "", "", List.of(), Instant.EPOCH));

        GraphPayloads.Open graph = GraphSnapshot.from(state, "");

        assertEquals(GraphPayloads.MAX_AGENTS, graph.agents().size());
        assertEquals("goat", graph.agents().getFirst().id());
        assertEquals("triage", graph.agents().getFirst().task());
        assertTrue(graph.truncated());
    }
}
