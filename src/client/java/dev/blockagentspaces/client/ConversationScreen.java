package dev.blockagentspaces.client;

import dev.blockagentspaces.network.ConversationPayloads;
import dev.blockagentspaces.network.GraphPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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
 * <p>The server remains authoritative for transcripts. A player message is shown as
 * an outgoing note, and an adapter reply is shown as a separate incoming note. The
 * notebook never turns a player's own outbox entry into a fake agent response.</p>
 */
final class ConversationScreen extends Screen {
    private static ConversationScreen active;
    private static final int MAX_MESSAGE_LENGTH = 400;
    private static final Pattern TICKET_REQUEST = Pattern.compile("^codex:\\s*([a-z][a-z0-9-]{0,63})\\s*\\|\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TICKET_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern INSPECTION_REQUEST = Pattern.compile("^inspect:\\s*([a-z][a-z0-9-]{0,63})$", Pattern.CASE_INSENSITIVE);
    // GLFW key values are part of Minecraft's input contract, but GLFW is not exposed to mod compilation.
    private static final int ENTER_KEY = 257;
    private static final int KEYPAD_ENTER_KEY = 335;
    private ConversationPayloads.Open context;
    private final Screen parent;
    private final List<NotebookMessage> messages;
    private EditBox draft;
    private Button sendButton;
    private Button graphButton;
    private Button ticketButton;
    private Button inspectButton;
    private Button statusButton;
    private String deliveryStatus;
    private boolean sending;
    private boolean preserveDraftOnSuccess;
    private String pendingRequestId = "";
    private int refreshTicks;
    private int transcriptScroll;
    private int maxTranscriptScroll;

    ConversationScreen(ConversationPayloads.Open context) {
        this(context, null);
    }

    ConversationScreen(ConversationPayloads.Open context, Screen parent) {
        super(Component.literal(safe(context.name()) + " notebook"));
        this.context = context;
        this.parent = parent;
        this.messages = new ArrayList<>(conversationFor(context));
        this.deliveryStatus = safe(context.deliveryStatus());
    }

    @Override
    protected void init() {
        String existingDraft = draft == null ? "" : draft.getValue();
        sendButton = null;
        ticketButton = null;
        inspectButton = null;
        statusButton = null;
        int panelWidth = panelWidth();
        int panelX = (width - panelWidth) / 2;
        int composeY = Math.max(138, height - 48);
        int sendWidth = 58;
        int focusWidth = 82;
        int draftWidth = Math.max(80, panelWidth - sendWidth - focusWidth - 28);

        draft = new EditBox(font, panelX + 8, composeY, draftWidth, 20, Component.literal("Message " + safe(context.name())));
        draft.setMaxLength(MAX_MESSAGE_LENGTH);
        draft.setHint(Component.literal("Write a note to " + safe(context.name()) + "…"));
        draft.setValue(existingDraft);
        draft.setEditable(!sending);
        draft.setResponder(value -> {
            boolean ready = !sending && !value.trim().isEmpty();
            if (sendButton != null) sendButton.active = ready;
            if (ticketButton != null) ticketButton.active = ready;
        });
        addRenderableWidget(draft);

        sendButton = addRenderableWidget(Button.builder(Component.literal("Send"), button -> send())
                .bounds(panelX + panelWidth - sendWidth - 8, composeY, sendWidth, 20)
                .build());
        sendButton.active = !sending && !existingDraft.trim().isEmpty();
        graphButton = addRenderableWidget(Button.builder(Component.literal("Graph"), button -> ClientPlayNetworking.send(new GraphPayloads.Request("")))
                .bounds(panelX + panelWidth - sendWidth - focusWidth - 12, composeY, focusWidth, 20)
                .build());
        graphButton.active = true;
        if (isGoat()) {
            ticketButton = addRenderableWidget(Button.builder(Component.literal("Request ticket"), button -> requestTicket())
                .bounds(panelX + panelWidth - 116, 18, 106, 18).build());
            ticketButton.active = !sending && !existingDraft.trim().isEmpty();
            inspectButton = addRenderableWidget(Button.builder(Component.literal("Inspect ticket"), button -> requestInspection())
                .bounds(panelX + panelWidth - 116, 115, 106, 18).build());
            inspectButton.active = !sending && validTicket(context.ticket());
            statusButton = addRenderableWidget(Button.builder(Component.literal("Status"), button -> requestStatus())
                .bounds(panelX + panelWidth - 184, 115, 64, 18).build());
            statusButton.active = !sending;
        } else {
            addRenderableWidget(Button.builder(Component.literal("Ask Goat"), button -> {
                deliveryStatus = "Opening Goat's notebook…";
                ClientPlayNetworking.send(new GraphPayloads.OpenAgent("goat"));
            }).bounds(panelX + panelWidth - 116, 18, 106, 18).build());
        }
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
        boolean goat = isGoat();
        graphics.text(font, Component.literal(clipped(name + (goat ? "  • LEAD AGENT" : "  • WORKSPACE AGENT"), panelWidth - 128)), panelX + 10, panelY + 10, goat ? 0xFFF6CE72 : 0xFFE4E9F0, true);
        int columnWidth = Math.max(95, panelWidth / 2 - 18);
        drawField(graphics, "State: " + context.state(), panelX + 10, panelY + 34, columnWidth, stateColor(context.state()), mouseX, mouseY);
        drawField(graphics, "Task: " + context.task(), panelX + 10, panelY + 47, columnWidth, 0xFFD7DFE8, mouseX, mouseY);
        drawField(graphics, "Ticket: " + context.ticket(), panelX + 10, panelY + 62, columnWidth, 0xFFAFC3D9, mouseX, mouseY);
        drawField(graphics, "Workspace: " + context.workspace(), panelX + 10, panelY + 75, columnWidth, 0xFFAFC3D9, mouseX, mouseY);

        int rightX = panelX + Math.max(10, panelWidth / 2);
        drawField(graphics, "Branch: " + context.branch(), rightX, panelY + 34, columnWidth, 0xFFAFC3D9, mouseX, mouseY);
        drawField(graphics, "Review: " + context.review(), rightX, panelY + 47, columnWidth, reviewColor(context.review()), mouseX, mouseY);
        drawField(graphics, "Acceptance: " + context.acceptance(), rightX, panelY + 62, columnWidth, acceptanceColor(context.acceptance()), mouseX, mouseY);
        drawField(graphics, context.detail(), rightX, panelY + 75, columnWidth, 0xFFBAC2CB, mouseX, mouseY);

        graphics.text(font, Component.literal(goat ? "Chat" : "Conversation"), panelX + 10, panelY + 106, 0xFFB8C7D9, true);
        drawMessages(graphics, panelX + 12, panelY + 120, panelWidth - 24, composeY - (panelY + 128));
        drawField(graphics, deliveryStatus, panelX + 10, composeY + 25, panelWidth - 20, 0xFF94A1AF, mouseX, mouseY);

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
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        int panelX = (width - panelWidth()) / 2;
        int composeY = Math.max(138, height - 48);
        if (x >= panelX && x < panelX + panelWidth() && y >= 134 && y < composeY - 8) {
            transcriptScroll = Math.max(0, Math.min(maxTranscriptScroll, transcriptScroll + (int) Math.signum(vertical) * 30));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override public void added() { super.added(); active = this; }
    @Override public void removed() { if (active == this) active = null; super.removed(); }
    @Override public void onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent);
        else super.onClose();
    }
    static ConversationScreen active() { return active; }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTicks >= 40) {
            refreshTicks = 0;
            ClientPlayNetworking.send(new ConversationPayloads.Refresh(context.agentId()));
        }
    }

    boolean displays(String agentId) { return context.agentId().equals(agentId); }

    void update(ConversationPayloads.Open update) {
        context = update;
        messages.clear();
        messages.addAll(conversationFor(update));
        if (inspectButton != null) inspectButton.active = !sending && validTicket(context.ticket());
        if (statusButton != null) statusButton.active = !sending;
        if (!sending) deliveryStatus = safe(update.deliveryStatus());
    }

    void sent(ConversationPayloads.SendResult result) {
        if (!sending || !pendingRequestId.equals(result.requestId())) return;
        sending = false;
        pendingRequestId = "";
        draft.setEditable(true);
        if (result.accepted() && !preserveDraftOnSuccess) draft.setValue("");
        preserveDraftOnSuccess = false;
        sendButton.active = !draft.getValue().trim().isEmpty();
        if (ticketButton != null) ticketButton.active = sendButton.active;
        if (inspectButton != null) inspectButton.active = validTicket(context.ticket());
        if (statusButton != null) statusButton.active = true;
        deliveryStatus = safe(result.status());
    }

    private void send() {
        String body = safe(draft.getValue()).trim();
        sendBody(body, false);
    }

    private void requestTicket() {
        if (!isGoat()) return;
        String prompt = safe(draft.getValue()).trim();
        if (prompt.isEmpty()) return;
        String ticketId = "ticket-" + java.util.UUID.randomUUID().toString().substring(0, 12);
        String body = "codex: " + ticketId + " | " + prompt;
        if (body.length() > MAX_MESSAGE_LENGTH) {
            deliveryStatus = "Ticket request is too long; shorten the draft and try again.";
            return;
        }
        sendBody(body, false);
    }

    private void requestInspection() {
        if (!isGoat() || !validTicket(context.ticket())) return;
        sendBody("inspect: " + context.ticket(), true);
    }

    private void requestStatus() {
        if (!isGoat()) return;
        sendBody("status", true);
    }

    private void sendBody(String body, boolean preserveDraft) {
        if (body.isEmpty() || sending) return;
        sending = true;
        preserveDraftOnSuccess = preserveDraft;
        pendingRequestId = java.util.UUID.randomUUID().toString();
        draft.setEditable(false);
        sendButton.active = false;
        if (ticketButton != null) ticketButton.active = false;
        if (inspectButton != null) inspectButton.active = false;
        if (statusButton != null) statusButton.active = false;
        deliveryStatus = body.startsWith("codex: ") ? "Requesting a development ticket from Goat…"
            : body.startsWith("inspect: ") ? "Requesting a local ticket diff summary…"
            : body.equals("status") ? "Requesting reported team status…" : "Sending to the local bridge…";
        ClientPlayNetworking.send(new ConversationPayloads.Send(context.agentId(), body, pendingRequestId));
    }

    private void drawMessages(GuiGraphicsExtractor graphics, int x, int y, int availableWidth, int availableHeight) {
        if (messages.isEmpty()) {
            graphics.text(font, Component.literal("No messages yet. Send a concise update or question."), x, y, 0xFF8C98A5, false);
            return;
        }
        int cardWidth = Math.max(92, (availableWidth * 3) / 4);
        int totalHeight = 0;
        for (NotebookMessage message : messages) totalHeight += 16 + 10 * font.split(Component.literal(message.body), cardWidth - 8).size();
        maxTranscriptScroll = Math.max(0, totalHeight - Math.max(1, availableHeight));
        transcriptScroll = Math.min(transcriptScroll, maxTranscriptScroll);
        int cursorY = y - maxTranscriptScroll + transcriptScroll;
        graphics.enableScissor(x - 2, y - 3, x + availableWidth + 2, y + Math.max(1, availableHeight));
        for (NotebookMessage message : messages) {
            var lines = font.split(Component.literal(message.body), cardWidth - 8);
            int cardHeight = 12 + 10 * lines.size();
            int cardX = message.outgoing ? x + availableWidth - cardWidth : x;
            int fill = message.outgoing ? 0xCC173D35 : 0xCC28313D;
            int border = message.outgoing ? 0xFF4FAF86 : 0xFF617791;
            graphics.fill(cardX, cursorY - 2, cardX + cardWidth, cursorY + cardHeight, fill);
            graphics.outline(cardX, cursorY - 2, cardX + cardWidth, cursorY + cardHeight, border);
            String label = message.outgoing ? "You • sent" : message.sender + " • incoming";
            graphics.text(font, Component.literal(clipped(label, cardWidth - 8)), cardX + 4, cursorY, message.outgoing ? 0xFF9BDAAE : 0xFFE0E7EF, false);
            int lineY = cursorY + 10;
            for (var line : lines) { graphics.text(font, line, cardX + 4, lineY, message.outgoing ? 0xFFD3F1DF : 0xFFE0E7EF, false); lineY += 10; }
            cursorY += cardHeight + 4;
        }
        graphics.disableScissor();
    }

    private int panelWidth() {
        return Math.max(250, Math.min(640, width - 24));
    }

    private static List<NotebookMessage> conversationFor(ConversationPayloads.Open context) {
        if (context.conversation() == null) return List.of();
        return context.conversation().stream()
            .map(entry -> new NotebookMessage(safe(entry.sender()), displayBody(context, entry), entry.outgoing()))
            .toList();
    }

    private static String displayBody(ConversationPayloads.Open context, ConversationPayloads.Entry entry) {
        String body = safe(entry.body());
        if (!entry.outgoing() || !"goat".equalsIgnoreCase(context.agentId())) return body;
        var request = TICKET_REQUEST.matcher(body);
        if (request.matches()) return "Ticket request (" + request.group(1) + "): " + request.group(2);
        var inspection = INSPECTION_REQUEST.matcher(body);
        if (inspection.matches()) return "Inspect ticket (" + inspection.group(1) + ")";
        return body.equals("status") ? "Team status request" : body;
    }

    private boolean isGoat() { return "goat".equalsIgnoreCase(context.agentId()); }

    private static boolean validTicket(String value) { return value != null && TICKET_ID.matcher(value).matches(); }

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
        StringBuilder result = new StringBuilder(Math.min(value.length(), 4000));
        for (int index = 0; index < value.length() && result.length() < 4000; index++) {
            char character = value.charAt(index);
            if (character == '\n' || character == '\r' || character == '\t') result.append(' ');
            else if (!Character.isISOControl(character)) result.append(character);
        }
        return result.toString();
    }

    private String clipped(String value, int pixelWidth) {
        String plain = safe(value).replaceAll("\\s+", " ").trim();
        if (font.width(plain) <= pixelWidth) return plain;
        return font.plainSubstrByWidth(plain, Math.max(1, pixelWidth - font.width("…"))) + "…";
    }

    private void drawField(GuiGraphicsExtractor graphics, String value, int x, int y, int width, int color, int mouseX, int mouseY) {
        String visible = clipped(value, width);
        graphics.text(font, Component.literal(visible), x, y, color, false);
        if (visible.endsWith("…") && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 11)
            graphics.setTooltipForNextFrame(Component.literal(safe(value)), mouseX, mouseY);
    }

    private record NotebookMessage(String sender, String body, boolean outgoing) { }
}
