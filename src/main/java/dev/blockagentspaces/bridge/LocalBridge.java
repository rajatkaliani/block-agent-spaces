package dev.blockagentspaces.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.blockagentspaces.model.*;
import dev.blockagentspaces.service.WorldState;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
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
        server.createContext("/health", x -> safely(x, this::health));
        server.createContext("/v1/snapshot", x -> safely(x, this::snapshot));
        server.createContext("/v1/events", x -> safely(x, this::events));
        server.createContext("/v1/events/ack", x -> safely(x, this::acknowledge));
        server.createContext("/v1/agents", x -> safely(x, this::agents));
        server.createContext("/v1/tasks", x -> safely(x, this::tasks));
        server.createContext("/v1/graph/nodes", x -> safely(x, this::nodes));
        server.createContext("/v1/graph/edges", x -> safely(x, this::edges));
        server.createContext("/v1/messages", x -> safely(x, this::messages));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }
    public void stop() { if (server != null) server.stop(0); }
    public boolean isRunning() { return server != null; }

    private void health(HttpExchange x) throws IOException { respond(x, 200, "{\"status\":\"ok\",\"bridge\":\"block-agent-spaces\"}"); }
    private void events(HttpExchange x) throws IOException {
        if (!x.getRequestMethod().equals("GET")) throw new IllegalArgumentException("Only GET is supported for this endpoint");
        long after = queryLong(x.getRequestURI(), "after", 0);
        if (after < 0) throw new IllegalArgumentException("after must not be negative");
        var events = state.eventsAfter(after);
        long nextCursor = events.isEmpty() ? after : events.getLast().sequence();
        String payload = events.stream().map(event -> "{\"id\":" + event.sequence() + ",\"type\":" + Json.quote(event.type()) + ",\"subjectId\":" + Json.quote(event.subjectId()) + ",\"createdAt\":" + Json.quote(event.createdAt().toString()) + "}").reduce((a,b) -> a + "," + b).orElse("");
        respond(x, 200, "{\"events\":[" + payload + "],\"nextCursor\":" + nextCursor + "}");
    }
    private void acknowledge(HttpExchange x) throws IOException {
        requirePost(x);
        String b = body(x);
        state.acknowledge(Json.string(b, "consumer"), Json.longValue(b, "cursor"));
        respond(x, 202, "{\"acknowledged\":true}");
    }
    private void snapshot(HttpExchange x) throws IOException {
        respond(x, 200, "{\"agents\":" + agentsJson() + ",\"tasks\":" + tasksJson() + ",\"nodes\":" + nodesJson() + ",\"edges\":" + edgesJson() + "}");
    }
    private void agents(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, agentsJson()); return; }
        requirePost(x);
        String b = body(x); state.putAgent(new Agent(Json.string(b,"id"), Json.string(b,"displayName"), AgentState.fromWire(Json.string(b,"state")), Json.string(b,"taskId"), Json.string(b,"detail"), Json.strings(b,"graphFocus"), Instant.now())); respond(x, 202, "{\"accepted\":true}");
    }
    private void tasks(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, tasksJson()); return; }
        requirePost(x);
        String b = body(x); state.putTask(new Task(Json.string(b,"id"), Json.string(b,"title"), Json.string(b,"description"), Json.string(b,"status"), Instant.now())); respond(x, 202, "{\"accepted\":true}");
    }
    private void nodes(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, nodesJson()); return; }
        requirePost(x);
        String b = body(x); state.putNode(new GraphNode(Json.string(b,"id"), Json.string(b,"label"), Json.string(b,"type"), java.util.Map.of())); respond(x, 202, "{\"accepted\":true}");
    }
    private void edges(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, edgesJson()); return; }
        requirePost(x);
        String b = body(x); state.putEdge(new GraphEdge(Json.string(b,"id"), Json.string(b,"sourceId"), Json.string(b,"targetId"), Json.string(b,"relationship"))); respond(x, 202, "{\"accepted\":true}");
    }
    private void messages(HttpExchange x) throws IOException {
        if (x.getRequestMethod().equals("GET")) { respond(x, 200, messagesJson()); return; }
        requirePost(x);
        String b = body(x); state.addMessage(new AgentMessage(Json.string(b,"id"), Json.string(b,"from"), Json.string(b,"to"), Json.string(b,"body"), Instant.now())); respond(x, 202, "{\"accepted\":true}");
    }
    private String agentsJson() { return "[" + state.agents().stream().map(a -> "{\"id\":"+Json.quote(a.id())+",\"displayName\":"+Json.quote(a.displayName())+",\"state\":"+Json.quote(a.state().name())+",\"taskId\":"+Json.quote(a.taskId())+",\"detail\":"+Json.quote(a.detail())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String tasksJson() { return "[" + state.tasks().stream().map(t -> "{\"id\":"+Json.quote(t.id())+",\"title\":"+Json.quote(t.title())+",\"status\":"+Json.quote(t.status())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String nodesJson() { return "[" + state.nodes().stream().map(n -> "{\"id\":"+Json.quote(n.id())+",\"label\":"+Json.quote(n.label())+",\"type\":"+Json.quote(n.type())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String edgesJson() { return "[" + state.edges().stream().map(e -> "{\"id\":"+Json.quote(e.id())+",\"sourceId\":"+Json.quote(e.sourceId())+",\"targetId\":"+Json.quote(e.targetId())+",\"relationship\":"+Json.quote(e.relationship())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String messagesJson() { return "[" + state.messages().stream().map(m -> "{\"id\":"+Json.quote(m.id())+",\"from\":"+Json.quote(m.from())+",\"to\":"+Json.quote(m.to())+",\"body\":"+Json.quote(m.body())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private static String body(HttpExchange x) throws IOException { return new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); }
    private static long queryLong(URI uri, String name, long defaultValue) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) return defaultValue;
        for (String entry : query.split("&")) {
            String[] pair = entry.split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) {
                try { return Long.parseLong(pair[1]); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException(name + " must be an integer"); }
            }
        }
        return defaultValue;
    }
    private static void respond(HttpExchange x, int code, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); x.sendResponseHeaders(code, bytes.length); try (OutputStream os=x.getResponseBody()) { os.write(bytes); } }
    private static void requirePost(HttpExchange x) {
        if (!x.getRequestMethod().equals("POST")) throw new IllegalArgumentException("Only GET and POST are supported for this endpoint");
    }
    private static void safely(HttpExchange exchange, ExchangeHandler handler) throws IOException {
        try { handler.handle(exchange); }
        catch (IllegalArgumentException invalid) { respond(exchange, 400, "{\"error\":" + Json.quote(invalid.getMessage()) + "}"); }
        catch (Exception failure) { respond(exchange, 500, "{\"error\":\"bridge request failed\"}"); }
    }
    @FunctionalInterface private interface ExchangeHandler { void handle(HttpExchange exchange) throws Exception; }
}
