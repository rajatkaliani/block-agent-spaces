package dev.blockagentspaces.model;

import java.time.Instant;

public record AgentMessage(String id, String from, String to, String body, Instant createdAt) {
    public AgentMessage {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("message id is required");
        from = from == null ? "orchestrator" : from;
        to = to == null ? "*" : to;
        body = body == null ? "" : body;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
