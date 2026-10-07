package dev.blockagentspaces.network;

import dev.blockagentspaces.BlockAgentSpacesMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class ConversationPayloads {
    private ConversationPayloads() { }
    /** A server-labelled notebook entry. Outgoing entries are always the player, never an agent reply. */
    public record Entry(String sender, String body, boolean outgoing) { }
    public record Open(String agentId, String name, String state, String task, String ticket, String workspace, String branch, String review, String acceptance, String detail, List<Entry> conversation, String deliveryStatus) implements CustomPacketPayload {
        private static final int MAX_CONVERSATION = 12;
        public static final Type<Open> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "open_conversation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.of((buf, p) -> {
            for (String value : new String[]{p.agentId,p.name,p.state,p.task,p.ticket,p.workspace,p.branch,p.review,p.acceptance,p.detail}) buf.writeUtf(value, 512);
            List<Entry> entries = p.conversation == null ? List.of() : p.conversation;
            buf.writeVarInt(Math.min(MAX_CONVERSATION, entries.size()));
            for (Entry entry : entries.subList(Math.max(0, entries.size() - MAX_CONVERSATION), entries.size())) {
                buf.writeUtf(entry.sender, 128); buf.writeUtf(entry.body, 512); buf.writeBoolean(entry.outgoing);
            }
            buf.writeUtf(p.deliveryStatus, 256);
        }, buf -> {
            String agentId = buf.readUtf(64), name = buf.readUtf(128), state = buf.readUtf(64), task = buf.readUtf(128), ticket = buf.readUtf(128), workspace = buf.readUtf(128), branch = buf.readUtf(128), review = buf.readUtf(64), acceptance = buf.readUtf(64), detail = buf.readUtf(512);
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_CONVERSATION) throw new IllegalArgumentException("invalid conversation size");
            List<Entry> entries = new ArrayList<>(size);
            for (int index = 0; index < size; index++) entries.add(new Entry(buf.readUtf(128), buf.readUtf(512), buf.readBoolean()));
            return new Open(agentId, name, state, task, ticket, workspace, branch, review, acceptance, detail, List.copyOf(entries), buf.readUtf(256));
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Send(String agentId, String body) implements CustomPacketPayload {
        public static final Type<Send> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "send_conversation_message"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Send> CODEC = StreamCodec.of((buf, p) -> { buf.writeUtf(p.agentId, 64); buf.writeUtf(p.body, 400); }, buf -> new Send(buf.readUtf(64), buf.readUtf(400)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Requests a fresh server-authoritative transcript after an adapter publishes a reply. */
    public record Refresh(String agentId) implements CustomPacketPayload {
        public static final Type<Refresh> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "refresh_conversation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Refresh> CODEC = StreamCodec.of((buf, p) -> buf.writeUtf(p.agentId, 64), buf -> new Refresh(buf.readUtf(64)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record ClearFocus() implements CustomPacketPayload {
        public static final Type<ClearFocus> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "clear_graph_focus"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClearFocus> CODEC = StreamCodec.unit(new ClearFocus());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
