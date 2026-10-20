package dev.blockagentspaces.graph;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.GraphNode;
import dev.blockagentspaces.model.GraphEdge;
import dev.blockagentspaces.network.GraphPayloads;
import dev.blockagentspaces.service.WorldState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds a deterministic bounded snapshot for clients, with no agent execution authority. */
public final class GraphSnapshot {
    private GraphSnapshot() { }
    public static GraphPayloads.Open from(WorldState state, String selected) {
        var allNodes = state.nodes();
        // IDs are protocol identities: never silently truncate them into another node's identity.
        Map<String, GraphNode> valid = allNodes.stream().filter(n -> n.id().length() <= 128)
            .collect(Collectors.toMap(GraphNode::id, n -> n));
        var allEdges = state.edges();
        Map<String, Set<String>> neighbors = new HashMap<>();
        for (GraphEdge edge : allEdges) {
            if (!valid.containsKey(edge.sourceId()) || !valid.containsKey(edge.targetId())) continue;
            neighbors.computeIfAbsent(edge.sourceId(), ignored -> new HashSet<>()).add(edge.targetId());
            neighbors.computeIfAbsent(edge.targetId(), ignored -> new HashSet<>()).add(edge.sourceId());
        }
        Comparator<String> importance = Comparator.<String>comparingInt(id -> neighbors.getOrDefault(id, Set.of()).size())
            .reversed().thenComparing(id -> id);
        List<String> seeds = valid.keySet().stream().sorted(importance).toList();
        List<String> visible = new ArrayList<>(Math.min(valid.size(), GraphPayloads.MAX_NODES));
        Set<String> seen = new HashSet<>();
        if (valid.containsKey(selected)) collectComponent(selected, neighbors, importance, seen, visible);
        for (String seed : seeds) {
            if (visible.size() >= GraphPayloads.MAX_NODES) break;
            collectComponent(seed, neighbors, importance, seen, visible);
        }
        var nodes = visible.stream().map(id -> valid.get(id))
            .map(n -> new GraphPayloads.Node(n.id(), limit(n.label(), 128), limit(n.type(), 64), linkedAgent(state, n))).toList();
        Set<String> ids = nodes.stream().map(GraphPayloads.Node::id).collect(Collectors.toSet());
        var visibleEdges = allEdges.stream().filter(e -> ids.contains(e.sourceId()) && ids.contains(e.targetId()))
            .sorted(Comparator.comparing(dev.blockagentspaces.model.GraphEdge::id)).toList();
        var edges = visibleEdges.stream().limit(GraphPayloads.MAX_EDGES)
            .map(e -> new GraphPayloads.Edge(e.sourceId(), e.targetId(), limit(e.relationship(), 64))).toList();
        return new GraphPayloads.Open(nodes, edges, ids.contains(selected) ? selected : "", limit(state.presentationStatus(), 128),
            allNodes.size() != nodes.size() || visibleEdges.size() != edges.size());
    }
    private static void collectComponent(String seed, Map<String, Set<String>> neighbors, Comparator<String> importance,
                                         Set<String> seen, List<String> visible) {
        if (!seen.add(seed)) return;
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(seed);
        while (!queue.isEmpty() && visible.size() < GraphPayloads.MAX_NODES) {
            String id = queue.removeFirst();
            visible.add(id);
            neighbors.getOrDefault(id, Set.of()).stream().sorted(importance).forEach(next -> {
                if (seen.add(next)) queue.addLast(next);
            });
        }
    }
    private static String linkedAgent(WorldState state, GraphNode node) {
        return state.agents().stream().filter(a -> a.id().length() <= 128 && (a.ticketId().equalsIgnoreCase(node.id())
            || a.taskId().equalsIgnoreCase(node.id()) || a.graphFocus().stream().anyMatch(id -> id.equalsIgnoreCase(node.id()))))
            .sorted(Comparator.comparing(Agent::id)).map(Agent::id).findFirst().orElse("");
    }
    private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
