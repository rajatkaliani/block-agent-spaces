package dev.blockagentspaces.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Controls who may mutate the loopback bridge. A local address is not an
 * identity boundary: browsers, plugins, and other processes can all reach it.
 */
public final class BridgeAccessPolicy {
    public static final String TOKEN_PROPERTY = "blockagentspaces.bridge.token";
    public static final String TOKEN_ENVIRONMENT = "BLOCK_AGENT_SPACES_BRIDGE_TOKEN";
    public static final String DEMO_PROPERTY = "blockagentspaces.bridge.demo";
    public static final String DEMO_ENVIRONMENT = "BLOCK_AGENT_SPACES_BRIDGE_DEMO";

    private final String pairingToken;
    private final boolean explicitDemoMode;

    private BridgeAccessPolicy(String pairingToken, boolean explicitDemoMode) {
        this.pairingToken = pairingToken == null ? "" : pairingToken.trim();
        this.explicitDemoMode = explicitDemoMode && this.pairingToken.isBlank();
    }

    public static BridgeAccessPolicy fromEnvironment() {
        String token = firstNonBlank(System.getProperty(TOKEN_PROPERTY), System.getenv(TOKEN_ENVIRONMENT));
        boolean demo = Boolean.parseBoolean(firstNonBlank(System.getProperty(DEMO_PROPERTY), System.getenv(DEMO_ENVIRONMENT)));
        return new BridgeAccessPolicy(token, demo);
    }

    /** Creates a policy for tests or a server-side configuration adapter. */
    public static BridgeAccessPolicy configured(String pairingToken, boolean explicitDemoMode) {
        return new BridgeAccessPolicy(pairingToken, explicitDemoMode);
    }

    public boolean mutationAllowed(String authorization, String tokenHeader) {
        if (explicitDemoMode) return true;
        if (pairingToken.isBlank()) return false;
        String supplied = bearerToken(authorization);
        if (supplied == null) supplied = tokenHeader;
        return supplied != null && MessageDigest.isEqual(
            pairingToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    public boolean requiresPairing() { return !explicitDemoMode; }
    public boolean isExplicitDemoMode() { return explicitDemoMode; }
    public boolean hasConfiguredToken() { return !pairingToken.isBlank(); }
    public String modeLabel() { return explicitDemoMode ? "explicit_demo" : pairingToken.isBlank() ? "pairing_required" : "paired"; }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }
}
