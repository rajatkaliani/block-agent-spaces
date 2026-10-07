package dev.blockagentspaces.graph;

import dev.blockagentspaces.model.GraphNode;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** A stable six-slot observatory layout independent of map iteration order. */
public final class GraphLayout {
    public static final int MAX_VISIBLE_NODES = 6;

    private GraphLayout() { }

    public static List<PlacedNode> arrange(Collection<GraphNode> graphNodes) {
        return graphNodes.stream().filter(node -> node != null)
            .sorted(Comparator.comparing(GraphNode::id))
            .limit(MAX_VISIBLE_NODES)
            .mapMulti(new java.util.function.BiConsumer<GraphNode, java.util.function.Consumer<PlacedNode>>() {
                private int index;
                @Override public void accept(GraphNode node, java.util.function.Consumer<PlacedNode> result) {
                    int position = index++;
                    result.accept(new PlacedNode(node, 19 + (position % 3) * 4, 2 + (position / 3) * 2, 2 + (position % 2) * 4));
                }
            }).toList();
    }

    public record PlacedNode(GraphNode node, int xOffset, int yOffset, int zOffset) { }
}
