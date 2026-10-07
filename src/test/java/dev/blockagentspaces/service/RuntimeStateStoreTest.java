package dev.blockagentspaces.service;

import dev.blockagentspaces.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeStateStoreTest {
    @Test void sanitizesPersistedTextAndRestoresACompleteBoundedSnapshot() {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        WorldState.PersistenceSnapshot snapshot = new WorldState.PersistenceSnapshot(
            List.of(new Agent("builder", "Builder", AgentState.WORKING, "ticket-1", "ticket-1", "/Users/example/project", "feature/demo", "reviewing", "pending", "token=top-secret at /private/tmp/file", List.of("graph-1"), now)),
            List.of(new Task("ticket-1", "Build", "Read /Users/example/project", "working", now)),
            List.of(new GraphNode("graph-1", "Project", "project", Map.of("source", "/tmp/private"))),
            List.of(new GraphEdge("edge-1", "graph-1", "ticket-1", "owns")),
            List.of(new AgentMessage("message-1", "builder", "goat", "api_key: hidden-value in /var/tmp", now)),
            List.of(new BridgeEvent(9, "agent.updated", "builder", now)), Map.of("adapter", 9L), 9, true);

        WorldState.PersistenceSnapshot restored = RuntimeStateStore.sanitize(snapshot).toSnapshot();

        assertEquals("", restored.agents().getFirst().workspace());
        assertTrue(restored.agents().getFirst().detail().contains("[redacted]"));
        assertFalse(restored.agents().getFirst().detail().contains("/private"));
        assertFalse(restored.messages().getFirst().body().contains("hidden-value"));
        assertFalse(restored.nodes().getFirst().attributes().get("source").contains("/tmp"));
        assertEquals(9, restored.nextEventSequence());
    }

    @Test void restoredStateKeepsTheSequenceAndShowsRestoredRatherThanLiveData() {
        WorldState original = new WorldState();
        original.seedExample();
        original.markExternalUpdate();
        WorldState restored = new WorldState();

        assertTrue(restored.restore(RuntimeStateStore.sanitize(original.snapshotForPersistence()).toSnapshot()));

        assertEquals(original.agents().size(), restored.agents().size());
        assertEquals("RESTORED LOCAL DATA", restored.presentationStatus());
        assertTrue(restored.latestEventSequence() > original.latestEventSequence());
        assertEquals("presentation.restored", restored.eventsAfter(original.latestEventSequence()).getFirst().type());
    }
}
