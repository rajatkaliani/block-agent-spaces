package dev.blockagentspaces.client;

import dev.blockagentspaces.network.GraphPayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A client-rendered graph, independent of the in-world display entities. */
final class GraphScreen extends Screen {
    private static GraphScreen active;
    private GraphPayloads.Open graph;
    private final Map<String, Point> points = new HashMap<>();
    private GraphPayloads.Node selected;
    private Button agentButton;
    private EditBox search;
    private double zoom = 1.0, panX, panY;
    private int refreshTicks;

    GraphScreen(GraphPayloads.Open graph) {
        super(Component.literal("Knowledge graph"));
        this.graph = graph;
        selected = graph.nodes().stream().filter(n -> n.id().equals(graph.selected())).findFirst().orElse(null);
        layout();
    }

    @Override protected void init() {
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, 12, 38, Math.max(70, Math.min(230, sideX() - 24)), 18, Component.literal("Search graph"));
        search.setMaxLength(80);
        search.setHint(Component.literal("Search nodes…"));
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
        g.enableScissor(0, 60, sideX(), height - 24);
        for (GraphPayloads.Edge edge : graph.edges()) {
            Point a = points.get(edge.source()), b = points.get(edge.target());
            if (a == null || b == null) continue;
            boolean related = selected == null || selected.id().equals(edge.source()) || selected.id().equals(edge.target());
            line(g, x(a), y(a), x(b), y(b), related ? 0xFF577D91 : 0xFF25323D);
        }
        for (GraphPayloads.Node node : graph.nodes()) {
            Point p = points.get(node.id());
            int x = x(p), y = y(p);
            boolean active = selected != null && selected.id().equals(node.id());
            boolean matches = matches(node);
            int color = matches ? color(node.type()) : 0xFF35404B;
            circle(g, x, y, active ? 8 : 6, active ? 0xFFFFFFFF : 0xFF31414C);
            circle(g, x, y, active ? 6 : 4, color);
            String label = clip(node.label(), 120);
            g.centeredText(font, label, x, y + 10, active ? 0xFFFFFFFF : matches ? 0xFFB6C7D3 : 0xFF4D5962);
        }
        g.disableScissor();
        g.text(font, "KNOWLEDGE GRAPH", 12, 10, 0xFFE2EDF3, true);
        g.text(font, clip(graph.status(), Math.max(50, sideX() - 24)), 12, 23, 0xFF8CA7B6, false);
        g.text(font, "Drag to pan  •  Scroll to zoom  •  Click a node", 12, height - 16, 0xFF8194A1, false);
        int sx = sideX() + 10, sw = width - sx - 8;
        g.text(font, "INSPECT", sx, 12, 0xFF8EA9B8, true);
        if (selected == null) {
            g.text(font, "Select a node", sx, 34, 0xFFD8E4EB, false);
            g.text(font, graph.nodes().size() + " nodes  •  " + graph.edges().size() + " links", sx, 50, 0xFF9CAFB9, false);
        } else {
            g.text(font, clip(selected.label(), sw), sx, 34, 0xFFF4F8FA, true);
            g.text(font, clip(selected.type() + "  •  " + selected.id(), sw), sx, 49, 0xFF9AB6C6, false);
            g.text(font, "RELATIONSHIPS", sx, 70, 0xFF8EA9B8, true);
            int y = 85, count = 0;
            for (GraphPayloads.Edge edge : graph.edges()) {
                if (!edge.source().equals(selected.id()) && !edge.target().equals(selected.id())) continue;
                if (y + 25 > height - 50) break;
                boolean outgoing = edge.source().equals(selected.id());
                String other = outgoing ? edge.target() : edge.source();
                String label = graph.nodes().stream().filter(n -> n.id().equals(other)).map(GraphPayloads.Node::label).findFirst().orElse(other);
                g.text(font, clip((outgoing ? "→ " : "← ") + label, sw), sx, y, 0xFFDFEAF0, false);
                g.text(font, clip(edge.relationship(), sw), sx + 8, y + 10, 0xFF8BA2B0, false);
                y += 25; count++;
            }
            if (count == 0) g.text(font, "No visible links", sx, y, 0xFF82949E, false);
        }
        if (graph.truncated()) g.text(font, clip("Graph limited to 128 nodes / 256 links", sw), sx, height - 48, 0xFFFFC472, false);
        super.extractRenderState(g, mx, my, delta);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0 || event.x() >= sideX() || event.y() < 60 || event.y() >= height - 24) return false;
        GraphPayloads.Node nearest = null;
        double distance = 14 * 14;
        for (GraphPayloads.Node node : graph.nodes()) {
            Point p = points.get(node.id());
            double dx = event.x() - x(p), dy = event.y() - y(p), candidate = dx * dx + dy * dy;
            if (candidate < distance) { distance = candidate; nearest = node; }
        }
        if (nearest != null) { selected = nearest; updateButton(); }
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == 0 && event.x() < sideX()) { panX += dx; panY += dy; return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx >= sideX()) return false;
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
    @Override public void tick() {
        super.tick();
        if (++refreshTicks >= 100) { refreshTicks = 0; ClientPlayNetworking.send(new GraphPayloads.Refresh(selected == null ? "" : selected.id())); }
    }
    static GraphScreen active() { return active; }
    void update(GraphPayloads.Open next) {
        String selectedId = selected == null ? "" : selected.id();
        boolean changed = !graph.nodes().equals(next.nodes()) || !graph.edges().equals(next.edges());
        graph = next;
        selected = graph.nodes().stream().filter(n -> n.id().equals(selectedId)).findFirst().orElse(null);
        if (changed) { points.clear(); layout(); }
        updateButton();
    }
    private void updateButton() { agentButton.active = selected != null && !selected.agentId().isEmpty(); }
    private int sideX() { return Math.max(100, width - Math.min(200, Math.max(120, width / 3))); }
    private boolean matches(GraphPayloads.Node node) {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        return query.isEmpty() || (node.id() + " " + node.label() + " " + node.type()).toLowerCase(java.util.Locale.ROOT).contains(query);
    }
    private int x(Point p) { return (int) Math.round(sideX() / 2.0 + panX + p.x * zoom * Math.max(40, sideX() * 0.40)); }
    private int y(Point p) { return (int) Math.round(height / 2.0 + panY + p.y * zoom * Math.max(40, (height - 80) * 0.40)); }
    private String clip(String s, int w) { return font.width(s) <= w ? s : font.plainSubstrByWidth(s, Math.max(0, w - font.width("…"))) + "…"; }

    /** Stable force layout. The bounded graph keeps this calculation small and predictable. */
    private void layout() {
        List<GraphPayloads.Node> nodes = graph.nodes().stream().sorted(java.util.Comparator.comparing(GraphPayloads.Node::id)).toList();
        int n = nodes.size();
        List<Point> positions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double angle = i * Math.PI * 2 / Math.max(1, n);
            Point p = new Point(Math.cos(angle) * 0.7, Math.sin(angle) * 0.7);
            positions.add(p); points.put(nodes.get(i).id(), p);
        }
        for (int iteration = 0; iteration < 100 && n > 1; iteration++) {
            double[] dx = new double[n], dy = new double[n];
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
                double x = positions.get(i).x - positions.get(j).x, y = positions.get(i).y - positions.get(j).y;
                double d2 = Math.max(0.002, x * x + y * y), force = 0.002 / d2;
                dx[i] += x * force; dy[i] += y * force; dx[j] -= x * force; dy[j] -= y * force;
            }
            for (GraphPayloads.Edge edge : graph.edges()) {
                int a = index(nodes, edge.source()), b = index(nodes, edge.target());
                if (a < 0 || b < 0 || a == b) continue;
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
    private static int index(List<GraphPayloads.Node> nodes, String id) { for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id().equals(id)) return i; return -1; }
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
