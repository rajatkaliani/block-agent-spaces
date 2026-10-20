package dev.blockagentspaces.client;

import dev.blockagentspaces.network.GraphPayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** A client-rendered graph, independent of the in-world display entities. */
final class GraphScreen extends Screen {
    private static GraphScreen active;
    private GraphPayloads.Open graph;
    private final Screen parent;
    private final Map<String, Point> points = new HashMap<>();
    private GraphPayloads.Node selected;
    private Button agentButton;
    private EditBox search;
    private double zoom = 1.0, panX, panY;
    private int refreshTicks;
    private int relationshipScroll;
    private int agentScroll;
    private boolean showAgents;

    GraphScreen(GraphPayloads.Open graph, Screen parent) {
        super(Component.literal("Knowledge graph"));
        this.graph = graph;
        this.parent = parent;
        selected = graph.nodes().stream().filter(n -> n.id().equals(graph.selected())).findFirst().orElse(null);
        layout();
    }

    @Override protected void init() {
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, 12, 38, Math.max(70, Math.min(230, sideX() - 24)), 18, Component.literal("Search"));
        search.setMaxLength(80);
        search.setHint(Component.literal(showAgents ? "Search agents…" : "Search nodes…"));
        search.setValue(query);
        addRenderableWidget(search);
        agentButton = addRenderableWidget(Button.builder(Component.literal("Open agent notebook"), b -> {
            if (selected != null && !selected.agentId().isEmpty()) ClientPlayNetworking.send(new GraphPayloads.OpenAgent(selected.agentId()));
        }).bounds(sideX() + 8, height - 32, Math.max(90, width - sideX() - 16), 20).build());
        updateButton();
    }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xFF080B10);
        g.fill(sideX(), 0, width, height, 0xFF131A24);
        g.fill(sideX(), 0, sideX() + 1, height, 0xFF344150);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        GraphPayloads.Node hovered = nodeAt(mx, my);
        GraphPayloads.Node inspected = selected == null ? hovered : selected;
        Set<String> neighborhood = new HashSet<>();
        if (inspected != null) {
            neighborhood.add(inspected.id());
            for (GraphPayloads.Edge edge : relationshipsFor(inspected.id())) {
                neighborhood.add(edge.source());
                neighborhood.add(edge.target());
            }
        }
        String query = showAgents || search == null ? "" : search.getValue().trim();
        int labelledMatches = 0;
        g.enableScissor(0, 60, sideX(), height - 24);
        for (GraphPayloads.Edge edge : graph.edges()) {
            Point a = points.get(edge.source()), b = points.get(edge.target());
            if (a == null || b == null) continue;
            boolean related = inspected == null || inspected.id().equals(edge.source()) || inspected.id().equals(edge.target());
            line(g, x(a), y(a), x(b), y(b), related ? 0xFF577D91 : 0xFF25323D);
        }
        for (GraphPayloads.Node node : graph.nodes()) {
            Point p = points.get(node.id());
            int x = x(p), y = y(p);
            boolean active = selected != null && selected.id().equals(node.id());
            boolean matches = matches(node);
            boolean related = inspected == null || neighborhood.contains(node.id());
            int color = matches && related ? color(node.type()) : 0xFF35404B;
            circle(g, x, y, active ? 8 : 6, active ? 0xFFFFFFFF : 0xFF31414C);
            circle(g, x, y, active ? 6 : 4, color);
            boolean showLabel = active || node == hovered || graph.nodes().size() <= 20 && query.isEmpty()
                || !query.isEmpty() && matches && labelledMatches++ < 12;
            if (showLabel) g.centeredText(font, clip(node.label(), 120), x, y + 10,
                active ? 0xFFFFFFFF : matches ? 0xFFB6C7D3 : 0xFF4D5962);
        }
        g.disableScissor();
        g.text(font, "KNOWLEDGE GRAPH", 12, 10, 0xFFE2EDF3, true);
        g.text(font, clip(graph.status(), Math.max(50, sideX() - 24)), 12, 23, 0xFF8CA7B6, false);
        g.text(font, "Drag to pan  •  Scroll to zoom  •  Click a node", 12, height - 16, 0xFF8194A1, false);
        int sx = sideX() + 10, sw = width - sx - 8;
        g.text(font, "INSPECT", sx, 12, showAgents ? 0xFF708694 : 0xFFEAF3F7, true);
        g.text(font, "AGENTS", sx + 70, 12, showAgents ? 0xFFEAF3F7 : 0xFF708694, true);
        if (showAgents) {
            drawAgents(g, mx, my, sx, sw);
            if (graph.truncated()) g.text(font, clip("Directory or graph limited", sw), sx, height - 48, 0xFFFFC472, false);
            super.extractRenderState(g, mx, my, delta);
            return;
        }
        if (inspected == null) {
            g.text(font, "Hover or select a node", sx, 34, 0xFFD8E4EB, false);
            g.text(font, graph.nodes().size() + " nodes  •  " + graph.edges().size() + " links", sx, 50, 0xFF9CAFB9, false);
        } else {
            g.text(font, clip(inspected.label(), sw), sx, 34, 0xFFF4F8FA, true);
            g.text(font, clip(inspected.type() + "  •  " + inspected.id(), sw), sx, 49, 0xFF9AB6C6, false);
            g.text(font, "RELATIONSHIPS", sx, 70, 0xFF8EA9B8, true);
            List<GraphPayloads.Edge> relations = relationshipsFor(inspected.id());
            int rows = relationshipRows();
            relationshipScroll = Math.min(relationshipScroll, Math.max(0, relations.size() - rows));
            for (int i = relationshipScroll; i < Math.min(relations.size(), relationshipScroll + rows); i++) {
                GraphPayloads.Edge edge = relations.get(i);
                int y = 85 + (i - relationshipScroll) * 25;
                boolean outgoing = edge.source().equals(inspected.id());
                String other = outgoing ? edge.target() : edge.source();
                String label = graph.nodes().stream().filter(n -> n.id().equals(other)).map(GraphPayloads.Node::label).findFirst().orElse(other);
                if (selected != null && mx >= sx && my >= y && my < y + 23)
                    g.fill(sx - 3, y - 2, width - 5, y + 22, 0xFF263643);
                g.text(font, clip((outgoing ? "→ " : "← ") + label, sw), sx, y, 0xFFDFEAF0, false);
                g.text(font, clip(edge.relationship(), sw), sx + 8, y + 10, 0xFF8BA2B0, false);
            }
            if (relations.isEmpty()) g.text(font, "No visible links", sx, 85, 0xFF82949E, false);
            else if (relations.size() > rows) g.text(font, clip("Scroll links • click to follow", sw), sx, height - 62, 0xFF8BA2B0, false);
        }
        if (graph.truncated()) g.text(font, clip("Graph limited to 128 nodes / 256 links", sw), sx, height - 48, 0xFFFFC472, false);
        super.extractRenderState(g, mx, my, delta);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;
        if (event.x() >= sideX()) {
            if (event.y() >= 5 && event.y() < 29) {
                selectPanel(event.x() >= sideX() + 70);
                return true;
            }
            if (showAgents) {
                List<GraphPayloads.AgentSummary> agents = filteredAgents();
                if (event.y() >= 54 && event.y() < 54 + agentRows() * 36) {
                    int index = agentScroll + (int) ((event.y() - 54) / 36);
                    if (index < agents.size()) ClientPlayNetworking.send(new GraphPayloads.OpenAgent(agents.get(index).id()));
                }
                return true;
            }
            if (selected != null && event.y() >= 85 && event.y() < 85 + relationshipRows() * 25) {
                List<GraphPayloads.Edge> relations = relationshipsFor(selected.id());
                int index = relationshipScroll + (int) ((event.y() - 85) / 25);
                if (index < relations.size()) {
                    GraphPayloads.Edge edge = relations.get(index);
                    String other = edge.source().equals(selected.id()) ? edge.target() : edge.source();
                    graph.nodes().stream().filter(node -> node.id().equals(other)).findFirst().ifPresent(this::selectAndCenter);
                }
            }
            return true;
        }
        if (event.y() < 60 || event.y() >= height - 24) return false;
        GraphPayloads.Node nearest = nodeAt(event.x(), event.y());
        if (nearest != null) { selectPanel(false); selected = nearest; relationshipScroll = 0; updateButton(); }
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257 || event.key() == 335) {
            String query = search == null ? "" : search.getValue().trim();
            if (!query.isEmpty() && search.isFocused()) {
                if (showAgents) filteredAgents().stream().findFirst().ifPresent(agent -> ClientPlayNetworking.send(new GraphPayloads.OpenAgent(agent.id())));
                else graph.nodes().stream().filter(this::matches).findFirst().ifPresent(this::selectAndCenter);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == 0 && event.x() < sideX()) { panX += dx; panY += dy; return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx >= sideX()) {
            if (showAgents) {
                int max = Math.max(0, filteredAgents().size() - agentRows());
                agentScroll = Math.max(0, Math.min(max, agentScroll - (int) Math.signum(vertical)));
                return true;
            }
            if (selected == null) return false;
            int max = Math.max(0, relationshipsFor(selected.id()).size() - relationshipRows());
            relationshipScroll = Math.max(0, Math.min(max, relationshipScroll - (int) Math.signum(vertical)));
            return true;
        }
        double old = zoom;
        zoom = Math.max(0.35, Math.min(3.0, zoom * Math.pow(1.15, vertical)));
        double cx = sideX() / 2.0, cy = height / 2.0;
        panX = mx - cx - (mx - cx - panX) * zoom / old;
        panY = my - cy - (my - cy - panY) * zoom / old;
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void added() { super.added(); active = this; }
    @Override public void removed() { if (active == this) active = null; super.removed(); }
    @Override public void onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent);
        else super.onClose();
    }
    @Override public void tick() {
        super.tick();
        if (++refreshTicks >= 100) { refreshTicks = 0; ClientPlayNetworking.send(new GraphPayloads.Refresh(selected == null ? "" : selected.id())); }
    }
    static GraphScreen active() { return active; }
    void update(GraphPayloads.Open next) {
        String selectedId = selected == null ? "" : selected.id();
        boolean changed = topologyChanged(graph, next);
        graph = next;
        selected = graph.nodes().stream().filter(n -> n.id().equals(selectedId)).findFirst().orElse(null);
        if (changed) layout();
        updateButton();
    }
    private static boolean topologyChanged(GraphPayloads.Open before, GraphPayloads.Open after) {
        Set<String> oldIds = before.nodes().stream().map(GraphPayloads.Node::id).collect(java.util.stream.Collectors.toSet());
        Set<String> newIds = after.nodes().stream().map(GraphPayloads.Node::id).collect(java.util.stream.Collectors.toSet());
        if (!oldIds.equals(newIds)) return true;
        Set<Connection> oldLinks = before.edges().stream().map(Connection::from).collect(java.util.stream.Collectors.toSet());
        Set<Connection> newLinks = after.edges().stream().map(Connection::from).collect(java.util.stream.Collectors.toSet());
        return !oldLinks.equals(newLinks);
    }
    private void updateButton() {
        if (agentButton != null) {
            agentButton.visible = !showAgents;
            agentButton.active = !showAgents && selected != null && !selected.agentId().isEmpty();
        }
    }
    private int sideX() { return Math.max(100, width - Math.min(200, Math.max(120, width / 3))); }
    private boolean matches(GraphPayloads.Node node) {
        if (showAgents) return true;
        String query = search == null ? "" : search.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        return query.isEmpty() || (node.id() + " " + node.label() + " " + node.type()).toLowerCase(java.util.Locale.ROOT).contains(query);
    }
    private List<GraphPayloads.AgentSummary> filteredAgents() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        return graph.agents().stream().filter(agent -> query.isEmpty()
            || (agent.id() + " " + agent.name() + " " + agent.state() + " " + agent.task())
                .toLowerCase(java.util.Locale.ROOT).contains(query)).toList();
    }
    private void drawAgents(GuiGraphicsExtractor g, int mx, int my, int sx, int sw) {
        List<GraphPayloads.AgentSummary> agents = filteredAgents();
        g.text(font, clip(agents.size() + " agents • click to open", sw), sx, 36, 0xFF9AB6C6, false);
        int rows = agentRows();
        agentScroll = Math.min(agentScroll, Math.max(0, agents.size() - rows));
        if (agents.isEmpty()) g.text(font, "No matching agents", sx, 56, 0xFF82949E, false);
        for (int i = agentScroll; i < Math.min(agents.size(), agentScroll + rows); i++) {
            GraphPayloads.AgentSummary agent = agents.get(i);
            int y = 54 + (i - agentScroll) * 36;
            int fill = mx >= sx && my >= y && my < y + 33 ? 0xFF314453 : 0xFF1E2A35;
            g.fill(sx - 3, y - 2, width - 6, y + 32, fill);
            g.text(font, clip(agent.name(), sw), sx, y, agent.id().equalsIgnoreCase("goat") ? 0xFFF4D48D : 0xFFE8EFF4, true);
            g.text(font, clip(agent.state() + (agent.task().isBlank() ? "" : " • " + agent.task()), sw), sx, y + 14, 0xFF9BB2C2, false);
        }
        if (agents.size() > rows) g.text(font, clip("Scroll for more agents", sw), sx, height - 62, 0xFF8BA2B0, false);
    }
    private int agentRows() { return Math.max(0, (height - 102) / 36); }
    private void selectPanel(boolean agents) {
        if (showAgents == agents) return;
        showAgents = agents;
        agentScroll = 0;
        relationshipScroll = 0;
        search.setValue("");
        search.setHint(Component.literal(agents ? "Search agents…" : "Search nodes…"));
        updateButton();
    }
    private List<GraphPayloads.Edge> relationshipsFor(String nodeId) {
        return graph.edges().stream().filter(edge -> edge.source().equals(nodeId) || edge.target().equals(nodeId)).toList();
    }
    private int relationshipRows() { return Math.max(0, (height - 145) / 25); }
    private void selectAndCenter(GraphPayloads.Node node) {
        selected = node;
        relationshipScroll = 0;
        updateButton();
        Point p = points.get(node.id());
        if (p != null) {
            panX = -p.x * zoom * Math.max(40, sideX() * 0.40);
            panY = -p.y * zoom * Math.max(40, (height - 80) * 0.40);
        }
    }
    private GraphPayloads.Node nodeAt(double mx, double my) {
        if (mx >= sideX() || my < 60 || my >= height - 24) return null;
        GraphPayloads.Node nearest = null;
        double distance = 14 * 14;
        for (GraphPayloads.Node node : graph.nodes()) {
            Point p = points.get(node.id());
            if (p == null) continue;
            double dx = mx - x(p), dy = my - y(p), candidate = dx * dx + dy * dy;
            if (candidate < distance) { distance = candidate; nearest = node; }
        }
        return nearest;
    }
    private int x(Point p) { return (int) Math.round(sideX() / 2.0 + panX + p.x * zoom * Math.max(40, sideX() * 0.40)); }
    private int y(Point p) { return (int) Math.round(height / 2.0 + panY + p.y * zoom * Math.max(40, (height - 80) * 0.40)); }
    private String clip(String s, int w) { return font.width(s) <= w ? s : font.plainSubstrByWidth(s, Math.max(0, w - font.width("…"))) + "…"; }

    /** Stable force layout. The bounded graph keeps this calculation small and predictable. */
    private void layout() {
        List<GraphPayloads.Node> nodes = graph.nodes().stream().sorted(java.util.Comparator.comparing(GraphPayloads.Node::id)).toList();
        int n = nodes.size();
        List<Point> positions = new ArrayList<>(n);
        Map<String, Point> previous = new HashMap<>(points);
        points.clear();
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < n; i++) {
            double angle = i * Math.PI * 2 / Math.max(1, n);
            Point old = previous.get(nodes.get(i).id());
            Point p = old == null ? new Point(Math.cos(angle) * (n == 1 ? 0 : 0.7), Math.sin(angle) * (n == 1 ? 0 : 0.7))
                : new Point(old.x, old.y);
            positions.add(p); points.put(nodes.get(i).id(), p); indices.put(nodes.get(i).id(), i);
        }
        List<int[]> links = new ArrayList<>();
        for (GraphPayloads.Edge edge : graph.edges()) {
            Integer a = indices.get(edge.source()), b = indices.get(edge.target());
            if (a != null && b != null && !a.equals(b)) links.add(new int[]{a, b});
        }
        for (int iteration = 0; iteration < 100 && n > 1; iteration++) {
            double[] dx = new double[n], dy = new double[n];
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
                double x = positions.get(i).x - positions.get(j).x, y = positions.get(i).y - positions.get(j).y;
                double d2 = Math.max(0.002, x * x + y * y), force = 0.002 / d2;
                dx[i] += x * force; dy[i] += y * force; dx[j] -= x * force; dy[j] -= y * force;
            }
            for (int[] link : links) {
                int a = link[0], b = link[1];
                double x = positions.get(b).x - positions.get(a).x, y = positions.get(b).y - positions.get(a).y;
                dx[a] += x * 0.015; dy[a] += y * 0.015; dx[b] -= x * 0.015; dy[b] -= y * 0.015;
            }
            for (int i = 0; i < n; i++) {
                Point p = positions.get(i);
                p.x = Math.max(-1, Math.min(1, p.x + Math.max(-0.04, Math.min(0.04, dx[i] - p.x * 0.003))));
                p.y = Math.max(-1, Math.min(1, p.y + Math.max(-0.04, Math.min(0.04, dy[i] - p.y * 0.003))));
            }
        }
    }
    private record Connection(String first, String second) {
        static Connection from(GraphPayloads.Edge edge) {
            return edge.source().compareTo(edge.target()) <= 0
                ? new Connection(edge.source(), edge.target()) : new Connection(edge.target(), edge.source());
        }
    }
    private static int color(String type) {
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "ticket", "task" -> 0xFFF6C36C; case "file", "note" -> 0xFF8BC7F2;
            case "review" -> 0xFFF09D9D; case "workspace" -> 0xFF9CD6AC; default -> 0xFFBAA6EF;
        };
    }
    private static void circle(GuiGraphicsExtractor g, int x, int y, int r, int color) {
        for (int row = -r; row <= r; row++) { int half = (int) Math.sqrt(r * r - row * row); g.fill(x - half, y + row, x + half + 1, y + row + 1, color); }
    }
    private static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1, dy = y2 - y1;
        g.pose().pushMatrix();
        g.pose().translate(x1, y1).rotate((float) Math.atan2(dy, dx));
        g.fill(0, 0, Math.max(1, (int) Math.round(Math.hypot(dx, dy))), 1, color);
        g.pose().popMatrix();
    }
    private static final class Point { double x, y; Point(double x, double y) { this.x = x; this.y = y; } }
}
