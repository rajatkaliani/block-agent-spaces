package dev.blockagentspaces.service;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.blockagentspaces.model.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** World-saved, bounded presentation state. Credentials and adapter configuration are intentionally excluded. */
public final class RuntimeStateStore extends SavedData {
    private static final int MAX_AGENTS = 32;
    private static final int MAX_TASKS = 128;
    private static final int MAX_NODES = 256;
    private static final int MAX_EDGES = 512;
    private static final int MAX_ATTRIBUTES = 16;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern SECRET = Pattern.compile("(?i)\\b(?:token|api[_-]?key|secret|password)\\s*[:=]\\s*[^\\s]+");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?i)(?:~[\\\\/]|[a-z]:[\\\\/]|/(?:users|home|private|tmp|var|etc|opt|mnt)(?:[\\\\/][^\\s]*)*)\\S*");

    private static final Codec<PersistedAgent> AGENT_CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("id").forGetter(PersistedAgent::id), Codec.STRING.fieldOf("displayName").forGetter(PersistedAgent::displayName),
        Codec.STRING.fieldOf("state").forGetter(PersistedAgent::state), Codec.STRING.fieldOf("taskId").forGetter(PersistedAgent::taskId),
        Codec.STRING.fieldOf("ticketId").forGetter(PersistedAgent::ticketId), Codec.STRING.fieldOf("workspace").forGetter(PersistedAgent::workspace),
        Codec.STRING.fieldOf("branch").forGetter(PersistedAgent::branch), Codec.STRING.fieldOf("reviewStatus").forGetter(PersistedAgent::reviewStatus),
        Codec.STRING.fieldOf("acceptanceStatus").forGetter(PersistedAgent::acceptanceStatus), Codec.STRING.fieldOf("detail").forGetter(PersistedAgent::detail),
        Codec.list(Codec.STRING).fieldOf("graphFocus").forGetter(PersistedAgent::graphFocus), Codec.LONG.fieldOf("updatedAt").forGetter(PersistedAgent::updatedAt)
    ).apply(i, PersistedAgent::new));
    private static final Codec<PersistedTask> TASK_CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("id").forGetter(PersistedTask::id), Codec.STRING.fieldOf("title").forGetter(PersistedTask::title),
        Codec.STRING.fieldOf("description").forGetter(PersistedTask::description), Codec.STRING.fieldOf("status").forGetter(PersistedTask::status),
        Codec.LONG.fieldOf("updatedAt").forGetter(PersistedTask::updatedAt)
    ).apply(i, PersistedTask::new));
    private static final Codec<PersistedNode> NODE_CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("id").forGetter(PersistedNode::id), Codec.STRING.fieldOf("label").forGetter(PersistedNode::label),
        Codec.STRING.fieldOf("type").forGetter(PersistedNode::type), Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("attributes").forGetter(PersistedNode::attributes)
    ).apply(i, PersistedNode::new));
    private static final Codec<PersistedEdge> EDGE_CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("id").forGetter(PersistedEdge::id), Codec.STRING.fieldOf("sourceId").forGetter(PersistedEdge::sourceId),
        Codec.STRING.fieldOf("targetId").forGetter(PersistedEdge::targetId), Codec.STRING.fieldOf("relationship").forGetter(PersistedEdge::relationship)
    ).apply(i, PersistedEdge::new));
    private static final Codec<PersistedMessage> MESSAGE_CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("id").forGetter(PersistedMessage::id), Codec.STRING.fieldOf("from").forGetter(PersistedMessage::from),
        Codec.STRING.fieldOf("to").forGetter(PersistedMessage::to), Codec.STRING.fieldOf("body").forGetter(PersistedMessage::body), Codec.LONG.fieldOf("createdAt").forGetter(PersistedMessage::createdAt)
    ).apply(i, PersistedMessage::new));
    private static final Codec<PersistedEvent> EVENT_CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.LONG.fieldOf("sequence").forGetter(PersistedEvent::sequence), Codec.STRING.fieldOf("type").forGetter(PersistedEvent::type),
        Codec.STRING.fieldOf("subjectId").forGetter(PersistedEvent::subjectId), Codec.LONG.fieldOf("createdAt").forGetter(PersistedEvent::createdAt)
    ).apply(i, PersistedEvent::new));
    private static final Codec<PersistedRuntime> RUNTIME_CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.list(AGENT_CODEC).optionalFieldOf("agents", List.of()).forGetter(PersistedRuntime::agents), Codec.list(TASK_CODEC).optionalFieldOf("tasks", List.of()).forGetter(PersistedRuntime::tasks),
        Codec.list(NODE_CODEC).optionalFieldOf("nodes", List.of()).forGetter(PersistedRuntime::nodes), Codec.list(EDGE_CODEC).optionalFieldOf("edges", List.of()).forGetter(PersistedRuntime::edges),
        Codec.list(MESSAGE_CODEC).optionalFieldOf("messages", List.of()).forGetter(PersistedRuntime::messages), Codec.list(EVENT_CODEC).optionalFieldOf("events", List.of()).forGetter(PersistedRuntime::events),
        Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("acknowledgements", Map.of()).forGetter(PersistedRuntime::acknowledgements),
        Codec.LONG.optionalFieldOf("nextEventSequence", 0L).forGetter(PersistedRuntime::nextEventSequence), Codec.BOOL.optionalFieldOf("hadExternalData", false).forGetter(PersistedRuntime::hadExternalData)
    ).apply(i, PersistedRuntime::new));
    private static final SavedDataType<RuntimeStateStore> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("block_agent_spaces", "runtime_state"), RuntimeStateStore::new, RUNTIME_CODEC.xmap(RuntimeStateStore::new, store -> store.runtime), DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private PersistedRuntime runtime;

    private RuntimeStateStore() { this(PersistedRuntime.empty()); }
    private RuntimeStateStore(PersistedRuntime runtime) { this.runtime = runtime; }

    public static RuntimeStateStore get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(TYPE); }
    public boolean restoreInto(WorldState state) { return state.restore(runtime.toSnapshot()); }
    public void saveFrom(WorldState state) { runtime = PersistedRuntime.from(state.snapshotForPersistence()); setDirty(); }

    static PersistedRuntime sanitize(WorldState.PersistenceSnapshot snapshot) { return PersistedRuntime.from(snapshot); }

    private static String text(String value, int max) {
        String clean = value == null ? "" : ABSOLUTE_PATH.matcher(SECRET.matcher(CONTROL.matcher(value).replaceAll(" ")).replaceAll("[redacted]")).replaceAll("[redacted path]").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
    private static String id(String value) { return text(value, 128).replaceAll("[^A-Za-z0-9._:/-]", ""); }
    private static String workspace(String value) {
        String clean = text(value, 128);
        return clean.contains("[redacted path]") || clean.startsWith("/") || clean.startsWith("~") || clean.matches("(?i)^[a-z]:.*") || clean.contains("\\\\") || clean.contains("..") ? "" : clean;
    }
    private static long time(long epochMillis) { return Math.max(0, epochMillis); }
    private static <T> List<T> bounded(Collection<T> source, int max) { return source.stream().limit(max).toList(); }
    private static Map<String, String> attributes(Map<String, String> source) {
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> { if (sanitized.size() < MAX_ATTRIBUTES && !id(key).isEmpty()) sanitized.put(id(key), text(value, 256)); });
        return Map.copyOf(sanitized);
    }

    record PersistedAgent(String id, String displayName, String state, String taskId, String ticketId, String workspace, String branch, String reviewStatus, String acceptanceStatus, String detail, List<String> graphFocus, long updatedAt) { }
    record PersistedTask(String id, String title, String description, String status, long updatedAt) { }
    record PersistedNode(String id, String label, String type, Map<String, String> attributes) { }
    record PersistedEdge(String id, String sourceId, String targetId, String relationship) { }
    record PersistedMessage(String id, String from, String to, String body, long createdAt) { }
    record PersistedEvent(long sequence, String type, String subjectId, long createdAt) { }

    record PersistedRuntime(List<PersistedAgent> agents, List<PersistedTask> tasks, List<PersistedNode> nodes, List<PersistedEdge> edges,
                            List<PersistedMessage> messages, List<PersistedEvent> events, Map<String, Long> acknowledgements,
                            long nextEventSequence, boolean hadExternalData) {
        static PersistedRuntime empty() { return new PersistedRuntime(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), 0, false); }
        static PersistedRuntime from(WorldState.PersistenceSnapshot snapshot) {
            List<PersistedAgent> agents = new ArrayList<>();
            for (Agent agent : bounded(snapshot.agents(), MAX_AGENTS)) if (!id(agent.id()).isEmpty()) agents.add(new PersistedAgent(id(agent.id()), text(agent.displayName(), 128), agent.state().name(), id(agent.taskId()), id(agent.ticketId()), workspace(agent.workspace()), text(agent.branch(), 128), text(agent.reviewStatus(), 64), text(agent.acceptanceStatus(), 64), text(agent.detail(), 1_000), bounded(agent.graphFocus().stream().map(RuntimeStateStore::id).filter(value -> !value.isEmpty()).toList(), 16), time(agent.updatedAt().toEpochMilli())));
            List<PersistedTask> tasks = new ArrayList<>();
            for (Task task : bounded(snapshot.tasks(), MAX_TASKS)) if (!id(task.id()).isEmpty()) tasks.add(new PersistedTask(id(task.id()), text(task.title(), 256), text(task.description(), 1_000), text(task.status(), 64), time(task.updatedAt().toEpochMilli())));
            List<PersistedNode> nodes = new ArrayList<>();
            for (GraphNode node : bounded(snapshot.nodes(), MAX_NODES)) if (!id(node.id()).isEmpty()) nodes.add(new PersistedNode(id(node.id()), text(node.label(), 256), text(node.type(), 64), attributes(node.attributes())));
            List<PersistedEdge> edges = new ArrayList<>();
            for (GraphEdge edge : bounded(snapshot.edges(), MAX_EDGES)) if (!id(edge.id()).isEmpty() && !id(edge.sourceId()).isEmpty() && !id(edge.targetId()).isEmpty()) edges.add(new PersistedEdge(id(edge.id()), id(edge.sourceId()), id(edge.targetId()), text(edge.relationship(), 64)));
            List<PersistedMessage> messages = new ArrayList<>();
            for (AgentMessage message : bounded(snapshot.messages(), WorldState.MAX_PERSISTED_MESSAGES)) if (!id(message.id()).isEmpty()) messages.add(new PersistedMessage(id(message.id()), id(message.from()), id(message.to()), text(message.body(), 400), time(message.createdAt().toEpochMilli())));
            List<PersistedEvent> events = new ArrayList<>();
            for (BridgeEvent event : bounded(snapshot.events(), WorldState.MAX_PERSISTED_EVENTS)) events.add(new PersistedEvent(Math.max(0, event.sequence()), text(event.type(), 64), id(event.subjectId()), time(event.createdAt().toEpochMilli())));
            LinkedHashMap<String, Long> acknowledgements = new LinkedHashMap<>();
            snapshot.acknowledgements().forEach((consumer, cursor) -> { if (acknowledgements.size() < WorldState.MAX_PERSISTED_ACKNOWLEDGEMENTS && !id(consumer).isEmpty()) acknowledgements.put(id(consumer), Math.max(0, cursor)); });
            return new PersistedRuntime(List.copyOf(agents), List.copyOf(tasks), List.copyOf(nodes), List.copyOf(edges), List.copyOf(messages), List.copyOf(events), Map.copyOf(acknowledgements), Math.max(0, snapshot.nextEventSequence()), snapshot.hadExternalData());
        }
        WorldState.PersistenceSnapshot toSnapshot() {
            List<Agent> agents = new ArrayList<>(); for (PersistedAgent agent : agents()) try { agents.add(new Agent(agent.id(), agent.displayName(), AgentState.fromWire(agent.state()), agent.taskId(), agent.ticketId(), agent.workspace(), agent.branch(), agent.reviewStatus(), agent.acceptanceStatus(), agent.detail(), agent.graphFocus(), Instant.ofEpochMilli(time(agent.updatedAt())))); } catch (IllegalArgumentException ignored) { }
            List<Task> tasks = new ArrayList<>(); for (PersistedTask task : tasks()) try { tasks.add(new Task(task.id(), task.title(), task.description(), task.status(), Instant.ofEpochMilli(time(task.updatedAt())))); } catch (IllegalArgumentException ignored) { }
            List<GraphNode> nodes = new ArrayList<>(); for (PersistedNode node : nodes()) try { nodes.add(new GraphNode(node.id(), node.label(), node.type(), attributes(node.attributes()))); } catch (IllegalArgumentException ignored) { }
            List<GraphEdge> edges = new ArrayList<>(); for (PersistedEdge edge : edges()) try { edges.add(new GraphEdge(edge.id(), edge.sourceId(), edge.targetId(), edge.relationship())); } catch (IllegalArgumentException ignored) { }
            List<AgentMessage> messages = new ArrayList<>(); for (PersistedMessage message : messages()) try { messages.add(new AgentMessage(message.id(), message.from(), message.to(), message.body(), Instant.ofEpochMilli(time(message.createdAt())))); } catch (IllegalArgumentException ignored) { }
            List<BridgeEvent> events = events().stream().map(event -> new BridgeEvent(Math.max(0, event.sequence()), event.type(), event.subjectId(), Instant.ofEpochMilli(time(event.createdAt())))).toList();
            return new WorldState.PersistenceSnapshot(agents, tasks, nodes, edges, messages, events, acknowledgements(), Math.max(0, nextEventSequence()), hadExternalData());
        }
    }
}
