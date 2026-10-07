package dev.blockagentspaces.client;

import dev.blockagentspaces.network.ConversationPayloads;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * A compact, deliberately plain-text notebook for talking to one workspace agent.
 *
 * <p>The server remains authoritative for messages. This screen only keeps a small
 * local echo so a player can still see the messages they just sent when reopening a
 * notebook before the next bridge refresh arrives.</p>
 */
final class ConversationScreen extends Screen {
    private static final int MAX_HISTORY = 6;
    private static final int MAX_MESSAGE_LENGTH = 400;
    // GLFW key values are part of Minecraft's input contract, but GLFW is not exposed to mod compilation.
    private static final int ENTER_KEY = 257;
    private static final int KEYPAD_ENTER_KEY = 335;
    private static final Map<String, Deque<NotebookMessage>> LOCAL_HISTORY = new LinkedHashMap<>();

    private final ConversationPayloads.Open context;
    private final List<NotebookMessage> messages;
    private EditBox draft;
    private Button sendButton;

    ConversationScreen(ConversationPayloads.Open context) {
        super(Component.literal(safe(context.name()) + " notebook"));
        this.context = context;
        this.messages = conversationFor(context);
    }

    @Override
    protected void init() {
        int panelWidth = panelWidth();
        int panelX = (width - panelWidth) / 2;
        int composeY = Math.max(138, height - 48);
        int sendWidth = 58;
        int draftWidth = Math.max(80, panelWidth - sendWidth - 18);

        draft = new EditBox(font, panelX + 8, composeY, draftWidth, 20, Component.literal("Message " + safe(context.name())));
        draft.setMaxLength(MAX_MESSAGE_LENGTH);
        draft.setHint(Component.literal("Write a note to " + safe(context.name()) + "…"));
        draft.setResponder(value -> sendButton.active = !value.trim().isEmpty());
        addRenderableWidget(draft);

        sendButton = addRenderableWidget(Button.builder(Component.literal("Send"), button -> send())
                .bounds(panelX + panelWidth - sendWidth - 8, composeY, sendWidth, 20)
                .build());
        sendButton.active = false;
        setInitialFocus(draft);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);

        int panelWidth = panelWidth();
        int panelX = (width - panelWidth) / 2;
        int panelY = 14;
        int panelBottom = Math.max(panelY + 150, height - 14);
        int composeY = Math.max(138, height - 48);
        int transcriptY = panelY + 102;

        // A dark "open book" page with restrained cards keeps the world visible behind it.
        graphics.fill(panelX, panelY, panelX + panelWidth, panelBottom, 0xEE171A20);
        graphics.outline(panelX, panelY, panelX + panelWidth, panelBottom, 0xFF938469);
        graphics.fill(panelX + 6, panelY + 28, panelX + panelWidth - 6, panelY + 94, 0xCC252C35);
        graphics.outline(panelX + 6, panelY + 28, panelX + panelWidth - 6, panelY + 94, 0xFF4D5B6B);
        graphics.fill(panelX + 6, transcriptY, panelX + panelWidth - 6, composeY - 8, 0xA812151A);
        graphics.outline(panelX + 6, transcriptY, panelX + panelWidth - 6, composeY - 8, 0xFF39424D);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int panelWidth = panelWidth();
        int panelX = (width - panelWidth) / 2;
        int panelY = 14;
        int composeY = Math.max(138, height - 48);

        String name = safe(context.name());
        boolean isGoat = "goat".equalsIgnoreCase(context.agentId()) || "goat".equalsIgnoreCase(name);
        graphics.text(font, Component.literal(clipped(name + (isGoat ? "  • LEAD AGENT" : "  • WORKSPACE AGENT"), panelWidth - 20)), panelX + 10, panelY + 10, isGoat ? 0xFFF6CE72 : 0xFFE4E9F0, true);
        int columnWidth = Math.max(95, panelWidth / 2 - 18);
        graphics.text(font, Component.literal(clipped("State: " + context.state(), columnWidth)), panelX + 10, panelY + 34, stateColor(context.state()), false);
        graphics.text(font, Component.literal(clipped("Task: " + context.task(), columnWidth)), panelX + 10, panelY + 47, 0xFFD7DFE8, false);
        graphics.text(font, Component.literal(clipped("Ticket: " + context.ticket(), columnWidth)), panelX + 10, panelY + 62, 0xFFAFC3D9, false);
        graphics.text(font, Component.literal(clipped("Workspace: " + context.workspace(), columnWidth)), panelX + 10, panelY + 75, 0xFFAFC3D9, false);

        int rightX = panelX + Math.max(10, panelWidth / 2);
        graphics.text(font, Component.literal(clipped("Branch: " + context.branch(), columnWidth)), rightX, panelY + 34, 0xFFAFC3D9, false);
        graphics.text(font, Component.literal(clipped("Review: " + context.review(), columnWidth)), rightX, panelY + 47, reviewColor(context.review()), false);
        graphics.text(font, Component.literal(clipped("Acceptance: " + context.acceptance(), columnWidth)), rightX, panelY + 62, acceptanceColor(context.acceptance()), false);
        graphics.text(font, Component.literal(clipped(context.detail(), columnWidth)), rightX, panelY + 75, 0xFFBAC2CB, false);

        graphics.text(font, Component.literal("Recent conversation"), panelX + 10, panelY + 106, 0xFFB8C7D9, true);
        drawMessages(graphics, panelX + 12, panelY + 120, panelWidth - 24, composeY - (panelY + 128));
        graphics.text(font, Component.literal(clipped("Enter sends  •  Esc closes  •  " + MAX_MESSAGE_LENGTH + " character maximum", panelWidth - 20)), panelX + 10, composeY + 25, 0xFF94A1AF, false);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == ENTER_KEY || event.key() == KEYPAD_ENTER_KEY) && draft != null && !draft.getValue().trim().isEmpty()) {
            send();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void send() {
        String body = safe(draft.getValue()).trim();
        if (body.isEmpty()) return;
        remember(context.agentId(), new NotebookMessage("You", body));
        ClientPlayNetworking.send(new ConversationPayloads.Send(context.agentId(), body));
        onClose();
    }

    private void drawMessages(GuiGraphicsExtractor graphics, int x, int y, int availableWidth, int availableHeight) {
        int maxRows = Math.max(1, Math.min(MAX_HISTORY, availableHeight / 13));
        List<NotebookMessage> visible = messages.size() <= maxRows
                ? messages
                : messages.subList(messages.size() - maxRows, messages.size());
        if (visible.isEmpty()) {
            graphics.text(font, Component.literal("No messages yet. Send a concise update or question."), x, y, 0xFF8C98A5, false);
            return;
        }
        int cursorY = y;
        for (NotebookMessage message : visible) {
            String line = clipped(message.sender + ": " + message.body, availableWidth);
            graphics.text(font, Component.literal(line), x, cursorY, message.sender.equals("You") ? 0xFF9BDAAE : 0xFFE0E7EF, false);
            cursorY += 13;
        }
    }

    private int panelWidth() {
        return Math.max(250, Math.min(640, width - 24));
    }

    private static List<NotebookMessage> conversationFor(ConversationPayloads.Open context) {
        Deque<NotebookMessage> history = LOCAL_HISTORY.computeIfAbsent(context.agentId(), ignored -> new ArrayDeque<>());
        String recent = safe(context.recent()).trim();
        if (!recent.isEmpty() && !recent.equalsIgnoreCase("No recent conversation.")) {
            int divider = recent.indexOf(':');
            NotebookMessage incoming = divider > 0
                    ? new NotebookMessage(safe(recent.substring(0, divider)), safe(recent.substring(divider + 1)).trim())
                    : new NotebookMessage("Agent", recent);
            if (history.stream().noneMatch(existing -> existing.equals(incoming))) remember(context.agentId(), incoming);
        }
        return List.copyOf(history);
    }

    private static void remember(String agentId, NotebookMessage message) {
        Deque<NotebookMessage> history = LOCAL_HISTORY.computeIfAbsent(agentId, ignored -> new ArrayDeque<>());
        history.addLast(message);
        while (history.size() > MAX_HISTORY) history.removeFirst();
    }

    private static int stateColor(String state) {
        String value = safe(state).toLowerCase();
        if (value.contains("block")) return 0xFFE77E7E;
        if (value.contains("review")) return 0xFFF3C76D;
        if (value.contains("work")) return 0xFF8FD3FF;
        return 0xFFB9C5D1;
    }

    private static int reviewColor(String review) {
        return safe(review).toLowerCase().contains("request") ? 0xFFE6A4A4 : 0xFFF3C76D;
    }

    private static int acceptanceColor(String acceptance) {
        return safe(acceptance).toLowerCase().contains("accept") ? 0xFF95D9A5 : 0xFFC8D0D9;
    }

    /** Components are literal and this removes control characters before any text reaches the renderer. */
    private static String safe(String value) {
        if (value == null) return "—";
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_MESSAGE_LENGTH));
        for (int index = 0; index < value.length() && result.length() < MAX_MESSAGE_LENGTH; index++) {
            char character = value.charAt(index);
            if (!Character.isISOControl(character) || character == ' ') result.append(character);
        }
        return result.toString();
    }

    private String clipped(String value, int pixelWidth) {
        String plain = safe(value).replaceAll("\\s+", " ").trim();
        if (font.width(plain) <= pixelWidth) return plain;
        return font.plainSubstrByWidth(plain, Math.max(1, pixelWidth - font.width("…"))) + "…";
    }

    private record NotebookMessage(String sender, String body) { }
}
