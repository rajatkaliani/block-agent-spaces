# Goat-led team workflow and bridge contract

## Purpose

Block Agent Spaces is a Minecraft-native window onto an externally operated
software team. **Goat** is the lead agent: it turns goals into tickets,
coordinates developers and reviewers, and reports the team’s state. Developer
agents take tickets in isolated code workspaces; review agents evaluate change
requests. Minecraft represents that work in an agent room, a knowledge-graph
observatory, and a shared lectern log.

This document defines the intended domain contract. It does **not** make the
mod a source-control system or an autonomous agent runner. An external,
trusted orchestrator plus its adapters perform actual Git operations,
workspace creation, code execution, review, and Goat’s autonomous routing.
The mod receives and displays their state, records player messages, and
exchanges events through the existing local bridge.

## Roles

| Role | External responsibility | Minecraft representation |
| --- | --- | --- |
| Goat (lead) | Break work into tickets, assign/reassign work, request reviews, decide or record acceptance, coordinate status | A named lead NPC in the command area; its notebook is the team entry point |
| Developer | Claim an assigned ticket, develop and test in its isolated workspace, submit a change request | A workstation NPC; current ticket and branch/workspace state are visible |
| Reviewer | Inspect a submitted change request, return comments or approve/reject it | A review station NPC; review status is visible |
| Player | Ask for status, send direction, and inspect the graph and historical log | Opens agent notebooks and the team lectern |
| Orchestrator/adapters | Run agents and tools; create worktrees/branches; make commits; host review/merge integration; emit authoritative events | Not represented as an agent unless the integration chooses to publish it |

Goat may route an incoming player message to the right developer or reviewer,
but that choice is made by the external orchestrator. The mod must never infer
that a message changed an assignment, approved a merge, or executed Git.

## Ticket and code lifecycle

The orchestrator owns the authoritative transition history. A recommended
workflow is:

1. Goat creates a ticket with a clear goal, acceptance criteria, and graph
   links to affected files, notes, issues, or design nodes.
2. Goat assigns the ticket to a developer. The adapter creates an isolated
   workspace and branch from the recorded base revision. No other ticket
   develops in that workspace.
3. The developer works, emits progress, and produces commits in that isolated
   branch. Minecraft displays the ticket as `in_progress` and can focus its
   related graph region.
4. The developer opens a change request with a head revision, test evidence,
   and a review target. Goat routes it to a reviewer.
5. The reviewer returns comments, requests changes, rejects, or approves.
   Requested changes return the ticket to the developer without merging it.
6. Goat (or the configured external merge policy) records acceptance. The Git
   adapter performs the actual merge, captures the resulting revision, and
   emits a merge event. Only then does the ticket become `done`.
7. The adapter retains branch, base revision, head revision, commit IDs,
   review decisions, and merge revision in its central history store. The mod
   shows safe summaries and identifiers supplied by that adapter; it is not
   the source of truth or a replacement for Git hosting.

Suggested ticket states are `planned`, `ready`, `assigned`, `in_progress`,
`in_review`, `changes_requested`, `accepted`, `merged`, `done`, `blocked`,
and `cancelled`. `accepted` means the review/merge decision is recorded;
`merged` requires an adapter-confirmed merge. Integrations may use a smaller
set, but should preserve the distinction between approval and merge.

## Domain payloads

The examples below are JSON **resources/events**, not new HTTP endpoints. An
adapter can publish the compatible parts through the current local bridge and
publish the remaining fields when the bridge domain model is extended. Event
names and schemas should be versioned before an adapter relies on them.

### Ticket creation

```json
{
  "id": "ticket-graph-layout",
  "type": "ticket.created",
  "ticket": {
    "id": "ticket-graph-layout",
    "title": "Lay out the graph observatory",
    "status": "ready",
    "createdBy": "goat",
    "acceptanceCriteria": [
      "Nodes remain readable from the viewing room",
      "Edges identify the selected agent's focus"
    ],
    "graphFocus": ["observatory", "graph-layout"]
  }
}
```

### Assignment and isolated workspace

```json
{
  "id": "assignment-104",
  "type": "ticket.assigned",
  "ticketId": "ticket-graph-layout",
  "assignee": "builder",
  "assignedBy": "goat",
  "workspace": {
    "id": "worktree-builder-graph-layout",
    "branch": "agent/builder/graph-layout",
    "baseRevision": "base revision supplied by adapter",
    "isolation": "dedicated-worktree"
  }
}
```

The `workspace` fields communicate provenance to the visualizer. The adapter
creates and removes the worktree/branch and applies access controls. Minecraft
must not expose a filesystem path or grant direct code execution to a player.

### Review and merge acceptance

```json
{
  "id": "review-77",
  "type": "review.submitted",
  "ticketId": "ticket-graph-layout",
  "changeRequest": {
    "id": "change-graph-layout",
    "branch": "agent/builder/graph-layout",
    "baseRevision": "base revision supplied by adapter",
    "headRevision": "head revision supplied by adapter",
    "status": "awaiting_review",
    "tests": {"status": "passed", "summary": "adapter-provided summary"}
  },
  "reviewer": "reviewer-1"
}
```

```json
{
  "id": "merge-31",
  "type": "merge.accepted",
  "ticketId": "ticket-graph-layout",
  "changeRequestId": "change-graph-layout",
  "acceptedBy": "goat",
  "review": {"reviewer": "reviewer-1", "decision": "approved"},
  "merge": {
    "status": "merged",
    "targetBranch": "main",
    "resultRevision": "merge revision supplied by adapter",
    "performedBy": "git-adapter"
  }
}
```

The final payload is only emitted after the adapter confirms its Git-hosting
operation. If a merge fails, publish a failure/blocked status with a concise
reason instead of emitting `merge.accepted`.

## Messaging and the shared lectern

Messages are durable conversation records owned by the orchestrator or
integration store. The existing bridge can already receive player-originated
messages and expose its ordered outbox; an adapter polls the outbox, persists
the message, lets Goat route it, then publishes resulting agent updates and
replies.

```json
{
  "id": "message-218",
  "type": "message.created",
  "from": {"kind": "player", "id": "minecraft-player"},
  "to": {"kind": "agent", "id": "goat"},
  "body": "Which ticket should the team tackle next?",
  "threadId": "team-planning",
  "visibility": "team",
  "createdAt": "adapter-provided timestamp"
}
```

```json
{
  "id": "message-219",
  "type": "message.routed",
  "from": {"kind": "agent", "id": "goat"},
  "to": {"kind": "agent", "id": "builder"},
  "body": "Please estimate ticket-graph-layout and report dependencies.",
  "threadId": "ticket-graph-layout",
  "visibility": "ticket",
  "inReplyTo": "message-218"
}
```

### Lectern log

The lectern is a **team-wide, read-first activity ledger**. Opening it should
show a paged, chronological feed of ticket changes, assignments, review
decisions, merge results, and messages marked `team`. It should make a player
able to answer “what changed and why?” without opening every notebook.

Ticket-private messages may show only a brief activity entry in the lectern
unless the orchestrator marks them team-visible. Sensitive data, secrets,
full diffs, and raw tool logs must not be sent to the game. The adapter should
publish sanitized summaries, authors, time, ticket/change-request references,
and a link-like in-game affordance to the associated notebook or graph focus.

## Click-to-chat notebook UI

Right-clicking an NPC opens a notebook-style screen rather than requiring a
command. The same design applies to Goat, developers, and reviewers. The click
also focuses that player's own graph observatory on the agent's published graph
focus, with ticket/task and one-hop relationship fallbacks. A **Full graph**
button clears the per-installation selection; it never sends a chat command or
changes another player's observatory.

The notebook has four compact areas:

1. **Header:** agent name, role, presence/state, current ticket, and whether
   the state is live or last known.
2. **Work card:** ticket goal, acceptance criteria, branch/workspace summary,
   test/review state, and Full graph control for clearing the saved focus.
3. **Conversation:** newest-first or chronological bounded message thread,
   with clear sender and delivery/pending indicators.
4. **Compose:** a short player message field and Send action. Messages to
   Goat use the team-planning thread by default; messages to a developer or
   reviewer default to its active ticket thread.

Sending only creates a player message locally and adds it to the bridge
outbox. It does not promise immediate delivery or agent action. The UI should
show `queued for orchestrator` until an adapter acknowledges or responds. If
the bridge is unavailable, it should preserve a clear unsent/error state and
offer retry rather than losing the player’s text.

Goat’s notebook additionally provides a read-only team queue: planned,
active, blocked, and awaiting-review tickets. Future integration-specific
actions such as “request a reassignment” should be expressed as player
messages or explicitly designed command resources—not hidden side effects of
clicking a UI control.

## Goat control board

The physical board next to Goat is a compact, read-only summary of adapter-
reported ticket state. It has Ready, Active, Blocked, Review, and Accepted
columns and shows at most three stable-ID-sorted tickets in each. Its text
labels and every card's reported status remain readable without relying on
color. Clicking the board opens Goat's existing notebook and focuses that
player's observatory on Goat; it does not create tickets, assign work, accept
changes, or merge code. Those remain explicit, adapter-confirmed actions.

## Knowledge graph mapping

The observatory explains the work, not only the workers:

- Ticket nodes link to files, notes, architecture decisions, tasks, and change
  requests.
- Agent-to-ticket edges show current ownership; review edges show who is
  assessing a change.
- A merge adds a history relationship from the accepted change request to its
  resulting revision and target branch summary.
- Selecting an NPC highlights its active ticket, related knowledge, and the
  latest relevant review/merge state.

Adapters should publish stable external IDs for graph entities and maintain
their own mapping from those IDs to provider-specific issues, pull requests,
or repository objects. This preserves a useful visualization while keeping
the mod independent of a particular agent platform or Git host.

## Integration safety and rollout

Start with a local trusted adapter that mirrors only a small team and sanitized
metadata. Validate events before changing mod state, use idempotent external
IDs where possible, and reconnect from the bridge outbox cursor after restarts.
The mod’s local loopback bridge remains a display-and-message boundary; an
adapter is responsible for authentication to any remote agent or Git service.

Before adding a new transport or write operation, specify its authentication,
authorization, idempotency, retention, and failure behavior. The first
click-to-chat milestone should therefore be: display current state, submit a
bounded message, show delivery state, and render a confirmed response—without
giving the game direct repository or shell authority.
