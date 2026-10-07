package dev.blockagentspaces.model;

import java.time.Instant;

/** An ordered, immutable record for local bridge consumers. */
public record BridgeEvent(long sequence, String type, String subjectId, Instant createdAt) { }
