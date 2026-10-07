package dev.blockagentspaces.world;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.GraphEdge;
import dev.blockagentspaces.model.GraphNode;
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

import java.util.*;

/** Builds a player-centered installation only after the whole placement plan has been proven safe. */
public final class WorkspaceBuilder {
    private static final int HEIGHT = FlatPatchPlanner.CLEARANCE_HEIGHT;
    private static final Map<String, Set<String>> SPAWNED_AGENTS = new HashMap<>();
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

        Map<BlockPos, BlockState> target = createLayout(origin, state);
        String conflict = ownershipConflict(level, target, previous);
        if (conflict != null) return new BuildResult(false, refreshed, conflict, 0, 0, 0);

        apply(level, target);
        renderSigns(level, origin);
        renderTeamLog(level, origin, state);
        renderAgents(level, origin, state);
        store.saveInstallation(level, player.getUUID(), origin, target);

        String message = refreshed
            ? "Block Agent Spaces refreshed at its existing entrance."
            : "Block Agent Spaces built around this flat patch. Enter through the oak gateway to meet Goat.";
        return new BuildResult(true, refreshed, message, state.agents().size(), state.nodes().size(), state.edges().size());
    }

    /** The first build needs air. Later rebuilds may only change blocks that this installation recorded. */
    private String ownershipConflict(ServerLevel level, Map<BlockPos, BlockState> target, Map<BlockPos, BlockState> previous) {
        for (Map.Entry<BlockPos, BlockState> entry : target.entrySet()) {
            BlockState actual = level.getBlockState(entry.getKey());
            BlockState owned = previous.get(entry.getKey());
            if (owned == null) {
                if (!actual.isAir()) return "The planned installation overlaps a player block at " + describe(entry.getKey()) + ". Move to a clear flat patch; nothing was changed.";
            } else if (!actual.equals(owned) && !actual.isAir()) {
                return "A player change was found at " + describe(entry.getKey()) + ". Refresh stopped without replacing it.";
            }
        }
        return null;
    }

    private Map<BlockPos, BlockState> createLayout(BlockPos origin, WorldState state) {
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
        renderGraph(target, origin, state);
        renderLegend(target, origin);
        put(target, origin.offset(12, 1, 2), Blocks.LECTERN);
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
        String key = level.dimension() + ":" + origin.asLong();
        Set<String> spawned = SPAWNED_AGENTS.computeIfAbsent(key, unused -> new HashSet<>());
        state.agents().stream().filter(WorkspaceBuilder::isGoat).findFirst().ifPresent(agent -> {
            if (spawned.add(agent.id())) spawnVillager(level, origin.offset(15, 1, 3), agent);
        });
        List<Agent> agents = state.agents().stream().filter(agent -> !isGoat(agent)).limit(4).toList();
        for (int i = 0; i < agents.size(); i++) {
            Agent agent = agents.get(i);
            if (spawned.add(agent.id())) spawnVillager(level, origin.offset(3 + i * 3, 1, 4), agent);
        }
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
        villager.setPos(station.getX() + 0.5, station.getY(), station.getZ() + 0.5);
        villager.setCustomName(Component.literal(agent.displayName() + " • " + agent.state() + " • " + agent.taskId()));
        villager.setCustomNameVisible(true);
        villager.setNoAi(true);
        villager.setPersistenceRequired();
        level.addFreshEntity(villager);
        AGENT_BY_ENTITY.put(villager.getUUID(), agent.id());
    }

    private void renderGraph(Map<BlockPos, BlockState> target, BlockPos origin, WorldState state) {
        List<GraphNode> nodes = state.nodes().stream().limit(6).toList();
        Map<String, BlockPos> positions = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode node = nodes.get(i);
            BlockPos pos = origin.offset(19 + (i % 3) * 4, 2 + (i / 3) * 2, 2 + (i % 2) * 4);
            positions.put(node.id(), pos);
            put(target, pos, nodeBlock(node));
            put(target, pos.above(), Blocks.END_ROD);
        }
        for (GraphEdge edge : state.edges()) {
            BlockPos start = positions.get(edge.sourceId());
            BlockPos end = positions.get(edge.targetId());
            if (start != null && end != null) drawEdge(target, start, end);
        }
    }

    private void renderLegend(Map<BlockPos, BlockState> target, BlockPos origin) {
        put(target, origin.offset(19, 1, 7), Blocks.OAK_SIGN);
        put(target, origin.offset(25, 1, 7), Blocks.OAK_SIGN);
    }

    private void drawEdge(Map<BlockPos, BlockState> target, BlockPos start, BlockPos end) {
        int steps = Math.max(Math.max(Math.abs(end.getX() - start.getX()), Math.abs(end.getY() - start.getY())), Math.abs(end.getZ() - start.getZ()));
        for (int i = 1; i < steps; i++) {
            double fraction = i / (double) steps;
            BlockPos pos = new BlockPos((int) Math.round(start.getX() + (end.getX() - start.getX()) * fraction), (int) Math.round(start.getY() + (end.getY() - start.getY()) * fraction), (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * fraction));
            if (target.getOrDefault(pos, Blocks.AIR.defaultBlockState()).isAir()) put(target, pos, Blocks.END_ROD);
        }
    }

    private void apply(ServerLevel level, Map<BlockPos, BlockState> target) {
        target.forEach((position, state) -> {
            if (!level.getBlockState(position).equals(state)) level.setBlock(position, state, 3);
        });
    }

    private void renderSigns(ServerLevel level, BlockPos origin) {
        writeSign(level, origin.offset(12, 1, 10), List.of("BLOCK AGENT", "SPACES", "Enter the", "workspace"));
        writeSign(level, origin.offset(18, 1, 10), List.of("RIGHT-CLICK", "an agent", "to open its", "notebook"));
        writeSign(level, origin.offset(15, 1, 6), List.of("GOAT CONTROL", "Lead agent", "Tickets + merges", "Right-click Goat"));
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

    private Block nodeBlock(GraphNode node) {
        return switch (node.type().toLowerCase()) {
            case "task", "ticket" -> Blocks.CONCRETE.yellow();
            case "file" -> Blocks.CONCRETE.blue();
            case "project" -> Blocks.CONCRETE.purple();
            default -> Blocks.SEA_LANTERN;
        };
    }

    private static void put(Map<BlockPos, BlockState> target, BlockPos pos, Block block) { target.put(pos, block.defaultBlockState()); }
    private static String describe(BlockPos position) { return position.getX() + ", " + position.getY() + ", " + position.getZ(); }
    public static Optional<String> agentIdFor(UUID entityId) { return Optional.ofNullable(AGENT_BY_ENTITY.get(entityId)); }
    public record BuildResult(boolean built, boolean refreshed, String message, int agentCount, int nodeCount, int edgeCount) { }

    private record MinecraftSurface(ServerLevel level) implements FlatPatchPlanner.Surface {
        @Override public int groundY(int x, int z) { return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1; }
        @Override public boolean hasSafeGround(int x, int y, int z) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            return state.isSolid() && state.getFluidState().isEmpty();
        }
        @Override public boolean isClear(int x, int y, int z) { return level.getBlockState(new BlockPos(x, y, z)).isAir(); }
    }
}
