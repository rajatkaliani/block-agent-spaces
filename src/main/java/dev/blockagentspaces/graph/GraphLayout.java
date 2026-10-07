package dev.blockagentspaces.graph;

import dev.blockagentspaces.model.GraphNode;
import dev.blockagentspaces.model.GraphEdge;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * A compact, deterministic spatial layout for the observatory hologram.
 *
 * <p>This is deliberately a graph layout rather than a grid of blocks. The most connected
 * (or currently focused) node occupies the centre and the rest orbit it in a stable order.
 * The small fixed canvas keeps labels readable in a Minecraft room and means a bridge refresh
 * never makes the graph jump around just because a map happened to iterate differently.</p>
 */
public final class GraphLayout {
    public static final int MAX_VISIBLE_NODES = 9;
    private static final List<Position> ORBIT = List.of(
        new Position(24, 3, 4), // centre / highest priority
        new Position(20, 3, 2), new Position(24, 4, 1), new Position(28, 3, 2),
        new Position(29, 2, 4), new Position(28, 4, 6), new Position(24, 2, 7),
        new Position(20, 4, 6), new Position(19, 2, 4)
    );

    private GraphLayout() { }

    public static List<PlacedNode> arrange(Collection<GraphNode> graphNodes) {
        return arrange(graphNodes, List.of(), Set.of());
    }

    /** Focused nodes get the first stable slots so selecting an agent always has a visible effect. */
    public static List<PlacedNode> arrange(Collection<GraphNode> graphNodes, Set<String> focusedNodeIds) {
        return arrange(graphNodes, List.of(), focusedNodeIds);
    }

    /**
     * Uses in-room degree as a gentle layout hint. It is intentionally deterministic: ties are
     * broken by node id, and relationships outside the visible set cannot reorder the world.
     */
    public static List<PlacedNode> arrange(Collection<GraphNode> graphNodes, Collection<GraphEdge> graphEdges, Set<String> focusedNodeIds) {
        HashMap<String, Integer> degree = new HashMap<>();
        if (graphEdges != null) for (GraphEdge edge : graphEdges) {
            if (edge == null) continue;
            degree.merge(edge.sourceId(), 1, Integer::sum);
            degree.merge(edge.targetId(), 1, Integer::sum);
        }
        return graphNodes.stream().filter(node -> node != null)
            .sorted(Comparator.comparing((GraphNode node) -> !focusedNodeIds.contains(node.id()))
                .thenComparing((GraphNode node) -> degree.getOrDefault(node.id(), 0), Comparator.reverseOrder())
                .thenComparing(GraphNode::id))
            .limit(MAX_VISIBLE_NODES)
            .mapMulti(new java.util.function.BiConsumer<GraphNode, java.util.function.Consumer<PlacedNode>>() {
                private int index;
                @Override public void accept(GraphNode node, java.util.function.Consumer<PlacedNode> result) {
                    Position position = ORBIT.get(index++);
                    result.accept(new PlacedNode(node, position.x(), position.y(), position.z()));
                }
            }).toList();
    }

    public record PlacedNode(GraphNode node, int xOffset, int yOffset, int zOffset) { }
    private record Position(int x, int y, int z) { }
}
