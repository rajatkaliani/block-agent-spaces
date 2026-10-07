package dev.blockagentspaces.graph;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.AgentState;
import dev.blockagentspaces.model.GraphEdge;
import dev.blockagentspaces.model.GraphNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphFocusResolverTest {
    @Test void publishedFocusWinsThenAddsOnlyOneSortedHop() {
        Agent agent = agent("missing-ticket", "ticket-1", List.of("project", "task"));
        List<GraphNode> nodes = List.of(node("project"), node("task"), node("file-b"), node("file-a"), node("deep"));
        List<GraphEdge> edges = List.of(
            new GraphEdge("z-deep", "file-a", "deep", "contains"),
            new GraphEdge("b-project", "project", "file-b", "uses"),
            new GraphEdge("a-task", "task", "file-a", "owns")
        );

        GraphFocusResolver.Focus focus = GraphFocusResolver.resolve(agent, nodes, edges);

        assertEquals(List.of("file-a", "file-b", "project", "task"), focus.nodeIds().stream().sorted().toList());
        assertEquals(List.of("a-task", "b-project"), focus.edgeIds().stream().sorted().toList());
        assertTrue(!focus.includesNode("deep"));
    }

    @Test void ticketAndTaskIdsProvideFallbackWhenNoPublishedNodesExist() {
        GraphFocusResolver.Focus focus = GraphFocusResolver.resolve(agent("task-7", "ticket-9", List.of("unknown")), List.of(node("ticket-9"), node("task-7")), List.of());

        assertEquals(List.of("task-7", "ticket-9"), focus.nodeIds().stream().sorted().toList());
    }

    @Test void layoutUsesNodeIdsRatherThanInputOrMapOrder() {
        List<GraphLayout.PlacedNode> layout = GraphLayout.arrange(List.of(node("z"), node("b"), node("a"), node("c")));

        assertEquals(List.of("a", "b", "c", "z"), layout.stream().map(placed -> placed.node().id()).toList());
        assertEquals(new GraphLayout.PlacedNode(node("a"), 24, 3, 4), layout.getFirst());
    }

    @Test void layoutMakesFocusedNodesVisibleBeforeUnrelatedNodes() {
        List<GraphNode> nodes = List.of(node("a"), node("b"), node("c"), node("d"), node("e"), node("f"), node("focused"));

        List<GraphLayout.PlacedNode> layout = GraphLayout.arrange(nodes, java.util.Set.of("focused"));

        assertEquals("focused", layout.getFirst().node().id());
        assertEquals(7, layout.size());
    }

    @Test void layoutPlacesTheMostConnectedUnfocusedNodeAtTheCentre() {
        List<GraphNode> nodes = List.of(node("quiet"), node("hub"), node("leaf-a"), node("leaf-b"));
        List<GraphEdge> edges = List.of(
            new GraphEdge("a", "hub", "leaf-a", "references"),
            new GraphEdge("b", "hub", "leaf-b", "references")
        );

        List<GraphLayout.PlacedNode> layout = GraphLayout.arrange(nodes, edges, java.util.Set.of());

        assertEquals("hub", layout.getFirst().node().id());
        assertEquals(24, layout.getFirst().xOffset());
        assertEquals(3, layout.getFirst().yOffset());
        assertEquals(4, layout.getFirst().zOffset());
    }

    private static Agent agent(String taskId, String ticketId, List<String> graphFocus) {
        return new Agent("builder", "Builder", AgentState.WORKING, taskId, ticketId, "workspace", "branch", "", "", "", graphFocus, Instant.EPOCH);
    }

    private static GraphNode node(String id) { return new GraphNode(id, id, "note", Map.of()); }
}
