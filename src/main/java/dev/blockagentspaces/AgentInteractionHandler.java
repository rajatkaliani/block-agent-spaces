package dev.blockagentspaces;

import dev.blockagentspaces.service.AgentCommunicationService;
import dev.blockagentspaces.service.WorldState;
import dev.blockagentspaces.world.WorkspaceBuilder;
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
                serverPlayer.sendOverlayMessage(Component.literal(agent.displayName() + " [" + agent.state() + "] — " + agent.taskId()));
                serverPlayer.sendSystemMessage(Component.literal(agent.detail()));
                serverPlayer.sendSystemMessage(Component.literal("Graph focus: " + (agent.graphFocus().isEmpty() ? "none" : String.join(", ", agent.graphFocus()))));
                communication.recentMessageFor(agent.id()).ifPresent(message -> serverPlayer.sendSystemMessage(Component.literal("Recent: " + message.body())));
                return InteractionResult.SUCCESS_SERVER;
            }).orElse(InteractionResult.PASS);
        });
    }
}
