package dev.blockagentspaces.model;

public enum AgentState {
    IDLE, WORKING, BLOCKED, REVIEWING, COMPLETE;

    public static AgentState fromWire(String value) {
        try { return value == null ? IDLE : valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return IDLE; }
    }
}
