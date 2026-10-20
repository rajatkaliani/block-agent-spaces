package dev.blockagentspaces.graph;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.GraphNode;
import dev.blockagentspaces.network.GraphPayloads;
import dev.blockagentspaces.service.WorldState;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds a deterministic bounded snapshot for clients, with no agent execution authority. */
public final class GraphSnapshot {
    private GraphSnapshot() { }
    public static GraphPayloads.Open from(WorldState state, String selected) {
        var allNodes = state.nodes().stream().sorted(Comparator.comparing((GraphNode n) -> !n.id().equals(selected)).thenComparing(GraphNode::id)).toList();
        // IDs are protocol identities: never silently truncate them into another node's identity.
        var nodes = allNodes.stream().filter(n -> n.id().length() <= 64)
            .limit(GraphPayloads.MAX_NODES).map(n -> new GraphPayloads.Node(n.id(), limit(n.label(), 128), limit(n.type(), 64), linkedAgent(state, n))) .toList();
        Set<String> ids = nodes.stream().map(GraphPayloads.Node::id).collect(Collectors.toSet());
        var allEdges = state.edges().stream().filter(e -> ids.contains(e.sourceId()) && ids.contains(e.targetId()))
            .sorted(Comparator.comparing(dev.blockagentspaces.model.GraphEdge::id)).toList();
        var edges = allEdges.stream().limit(GraphPayloads.MAX_EDGES)
            .map(e -> new GraphPayloads.Edge(e.sourceId(), e.targetId(), limit(e.relationship(), 64))).toList();
        return new GraphPayloads.Open(nodes, edges, ids.contains(selected) ? selected : "", limit(state.presentationStatus(), 128),
            allNodes.size() != nodes.size() || allEdges.size() != edges.size());
    }
    private static String linkedAgent(WorldState state, GraphNode node) {
        return state.agents().stream().filter(a -> a.id().length() <= 64 && (a.ticketId().equalsIgnoreCase(node.id())
            || a.taskId().equalsIgnoreCase(node.id()) || a.graphFocus().stream().anyMatch(id -> id.equalsIgnoreCase(node.id()))))
            .sorted(Comparator.comparing(Agent::id)).map(Agent::id).findFirst().orElse("");
    }
    private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
