package dev.blockagentspaces.model;

import java.util.Map;

public record GraphNode(String id, String label, String type, Map<String, String> attributes) {
    public GraphNode {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("node id is required");
        label = label == null || label.isBlank() ? id : label;
        type = type == null || type.isBlank() ? "note" : type;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
