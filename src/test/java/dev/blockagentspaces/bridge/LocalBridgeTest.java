package dev.blockagentspaces.bridge;

import dev.blockagentspaces.service.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LocalBridgeTest {
    private LocalBridge bridge;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach void stopBridge() { if (bridge != null) bridge.stop(); }

    @Test void mutationEndpointsRequireAPairedTokenByDefault() throws Exception {
        bridge = new LocalBridge(new WorldState(), 0, BridgeAccessPolicy.configured("paired-secret", false));
        bridge.start();

        HttpResponse<String> response = post("/v1/tasks", "{\"id\":\"task-1\",\"title\":\"Secure\",\"status\":\"open\"}", null);

        assertEquals(403, response.statusCode());
        assertFalse(response.body().contains("paired-secret"));
    }

    @Test void pairedMutationValidatesJsonThenNotifiesTheIntegrationHook() throws Exception {
        AtomicInteger updates = new AtomicInteger();
        WorldState state = new WorldState();
        bridge = new LocalBridge(state, 0, BridgeAccessPolicy.configured("paired-secret", false), updates::incrementAndGet);
        bridge.start();

        HttpResponse<String> accepted = post("/v1/tasks", "{\"id\":\"task-1\",\"title\":\"Secure\",\"status\":\"open\"}", "Bearer paired-secret");
        HttpResponse<String> malformed = post("/v1/tasks", "{\"id\":\"task-2\",", "Bearer paired-secret");
        HttpResponse<String> invalidState = post("/v1/agents", "{\"id\":\"builder\",\"state\":\"teleporting\"}", "Bearer paired-secret");

        assertEquals(202, accepted.statusCode());
        assertEquals(400, malformed.statusCode());
        assertEquals(400, invalidState.statusCode());
        assertEquals(1, state.tasks().size());
        assertEquals(1, updates.get());
    }

    @Test void rejectsUnsupportedMethodsContentTypesAndOversizedBodies() throws Exception {
        bridge = new LocalBridge(new WorldState(), 0, BridgeAccessPolicy.configured("paired-secret", false));
        bridge.start();

        HttpRequest put = HttpRequest.newBuilder(uri("/v1/tasks")).PUT(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> method = client.send(put, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> type = rawPost("/v1/tasks", "text/plain", "{}", "Bearer paired-secret");
        HttpResponse<String> large = post("/v1/tasks", "{\"id\":\"task\",\"title\":\"" + "x".repeat(16 * 1024) + "\",\"status\":\"open\"}", "Bearer paired-secret");

        assertEquals(405, method.statusCode());
        assertEquals("POST", method.headers().firstValue("Allow").orElseThrow());
        assertEquals(415, type.statusCode());
        assertEquals(413, large.statusCode());
    }

    @Test void explicitDemoModeIsDiscoverableAndAllowsAZeroSetupDemo() throws Exception {
        bridge = new LocalBridge(new WorldState(), 0, BridgeAccessPolicy.configured("", true));
        bridge.start();

        HttpResponse<String> health = client.send(HttpRequest.newBuilder(uri("/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response = post("/v1/graph/nodes", "{\"id\":\"demo\",\"label\":\"Demo\",\"type\":\"project\"}", null);

        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("explicit_demo"));
        assertEquals(202, response.statusCode());
    }

    private HttpResponse<String> post(String path, String body, String authorization) throws Exception {
        return rawPost(path, "application/json", body, authorization);
    }
    private HttpResponse<String> rawPost(String path, String contentType, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + bridge.port() + path); }
}
