package dev.blockagentspaces;

import dev.blockagentspaces.bridge.LocalBridge;
import dev.blockagentspaces.command.BlockAgentsCommands;
import dev.blockagentspaces.service.WorldState;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlockAgentSpacesMod implements ModInitializer {
    public static final String MOD_ID = "block_agent_spaces";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private final WorldState worldState = new WorldState();
    private final LocalBridge bridge = new LocalBridge(worldState);

    @Override
    public void onInitialize() {
        BlockAgentsCommands.register(worldState, bridge);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            worldState.seedExample();
            try {
                bridge.start();
                LOGGER.info("Block Agent Spaces bridge listening on http://127.0.0.1:{}", LocalBridge.PORT);
            } catch (Exception e) {
                LOGGER.error("Could not start local bridge; in-game commands remain available", e);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> bridge.stop());
    }
}
