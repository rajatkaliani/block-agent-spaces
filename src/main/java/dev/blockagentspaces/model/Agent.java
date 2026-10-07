package dev.blockagentspaces.model;

import java.time.Instant;
import java.util.List;

public record Agent(String id, String displayName, AgentState state, String taskId,
                    String ticketId, String workspace, String branch, String reviewStatus, String acceptanceStatus,
                    String detail, List<String> graphFocus, Instant updatedAt) {
    public Agent {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("agent id is required");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        state = state == null ? AgentState.IDLE : state;
        taskId = taskId == null ? "" : taskId;
        ticketId = ticketId == null ? "" : ticketId;
        workspace = workspace == null ? "" : workspace;
        branch = branch == null ? "" : branch;
        reviewStatus = reviewStatus == null || reviewStatus.isBlank() ? "not_requested" : reviewStatus;
        acceptanceStatus = acceptanceStatus == null || acceptanceStatus.isBlank() ? "pending" : acceptanceStatus;
        detail = detail == null ? "" : detail;
        graphFocus = graphFocus == null ? List.of() : List.copyOf(graphFocus);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
