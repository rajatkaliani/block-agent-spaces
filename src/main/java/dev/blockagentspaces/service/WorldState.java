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
    public static final int MAX_PERSISTED_MESSAGES = 100;
    public static final int MAX_PERSISTED_EVENTS = 200;
    public static final int MAX_PERSISTED_ACKNOWLEDGEMENTS = 64;
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
    private volatile boolean restoredExternalData;

    public void putAgent(Agent agent) { agents.put(agent.id(), agent); event("agent.updated", agent.id()); }
    public void putTask(Task task) { tasks.put(task.id(), task); event("task.updated", task.id()); }
    public void putNode(GraphNode node) { nodes.put(node.id(), node); event("graph.node.updated", node.id()); }
    public void putEdge(GraphEdge edge) { edges.put(edge.id(), edge); event("graph.edge.updated", edge.id()); }
    public void addMessage(AgentMessage message) {
        messages.add(message);
        if (messages.size() > MAX_PERSISTED_MESSAGES) messages.remove(0);
        event("message.created", message.id());
    }

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
    public boolean hasRestoredExternalData() { return restoredExternalData; }
    public boolean bridgeAvailable() { return bridgeAvailable; }
    public String presentationStatus() {
        if (externalUpdates) return "LIVE LOCAL UPDATES";
        if (restoredExternalData) return "RESTORED LOCAL DATA";
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
        restoredExternalData = false;
        event("presentation.updated", "local-bridge");
    }

    /** A bounded, immutable copy for world persistence. It contains only the presentation domain. */
    public PersistenceSnapshot snapshotForPersistence() {
        return new PersistenceSnapshot(
            agents.values().stream().sorted(Comparator.comparing(Agent::id)).toList(),
            tasks.values().stream().sorted(Comparator.comparing(Task::id)).toList(),
            nodes.values().stream().sorted(Comparator.comparing(GraphNode::id)).toList(),
            edges.values().stream().sorted(Comparator.comparing(GraphEdge::id)).toList(),
            tail(messages, MAX_PERSISTED_MESSAGES),
            tail(events, MAX_PERSISTED_EVENTS),
            acknowledgements.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(MAX_PERSISTED_ACKNOWLEDGEMENTS).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)),
            nextEventSequence.get(),
            externalUpdates || restoredExternalData
        );
    }

    /** Restores a previously sanitized snapshot before the bridge is opened. */
    public boolean restore(PersistenceSnapshot snapshot) {
        agents.clear(); tasks.clear(); nodes.clear(); edges.clear(); messages.clear(); events.clear(); acknowledgements.clear();
        snapshot.agents().forEach(agent -> agents.put(agent.id(), agent));
        snapshot.tasks().forEach(task -> tasks.put(task.id(), task));
        snapshot.nodes().forEach(node -> nodes.put(node.id(), node));
        snapshot.edges().forEach(edge -> edges.put(edge.id(), edge));
        messages.addAll(tail(snapshot.messages(), MAX_PERSISTED_MESSAGES));
        events.addAll(tail(snapshot.events(), MAX_PERSISTED_EVENTS));
        snapshot.acknowledgements().entrySet().stream().limit(MAX_PERSISTED_ACKNOWLEDGEMENTS).forEach(entry -> acknowledgements.put(entry.getKey(), entry.getValue()));
        long greatestEvent = events.stream().mapToLong(BridgeEvent::sequence).max().orElse(0);
        nextEventSequence.set(Math.max(snapshot.nextEventSequence(), greatestEvent));
        bridgeAvailable = false;
        externalUpdates = false;
        restoredExternalData = snapshot.hadExternalData();
        boolean restored = !snapshot.isEmpty();
        if (restored) event("presentation.restored", "runtime-state");
        return restored;
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
        if (events.size() > MAX_PERSISTED_EVENTS) events.remove(0);
        for (Consumer<BridgeEvent> listener : listeners) {
            try { listener.accept(event); }
            catch (RuntimeException ignored) { }
        }
    }

    private static <T> List<T> tail(List<T> values, int limit) {
        return List.copyOf(values.subList(Math.max(0, values.size() - limit), values.size()));
    }

    public record PersistenceSnapshot(List<Agent> agents, List<Task> tasks, List<GraphNode> nodes, List<GraphEdge> edges,
                                      List<AgentMessage> messages, List<BridgeEvent> events, Map<String, Long> acknowledgements,
                                      long nextEventSequence, boolean hadExternalData) {
        public PersistenceSnapshot {
            agents = List.copyOf(agents); tasks = List.copyOf(tasks); nodes = List.copyOf(nodes); edges = List.copyOf(edges);
            messages = List.copyOf(messages); events = List.copyOf(events); acknowledgements = Map.copyOf(acknowledgements);
        }
        public boolean isEmpty() {
            return agents.isEmpty() && tasks.isEmpty() && nodes.isEmpty() && edges.isEmpty() && messages.isEmpty() && !hadExternalData;
        }
    }
}
