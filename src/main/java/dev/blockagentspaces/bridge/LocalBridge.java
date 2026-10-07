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
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Executors;

/** Loopback-only HTTP boundary between Minecraft and a local agent adapter. */
public final class LocalBridge {
    public static final int PORT = 8787;
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_LABEL_LENGTH = 256;
    private static final int MAX_DETAIL_LENGTH = 4_000;
    private final WorldState state;
    private final int requestedPort;
    private final BridgeAccessPolicy access;
    private final Runnable externalStateUpdated;
    private HttpServer server;

    public LocalBridge(WorldState state) { this(state, PORT, BridgeAccessPolicy.fromEnvironment(), () -> { }); }
    /** Use this constructor to schedule presentation refreshes after a validated external mutation. */
    public LocalBridge(WorldState state, Runnable externalStateUpdated) { this(state, PORT, BridgeAccessPolicy.fromEnvironment(), externalStateUpdated); }
    public LocalBridge(WorldState state, int port, BridgeAccessPolicy access) { this(state, port, access, () -> { }); }
    public LocalBridge(WorldState state, int port, BridgeAccessPolicy access, Runnable externalStateUpdated) {
        this.state = state; this.requestedPort = port; this.access = access;
        this.externalStateUpdated = externalStateUpdated == null ? () -> { } : externalStateUpdated;
    }
    public void start() throws IOException {
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
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
    public void stop() { if (server != null) { server.stop(0); server = null; } }
    public boolean isRunning() { return server != null; }
    public int port() { return server == null ? requestedPort : server.getAddress().getPort(); }

    private void health(HttpExchange x) throws IOException {
        requireMethod(x, "GET");
        respond(x, 200, "{\"status\":\"ok\",\"bridge\":\"block-agent-spaces\",\"mutationMode\":" + Json.quote(access.modeLabel()) + "}");
    }
    private void events(HttpExchange x) throws IOException {
        requireMethod(x, "GET");
        long after = queryLong(x.getRequestURI(), "after", 0);
        if (after < 0) throw new IllegalArgumentException("after must not be negative");
        var events = state.eventsAfter(after);
        long next = events.isEmpty() ? after : events.getLast().sequence();
        String payload = events.stream().map(e -> "{\"id\":" + e.sequence() + ",\"type\":" + Json.quote(e.type()) + ",\"subjectId\":" + Json.quote(e.subjectId()) + ",\"createdAt\":" + Json.quote(e.createdAt().toString()) + "}").reduce((a,b) -> a + "," + b).orElse("");
        respond(x, 200, "{\"events\":[" + payload + "],\"nextCursor\":" + next + "}");
    }
    private void acknowledge(HttpExchange x) throws IOException {
        Map<String, Object> b = mutationBody(x);
        state.acknowledge(Json.requiredString(b, "consumer", 64), Json.requiredLong(b, "cursor"));
        accepted(x);
    }
    private void snapshot(HttpExchange x) throws IOException { requireMethod(x, "GET"); respond(x, 200, "{\"agents\":" + agentsJson() + ",\"tasks\":" + tasksJson() + ",\"nodes\":" + nodesJson() + ",\"edges\":" + edgesJson() + "}"); }
    private void agents(HttpExchange x) throws IOException {
        if (get(x, agentsJson())) return;
        Map<String, Object> b = mutationBody(x);
        state.putAgent(new Agent(id(b, "id"), Json.optionalString(b, "displayName", MAX_LABEL_LENGTH), state(b), Json.optionalString(b, "taskId", MAX_ID_LENGTH), Json.optionalString(b, "ticketId", MAX_ID_LENGTH), Json.optionalString(b, "workspace", MAX_LABEL_LENGTH), Json.optionalString(b, "branch", MAX_LABEL_LENGTH), Json.optionalString(b, "reviewStatus", 64), Json.optionalString(b, "acceptanceStatus", 64), Json.optionalString(b, "detail", MAX_DETAIL_LENGTH), Json.optionalStrings(b, "graphFocus", 32, MAX_ID_LENGTH), Instant.now()));
        notifyExternalUpdate();
        accepted(x);
    }
    private void tasks(HttpExchange x) throws IOException {
        if (get(x, tasksJson())) return;
        Map<String, Object> b = mutationBody(x);
        state.putTask(new Task(id(b, "id"), Json.requiredString(b, "title", MAX_LABEL_LENGTH), Json.optionalString(b, "description", MAX_DETAIL_LENGTH), Json.requiredString(b, "status", 64), Instant.now()));
        notifyExternalUpdate();
        accepted(x);
    }
    private void nodes(HttpExchange x) throws IOException {
        if (get(x, nodesJson())) return;
        Map<String, Object> b = mutationBody(x);
        state.putNode(new GraphNode(id(b, "id"), Json.requiredString(b, "label", MAX_LABEL_LENGTH), Json.requiredString(b, "type", 64), Map.of()));
        notifyExternalUpdate();
        accepted(x);
    }
    private void edges(HttpExchange x) throws IOException {
        if (get(x, edgesJson())) return;
        Map<String, Object> b = mutationBody(x);
        state.putEdge(new GraphEdge(id(b, "id"), id(b, "sourceId"), id(b, "targetId"), Json.requiredString(b, "relationship", 64)));
        notifyExternalUpdate();
        accepted(x);
    }
    private void messages(HttpExchange x) throws IOException {
        if (get(x, messagesJson())) return;
        Map<String, Object> b = mutationBody(x);
        state.addMessage(new AgentMessage(id(b, "id"), id(b, "from"), id(b, "to"), Json.requiredString(b, "body", MAX_DETAIL_LENGTH), Instant.now()));
        notifyExternalUpdate();
        accepted(x);
    }
    private static boolean get(HttpExchange x, String payload) throws IOException { if (x.getRequestMethod().equals("GET")) { respond(x, 200, payload); return true; } return false; }
    private static String id(Map<String, Object> b, String key) { return Json.requiredString(b, key, MAX_ID_LENGTH); }
    private static AgentState state(Map<String, Object> b) {
        String state = Json.requiredString(b, "state", 32).toUpperCase(Locale.ROOT);
        try { return AgentState.valueOf(state); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("state must be one of: IDLE, WORKING, BLOCKED, REVIEWING, COMPLETE"); }
    }
    private static void accepted(HttpExchange x) throws IOException { respond(x, 202, "{\"accepted\":true}"); }
    private void notifyExternalUpdate() { try { externalStateUpdated.run(); } catch (RuntimeException ignored) { /* A visual refresh must not make a committed bridge update fail. */ } }

    private Map<String, Object> mutationBody(HttpExchange x) throws IOException {
        requireMethod(x, "POST");
        if (!access.mutationAllowed(x.getRequestHeaders().getFirst("Authorization"), x.getRequestHeaders().getFirst("X-Block-Agent-Spaces-Token"))) {
            String guidance = access.hasConfiguredToken() ? "a valid pairing token is required" : "configure a pairing token or explicitly enable demo mode";
            throw new BridgeRequestException(403, "Bridge mutation rejected: " + guidance);
        }
        String type = x.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) throw new BridgeRequestException(415, "Content-Type must be application/json");
        return Json.object(body(x));
    }
    private static String body(HttpExchange x) throws IOException {
        String length = x.getRequestHeaders().getFirst("Content-Length");
        if (length != null) try { if (Long.parseLong(length) > MAX_REQUEST_BYTES) throw new BridgeRequestException(413, "request body is too large"); }
        catch (NumberFormatException invalid) { throw new BridgeRequestException(400, "invalid Content-Length"); }
        try (InputStream input = x.getRequestBody()) {
            byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) throw new BridgeRequestException(413, "request body is too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    private String agentsJson() { return "[" + state.agents().stream().map(a -> "{\"id\":"+Json.quote(a.id())+",\"displayName\":"+Json.quote(a.displayName())+",\"state\":"+Json.quote(a.state().name())+",\"taskId\":"+Json.quote(a.taskId())+",\"ticketId\":"+Json.quote(a.ticketId())+",\"workspace\":"+Json.quote(a.workspace())+",\"branch\":"+Json.quote(a.branch())+",\"reviewStatus\":"+Json.quote(a.reviewStatus())+",\"acceptanceStatus\":"+Json.quote(a.acceptanceStatus())+",\"detail\":"+Json.quote(a.detail())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String tasksJson() { return "[" + state.tasks().stream().map(t -> "{\"id\":"+Json.quote(t.id())+",\"title\":"+Json.quote(t.title())+",\"status\":"+Json.quote(t.status())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String nodesJson() { return "[" + state.nodes().stream().map(n -> "{\"id\":"+Json.quote(n.id())+",\"label\":"+Json.quote(n.label())+",\"type\":"+Json.quote(n.type())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String edgesJson() { return "[" + state.edges().stream().map(e -> "{\"id\":"+Json.quote(e.id())+",\"sourceId\":"+Json.quote(e.sourceId())+",\"targetId\":"+Json.quote(e.targetId())+",\"relationship\":"+Json.quote(e.relationship())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private String messagesJson() { return "[" + state.messages().stream().map(m -> "{\"id\":"+Json.quote(m.id())+",\"from\":"+Json.quote(m.from())+",\"to\":"+Json.quote(m.to())+",\"body\":"+Json.quote(m.body())+"}").reduce((a,b)->a+","+b).orElse("") + "]"; }
    private static long queryLong(URI uri, String name, long fallback) {
        String query = uri.getRawQuery(); if (query == null || query.isBlank()) return fallback;
        for (String entry : query.split("&")) { String[] pair = entry.split("=", 2); if (pair.length == 2 && pair[0].equals(name)) try { return Long.parseLong(pair[1]); } catch (NumberFormatException invalid) { throw new IllegalArgumentException(name + " must be an integer"); } }
        return fallback;
    }
    private static void requireMethod(HttpExchange x, String method) { if (!x.getRequestMethod().equals(method)) throw new BridgeRequestException(405, "Only " + method + " is supported for this endpoint", method); }
    private static void respond(HttpExchange x, int code, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); x.sendResponseHeaders(code, bytes.length); try (OutputStream os=x.getResponseBody()) { os.write(bytes); } }
    private static void safely(HttpExchange exchange, ExchangeHandler handler) throws IOException {
        try { handler.handle(exchange); }
        catch (BridgeRequestException rejected) { if (rejected.allowedMethod != null) exchange.getResponseHeaders().set("Allow", rejected.allowedMethod); respond(exchange, rejected.status, "{\"error\":" + Json.quote(rejected.getMessage()) + "}"); }
        catch (IllegalArgumentException invalid) { respond(exchange, 400, "{\"error\":" + Json.quote(invalid.getMessage()) + "}"); }
        catch (Exception failure) { respond(exchange, 500, "{\"error\":\"bridge request failed\"}"); }
    }
    @FunctionalInterface private interface ExchangeHandler { void handle(HttpExchange exchange) throws Exception; }
    private static final class BridgeRequestException extends IllegalArgumentException { final int status; final String allowedMethod; BridgeRequestException(int status, String message) { this(status, message, null); } BridgeRequestException(int status, String message, String allowedMethod) { super(message); this.status = status; this.allowedMethod = allowedMethod; } }
}
