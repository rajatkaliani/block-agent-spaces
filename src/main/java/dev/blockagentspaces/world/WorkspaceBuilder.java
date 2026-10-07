package dev.blockagentspaces.world;

import dev.blockagentspaces.model.Agent;
import dev.blockagentspaces.model.GraphEdge;
import dev.blockagentspaces.model.GraphNode;
import dev.blockagentspaces.service.WorldState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Builds a deliberately small, elevated installation without replacing player blocks. */
public final class WorkspaceBuilder {
    private static final int WIDTH = 31;
    private static final int DEPTH = 11;
    private static final int HEIGHT = 7;
    private static final Map<String, Set<String>> SPAWNED_AGENTS = new HashMap<>();
    private static final Map<UUID, String> AGENT_BY_ENTITY = new HashMap<>();
    private final Map<UUID, BlockPos> installations = new HashMap<>();

    public BuildResult build(ServerPlayer player, WorldState state) {
        ServerLevel level = (ServerLevel) player.level();
        boolean refreshed = installations.containsKey(player.getUUID());
        BlockPos origin = installations.get(player.getUUID());
        if (origin == null) origin = findClearSite(level, player.blockPosition());
        if (origin == null) return new BuildResult(false, false, "No clear elevated area was found nearby. Move to open sky and try again.", 0, 0, 0);

        buildRoom(level, origin, 0, 0, 14, 8, false);
        buildRoom(level, origin, 17, 0, 29, 8, true);
        place(level, origin.offset(15, 0, 4), Blocks.LODESTONE);
        place(level, origin.offset(16, 1, 4), Blocks.GLOWSTONE);
        renderAgents(level, origin, state);
        renderGraph(level, origin, state);
        renderLegend(level, origin);
        installations.put(player.getUUID(), origin);
        return new BuildResult(true, refreshed, "Agent workspace and graph observatory " + (refreshed ? "refreshed" : "built") + " nearby.", state.agents().size(), state.nodes().size(), state.edges().size());
    }

    private BlockPos findClearSite(ServerLevel level, BlockPos around) {
        int[][] offsets = {{5, 0}, {-5, 0}, {0, 5}, {0, -5}, {10, 0}, {0, 10}};
        for (int[] offset : offsets) {
            BlockPos candidate = around.offset(offset[0], 3, offset[1]);
            if (isEmptyVolume(level, candidate)) return candidate;
        }
        return null;
    }

    private boolean isEmptyVolume(ServerLevel level, BlockPos origin) {
        for (int x = 0; x < WIDTH; x++) for (int y = 0; y < HEIGHT; y++) for (int z = 0; z < DEPTH; z++)
            if (!level.getBlockState(origin.offset(x, y, z)).isAir()) return false;
        return true;
    }

    private void buildRoom(ServerLevel level, BlockPos origin, int minX, int minZ, int maxX, int maxZ, boolean glass) {
        Block wall = glass ? Blocks.GLASS : Blocks.OAK_PLANKS;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            place(level, origin.offset(x, 0, z), Blocks.SMOOTH_STONE);
            if ((x == minX || x == maxX || z == minZ || z == maxZ)) {
                for (int y = 1; y <= 4; y++) place(level, origin.offset(x, y, z), wall);
            }
            if (x == minX || x == maxX || z == minZ || z == maxZ) place(level, origin.offset(x, 5, z), Blocks.GLOWSTONE);
        }
        // Doorway toward the central corridor.
        int doorX = glass ? minX : maxX;
        place(level, origin.offset(doorX, 1, (minZ + maxZ) / 2), Blocks.AIR);
        place(level, origin.offset(doorX, 2, (minZ + maxZ) / 2), Blocks.AIR);
    }

    private void renderAgents(ServerLevel level, BlockPos origin, WorldState state) {
        List<Agent> agents = state.agents().stream().limit(4).toList();
        if (agents.isEmpty()) return;
        String key = level.dimension() + ":" + origin.asLong();
        Set<String> spawned = SPAWNED_AGENTS.computeIfAbsent(key, unused -> new HashSet<>());
        for (int i = 0; i < agents.size(); i++) {
            Agent agent = agents.get(i);
            BlockPos station = origin.offset(3 + i * 3, 1, 4);
            place(level, station.below(), statusBlock(agent));
            place(level, station.relative(net.minecraft.core.Direction.NORTH), Blocks.LECTERN);
            if (spawned.add(agent.id())) spawnVillager(level, station, agent);
        }
    }

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

    private void renderGraph(ServerLevel level, BlockPos origin, WorldState state) {
        List<GraphNode> nodes = state.nodes().stream().limit(6).toList();
        Map<String, BlockPos> positions = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode node = nodes.get(i);
            BlockPos pos = origin.offset(19 + (i % 3) * 4, 2 + (i / 3) * 2, 2 + (i % 2) * 4);
            positions.put(node.id(), pos);
            place(level, pos, nodeBlock(node));
            place(level, pos.above(), Blocks.END_ROD);
        }
        for (GraphEdge edge : state.edges()) {
            BlockPos start = positions.get(edge.sourceId());
            BlockPos end = positions.get(edge.targetId());
            if (start != null && end != null) drawEdge(level, start, end);
        }
    }

    private void renderLegend(ServerLevel level, BlockPos origin) {
        writeSign(level, origin.offset(19, 1, 7), List.of("GRAPH LEGEND", "Purple: projects", "Blue: files", "Yellow: tasks"));
        writeSign(level, origin.offset(25, 1, 7), List.of("White: notes", "Glow rods: links", "Right-click agents", "for live context"));
    }

    private void writeSign(ServerLevel level, BlockPos pos, List<String> lines) {
        place(level, pos, Blocks.OAK_SIGN);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            List<Component> text = lines.stream().map(line -> (Component) Component.literal(line)).toList();
            sign.setText(new SignText(text, List.of(Component.empty(), Component.empty(), Component.empty(), Component.empty()), DyeColor.WHITE, true), SignTextSlot.FRONT);
            sign.setChanged();
        }
    }

    private Block nodeBlock(GraphNode node) {
        return switch (node.type().toLowerCase()) {
            case "task" -> Blocks.CONCRETE.yellow();
            case "file" -> Blocks.CONCRETE.blue();
            case "project" -> Blocks.CONCRETE.purple();
            default -> Blocks.SEA_LANTERN;
        };
    }

    private void drawEdge(ServerLevel level, BlockPos start, BlockPos end) {
        int steps = Math.max(Math.max(Math.abs(end.getX() - start.getX()), Math.abs(end.getY() - start.getY())), Math.abs(end.getZ() - start.getZ()));
        for (int i = 1; i < steps; i++) {
            double fraction = i / (double) steps;
            BlockPos pos = new BlockPos((int) Math.round(start.getX() + (end.getX() - start.getX()) * fraction), (int) Math.round(start.getY() + (end.getY() - start.getY()) * fraction), (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * fraction));
            if (level.getBlockState(pos).isAir()) place(level, pos, Blocks.END_ROD);
        }
    }

    private void place(ServerLevel level, BlockPos pos, Block block) { level.setBlock(pos, block.defaultBlockState(), 3); }
    public static Optional<String> agentIdFor(UUID entityId) { return Optional.ofNullable(AGENT_BY_ENTITY.get(entityId)); }
    public record BuildResult(boolean built, boolean refreshed, String message, int agentCount, int nodeCount, int edgeCount) { }
}
