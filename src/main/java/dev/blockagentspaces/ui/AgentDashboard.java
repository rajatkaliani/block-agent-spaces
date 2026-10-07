package dev.blockagentspaces.ui;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.service.AgentCommunicationService;
import dev.blockagentspaces.service.WorldState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.*;

/** A server-side, vanilla chest interface for the current local agent workspace. */
public final class AgentDashboard {
    private AgentDashboard() { }
    public static void open(ServerPlayer player, WorldState state) {
        MenuProvider provider = new SimpleMenuProvider((id, inventory, ignored) -> new DashboardMenu(id, inventory, state), Component.literal("Block Agent Spaces • Goat's Console"));
        player.openMenu(provider);
    }

    static final class DashboardMenu extends ChestMenu {
        private final Map<Integer, Agent> agentsBySlot = new HashMap<>();
        private final AgentCommunicationService communication;

        DashboardMenu(int id, Inventory inventory, WorldState state) {
            this(id, inventory, new SimpleContainer(27), state);
        }
        private DashboardMenu(int id, Inventory inventory, SimpleContainer container, WorldState state) {
            super(MenuType.GENERIC_9x3, id, inventory, container, 3);
            communication = new AgentCommunicationService(state);
            populate(container, state);
        }
        private void populate(SimpleContainer container, WorldState state) {
            container.setItem(4, item(Items.COMPASS, "Goat's team console", List.of("Lead agent: Goat", "Tickets • review • acceptance", "Click an agent for detail")));
            List<Agent> agents = state.agents().stream().sorted(Comparator.comparing((Agent agent) -> !agent.id().equalsIgnoreCase("goat")).thenComparing(Agent::displayName)).limit(7).toList();
            int slot = 10;
            for (Agent agent : agents) {
                agentsBySlot.put(slot, agent);
                String messageSignal = communication.recentMessageFor(agent.id()).isPresent() ? "Message: recent" : "Message: none";
                container.setItem(slot++, item(Items.PAPER, agent.displayName() + " • " + agent.state(), List.of(
                    "Ticket: " + emptyAs(agent.ticketId(), agent.taskId()),
                    "Workspace: " + emptyAs(agent.workspace(), "not published"),
                    "Branch: " + emptyAs(agent.branch(), "not published"),
                    "Review: " + agent.reviewStatus() + " | Accept: " + agent.acceptanceStatus(),
                    messageSignal,
                    "Click for detail")));
            }
            container.setItem(20, item(Items.BOOK, "Send a message", List.of("Use: /blockagents message <agent> <text>", "Minecraft messages enter the local outbox")));
            container.setItem(24, item(Items.CLOCK, "Refresh workspace", List.of("Use: /blockagents refresh", "Redraws the nearby rooms from bridge state")));
        }
        @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (slot >= 0 && slot < 27) {
                Agent agent = agentsBySlot.get(slot);
                if (player instanceof ServerPlayer serverPlayer) {
                    if (agent != null) showAgent(serverPlayer, agent);
                    else if (slot == 20) serverPlayer.sendSystemMessage(Component.literal("Send a message: /blockagents message <agent> <text>"));
                    else if (slot == 24) serverPlayer.sendSystemMessage(Component.literal("Refresh rooms: /blockagents refresh"));
                    else serverPlayer.sendSystemMessage(Component.literal("Select an agent card for detail. Goat triages and accepts changes into canonical history."));
                }
                return;
            }
            super.clicked(slot, button, input, player);
        }
        private void showAgent(ServerPlayer player, Agent agent) {
            player.sendOverlayMessage(Component.literal(agent.displayName() + " [" + agent.state() + "] • " + emptyAs(agent.ticketId(), agent.taskId())));
            player.sendSystemMessage(Component.literal("Workspace: " + emptyAs(agent.workspace(), "not published") + " | Branch: " + emptyAs(agent.branch(), "not published")));
            player.sendSystemMessage(Component.literal("Review: " + agent.reviewStatus() + " | Acceptance: " + agent.acceptanceStatus()));
            player.sendSystemMessage(Component.literal(agent.detail()));
            communication.recentMessageFor(agent.id()).ifPresent(message -> player.sendSystemMessage(Component.literal("Recent message: " + message.body())));
        }
        private static ItemStack item(net.minecraft.world.item.Item item, String name, List<String> lore) {
            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            stack.set(DataComponents.LORE, new ItemLore(lore.stream().map(line -> (Component) Component.literal(line)).toList()));
            return stack;
        }
        private static String emptyAs(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    }
}
