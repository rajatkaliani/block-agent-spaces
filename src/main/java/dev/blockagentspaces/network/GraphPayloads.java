package dev.blockagentspaces.network;

import dev.blockagentspaces.BlockAgentSpacesMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bounded, presentation-only graph snapshots; execution remains outside the mod. */
public final class GraphPayloads {
    private GraphPayloads() { }
    public static final int MAX_NODES = 128, MAX_EDGES = 256;
    public record Node(String id, String label, String type, String agentId) { }
    public record Edge(String source, String target, String relationship) { }
    public record Open(List<Node> nodes, List<Edge> edges, String selected, String status, boolean truncated) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "open_graph"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.nodes.size());
            for (Node n : p.nodes) { buf.writeUtf(n.id, 128); buf.writeUtf(n.label, 128); buf.writeUtf(n.type, 64); buf.writeUtf(n.agentId, 128); }
            buf.writeVarInt(p.edges.size());
            for (Edge e : p.edges) { buf.writeUtf(e.source, 128); buf.writeUtf(e.target, 128); buf.writeUtf(e.relationship, 64); }
            buf.writeUtf(p.selected, 128); buf.writeUtf(p.status, 128); buf.writeBoolean(p.truncated);
        }, buf -> {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_NODES) throw new IllegalArgumentException("invalid graph node count");
            List<Node> nodes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) nodes.add(new Node(buf.readUtf(128), buf.readUtf(128), buf.readUtf(64), buf.readUtf(128)));
            count = buf.readVarInt();
            if (count < 0 || count > MAX_EDGES) throw new IllegalArgumentException("invalid graph edge count");
            List<Edge> edges = new ArrayList<>(count);
            for (int i = 0; i < count; i++) edges.add(new Edge(buf.readUtf(128), buf.readUtf(128), buf.readUtf(64)));
            return new Open(List.copyOf(nodes), List.copyOf(edges), buf.readUtf(128), buf.readUtf(128), buf.readBoolean());
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Request(String selected) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "request_graph"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((buf, p) -> buf.writeUtf(p.selected, 128), buf -> new Request(buf.readUtf(128)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Refresh(String selected) implements CustomPacketPayload {
        public static final Type<Refresh> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "refresh_graph"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Refresh> CODEC = StreamCodec.of((buf, p) -> buf.writeUtf(p.selected, 128), buf -> new Refresh(buf.readUtf(128)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Update(Open snapshot) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "update_graph"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec.of((buf, p) -> Open.CODEC.encode(buf, p.snapshot), buf -> new Update(Open.CODEC.decode(buf)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record OpenAgent(String agentId) implements CustomPacketPayload {
        public static final Type<OpenAgent> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "graph_open_agent"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenAgent> CODEC = StreamCodec.of((buf, p) -> buf.writeUtf(p.agentId, 128), buf -> new OpenAgent(buf.readUtf(128)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
