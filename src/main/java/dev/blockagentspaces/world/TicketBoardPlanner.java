package dev.blockagentspaces.world;

import dev.blockagentspaces.model.Task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, stable content for Goat's physical ticket board. */
public final class TicketBoardPlanner {
    public static final int MAX_CARDS_PER_COLUMN = 3;

    private TicketBoardPlanner() { }

    public static Board plan(Collection<Task> tasks) {
        Map<Lane, List<Card>> cards = new EnumMap<>(Lane.class);
        for (Lane lane : Lane.values()) cards.put(lane, new ArrayList<>());
        tasks.stream().filter(task -> task != null).sorted(Comparator.comparing(Task::id)).forEach(task -> {
            List<Card> laneCards = cards.get(laneFor(task.status()));
            if (laneCards.size() < MAX_CARDS_PER_COLUMN) laneCards.add(new Card(task.id(), compact(task.title()), compact(task.status())));
        });
        return new Board(Lane.values(), Map.copyOf(cards));
    }

    static Lane laneFor(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "active", "in_progress", "working", "in_development" -> Lane.ACTIVE;
            case "blocked", "waiting", "needs_input" -> Lane.BLOCKED;
            case "review", "in_review", "reviewing", "changes_requested" -> Lane.REVIEW;
            case "accepted", "merged", "complete", "completed", "done" -> Lane.ACCEPTED;
            default -> Lane.READY;
        };
    }

    private static String compact(String text) {
        if (text == null || text.isBlank()) return "—";
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 24 ? singleLine : singleLine.substring(0, 23) + "…";
    }

    public enum Lane {
        READY("READY"), ACTIVE("ACTIVE"), BLOCKED("BLOCKED"), REVIEW("REVIEW"), ACCEPTED("ACCEPTED");
        private final String label;
        Lane(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Card(String ticketId, String title, String reportedStatus) { }
    public record Board(Lane[] lanes, Map<Lane, List<Card>> cardsByLane) {
        public Board {
            lanes = lanes.clone();
            Map<Lane, List<Card>> copy = new EnumMap<>(Lane.class);
            cardsByLane.forEach((lane, cards) -> copy.put(lane, List.copyOf(cards)));
            cardsByLane = Map.copyOf(copy);
        }
        public List<Card> cards(Lane lane) { return cardsByLane.getOrDefault(lane, List.of()); }
    }
}
