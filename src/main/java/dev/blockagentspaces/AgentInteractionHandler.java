package dev.blockagentspaces;

import dev.blockagentspaces.service.AgentCommunicationService;
import dev.blockagentspaces.service.WorkspaceAutoRefresher;
import dev.blockagentspaces.service.WorldState;
import dev.blockagentspaces.world.WorkspaceBuilder;
import dev.blockagentspaces.network.ConversationPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/** Makes workspace villagers a stable, server-side entry point into agent inspection. */
public final class AgentInteractionHandler {
    private AgentInteractionHandler() { }
    public static void register(WorldState state, WorkspaceBuilder builder, WorkspaceAutoRefresher autoRefresher) {
        AgentCommunicationService communication = new AgentCommunicationService(state);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
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

    private static void openNotebook(ServerPlayer player, AgentCommunicationService communication, dev.blockagentspaces.model.Agent agent) {
        String recent = communication.recentMessageFor(agent.id()).map(message -> message.from() + ": " + message.body()).orElse("No recent conversation.");
        ServerPlayNetworking.send(player, new ConversationPayloads.Open(agent.id(), agent.displayName(), agent.state().name(), agent.taskId(), agent.ticketId(), agent.workspace(), agent.branch(), agent.reviewStatus(), agent.acceptanceStatus(), agent.detail(), recent));
    }
}
