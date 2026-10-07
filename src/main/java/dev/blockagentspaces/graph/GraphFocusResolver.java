package dev.blockagentspaces.graph;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.GraphEdge;
import dev.blockagentspaces.model.GraphNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces the small, explainable part of the graph associated with one agent.
 *
 * <p>Published {@code graphFocus} IDs win. Ticket and task IDs are deterministic
 * fallbacks, and only one sorted hop is included. This deliberately does not try
 * to infer links from labels or arbitrary metadata.</p>
 */
public final class GraphFocusResolver {
    public static final int MAX_NODES = 12;
    public static final int MAX_EDGES = 16;

    private GraphFocusResolver() { }

    public static Focus resolve(Agent agent, Collection<GraphNode> graphNodes, Collection<GraphEdge> graphEdges) {
        if (agent == null) return Focus.empty();

        Map<String, GraphNode> nodesById = graphNodes.stream()
            .filter(node -> node != null)
            .sorted(Comparator.comparing(GraphNode::id))
            .collect(java.util.stream.Collectors.toMap(GraphNode::id, node -> node, (first, ignored) -> first, LinkedHashMap::new));
        if (nodesById.isEmpty()) return Focus.empty();

        LinkedHashSet<String> seeds = new LinkedHashSet<>();
        for (String id : agent.graphFocus()) addKnown(seeds, id, nodesById);
        addKnown(seeds, agent.ticketId(), nodesById);
        addKnown(seeds, agent.taskId(), nodesById);
        trim(seeds, MAX_NODES);
        if (seeds.isEmpty()) return Focus.empty();

        // Only the initially selected nodes expand. That keeps this exactly one hop.
        Set<String> initialNodes = Set.copyOf(seeds);
        List<GraphEdge> sortedEdges = graphEdges.stream().filter(edge -> edge != null)
            .sorted(Comparator.comparing(GraphEdge::id)).toList();
        LinkedHashSet<String> edgeIds = new LinkedHashSet<>();
        for (GraphEdge edge : sortedEdges) {
            if (edgeIds.size() >= MAX_EDGES) break;
            String neighbour = neighbourOf(edge, initialNodes);
            if (neighbour == null) continue;
            edgeIds.add(edge.id());
            addKnown(seeds, neighbour, nodesById);
        }

        return new Focus(Set.copyOf(seeds), Set.copyOf(edgeIds));
    }

    private static String neighbourOf(GraphEdge edge, Set<String> seeds) {
        boolean sourceFocused = seeds.contains(edge.sourceId());
        boolean targetFocused = seeds.contains(edge.targetId());
        if (!sourceFocused && !targetFocused) return null;
        return sourceFocused ? edge.targetId() : edge.sourceId();
    }

    private static void addKnown(LinkedHashSet<String> target, String id, Map<String, GraphNode> knownNodes) {
        if (target.size() < MAX_NODES && id != null && knownNodes.containsKey(id)) target.add(id);
    }

    private static void trim(LinkedHashSet<String> values, int max) {
        while (values.size() > max) values.removeLast();
    }

    public record Focus(Set<String> nodeIds, Set<String> edgeIds) {
        public Focus {
            nodeIds = Set.copyOf(nodeIds);
            edgeIds = Set.copyOf(edgeIds);
        }
        public static Focus empty() { return new Focus(Set.of(), Set.of()); }
        public boolean hasNodes() { return !nodeIds.isEmpty(); }
        public boolean includesNode(String id) { return nodeIds.contains(id); }
        public boolean includesEdge(String id) { return edgeIds.contains(id); }
    }
}
