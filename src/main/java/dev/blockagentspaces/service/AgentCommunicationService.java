package dev.blockagentspaces.service;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.AgentMessage;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resolves human-friendly agent names and keeps player-originated messages bounded. */
public final class AgentCommunicationService {
    /**
     * Stable protocol identity for messages that originate in Minecraft.  Adapters
     * consume this value, rather than a multiplayer display name, so a message
     * sent from the notebook follows the same path as a message created through
     * the legacy command surface.
     */
    public static final String MINECRAFT_PLAYER = "minecraft-player";
    private static final int MAX_REFERENCE_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 400;
    private final WorldState state;

    public AgentCommunicationService(WorldState state) { this.state = state; }

    public Optional<Agent> findAgent(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH) return Optional.empty();
        String normalized = reference.trim();
        return state.agents().stream().filter(a -> a.id().equalsIgnoreCase(normalized)).findFirst()
            .or(() -> state.agents().stream().filter(a -> a.displayName().equalsIgnoreCase(normalized)).findFirst());
    }

    public Optional<AgentMessage> recentMessageFor(String agentId) {
        return conversationFor(agentId).stream()
            .map(ConversationMessage::message)
            .max(Comparator.comparing(AgentMessage::createdAt));
    }

    /**
     * Returns only the two directions that belong in this agent's notebook.
     * In particular, a player-to-agent outbox entry is never rendered as if
     * the agent said it, and messages for another agent cannot leak into this
     * conversation.
     */
    public List<ConversationMessage> conversationFor(String reference) {
        Optional<Agent> agent = findAgent(reference);
        if (agent.isEmpty()) return List.of();
        String agentId = agent.get().id();
        return state.messages().stream()
            .filter(message -> isPlayerToAgent(message, agentId) || isAgentToPlayer(message, agentId))
            .sorted(Comparator.comparing(AgentMessage::createdAt))
            .map(message -> new ConversationMessage(message, isPlayerToAgent(message, agentId)
                ? Direction.OUTGOING : Direction.INCOMING))
            .toList();
    }

    private static boolean isPlayerToAgent(AgentMessage message, String agentId) {
        return MINECRAFT_PLAYER.equalsIgnoreCase(message.from()) && agentId.equalsIgnoreCase(message.to());
    }

    private static boolean isAgentToPlayer(AgentMessage message, String agentId) {
        return agentId.equalsIgnoreCase(message.from()) && MINECRAFT_PLAYER.equalsIgnoreCase(message.to());
    }

    public SendResult sendFromMinecraft(String reference, String body) {
        return sendFromPlayer(reference, MINECRAFT_PLAYER, body);
    }
    public SendResult sendFromPlayer(String reference, String sender, String body) {
        Optional<Agent> agent = findAgent(reference);
        if (agent.isEmpty()) return SendResult.error("No agent named '" + safeLabel(reference) + "' is currently published.");
        if (body == null || body.isBlank()) return SendResult.error("Message text cannot be empty.");
        if (body.length() > MAX_MESSAGE_LENGTH) return SendResult.error("Message is too long; keep it under " + MAX_MESSAGE_LENGTH + " characters.");
        String safeSender = sender == null || sender.isBlank() ? MINECRAFT_PLAYER : sender.trim().substring(0, Math.min(sender.trim().length(), 64));
        AgentMessage message = new AgentMessage("minecraft-" + UUID.randomUUID(), safeSender, agent.get().id(), body.trim(), Instant.now());
        state.addMessage(message);
        return SendResult.success(message, agent.get());
    }

    private static String safeLabel(String value) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= MAX_REFERENCE_LENGTH ? value.trim() : value.substring(0, MAX_REFERENCE_LENGTH);
    }

    public record SendResult(boolean sent, String error, AgentMessage message, Agent agent) {
        static SendResult success(AgentMessage message, Agent agent) { return new SendResult(true, "", message, agent); }
        static SendResult error(String error) { return new SendResult(false, error, null, null); }
    }

    public enum Direction { OUTGOING, INCOMING }
    public record ConversationMessage(AgentMessage message, Direction direction) { }
}
