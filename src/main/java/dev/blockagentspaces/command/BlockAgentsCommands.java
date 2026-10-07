package dev.blockagentspaces.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.blockagentspaces.bridge.LocalBridge;
import dev.blockagentspaces.service.WorldState;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.blockagentspaces.world.WorkspaceBuilder;
import dev.blockagentspaces.service.AgentCommunicationService;
import dev.blockagentspaces.ui.AgentDashboard;

/** Command-based first presentation while custom rooms and entity rendering are being built. */
public final class BlockAgentsCommands {
    private static final WorkspaceBuilder WORKSPACE_BUILDER = new WorkspaceBuilder();
    private BlockAgentsCommands() { }
    public static void register(WorldState state, LocalBridge bridge) {
        AgentCommunicationService communication = new AgentCommunicationService(state);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("blockagents")
                .then(Commands.literal("onboarding").executes(c -> onboarding(c.getSource(), bridge)))
                .then(Commands.literal("dashboard").executes(c -> dashboard(c.getSource(), state)))
                .then(Commands.literal("start").executes(c -> start(c.getSource(), state, bridge)))
                .then(Commands.literal("build").executes(c -> build(c.getSource(), state)))
                .then(Commands.literal("refresh").executes(c -> build(c.getSource(), state)))
                .then(Commands.literal("inspect")
                    .then(Commands.argument("agent", StringArgumentType.word())
                        .executes(c -> inspect(c.getSource(), communication, StringArgumentType.getString(c, "agent")))))
                .then(Commands.literal("message")
                    .then(Commands.argument("agent", StringArgumentType.word())
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                            .executes(c -> message(c.getSource(), communication, StringArgumentType.getString(c, "agent"), StringArgumentType.getString(c, "text"))))))
                .then(Commands.literal("status").executes(c -> status(c.getSource(), state, bridge)))
                .then(Commands.literal("agents").executes(c -> agents(c.getSource(), state)))
                .then(Commands.literal("graph").executes(c -> graph(c.getSource(), state)))
                .then(Commands.literal("seed").executes(c -> { state.seedExample(); tell(c.getSource(), "Example workspace loaded."); return Command.SINGLE_SUCCESS; }))
        ));
    }
    private static int dashboard(CommandSourceStack source, WorldState state) {
        try { AgentDashboard.open(source.getPlayerOrException(), state); return Command.SINGLE_SUCCESS; }
        catch (Exception error) { tell(source, "The dashboard can only be opened by a player in a world."); return 0; }
    }
    private static int start(CommandSourceStack source, WorldState state, LocalBridge bridge) {
        if (state.agents().isEmpty()) state.seedExample();
        int built = build(source, state);
        if (built == 0) return 0;
        int opened = dashboard(source, state);
        if (opened != 0) tell(source, bridge.isRunning() ? "Onboarding complete. A local orchestrator can publish updates to localhost:8787." : "Workspace is ready, but the local bridge is offline.");
        return opened;
    }
    private static int inspect(CommandSourceStack source, AgentCommunicationService communication, String reference) {
        return communication.findAgent(reference).map(agent -> {
            tell(source, agent.displayName() + " [" + agent.state() + "] — " + agent.detail());
            tell(source, "Current task: " + (agent.taskId().isBlank() ? "none" : agent.taskId()) + " | Graph focus: " + (agent.graphFocus().isEmpty() ? "none" : String.join(", ", agent.graphFocus())));
            communication.recentMessageFor(agent.id()).ifPresentOrElse(
                message -> tell(source, "Recent message (" + message.from() + " → " + message.to() + "): " + message.body()),
                () -> tell(source, "Recent message: none."));
            return Command.SINGLE_SUCCESS;
        }).orElseGet(() -> { tell(source, "No agent named '" + reference + "' is currently published. Use /blockagents agents to list agents."); return 0; });
    }
    private static int message(CommandSourceStack source, AgentCommunicationService communication, String reference, String body) {
        AgentCommunicationService.SendResult result = communication.sendFromMinecraft(reference, body);
        if (!result.sent()) { tell(source, result.error()); return 0; }
        tell(source, "Message delivered to " + result.agent().displayName() + ". The local bridge now exposes it at /v1/messages.");
        return Command.SINGLE_SUCCESS;
    }
    private static int build(CommandSourceStack source, WorldState state) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            WorkspaceBuilder.BuildResult result = WORKSPACE_BUILDER.build(player, state);
            tell(source, result.message());
            if (result.built()) tell(source, "Refreshed " + Math.min(result.agentCount(), 4) + " agent stations, " + Math.min(result.nodeCount(), 6) + " graph nodes, and " + result.edgeCount() + " glowing links.");
            return result.built() ? Command.SINGLE_SUCCESS : 0;
        } catch (Exception error) {
            tell(source, "The workspace could not be built: " + error.getMessage());
            return 0;
        }
    }
    private static int onboarding(CommandSourceStack source, LocalBridge bridge) {
        tell(source, "Block Agent Spaces is ready. The workspace room will host NPC agents; the adjacent glass observatory will render the knowledge graph.");
        tell(source, bridge.isRunning() ? "Integration bridge: connected at localhost:8787." : "Integration bridge: unavailable; use /blockagents status for details.");
        tell(source, "Fastest start: /blockagents start. It seeds demo data if needed, builds the workspace, and opens Goat's dashboard.");
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
