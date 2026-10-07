package dev.blockagentspaces;

import dev.blockagentspaces.service.AgentCommunicationService;
import dev.blockagentspaces.service.WorkspaceAutoRefresher;
import dev.blockagentspaces.service.WorldState;
import dev.blockagentspaces.world.WorkspaceBuilder;
import dev.blockagentspaces.network.ConversationPayloads;
import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.GraphNode;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import java.util.List;
import java.util.Optional;

/** Makes workspace villagers a stable, server-side entry point into agent inspection. */
public final class AgentInteractionHandler {
    private AgentInteractionHandler() { }
    public static void register(WorldState state, WorkspaceBuilder builder, WorkspaceAutoRefresher autoRefresher) {
        AgentCommunicationService communication = new AgentCommunicationService(state);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            // A graph node belongs to this player's observatory only. Resolve it
            // before generic entities so an interaction display is never treated
            // as an NPC, then open a relevant existing notebook when possible.
            Optional<GraphNode> graphNode = builder.graphNodeFor(serverPlayer, entity, state);
            if (graphNode.isPresent()) return inspectGraphNode(serverPlayer, graphNode.get(), state, builder, autoRefresher, communication);
            return WorkspaceBuilder.agentIdFor(entity.getUUID()).flatMap(communication::findAgent).<InteractionResult>map(agent -> {
                if (builder.focusInstallation(serverPlayer, agent.id())) autoRefresher.requestReconciliation();
                openNotebook(serverPlayer, communication, agent);
                return InteractionResult.SUCCESS_SERVER;
            }).orElse(InteractionResult.PASS);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return InteractionResult.PASS;
            BlockPos position = hit.getBlockPos();
            if (!builder.isTicketBoard(serverLevel, position)) return InteractionResult.PASS;
            return state.agents().stream().filter(agent -> agent.id().equalsIgnoreCase("goat") || agent.displayName().equalsIgnoreCase("goat")).findFirst().<InteractionResult>map(goat -> {
                if (builder.focusInstallation(serverPlayer, goat.id())) autoRefresher.requestReconciliation();
                openNotebook(serverPlayer, communication, goat);
                return InteractionResult.SUCCESS_SERVER;
            }).orElse(InteractionResult.PASS);
        });
    }

    private static InteractionResult inspectGraphNode(ServerPlayer player, GraphNode node, WorldState state,
                                                       WorkspaceBuilder builder, WorkspaceAutoRefresher autoRefresher,
                                                       AgentCommunicationService communication) {
        Optional<Agent> linkedAgent = state.agents().stream()
            .filter(agent -> agent.ticketId().equalsIgnoreCase(node.id())
                || agent.taskId().equalsIgnoreCase(node.id())
                || agent.graphFocus().stream().anyMatch(focus -> focus.equalsIgnoreCase(node.id())))
            .sorted(java.util.Comparator.comparing(Agent::id))
            .findFirst()
            .or(() -> isTicketOrReview(node) ? goat(state) : Optional.empty());
        if (linkedAgent.isPresent()) {
            Agent agent = linkedAgent.get();
            if (builder.focusInstallation(player, agent.id())) autoRefresher.requestReconciliation();
            openNotebook(player, communication, agent);
            return InteractionResult.SUCCESS_SERVER;
        }
        long relationships = state.edges().stream()
            .filter(edge -> edge.sourceId().equals(node.id()) || edge.targetId().equals(node.id())).count();
        player.sendSystemMessage(Component.literal("Graph node: " + node.label() + " [" + node.type() + "] • "
            + relationships + (relationships == 1 ? " relationship" : " relationships") + "."));
        return InteractionResult.SUCCESS_SERVER;
    }

    private static Optional<Agent> goat(WorldState state) {
        return state.agents().stream().filter(agent -> agent.id().equalsIgnoreCase("goat") || agent.displayName().equalsIgnoreCase("goat")).findFirst();
    }

    private static boolean isTicketOrReview(GraphNode node) {
        String type = node.type().toLowerCase(java.util.Locale.ROOT);
        return type.contains("ticket") || type.contains("review");
    }

    /** Sends a fully labelled, server-authoritative transcript to one player. */
    public static void openNotebook(ServerPlayer player, AgentCommunicationService communication, dev.blockagentspaces.model.Agent agent) {
        List<ConversationPayloads.Entry> conversation = communication.conversationFor(agent.id()).stream()
            .map(entry -> new ConversationPayloads.Entry(
                entry.direction() == AgentCommunicationService.Direction.OUTGOING ? "You" : agent.displayName(),
                entry.message().body(),
                entry.direction() == AgentCommunicationService.Direction.OUTGOING))
            .toList();
        String delivery = "Messages enter the local bridge outbox. Waiting for a configured adapter to reply.";
        ServerPlayNetworking.send(player, new ConversationPayloads.Open(agent.id(), agent.displayName(), agent.state().name(), agent.taskId(), agent.ticketId(), agent.workspace(), agent.branch(), agent.reviewStatus(), agent.acceptanceStatus(), agent.detail(), conversation, delivery));
    }
}
