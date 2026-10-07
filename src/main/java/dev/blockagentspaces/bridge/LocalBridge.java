package dev.blockagentspaces.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.blockagentspaces.model.*;
import dev.blockagentspaces.service.WorldState;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;

/** Local-only HTTP bridge. No authentication is required because it binds only to loopback. */
public final class LocalBridge {
    public static final int PORT = 8787;
    private final WorldState state;
    private HttpServer server;

    public LocalBridge(WorldState state) { this.state = state; }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/health", this::health);
        server.createContext("/v1/snapshot", this::snapshot);
        server.createContext("/v1/events", this::events);
        server.createContext("/v1/agents", this::agents);
        server.createContext("/v1/tasks", this::tasks);
        server.createContext("/v1/graph/nodes", this::nodes);
        server.createContext("/v1/graph/edges", this::edges);
        server.createContext("/v1/messages", this::messages);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }
    public void stop() { if (server != null) server.stop(0); }
    public boolean isRunning() { return server != null; }

    private void health(HttpExchange x) throws IOException { respond(x, 200, "{\"status\":\"ok\",\"bridge\":\"block-agent-spaces\"}"); }
    private void events(HttpExchange x) throws IOException { respond(x, 200, "[" + String.join(",", state.events()) + "]"); }
    private void snapshot(HttpExchange x) throws IOException {
        respond(x, 200, "{\"agents\":" + agentsJson() + ",\"tasks\":" + tasksJson() + ",\"nodes\":" + nodesJson() + ",\"edges\":" + edgesJson() + "}");
    }
    private void agents(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, agentsJson()); return; }
        String b = body(x); state.putAgent(new Agent(Json.string(b,"id"), Json.string(b,"displayName"), AgentState.fromWire(Json.string(b,"state")), Json.string(b,"taskId"), Json.string(b,"detail"), Json.strings(b,"graphFocus"), Instant.now())); respond(x, 202, "{\"accepted\":true}");
    }
    private void tasks(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, tasksJson()); return; }
        String b = body(x); state.putTask(new Task(Json.string(b,"id"), Json.string(b,"title"), Json.string(b,"description"), Json.string(b,"status"), Instant.now())); respond(x, 202, "{\"accepted\":true}");
    }
    private void nodes(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, nodesJson()); return; }
        String b = body(x); state.putNode(new GraphNode(Json.string(b,"id"), Json.string(b,"label"), Json.string(b,"type"), java.util.Map.of())); respond(x, 202, "{\"accepted\":true}");
    }
    private void edges(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, edgesJson()); return; }
        String b = body(x); state.putEdge(new GraphEdge(Json.string(b,"id"), Json.string(b,"sourceId"), Json.string(b,"targetId"), Json.string(b,"relationship"))); respond(x, 202, "{\"accepted\":true}");
    }
    private void messages(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, messagesJson()); return; }
        String b = body(x); state.addMessage(new AgentMessage(Json.string(b,"id"), Json.string(b,"from"), Json.string(b,"to"), Json.string(b,"body"), Instant.now())); respond(x, 202, "{\"accepted\":true}");
    }
    private String agentsJson() { return "[" + state.agents().stream().map(a -> "{\"id\":"+Json.quote(a.id())+",\"displayName\":"+Json.quote(a.displayName())+",\"state\":"+Json.quote(a.state().name())+",\"taskId\":"+Json.quote(a.taskId())+",\"detail\":"+Json.quote(a.detail())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String tasksJson() { return "[" + state.tasks().stream().map(t -> "{\"id\":"+Json.quote(t.id())+",\"title\":"+Json.quote(t.title())+",\"status\":"+Json.quote(t.status())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String nodesJson() { return "[" + state.nodes().stream().map(n -> "{\"id\":"+Json.quote(n.id())+",\"label\":"+Json.quote(n.label())+",\"type\":"+Json.quote(n.type())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String edgesJson() { return "[" + state.edges().stream().map(e -> "{\"id\":"+Json.quote(e.id())+",\"sourceId\":"+Json.quote(e.sourceId())+",\"targetId\":"+Json.quote(e.targetId())+",\"relationship\":"+Json.quote(e.relationship())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String messagesJson() { return "[" + state.messages().stream().map(m -> "{\"id\":"+Json.quote(m.id())+",\"from\":"+Json.quote(m.from())+",\"to\":"+Json.quote(m.to())+",\"body\":"+Json.quote(m.body())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private static String body(HttpExchange x) throws IOException { return new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); }
    private static void respond(HttpExchange x, int code, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); x.sendResponseHeaders(code, bytes.length); try (OutputStream os=x.getResponseBody()) { os.write(bytes); } }
}
