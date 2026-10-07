package dev.blockagentspaces.service;

import dev.blockagentspaces.model.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

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

    public void seedExample() {
        putTask(new Task("task-scaffold", "Scaffold Fabric mod", "Create the first runnable mod", "in_progress", Instant.now()));
        putNode(new GraphNode("project", "Block Agent Spaces", "project", Map.of()));
        putNode(new GraphNode("fabric", "Fabric mod", "file", Map.of()));
        putNode(new GraphNode("task-scaffold", "Scaffold Fabric mod", "task", Map.of()));
        putEdge(new GraphEdge("project-uses-fabric", "project", "fabric", "uses"));
        putEdge(new GraphEdge("task-builds-project", "task-scaffold", "project", "builds"));
        putAgent(new Agent("builder", "Builder", AgentState.WORKING, "task-scaffold",
                "Scaffolding the Fabric mod", List.of("project", "fabric", "task-scaffold"), Instant.now()));
        putAgent(new Agent("reviewer", "Reviewer", AgentState.REVIEWING, "task-scaffold",
                "Reviewing the proposed architecture", List.of("project", "task-scaffold"), Instant.now()));
    }

    private void event(String type, String id) {
        events.add(new BridgeEvent(nextEventSequence.incrementAndGet(), type, id, Instant.now()));
        if (events.size() > 500) events.remove(0);
    }
}
