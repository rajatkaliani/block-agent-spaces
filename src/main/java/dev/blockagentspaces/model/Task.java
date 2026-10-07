package dev.blockagentspaces.model;

import java.time.Instant;

public record Task(String id, String title, String description, String status, Instant updatedAt) {
    public Task {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("task id is required");
        title = title == null || title.isBlank() ? id : title;
        description = description == null ? "" : description;
        status = status == null || status.isBlank() ? "open" : status;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
