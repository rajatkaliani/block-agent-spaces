package dev.blockagentspaces;

import dev.blockagentspaces.bridge.LocalBridge;
import dev.blockagentspaces.command.BlockAgentsCommands;
import dev.blockagentspaces.service.WorldState;
import dev.blockagentspaces.service.AgentCommunicationService;
import dev.blockagentspaces.service.WorkspaceAutoRefresher;
import dev.blockagentspaces.service.RuntimeStatePersistence;
import dev.blockagentspaces.network.ConversationPayloads;
import dev.blockagentspaces.world.WorkspaceBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlockAgentSpacesMod implements ModInitializer {
    public static final String MOD_ID = "block_agent_spaces";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private final WorldState worldState = new WorldState();
    private final WorkspaceBuilder workspaceBuilder = new WorkspaceBuilder();
    private final WorkspaceAutoRefresher autoRefresher = new WorkspaceAutoRefresher(worldState, workspaceBuilder);
    private final RuntimeStatePersistence runtimePersistence = new RuntimeStatePersistence(worldState);
    private final LocalBridge bridge = new LocalBridge(worldState, autoRefresher::requestExternalUpdate);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(ConversationPayloads.Open.TYPE, ConversationPayloads.Open.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConversationPayloads.Send.TYPE, ConversationPayloads.Send.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConversationPayloads.Refresh.TYPE, ConversationPayloads.Refresh.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConversationPayloads.ClearFocus.TYPE, ConversationPayloads.ClearFocus.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ConversationPayloads.Send.TYPE, (payload, context) -> {
            // The local bridge has one stable player identity. Display names are
            // presentation only; leaking them into the protocol made notebook
            // messages invisible to adapters expecting minecraft-player.
            AgentCommunicationService.SendResult result = new AgentCommunicationService(worldState).sendFromMinecraft(payload.agentId(), payload.body());
            if (!result.sent()) context.player().sendSystemMessage(net.minecraft.network.chat.Component.literal(result.error()));
        });
        ServerPlayNetworking.registerGlobalReceiver(ConversationPayloads.Refresh.TYPE, (payload, context) -> {
            AgentCommunicationService communication = new AgentCommunicationService(worldState);
            communication.findAgent(payload.agentId()).ifPresent(agent -> AgentInteractionHandler.openNotebook(context.player(), communication, agent));
        });
        ServerPlayNetworking.registerGlobalReceiver(ConversationPayloads.ClearFocus.TYPE, (payload, context) -> {
            if (workspaceBuilder.clearInstallationFocus(context.player())) autoRefresher.requestReconciliation();
        });
        BlockAgentsCommands.register(worldState, bridge);
        AgentInteractionHandler.register(worldState, workspaceBuilder, autoRefresher);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            WorkspaceAutoRefresher.RefreshReport report = autoRefresher.tick(server);
            if (report.protectedInstallations() > 0) LOGGER.warn("Skipped {} Block Agent Spaces refresh(es) because player changes were protected", report.protectedInstallations());
            runtimePersistence.tick(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            boolean restored = runtimePersistence.restore(server);
            if (!restored) worldState.seedExample();
            else LOGGER.info("Restored Block Agent Spaces runtime state; waiting for the local adapter to publish fresh updates");
            try {
                bridge.start();
                worldState.setBridgeAvailable(true);
                LOGGER.info("Block Agent Spaces bridge listening on http://127.0.0.1:{}", LocalBridge.PORT);
            } catch (Exception e) {
                worldState.setBridgeAvailable(false);
                LOGGER.error("Could not start local bridge; in-game commands remain available", e);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            bridge.stop();
            worldState.setBridgeAvailable(false);
            runtimePersistence.flush(server);
        });
    }
}
