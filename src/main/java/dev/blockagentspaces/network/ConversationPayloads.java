package dev.blockagentspaces.network;

import dev.blockagentspaces.BlockAgentSpacesMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class ConversationPayloads {
    private ConversationPayloads() { }
    public record Open(String agentId, String name, String state, String task, String ticket, String workspace, String branch, String review, String acceptance, String detail, String recent) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "open_conversation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.of((buf, p) -> { for (String value : new String[]{p.agentId,p.name,p.state,p.task,p.ticket,p.workspace,p.branch,p.review,p.acceptance,p.detail,p.recent}) buf.writeUtf(value, 512); }, buf -> new Open(buf.readUtf(64),buf.readUtf(128),buf.readUtf(64),buf.readUtf(128),buf.readUtf(128),buf.readUtf(128),buf.readUtf(128),buf.readUtf(64),buf.readUtf(64),buf.readUtf(512),buf.readUtf(512)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Send(String agentId, String body) implements CustomPacketPayload {
        public static final Type<Send> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BlockAgentSpacesMod.MOD_ID, "send_conversation_message"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Send> CODEC = StreamCodec.of((buf, p) -> { buf.writeUtf(p.agentId, 64); buf.writeUtf(p.body, 400); }, buf -> new Send(buf.readUtf(64), buf.readUtf(400)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
