package dev.blockagentspaces;

import dev.blockagentspaces.service.AgentCommunicationService;
import dev.blockagentspaces.service.WorldState;
import dev.blockagentspaces.world.WorkspaceBuilder;
import dev.blockagentspaces.network.ConversationPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/** Makes workspace villagers a stable, server-side entry point into agent inspection. */
public final class AgentInteractionHandler {
    private AgentInteractionHandler() { }
    public static void register(WorldState state) {
        AgentCommunicationService communication = new AgentCommunicationService(state);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            return WorkspaceBuilder.agentIdFor(entity.getUUID()).flatMap(communication::findAgent).<InteractionResult>map(agent -> {
                String recent = communication.recentMessageFor(agent.id()).map(message -> message.from() + ": " + message.body()).orElse("No recent conversation.");
                ServerPlayNetworking.send(serverPlayer, new ConversationPayloads.Open(agent.id(), agent.displayName(), agent.state().name(), agent.taskId(), agent.ticketId(), agent.workspace(), agent.branch(), agent.reviewStatus(), agent.acceptanceStatus(), agent.detail(), recent));
                return InteractionResult.SUCCESS_SERVER;
            }).orElse(InteractionResult.PASS);
        });
    }
}
