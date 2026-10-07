package dev.blockagentspaces.client;

import dev.blockagentspaces.network.ConversationPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class BlockAgentSpacesClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ConversationPayloads.Open.TYPE, (payload, context) -> context.client().setScreenAndShow(new ConversationScreen(payload)));
    }
}
