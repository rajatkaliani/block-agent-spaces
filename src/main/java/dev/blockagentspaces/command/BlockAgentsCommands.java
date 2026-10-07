package dev.blockagentspaces.command;

import com.mojang.brigadier.Command;
import dev.blockagentspaces.bridge.LocalBridge;
import dev.blockagentspaces.service.WorldState;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Command-based first presentation while custom rooms and entity rendering are being built. */
public final class BlockAgentsCommands {
    private BlockAgentsCommands() { }
    public static void register(WorldState state, LocalBridge bridge) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("blockagents")
                .then(Commands.literal("onboarding").executes(c -> onboarding(c.getSource(), bridge)))
                .then(Commands.literal("status").executes(c -> status(c.getSource(), state, bridge)))
                .then(Commands.literal("agents").executes(c -> agents(c.getSource(), state)))
                .then(Commands.literal("graph").executes(c -> graph(c.getSource(), state)))
                .then(Commands.literal("seed").executes(c -> { state.seedExample(); tell(c.getSource(), "Example workspace loaded."); return Command.SINGLE_SUCCESS; }))
        ));
    }
    private static int onboarding(CommandSourceStack source, LocalBridge bridge) {
        tell(source, "Block Agent Spaces is ready. The workspace room will host NPC agents; the adjacent glass observatory will render the knowledge graph.");
        tell(source, bridge.isRunning() ? "Integration bridge: connected at localhost:8787." : "Integration bridge: unavailable; use /blockagents status for details.");
        tell(source, "Try /blockagents agents, /blockagents graph, or /blockagents seed.");
        return Command.SINGLE_SUCCESS;
    }
    private static int status(CommandSourceStack source, WorldState state, LocalBridge bridge) {
        tell(source, "Bridge " + (bridge.isRunning() ? "connected" : "offline") + " | " + state.agents().size() + " agents | " + state.tasks().size() + " tasks | " + state.nodes().size() + " nodes | " + state.edges().size() + " edges.");
        return Command.SINGLE_SUCCESS;
    }
    private static int agents(CommandSourceStack source, WorldState state) {
        if (state.agents().isEmpty()) tell(source, "No agents published. POST an agent to the local bridge or run /blockagents seed.");
        else state.agents().forEach(a -> tell(source, a.displayName() + " [" + a.state() + "] — " + a.detail() + " (task: " + a.taskId() + ")"));
        return Command.SINGLE_SUCCESS;
    }
    private static int graph(CommandSourceStack source, WorldState state) {
        tell(source, "Graph observatory: " + state.nodes().size() + " nodes and " + state.edges().size() + " glowing relationships loaded.");
        state.nodes().stream().limit(8).forEach(n -> tell(source, "• " + n.label() + " [" + n.type() + "]"));
        return Command.SINGLE_SUCCESS;
    }
    private static void tell(CommandSourceStack source, String text) { source.sendSuccess(() -> Component.literal(text), false); }
}
