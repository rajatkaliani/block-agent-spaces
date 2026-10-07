package dev.blockagentspaces.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persists only blocks placed by this mod so a later refresh never claims player construction. */
final class WorkspaceInstallationStore extends SavedData {
    private static final Codec<Installation> INSTALLATION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("dimension").forGetter(Installation::dimension),
        Codec.INT.fieldOf("originX").forGetter(Installation::originX),
        Codec.INT.fieldOf("originY").forGetter(Installation::originY),
        Codec.INT.fieldOf("originZ").forGetter(Installation::originZ),
        Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("ownedBlocks").forGetter(Installation::ownedBlocks),
        Codec.STRING.optionalFieldOf("focusedAgentId", "").forGetter(Installation::focusedAgentId)
    ).apply(instance, Installation::new));

    private static final Codec<WorkspaceInstallationStore> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.unboundedMap(Codec.STRING, INSTALLATION_CODEC).fieldOf("installations").forGetter(store -> store.installations)
    ).apply(instance, WorkspaceInstallationStore::new));

    private static final SavedDataType<WorkspaceInstallationStore> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("block_agent_spaces", "workspace_installations"),
        WorkspaceInstallationStore::new,
        CODEC,
        DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<String, Installation> installations;

    private WorkspaceInstallationStore() { this(new HashMap<>()); }
    private WorkspaceInstallationStore(Map<String, Installation> installations) { this.installations = new HashMap<>(installations); }

    static WorkspaceInstallationStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    Optional<Snapshot> installationFor(ServerLevel level, UUID playerId) {
        Installation installation = installations.get(playerId.toString());
        if (installation == null || !installation.dimension.equals(level.dimension().identifier().toString())) return Optional.empty();
        return Optional.of(snapshot(installation));
    }

    Map<UUID, Snapshot> installationsFor(ServerLevel level) {
        Map<UUID, Snapshot> snapshots = new HashMap<>();
        installations.forEach((playerId, installation) -> {
            if (!installation.dimension.equals(level.dimension().identifier().toString())) return;
            try { snapshots.put(UUID.fromString(playerId), snapshot(installation)); }
            catch (IllegalArgumentException ignored) { }
        });
        return Map.copyOf(snapshots);
    }

    private Snapshot snapshot(Installation installation) {
        Map<BlockPos, BlockState> expected = new HashMap<>();
        installation.ownedBlocks.forEach((packedPosition, blockId) -> {
            try {
                Identifier identifier = Identifier.tryParse(blockId);
                Block block = identifier == null ? null : BuiltInRegistries.BLOCK.getValue(identifier);
                if (block != null) expected.put(BlockPos.of(Long.parseLong(packedPosition)), block.defaultBlockState());
            } catch (IllegalArgumentException ignored) {
                // A corrupt or obsolete entry is not trusted as mod ownership.
            }
        });
        return new Snapshot(new BlockPos(installation.originX, installation.originY, installation.originZ), expected, safeAgentId(installation.focusedAgentId));
    }

    boolean focusInstallation(ServerLevel level, UUID playerId, String agentId) {
        Installation installation = installations.get(playerId.toString());
        if (installation == null || !installation.dimension.equals(level.dimension().identifier().toString())) return false;
        String focus = safeAgentId(agentId);
        if (installation.focusedAgentId.equals(focus)) return true;
        installations.put(playerId.toString(), installation.withFocusedAgentId(focus));
        setDirty();
        return true;
    }

    boolean clearFocus(ServerLevel level, UUID playerId) {
        return focusInstallation(level, playerId, "");
    }

    void saveInstallation(ServerLevel level, UUID playerId, BlockPos origin, Map<BlockPos, BlockState> expected, String focusedAgentId) {
        Map<String, String> ownedBlocks = new HashMap<>();
        expected.forEach((position, state) -> ownedBlocks.put(Long.toString(position.asLong()), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        installations.put(playerId.toString(), new Installation(level.dimension().identifier().toString(), origin.getX(), origin.getY(), origin.getZ(), ownedBlocks, safeAgentId(focusedAgentId)));
        setDirty();
    }

    private static String safeAgentId(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[^A-Za-z0-9._:/-]", "");
        return cleaned.length() <= 128 ? cleaned : cleaned.substring(0, 128);
    }

    record Snapshot(BlockPos origin, Map<BlockPos, BlockState> expected, String focusedAgentId) { }

    private record Installation(String dimension, int originX, int originY, int originZ, Map<String, String> ownedBlocks, String focusedAgentId) {
        Installation withFocusedAgentId(String agentId) { return new Installation(dimension, originX, originY, originZ, ownedBlocks, agentId); }
    }
}
