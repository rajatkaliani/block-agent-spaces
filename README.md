# Block Agent Spaces

Block Agent Spaces is a spatial visualization layer for an agent software team and its knowledge graph. It makes agent state, ticket flow, conversations, and the relationships between project artifacts inspectable in a shared Minecraft Java world.

Minecraft is the interface, not the source of truth. A local adapter owns agent execution, Git, tickets, and reviews; the mod renders the adapter-reported state and provides a bounded player-to-agent message boundary.

## What it visualizes

- **Agent workspace:** named NPCs represent Goat (the lead) and developer or reviewer agents. Their state, ticket, branch, workspace, review status, and acceptance status are available from a click-to-chat notebook.
- **Knowledge-graph observatory:** a dark, glass-fronted room renders graph nodes as interactive holograms and relationships as glowing links. Selecting an agent focuses the graph on its published context; selecting a graph node opens a linked agent notebook when one exists.
- **Goat control point:** Goat is the coordination role: it can triage work, delegate tickets, request review, and report acceptance when an external orchestrator confirms it. The physical ticket board shows adapter-reported Ready, Active, Blocked, Review, and Accepted work.
- **Conversation record:** notebooks clearly separate `You • sent` notes from incoming adapter replies. The lectern is a shared, bounded activity log.

The built-in seed data and explicitly named demo adapters are demonstrations only. They do not run a coding model, create real tickets, make branches, review code, or merge anything.

## Architecture

```text
Minecraft notebooks / graph / ticket board
                ↕
       loopback bridge on 127.0.0.1:8787
                ↕
  local Goat adapter / agent runtime / Git integration
```

The bridge exposes agent, task, graph-node, graph-edge, and message state, plus an ordered event outbox. An adapter polls events, persists its cursor, consumes player messages, and publishes authoritative updates back to the bridge. The Minecraft server coalesces those updates and redraws only its managed installation on the server thread.

Message semantics are deliberately strict: a Minecraft note is an outbound message to the local bridge; it is not an agent answer. An incoming notebook entry appears only when the relevant adapter publishes a separate message from that agent to `minecraft-player`.

For the state schema and workflow boundary, see [Goat-led workflow](docs/goat-led-workflow.md). Example payloads are in [bridge-payloads.json](examples/bridge-payloads.json).

## Codex workflow

Goat is designed to supervise—not impersonate—a development team. A real development adapter can:

1. Turn an explicit player request into a ticket.
2. Create an isolated worktree and branch for one developer agent.
3. Run Codex for the bounded ticket.
4. Publish progress and a review request.
5. Require a separate review and acceptance decision before any merge.

The included Codex adapter is guarded: dry-run is the default, and worktree creation/execution require explicit opt-in. It never auto-merges, pushes, deletes branches, or removes worktrees. See [Codex developer runtime](docs/codex-developer-runtime.md) for setup and safety details.

## Quick start

Requirements: Minecraft Java **26.3**, Fabric, and JDK 25 for development.

```bash
./gradlew build
./gradlew runClient
```

In the development client:

1. Create or join a world and stand near the middle of a level 31×11 patch of solid, dry ground with open air above it.
2. Run `/blockagents start` once. It validates the site, builds the space around the player, and opens Goat’s console.
3. Right-click an NPC to inspect its notebook, message it, or focus the graph. Right-click a graph hologram to inspect its relationship context or open its linked notebook.

The installation is persistent and player-safe: refreshes use the recorded installation location and stop rather than overwrite a block a player has changed.

### Run the local demo

With a world open, start the zero-dependency bridge demo:

```bash
python3 examples/bridge_demo.py
```

Right-click Builder, send a note, then use the notebook’s **Refresh** button. The demo sends a clearly labelled `DEMO REPLY`; it never echoes player text or claims an agent took real action. The deeper integration examples are:

- [Goat bridge adapter](examples/goat_bridge_adapter.py): replay-safe local message consumer with pairing support.
- [Goat lead demo](examples/goat_lead_adapter_demo.py): explicitly labelled deterministic triage visualization.
- [Ambient intelligence adapter](examples/ambient_intelligence_adapter.py): optional local-model classification and summaries with deterministic fallback.

## Local security and correctness

- The bridge binds only to `127.0.0.1`.
- Mutations require a configured pairing token by default. An unauthenticated mode exists only when explicit demo mode is enabled.
- The mod never stores API keys, pairing tokens, absolute workspace paths, or unbounded agent logs.
- The world state is presentation continuity, not an orchestrator’s durable source of truth. Adapters must republish authoritative tickets, Git state, and agent state after reconnecting.
- The board and observatory label demo, restored, and live-local data honestly; “live” only means a local process posted an update during the current server run.

## Development

```bash
./gradlew build
./gradlew runClient
```

The built Fabric JAR is written to `build/libs`. For the full bridge and product contract, start with [Goat-led workflow](docs/goat-led-workflow.md); for the optional local-model boundary, read [Ambient intelligence adapter](docs/ambient-intelligence-adapter.md).
