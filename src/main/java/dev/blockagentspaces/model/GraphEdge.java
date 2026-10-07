package dev.blockagentspaces.model;

public record GraphEdge(String id, String sourceId, String targetId, String relationship) {
    public GraphEdge {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("edge id is required");
        if (sourceId == null || sourceId.isBlank() || targetId == null || targetId.isBlank())
            throw new IllegalArgumentException("edge endpoints are required");
        relationship = relationship == null || relationship.isBlank() ? "relates_to" : relationship;
    }
}
