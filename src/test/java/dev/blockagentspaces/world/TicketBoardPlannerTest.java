package dev.blockagentspaces.world;

import dev.blockagentspaces.model.Task;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TicketBoardPlannerTest {
    @Test void mapsReportedStatesToReadableColumnsWithoutInferringGitActions() {
        TicketBoardPlanner.Board board = TicketBoardPlanner.plan(List.of(
            task("review", "In Review"), task("active", "in_progress"), task("blocked", "blocked"),
            task("accepted", "accepted"), task("merged", "merged"), task("ready", "open")
        ));

        assertEquals(List.of("ready"), ids(board.cards(TicketBoardPlanner.Lane.READY)));
        assertEquals(List.of("active"), ids(board.cards(TicketBoardPlanner.Lane.ACTIVE)));
        assertEquals(List.of("blocked"), ids(board.cards(TicketBoardPlanner.Lane.BLOCKED)));
        assertEquals(List.of("review"), ids(board.cards(TicketBoardPlanner.Lane.REVIEW)));
        assertEquals(List.of("accepted", "merged"), ids(board.cards(TicketBoardPlanner.Lane.ACCEPTED)));
        assertEquals("merged", board.cards(TicketBoardPlanner.Lane.ACCEPTED).getLast().reportedStatus());
    }

    @Test void keepsOnlyThreeIdSortedCardsPerColumnAndCompactsText() {
        TicketBoardPlanner.Board board = TicketBoardPlanner.plan(List.of(
            task("d", "open"), task("a", "open"), task("c", "open"), task("b", "open"),
            task("long", "A deliberately very long ticket title that will be compacted")
        ));

        assertEquals(List.of("a", "b", "c"), ids(board.cards(TicketBoardPlanner.Lane.READY)));
        assertEquals(24, TicketBoardPlanner.plan(List.of(task("long", "A deliberately very long ticket title that will be compacted", "open"))).cards(TicketBoardPlanner.Lane.READY).getFirst().title().length());
    }

    private static List<String> ids(List<TicketBoardPlanner.Card> cards) { return cards.stream().map(TicketBoardPlanner.Card::ticketId).toList(); }
    private static Task task(String id, String status) { return new Task(id, id + " title", "", status, Instant.EPOCH); }
    private static Task task(String id, String title, String status) { return new Task(id, title, "", status, Instant.EPOCH); }
}
