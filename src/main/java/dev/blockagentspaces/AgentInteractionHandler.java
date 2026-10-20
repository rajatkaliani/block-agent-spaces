package dev.blockagentspaces;

import dev.blockagentspaces.service.AgentCommunicationService;
import dev.blockagentspaces.service.WorkspaceAutoRefresher;
import dev.blockagentspaces.service.WorldState;
import dev.blockagentspaces.world.WorkspaceBuilder;
import dev.blockagentspaces.network.ConversationPayloads;
import dev.blockagentspaces.graph.GraphSnapshot;
import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.GraphNode;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
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
            // Resolve this player's graph display before generic NPCs.
            Optional<GraphNode> graphNode = builder.graphNodeFor(serverPlayer, entity, state);
            if (graphNode.isPresent()) {
                ServerPlayNetworking.send(serverPlayer, GraphSnapshot.from(state, graphNode.get().id()));
                return InteractionResult.SUCCESS_SERVER;
            }
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

    /** Sends a fully labelled, server-authoritative transcript to one player. */
    public static void openNotebook(ServerPlayer player, AgentCommunicationService communication, dev.blockagentspaces.model.Agent agent) {
        ServerPlayNetworking.send(player, notebookSnapshot(communication, agent));
    }

    public static ConversationPayloads.Open notebookSnapshot(AgentCommunicationService communication, dev.blockagentspaces.model.Agent agent) {
        List<ConversationPayloads.Entry> conversation = communication.conversationFor(agent.id()).stream()
            .map(entry -> new ConversationPayloads.Entry(
                entry.direction() == AgentCommunicationService.Direction.OUTGOING ? "You" : limit(agent.displayName(), 128),
                limit(entry.message().body(), 4000),
                entry.direction() == AgentCommunicationService.Direction.OUTGOING))
            .toList();
        String delivery = communication.notebookStatus(agent.id());
        return new ConversationPayloads.Open(agent.id(), limit(agent.displayName(), 128), agent.state().name(), limit(agent.taskId(), 128), limit(agent.ticketId(), 128), limit(agent.workspace(), 128), limit(agent.branch(), 128), limit(agent.reviewStatus(), 64), limit(agent.acceptanceStatus(), 64), limit(agent.detail(), 512), conversation, delivery);
    }
    private static String limit(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
}
