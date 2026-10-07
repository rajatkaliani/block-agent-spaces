package dev.blockagentspaces.world;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.GraphEdge;
import dev.blockagentspaces.model.GraphNode;
import dev.blockagentspaces.graph.GraphFocusResolver;
import dev.blockagentspaces.graph.GraphLayout;
import dev.blockagentspaces.service.WorldState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Builds a player-centered installation only after the whole placement plan has been proven safe. */
public final class WorkspaceBuilder {
    private static final int HEIGHT = FlatPatchPlanner.CLEARANCE_HEIGHT;
    private static final int TICKET_BOARD_Z = 7;
    private static final int TICKET_BOARD_BACKING_Z = 8;
    private static final int TICKET_BOARD_MIN_X = 1;
    private static final int TICKET_BOARD_COLUMN_SPACING = 3;
    private static final int TICKET_BOARD_HEADER_Y = 4;
    private static final Map<UUID, String> AGENT_BY_ENTITY = new HashMap<>();
    private static final String GRAPH_NODE_TAG_PREFIX = "block_agent_spaces:graph_node:";
    private static final String GRAPH_EDGE_TAG_PREFIX = "block_agent_spaces:graph_edge:";

    public BuildResult build(ServerPlayer player, WorldState state) {
        ServerLevel level = (ServerLevel) player.level();
        WorkspaceInstallationStore store = WorkspaceInstallationStore.get(level);
        Optional<WorkspaceInstallationStore.Snapshot> saved = store.installationFor(level, player.getUUID());
        boolean refreshed = saved.isPresent();
        BlockPos origin;
        Map<BlockPos, BlockState> previous;

        if (saved.isPresent()) {
            origin = saved.get().origin();
            previous = saved.get().expected();
        } else {
            FlatPatchPlanner.Result plan = FlatPatchPlanner.plan(new MinecraftSurface(level), player.getBlockX(), player.getBlockZ());
            if (!plan.isSuitable()) return new BuildResult(false, false, plan.problem(), 0, 0, 0);
            FlatPatchPlanner.Site site = plan.site();
            origin = new BlockPos(site.minX(), site.buildY(), site.minZ());
            previous = Map.of();
        }

        String focusedAgentId = selectedAgentId(saved.map(WorkspaceInstallationStore.Snapshot::focusedAgentId).orElse(""), state);
        Map<BlockPos, BlockState> target = createLayout(origin, state, focusedAgentId);
        String conflict = ownershipConflict(level, target, previous);
        if (conflict != null) return new BuildResult(false, refreshed, conflict, 0, 0, 0);

        apply(level, target);
        renderSigns(level, origin, state);
        renderTicketBoard(level, origin, state);
        renderTeamLog(level, origin, state);
        renderAgents(level, origin, state);
        renderGraphEntities(level, origin, state, focusedAgentId);
        store.saveInstallation(level, player.getUUID(), origin, target, focusedAgentId);

        String message = refreshed
            ? "Block Agent Spaces refreshed at its existing entrance."
            : "Block Agent Spaces built around this flat patch. Enter through the oak gateway to meet Goat.";
        return new BuildResult(true, refreshed, message, state.agents().size(), state.nodes().size(), state.edges().size());
    }

    /** Called from the server tick after bridge changes have settled; it never claims player-edited blocks. */
    public RefreshResult refreshInstalled(ServerLevel level, WorldState state) {
        WorkspaceInstallationStore store = WorkspaceInstallationStore.get(level);
        int refreshed = 0;
        int protectedFromOverwrite = 0;
        for (Map.Entry<UUID, WorkspaceInstallationStore.Snapshot> entry : store.installationsFor(level).entrySet()) {
            BlockPos origin = entry.getValue().origin();
            String focusedAgentId = selectedAgentId(entry.getValue().focusedAgentId(), state);
            Map<BlockPos, BlockState> target = createLayout(origin, state, focusedAgentId);
            if (ownershipConflict(level, target, entry.getValue().expected()) != null) {
                protectedFromOverwrite++;
                continue;
            }
            apply(level, target);
            renderSigns(level, origin, state);
            renderTicketBoard(level, origin, state);
            renderTeamLog(level, origin, state);
            renderAgents(level, origin, state);
            renderGraphEntities(level, origin, state, focusedAgentId);
            store.saveInstallation(level, entry.getKey(), origin, target, focusedAgentId);
            refreshed++;
        }
        return new RefreshResult(refreshed, protectedFromOverwrite);
    }

    /** The first build needs air. Later rebuilds may only change blocks that this installation recorded. */
    private String ownershipConflict(ServerLevel level, Map<BlockPos, BlockState> target, Map<BlockPos, BlockState> previous) {
        for (Map.Entry<BlockPos, BlockState> entry : target.entrySet()) {
            BlockState actual = level.getBlockState(entry.getKey());
            BlockState owned = previous.get(entry.getKey());
            boolean permitted = ManagedBlockOwnership.mayReplace(owned != null, owned != null && actual.equals(owned), actual.isAir());
            if (!permitted) return owned == null
                ? "The planned installation overlaps a player block at " + describe(entry.getKey()) + ". Move to a clear flat patch; nothing was changed."
                : "A player change was found at " + describe(entry.getKey()) + ". Refresh stopped without replacing it.";
        }
        return null;
    }

    /** Stores focus before a later server-tick reconciliation; this method never writes a block. */
    public boolean focusInstallation(ServerPlayer player, String agentId) {
        if (!(player.level() instanceof ServerLevel level)) return false;
        return WorkspaceInstallationStore.get(level).focusInstallation(level, player.getUUID(), agentId);
    }

    /** Clears only this player's persisted observatory selection. */
    public boolean clearInstallationFocus(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return false;
        return WorkspaceInstallationStore.get(level).clearFocus(level, player.getUUID());
    }

    private Map<BlockPos, BlockState> createLayout(BlockPos origin, WorldState state, String focusedAgentId) {
        Map<BlockPos, BlockState> target = new HashMap<>();
        // Record the full clear envelope. It lets future rebuilds distinguish our empty space from player changes.
        for (int x = 0; x < FlatPatchPlanner.WIDTH; x++) for (int y = 0; y < HEIGHT; y++) for (int z = 0; z < FlatPatchPlanner.DEPTH; z++)
            target.put(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());

        buildRoom(target, origin, 0, 0, 14, 8, false);
        buildObservatory(target, origin);
        buildEntry(target, origin);
        put(target, origin.offset(15, 0, 4), Blocks.LODESTONE);
        put(target, origin.offset(16, 1, 4), Blocks.GLOWSTONE);
        renderGoatControlPoint(target, origin, state);
        renderAgents(target, origin, state);
        renderTicketBoard(target, origin, state);
        renderLegend(target, origin);
        put(target, origin.offset(12, 1, 2), Blocks.LECTERN);
        put(target, origin.offset(15, 1, 7), Blocks.OAK_SIGN);
        return target;
    }

    private void buildRoom(Map<BlockPos, BlockState> target, BlockPos origin, int minX, int minZ, int maxX, int maxZ, boolean glass) {
        Block wall = glass ? Blocks.GLASS : Blocks.OAK_PLANKS;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            put(target, origin.offset(x, 0, z), Blocks.SMOOTH_STONE);
            if (x == minX || x == maxX || z == minZ || z == maxZ) {
                for (int y = 1; y <= 4; y++) put(target, origin.offset(x, y, z), wall);
                put(target, origin.offset(x, 5, z), Blocks.GLOWSTONE);
            }
        }
        int doorX = glass ? minX : maxX;
        put(target, origin.offset(doorX, 1, (minZ + maxZ) / 2), Blocks.AIR);
        put(target, origin.offset(doorX, 2, (minZ + maxZ) / 2), Blocks.AIR);
    }

    /** A deliberately dark shell makes the floating graph, rather than Minecraft blocks, the focal point. */
    private void buildObservatory(Map<BlockPos, BlockState> target, BlockPos origin) {
        int minX = 17, maxX = 30, minZ = 0, maxZ = 8;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            put(target, origin.offset(x, 0, z), Blocks.POLISHED_DEEPSLATE);
            boolean boundary = x == minX || x == maxX || z == minZ || z == maxZ;
            if (boundary) for (int y = 1; y <= 5; y++) {
                // The entrance facade remains a dark glass viewing wall; the other sides disappear into black.
                put(target, origin.offset(x, y, z), x == minX ? Blocks.STAINED_GLASS.black() : Blocks.CONCRETE.black());
            }
            put(target, origin.offset(x, 6, z), Blocks.CONCRETE.black());
        }
        int doorZ = (minZ + maxZ) / 2;
        put(target, origin.offset(minX, 1, doorZ), Blocks.AIR);
        put(target, origin.offset(minX, 2, doorZ), Blocks.AIR);
        put(target, origin.offset(minX, 3, doorZ), Blocks.AIR);
        // A thin low-light frame helps players find the observatory without competing with the graph.
        put(target, origin.offset(minX + 1, 1, doorZ - 1), Blocks.SOUL_LANTERN);
        put(target, origin.offset(minX + 1, 1, doorZ + 1), Blocks.SOUL_LANTERN);
    }

    private void buildEntry(Map<BlockPos, BlockState> target, BlockPos origin) {
        for (int x = 0; x < FlatPatchPlanner.WIDTH; x++) {
            put(target, origin.offset(x, 0, 9), Blocks.SMOOTH_STONE);
            put(target, origin.offset(x, 0, 10), Blocks.SMOOTH_STONE);
            if (x < 14 || x > 16) put(target, origin.offset(x, 1, 10), Blocks.OAK_FENCE);
        }
        for (int y = 1; y <= 3; y++) {
            put(target, origin.offset(14, y, 10), Blocks.OAK_LOG);
            put(target, origin.offset(16, y, 10), Blocks.OAK_LOG);
        }
        put(target, origin.offset(15, 3, 10), Blocks.GLOWSTONE);
        put(target, origin.offset(12, 1, 10), Blocks.OAK_SIGN);
        put(target, origin.offset(18, 1, 10), Blocks.OAK_SIGN);
    }

    private void renderGoatControlPoint(Map<BlockPos, BlockState> target, BlockPos origin, WorldState state) {
        put(target, origin.offset(15, 0, 3), Blocks.WOOL.purple());
        put(target, origin.offset(15, 1, 2), Blocks.LECTERN);
        put(target, origin.offset(15, 1, 6), Blocks.OAK_SIGN);
        state.agents().stream().filter(WorkspaceBuilder::isGoat).findFirst().ifPresent(agent -> put(target, origin.offset(15, 0, 3), statusBlock(agent)));
    }

    private void renderAgents(Map<BlockPos, BlockState> target, BlockPos origin, WorldState state) {
        List<Agent> agents = state.agents().stream().filter(agent -> !isGoat(agent)).limit(4).toList();
        for (int i = 0; i < agents.size(); i++) {
            Agent agent = agents.get(i);
            BlockPos station = origin.offset(3 + i * 3, 1, 4);
            put(target, station.below(), statusBlock(agent));
            put(target, station.relative(Direction.NORTH), Blocks.LECTERN);
        }
    }

    private void renderAgents(ServerLevel level, BlockPos origin, WorldState state) {
        Map<String, AgentStation> desired = new HashMap<>();
        state.agents().stream().filter(WorkspaceBuilder::isGoat).findFirst().ifPresent(agent -> desired.put(agent.id(), new AgentStation(agent, origin.offset(15, 1, 3))));
        List<Agent> agents = state.agents().stream().filter(agent -> !isGoat(agent)).limit(4).toList();
        for (int i = 0; i < agents.size(); i++) {
            Agent agent = agents.get(i);
            desired.put(agent.id(), new AgentStation(agent, origin.offset(3 + i * 3, 1, 4)));
        }
        Map<String, Villager> existing = managedVillagers(level, origin, desired.keySet());
        desired.forEach((agentId, station) -> {
            Villager villager = existing.get(agentId);
            if (villager == null) {
                spawnVillager(level, station.position(), station.agent());
            } else {
                updateVillager(villager, station.position(), station.agent());
                AGENT_BY_ENTITY.put(villager.getUUID(), agentId);
            }
        });
    }

    private void renderTicketBoard(Map<BlockPos, BlockState> target, BlockPos origin, WorldState state) {
        TicketBoardPlanner.Board board = TicketBoardPlanner.plan(state.tasks());
        for (int column = 0; column < board.lanes().length; column++) {
            int x = TICKET_BOARD_MIN_X + column * TICKET_BOARD_COLUMN_SPACING;
            for (int y = 1; y <= TICKET_BOARD_HEADER_Y; y++) {
                put(target, origin.offset(x, y, TICKET_BOARD_BACKING_Z), Blocks.DARK_OAK_PLANKS);
                target.put(origin.offset(x, y, TICKET_BOARD_Z), Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.SOUTH));
            }
        }
    }

    private void renderTicketBoard(ServerLevel level, BlockPos origin, WorldState state) {
        TicketBoardPlanner.Board board = TicketBoardPlanner.plan(state.tasks());
        for (int column = 0; column < board.lanes().length; column++) {
            TicketBoardPlanner.Lane lane = board.lanes()[column];
            int x = TICKET_BOARD_MIN_X + column * TICKET_BOARD_COLUMN_SPACING;
            List<TicketBoardPlanner.Card> cards = board.cards(lane);
            writeSign(level, origin.offset(x, TICKET_BOARD_HEADER_Y, TICKET_BOARD_Z), List.of(lane.label(), cards.size() + " ticket" + (cards.size() == 1 ? "" : "s"), "ADAPTER", "REPORTED"));
            for (int row = 0; row < TicketBoardPlanner.MAX_CARDS_PER_COLUMN; row++) {
                List<String> lines = row < cards.size() ? ticketLines(cards.get(row)) : List.of("—", "", "", "");
                writeSign(level, origin.offset(x, TICKET_BOARD_HEADER_Y - row - 1, TICKET_BOARD_Z), lines);
            }
        }
    }

    private static List<String> ticketLines(TicketBoardPlanner.Card card) {
        return List.of(abbreviate(card.ticketId(), 15), abbreviate(card.title(), 15), abbreviate(card.reportedStatus().toUpperCase(Locale.ROOT), 15), "");
    }

    /** Finds tagged mod villagers, removes stale/duplicate ones, and leaves ordinary villagers untouched. */
    private Map<String, Villager> managedVillagers(ServerLevel level, BlockPos origin, Set<String> desiredAgentIds) {
        AABB bounds = AABB.encapsulatingFullBlocks(origin, origin.offset(FlatPatchPlanner.WIDTH - 1, HEIGHT, FlatPatchPlanner.DEPTH - 1)).inflate(1);
        Map<String, Villager> found = new HashMap<>();
        for (Villager villager : level.getEntities(EntityTypeTest.forClass(Villager.class), bounds, candidate -> true)) {
            Optional<String> agentId = managedAgentId(villager);
            if (agentId.isEmpty()) continue;
            if (!desiredAgentIds.contains(agentId.get()) || found.putIfAbsent(agentId.get(), villager) != null) {
                AGENT_BY_ENTITY.remove(villager.getUUID());
                villager.discard();
            }
        }
        return found;
    }

    private static Optional<String> managedAgentId(Villager villager) {
        return villager.entityTags().stream().filter(tag -> tag.startsWith("block_agent_spaces:agent:")).map(tag -> tag.substring("block_agent_spaces:agent:".length())).findFirst();
    }

    private static boolean isGoat(Agent agent) { return agent.id().equalsIgnoreCase("goat") || agent.displayName().equalsIgnoreCase("goat"); }

    private Block statusBlock(Agent agent) {
        return switch (agent.state()) {
            case WORKING -> Blocks.WOOL.lime();
            case BLOCKED -> Blocks.WOOL.red();
            case REVIEWING -> Blocks.WOOL.yellow();
            case COMPLETE -> Blocks.WOOL.lightBlue();
            case IDLE -> Blocks.WOOL.gray();
        };
    }

    private void spawnVillager(ServerLevel level, BlockPos station, Agent agent) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("villager"));
        Villager villager = type == null ? null : (Villager) type.create(level, EntitySpawnReason.COMMAND);
        if (villager == null) return;
        updateVillager(villager, station, agent);
        villager.addTag("block_agent_spaces:agent:" + agent.id());
        level.addFreshEntity(villager);
        AGENT_BY_ENTITY.put(villager.getUUID(), agent.id());
    }

    private void updateVillager(Villager villager, BlockPos station, Agent agent) {
        villager.setPos(station.getX() + 0.5, station.getY(), station.getZ() + 0.5);
        villager.setCustomName(Component.literal(agent.displayName() + " • " + agent.state() + " • " + agent.taskId()));
        villager.setCustomNameVisible(true);
        villager.setNoAi(true);
        villager.setPersistenceRequired();
    }

    /**
     * Renders the knowledge graph as tagged display entities, never as part of the protected
     * player-edited block structure. Node labels and their colored items float in a dark room;
     * edge dots use glowing ink items. Minecraft does not expose a stable public API for a
     * stretched line display in this version, so dotted luminous links are intentional and keep
     * refreshes/restarts simple and safe.
     */
    private void renderGraphEntities(ServerLevel level, BlockPos origin, WorldState state, String focusedAgentId) {
        Optional<Agent> focusedAgent = state.agents().stream().filter(agent -> agent.id().equals(focusedAgentId)).findFirst();
        GraphFocusResolver.Focus focus = focusedAgent.map(agent -> GraphFocusResolver.resolve(agent, state.nodes(), state.edges())).orElse(GraphFocusResolver.Focus.empty());
        boolean dimUnrelated = focusedAgent.isPresent() && focus.hasNodes();
        clearGraphEntities(level, origin);
        Map<String, BlockPos> positions = new HashMap<>();
        for (GraphLayout.PlacedNode placed : GraphLayout.arrange(state.nodes(), state.edges(), focus.nodeIds())) {
            GraphNode node = placed.node();
            BlockPos pos = origin.offset(placed.xOffset(), placed.yOffset(), placed.zOffset());
            positions.put(node.id(), pos);
            boolean highlighted = !dimUnrelated || focus.includesNode(node.id());
            spawnGraphNode(level, pos, node, highlighted);
        }
        for (GraphEdge edge : state.edges().stream().sorted(Comparator.comparing(GraphEdge::id)).toList()) {
            BlockPos start = positions.get(edge.sourceId());
            BlockPos end = positions.get(edge.targetId());
            if (start != null && end != null) spawnGraphEdge(level, start, end, edge, !dimUnrelated || focus.includesEdge(edge.id()));
        }
    }

    private void clearGraphEntities(ServerLevel level, BlockPos origin) {
        AABB bounds = AABB.encapsulatingFullBlocks(origin.offset(17, 1, 0), origin.offset(30, 6, 8)).inflate(1);
        for (Entity entity : level.getEntities(EntityTypeTest.forClass(Entity.class), bounds, candidate -> isManagedGraphEntity(candidate))) entity.discard();
    }

    private static boolean isManagedGraphEntity(Entity entity) {
        return entity.entityTags().stream().anyMatch(tag -> tag.startsWith(GRAPH_NODE_TAG_PREFIX) || tag.startsWith(GRAPH_EDGE_TAG_PREFIX));
    }

    private void spawnGraphNode(ServerLevel level, BlockPos position, GraphNode node, boolean highlighted) {
        Display.ItemDisplay icon = createEntity(level, "item_display", Display.ItemDisplay.class);
        if (icon != null) {
            icon.setPos(position.getX() + 0.5, position.getY() + 0.1, position.getZ() + 0.5);
            icon.getSlot(0).set(new ItemStack(nodeIcon(node)));
            icon.setCustomName(Component.literal(abbreviate(node.label(), 36) + "  [" + node.type() + "]"));
            icon.setCustomNameVisible(true);
            icon.setNoGravity(true);
            icon.setGlowingTag(highlighted);
            icon.addTag(GRAPH_NODE_TAG_PREFIX + node.id());
            level.addFreshEntity(icon);
        }
        // Display entities are visual-only. An invisible Interaction entity gives every node a
        // consistent click target without making villagers or the room geometry interactive.
        Interaction hitbox = createEntity(level, "interaction", Interaction.class);
        if (hitbox != null) {
            hitbox.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
            hitbox.setNoGravity(true);
            hitbox.addTag(GRAPH_NODE_TAG_PREFIX + node.id());
            level.addFreshEntity(hitbox);
        }
    }

    private void spawnGraphEdge(ServerLevel level, BlockPos start, BlockPos end, GraphEdge edge, boolean highlighted) {
        int steps = Math.max(Math.max(Math.abs(end.getX() - start.getX()), Math.abs(end.getY() - start.getY())), Math.abs(end.getZ() - start.getZ()));
        // Keep the entity count bounded while still leaving a clearly continuous-looking dotted link.
        int dots = Math.min(steps - 1, 5);
        for (int i = 1; i <= dots; i++) {
            double fraction = i / (double) (dots + 1);
            Display.ItemDisplay dot = createEntity(level, "item_display", Display.ItemDisplay.class);
            if (dot == null) return;
            dot.setPos(start.getX() + 0.5 + (end.getX() - start.getX()) * fraction, start.getY() + 0.1 + (end.getY() - start.getY()) * fraction, start.getZ() + 0.5 + (end.getZ() - start.getZ()) * fraction);
            dot.getSlot(0).set(new ItemStack(Items.GLOW_INK_SAC));
            dot.setNoGravity(true);
            dot.setGlowingTag(highlighted);
            dot.addTag(GRAPH_EDGE_TAG_PREFIX + edge.id());
            level.addFreshEntity(dot);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Entity> T createEntity(ServerLevel level, String path, Class<T> expectedType) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(path));
        Entity entity = type == null ? null : type.create(level, EntitySpawnReason.COMMAND);
        return expectedType.isInstance(entity) ? (T) entity : null;
    }

    private static net.minecraft.world.item.Item nodeIcon(GraphNode node) {
        return switch (node.type().toLowerCase(Locale.ROOT)) {
            case "task", "ticket" -> Items.GOLD_INGOT;
            case "file" -> Items.LAPIS_LAZULI;
            case "project" -> Items.AMETHYST_SHARD;
            case "review" -> Items.EMERALD;
            case "workspace" -> Items.COMPASS;
            case "history" -> Items.WRITABLE_BOOK;
            default -> Items.QUARTZ;
        };
    }

    private void renderLegend(Map<BlockPos, BlockState> target, BlockPos origin) {
        put(target, origin.offset(19, 1, 7), Blocks.OAK_SIGN);
        put(target, origin.offset(25, 1, 7), Blocks.OAK_SIGN);
    }


    private void apply(ServerLevel level, Map<BlockPos, BlockState> target) {
        target.forEach((position, state) -> {
            if (!level.getBlockState(position).equals(state)) level.setBlock(position, state, 3);
        });
    }

    private void renderSigns(ServerLevel level, BlockPos origin, WorldState state) {
        writeSign(level, origin.offset(12, 1, 10), List.of("BLOCK AGENT", "SPACES", "Enter the", "workspace"));
        writeSign(level, origin.offset(18, 1, 10), List.of("RIGHT-CLICK", "an agent", "to open its", "notebook"));
        writeSign(level, origin.offset(15, 1, 6), List.of("GOAT CONTROL", "LEAD AGENT", "TICKETS + REVIEW", "CLICK GOAT/BOARD"));
        String[] status = state.presentationStatus().split(" • ", 2);
        writeSign(level, origin.offset(15, 1, 7), List.of("SPACE STATUS", status[0], status.length > 1 ? status[1] : "", "TEXT + COLOR"));
        writeSign(level, origin.offset(19, 1, 7), List.of("GRAPH LEGEND", "Purple: projects", "Blue: files", "Yellow: tasks"));
        writeSign(level, origin.offset(25, 1, 7), List.of("White: notes", "Glow rods: links", "Glass room =", "knowledge graph"));
    }

    private void renderTeamLog(ServerLevel level, BlockPos origin, WorldState state) {
        BlockPos lecternPos = origin.offset(12, 1, 2);
        if (level.getBlockEntity(lecternPos) instanceof LecternBlockEntity lectern) {
            List<String> entries = state.messages().stream().skip(Math.max(0, state.messages().size() - 8)).map(message ->
                message.from() + " → " + message.to() + ": " + abbreviate(message.body(), 180)).toList();
            if (entries.isEmpty()) entries = List.of("No team messages yet.", "Right-click an agent to open", "its notebook and send a message.");
            List<Filterable<Component>> pages = entries.stream().map(line -> (Component) Component.literal(line)).map(Filterable::passThrough).toList();
            ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
            book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Team Conversation"), "Goat", 0, pages, false));
            lectern.setBook(book);
        }
    }

    private static String abbreviate(String text, int max) { return text.length() <= max ? text : text.substring(0, max - 1) + "…"; }

    private void writeSign(ServerLevel level, BlockPos pos, List<String> lines) {
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            List<Component> text = lines.stream().map(line -> (Component) Component.literal(line)).toList();
            sign.setText(new SignText(text, List.of(Component.empty(), Component.empty(), Component.empty(), Component.empty()), DyeColor.WHITE, true), SignTextSlot.FRONT);
            sign.setChanged();
        }
    }

    private static void put(Map<BlockPos, BlockState> target, BlockPos pos, Block block) { target.put(pos, block.defaultBlockState()); }
    private static String describe(BlockPos position) { return position.getX() + ", " + position.getY() + ", " + position.getZ(); }
    private static String selectedAgentId(String candidate, WorldState state) {
        if (candidate == null || candidate.isBlank()) return "";
        return state.agents().stream().anyMatch(agent -> agent.id().equals(candidate)) ? candidate : "";
    }
    public static Optional<String> agentIdFor(UUID entityId) { return Optional.ofNullable(AGENT_BY_ENTITY.get(entityId)); }
    /**
     * Resolves only a graph node that belongs to the clicking player's own installed observatory.
     * A graph display in another player's nearby installation is never a valid interaction target.
     */
    public Optional<GraphNode> graphNodeFor(ServerPlayer player, Entity entity, WorldState state) {
        if (!(player.level() instanceof ServerLevel level)) return Optional.empty();
        Optional<WorkspaceInstallationStore.Snapshot> installation = WorkspaceInstallationStore.get(level).installationFor(level, player.getUUID());
        if (installation.isEmpty() || !insideObservatory(installation.get().origin(), entity.blockPosition())) return Optional.empty();
        Optional<String> nodeId = entity.entityTags().stream().filter(tag -> tag.startsWith(GRAPH_NODE_TAG_PREFIX))
            .map(tag -> tag.substring(GRAPH_NODE_TAG_PREFIX.length())).findFirst();
        return nodeId.flatMap(id -> state.nodes().stream().filter(node -> node.id().equals(id)).findFirst());
    }

    private static boolean insideObservatory(BlockPos origin, BlockPos position) {
        int x = position.getX() - origin.getX();
        int y = position.getY() - origin.getY();
        int z = position.getZ() - origin.getZ();
        return x >= 17 && x <= 30 && y >= 0 && y <= 6 && z >= 0 && z <= 8;
    }
    /** Board coordinates are used only to route a player to Goat's existing notebook. */
    public boolean isTicketBoard(ServerLevel level, BlockPos position) {
        for (WorkspaceInstallationStore.Snapshot installation : WorkspaceInstallationStore.get(level).installationsFor(level).values()) {
            BlockPos origin = installation.origin();
            int relativeX = position.getX() - origin.getX();
            int relativeY = position.getY() - origin.getY();
            int relativeZ = position.getZ() - origin.getZ();
            if (relativeZ >= TICKET_BOARD_Z && relativeZ <= TICKET_BOARD_BACKING_Z
                && relativeY >= 1 && relativeY <= TICKET_BOARD_HEADER_Y
                && relativeX >= TICKET_BOARD_MIN_X && relativeX <= TICKET_BOARD_MIN_X + TICKET_BOARD_COLUMN_SPACING * (TicketBoardPlanner.Lane.values().length - 1)) return true;
        }
        return false;
    }
    public record BuildResult(boolean built, boolean refreshed, String message, int agentCount, int nodeCount, int edgeCount) { }
    public record RefreshResult(int refreshedInstallations, int protectedInstallations) { }
    private record AgentStation(Agent agent, BlockPos position) { }

    private record MinecraftSurface(ServerLevel level) implements FlatPatchPlanner.Surface {
        @Override public int groundY(int x, int z) { return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1; }
        @Override public boolean hasSafeGround(int x, int y, int z) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            return state.isSolid() && state.getFluidState().isEmpty();
        }
        @Override public boolean isClear(int x, int y, int z) { return level.getBlockState(new BlockPos(x, y, z)).isAir(); }
    }
}
