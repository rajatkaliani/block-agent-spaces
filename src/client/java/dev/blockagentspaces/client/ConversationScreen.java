package dev.blockagentspaces.client;

import dev.blockagentspaces.network.ConversationPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ConversationScreen extends Screen {
    private final ConversationPayloads.Open context;
    private EditBox draft;
    ConversationScreen(ConversationPayloads.Open context) { super(Component.literal(context.name() + " notebook")); this.context = context; }
    @Override protected void init() {
        draft = new EditBox(font, width / 2 - 140, height - 52, 220, 20, Component.literal("Message"));
        draft.setMaxLength(400);
        draft.setHint(Component.literal(context.ticket() + " • " + context.workspace() + " • " + context.review()));
        addRenderableWidget(draft);
        addRenderableWidget(Button.builder(Component.literal("Send"), button -> send()).bounds(width / 2 + 86, height - 52, 54, 20).build());
    }
    private void send() {
        String body = draft.getValue().trim();
        if (!body.isEmpty()) { ClientPlayNetworking.send(new ConversationPayloads.Send(context.agentId(), body)); onClose(); }
    }
}
