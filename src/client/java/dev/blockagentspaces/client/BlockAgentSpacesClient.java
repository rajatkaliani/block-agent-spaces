package dev.blockagentspaces.client;

import dev.blockagentspaces.network.ConversationPayloads;
import dev.blockagentspaces.network.GraphPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class BlockAgentSpacesClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ConversationPayloads.Open.TYPE, (payload, context) -> {
            ConversationScreen screen = ConversationScreen.active();
            if (screen != null && screen.displays(payload.agentId())) screen.update(payload);
            else context.client().setScreenAndShow(new ConversationScreen(payload, GraphScreen.active()));
        });
        ClientPlayNetworking.registerGlobalReceiver(ConversationPayloads.Update.TYPE, (payload, context) -> {
            ConversationScreen screen = ConversationScreen.active();
            if (screen != null && screen.displays(payload.snapshot().agentId())) screen.update(payload.snapshot());
        });
        ClientPlayNetworking.registerGlobalReceiver(ConversationPayloads.SendResult.TYPE, (payload, context) -> {
            ConversationScreen screen = ConversationScreen.active();
            if (screen != null && screen.displays(payload.agentId())) screen.sent(payload);
        });
        ClientPlayNetworking.registerGlobalReceiver(GraphPayloads.Open.TYPE, (payload, context) -> context.client().setScreenAndShow(new GraphScreen(payload, ConversationScreen.active())));
        ClientPlayNetworking.registerGlobalReceiver(GraphPayloads.Update.TYPE, (payload, context) -> {
            GraphScreen screen = GraphScreen.active();
            if (screen != null) screen.update(payload.snapshot());
        });
    }
}
