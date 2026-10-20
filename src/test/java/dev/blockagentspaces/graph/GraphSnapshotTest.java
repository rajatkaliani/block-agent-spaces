package dev.blockagentspaces.graph;

import dev.blockagentspaces.model.GraphEdge;
import dev.blockagentspaces.model.GraphNode;
import dev.blockagentspaces.network.GraphPayloads;
import dev.blockagentspaces.service.WorldState;
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
        assertFalse(graph.truncated());
    }

    @Test void capsGraphAndRetainsSelectedNodeWithoutDanglingEdges() {
        WorldState state = new WorldState();
        for (int i = 0; i < 140; i++) state.putNode(new GraphNode(String.format("n%03d", i), "node", "note", Map.of()));
        state.putNode(new GraphNode("x".repeat(65), "oversized id", "note", Map.of()));
        state.putEdge(new GraphEdge("visible", "n139", "n000", "relates"));
        state.putEdge(new GraphEdge("hidden", "n138", "missing", "relates"));
        GraphPayloads.Open graph = GraphSnapshot.from(state, "n139");
        assertEquals(GraphPayloads.MAX_NODES, graph.nodes().size());
        assertTrue(graph.truncated());
        assertEquals("n139", graph.selected());
        assertTrue(graph.nodes().stream().anyMatch(n -> n.id().equals("n139")));
        assertEquals(1, graph.edges().size());
    }
}
