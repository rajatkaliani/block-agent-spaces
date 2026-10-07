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
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
    private static final Map<UUID, String> AGENT_BY_ENTITY = new HashMap<>();

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
        renderTeamLog(level, origin, state);
        renderAgents(level, origin, state);
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
            renderTeamLog(level, origin, state);
            renderAgents(level, origin, state);
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
        buildRoom(target, origin, 17, 0, 30, 8, true);
        buildEntry(target, origin);
        put(target, origin.offset(15, 0, 4), Blocks.LODESTONE);
        put(target, origin.offset(16, 1, 4), Blocks.GLOWSTONE);
        renderGoatControlPoint(target, origin, state);
        renderAgents(target, origin, state);
        renderGraph(target, origin, state, focusedAgentId);
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

    private void renderGraph(Map<BlockPos, BlockState> target, BlockPos origin, WorldState state, String focusedAgentId) {
        Optional<Agent> focusedAgent = state.agents().stream().filter(agent -> agent.id().equals(focusedAgentId)).findFirst();
        GraphFocusResolver.Focus focus = focusedAgent.map(agent -> GraphFocusResolver.resolve(agent, state.nodes(), state.edges())).orElse(GraphFocusResolver.Focus.empty());
        boolean dimUnrelated = focusedAgent.isPresent() && focus.hasNodes();
        Map<String, BlockPos> positions = new HashMap<>();
        for (GraphLayout.PlacedNode placed : GraphLayout.arrange(state.nodes(), focus.nodeIds())) {
            GraphNode node = placed.node();
            BlockPos pos = origin.offset(placed.xOffset(), placed.yOffset(), placed.zOffset());
            positions.put(node.id(), pos);
            put(target, pos, nodeBlock(node, !dimUnrelated || focus.includesNode(node.id())));
            put(target, pos.above(), Blocks.END_ROD);
        }
        for (GraphEdge edge : state.edges().stream().sorted(Comparator.comparing(GraphEdge::id)).toList()) {
            BlockPos start = positions.get(edge.sourceId());
            BlockPos end = positions.get(edge.targetId());
            if (start != null && end != null) drawEdge(target, start, end, !dimUnrelated || focus.includesEdge(edge.id()));
        }
    }

    private void renderLegend(Map<BlockPos, BlockState> target, BlockPos origin) {
        put(target, origin.offset(19, 1, 7), Blocks.OAK_SIGN);
        put(target, origin.offset(25, 1, 7), Blocks.OAK_SIGN);
    }

    private void drawEdge(Map<BlockPos, BlockState> target, BlockPos start, BlockPos end, boolean highlighted) {
        int steps = Math.max(Math.max(Math.abs(end.getX() - start.getX()), Math.abs(end.getY() - start.getY())), Math.abs(end.getZ() - start.getZ()));
        for (int i = 1; i < steps; i++) {
            double fraction = i / (double) steps;
            BlockPos pos = new BlockPos((int) Math.round(start.getX() + (end.getX() - start.getX()) * fraction), (int) Math.round(start.getY() + (end.getY() - start.getY()) * fraction), (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * fraction));
            if (target.getOrDefault(pos, Blocks.AIR.defaultBlockState()).isAir()) put(target, pos, highlighted ? Blocks.END_ROD : Blocks.TINTED_GLASS);
        }
    }

    private void apply(ServerLevel level, Map<BlockPos, BlockState> target) {
        target.forEach((position, state) -> {
            if (!level.getBlockState(position).equals(state)) level.setBlock(position, state, 3);
        });
    }

    private void renderSigns(ServerLevel level, BlockPos origin, WorldState state) {
        writeSign(level, origin.offset(12, 1, 10), List.of("BLOCK AGENT", "SPACES", "Enter the", "workspace"));
        writeSign(level, origin.offset(18, 1, 10), List.of("RIGHT-CLICK", "an agent", "to open its", "notebook"));
        writeSign(level, origin.offset(15, 1, 6), List.of("GOAT CONTROL", "Lead agent", "Tickets + merges", "Right-click Goat"));
        String[] status = state.presentationStatus().split(" • ", 2);
        writeSign(level, origin.offset(15, 1, 7), List.of("SPACE STATUS", status[0], status.length > 1 ? status[1] : "", "Auto-refresh on"));
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

    private Block nodeBlock(GraphNode node, boolean highlighted) {
        if (!highlighted) return switch (node.type().toLowerCase()) {
            case "task", "ticket" -> Blocks.STAINED_GLASS.yellow();
            case "file" -> Blocks.STAINED_GLASS.blue();
            case "project" -> Blocks.STAINED_GLASS.purple();
            default -> Blocks.TINTED_GLASS;
        };
        return switch (node.type().toLowerCase()) {
            case "task", "ticket" -> Blocks.CONCRETE.yellow();
            case "file" -> Blocks.CONCRETE.blue();
            case "project" -> Blocks.CONCRETE.purple();
            default -> Blocks.SEA_LANTERN;
        };
    }

    private static void put(Map<BlockPos, BlockState> target, BlockPos pos, Block block) { target.put(pos, block.defaultBlockState()); }
    private static String describe(BlockPos position) { return position.getX() + ", " + position.getY() + ", " + position.getZ(); }
    private static String selectedAgentId(String candidate, WorldState state) {
        if (candidate == null || candidate.isBlank()) return "";
        return state.agents().stream().anyMatch(agent -> agent.id().equals(candidate)) ? candidate : "";
    }
    public static Optional<String> agentIdFor(UUID entityId) { return Optional.ofNullable(AGENT_BY_ENTITY.get(entityId)); }
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
