package dev.blockagentspaces.service;

import dev.blockagentspaces.model.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Thread-safe source of truth for the bridge and Minecraft presentation layer. */
public final class WorldState {
    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, GraphNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, GraphEdge> edges = new ConcurrentHashMap<>();
    private final List<AgentMessage> messages = new CopyOnWriteArrayList<>();
    private final List<BridgeEvent> events = new CopyOnWriteArrayList<>();
    private final Map<String, Long> acknowledgements = new ConcurrentHashMap<>();
    private final AtomicLong nextEventSequence = new AtomicLong();
    private final List<Consumer<BridgeEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean bridgeAvailable;
    private volatile boolean externalUpdates;

    public void putAgent(Agent agent) { agents.put(agent.id(), agent); event("agent.updated", agent.id()); }
    public void putTask(Task task) { tasks.put(task.id(), task); event("task.updated", task.id()); }
    public void putNode(GraphNode node) { nodes.put(node.id(), node); event("graph.node.updated", node.id()); }
    public void putEdge(GraphEdge edge) { edges.put(edge.id(), edge); event("graph.edge.updated", edge.id()); }
    public void addMessage(AgentMessage message) { messages.add(message); event("message.created", message.id()); }

    public Collection<Agent> agents() { return List.copyOf(agents.values()); }
    public Collection<Task> tasks() { return List.copyOf(tasks.values()); }
    public Collection<GraphNode> nodes() { return List.copyOf(nodes.values()); }
    public Collection<GraphEdge> edges() { return List.copyOf(edges.values()); }
    public List<AgentMessage> messages() { return List.copyOf(messages); }
    public List<BridgeEvent> eventsAfter(long cursor) { return events.stream().filter(event -> event.sequence() > cursor).toList(); }
    public long latestEventSequence() { return nextEventSequence.get(); }
    public void acknowledge(String consumer, long cursor) {
        if (consumer == null || consumer.isBlank() || consumer.length() > 64) throw new IllegalArgumentException("consumer is required and must be at most 64 characters");
        if (cursor < 0 || cursor > latestEventSequence()) throw new IllegalArgumentException("cursor is outside the available event range");
        acknowledgements.merge(consumer.trim(), cursor, Math::max);
    }
    public long acknowledgementFor(String consumer) { return acknowledgements.getOrDefault(consumer, 0L); }
    public void addListener(Consumer<BridgeEvent> listener) { listeners.add(Objects.requireNonNull(listener)); }
    public boolean hasExternalUpdates() { return externalUpdates; }
    public boolean bridgeAvailable() { return bridgeAvailable; }
    public String presentationStatus() {
        if (externalUpdates) return "LIVE LOCAL UPDATES";
        return bridgeAvailable ? "DEMO DATA • BRIDGE READY" : "DEMO DATA • BRIDGE OFFLINE";
    }
    public void setBridgeAvailable(boolean available) {
        if (bridgeAvailable == available) return;
        bridgeAvailable = available;
        event("presentation.updated", "bridge");
    }
    public void markExternalUpdate() {
        if (externalUpdates) return;
        externalUpdates = true;
        event("presentation.updated", "local-bridge");
    }

    public void seedExample() {
        putTask(new Task("ticket-101", "Scaffold Fabric mod", "Create the first runnable mod", "in_progress", Instant.now()));
        putTask(new Task("ticket-102", "Review scaffold", "Review and decide whether to accept the proposed change", "in_review", Instant.now()));
        putNode(new GraphNode("project", "Block Agent Spaces", "project", Map.of()));
        putNode(new GraphNode("canonical-history", "Canonical history", "history", Map.of()));
        putNode(new GraphNode("fabric", "Fabric mod", "file", Map.of()));
        putNode(new GraphNode("ticket-101", "Ticket 101: Scaffold", "ticket", Map.of()));
        putNode(new GraphNode("workspace-builder", "Builder workspace", "workspace", Map.of()));
        putNode(new GraphNode("review-ticket-101", "Goat review", "review", Map.of()));
        putEdge(new GraphEdge("project-uses-fabric", "project", "fabric", "uses"));
        putEdge(new GraphEdge("ticket-workspace", "ticket-101", "workspace-builder", "assigned_workspace"));
        putEdge(new GraphEdge("ticket-review", "ticket-101", "review-ticket-101", "reviewed_by"));
        putEdge(new GraphEdge("review-history", "review-ticket-101", "canonical-history", "accepted_into"));
        putAgent(new Agent("goat", "Goat", AgentState.REVIEWING, "ticket-102", "ticket-102", "control-room", "main", "reviewing", "pending",
                "Triaging tickets, reviewing changes, and accepting work into canonical history", List.of("ticket-101", "review-ticket-101", "canonical-history"), Instant.now()));
        putAgent(new Agent("builder", "Builder", AgentState.WORKING, "ticket-101", "ticket-101", "workspace-builder", "ticket-101/scaffold", "changes_requested", "pending",
                "Scaffolding the Fabric mod in an isolated ticket workspace", List.of("project", "fabric", "ticket-101", "workspace-builder"), Instant.now()));
        putAgent(new Agent("reviewer", "Reviewer", AgentState.REVIEWING, "ticket-101", "ticket-101", "workspace-reviewer", "ticket-101/review", "reviewing", "pending",
                "Checking the proposed change before Goat accepts it", List.of("ticket-101", "review-ticket-101"), Instant.now()));
    }

    private void event(String type, String id) {
        BridgeEvent event = new BridgeEvent(nextEventSequence.incrementAndGet(), type, id, Instant.now());
        events.add(event);
        if (events.size() > 500) events.remove(0);
        for (Consumer<BridgeEvent> listener : listeners) {
            try { listener.accept(event); }
            catch (RuntimeException ignored) { }
        }
    }
}
