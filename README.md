# Block Agent Spaces

**Block Agent Spaces** turns a Minecraft Java world into a living, spatial view of knowledge and AI-agent work. Built as a Fabric mod, it makes the activity normally hidden in text logs feel visible, explorable, and playful.

## The experience

- **Agent workspace:** Villagers or custom NPCs represent sub-agents. They have live task labels and state indicators, can move and animate as work changes, and can be selected for a focused view of what they are doing.
- **Graph observatory:** An adjacent, glass-walled room contains a Minecraft-native knowledge graph. Floating nodes and glowing edges make relationships visible as a three-dimensional installation.

The graph can represent notes, files, tasks, projects, and the relationships between them. Selecting an agent highlights the graph elements relevant to its current work, connecting execution activity to the surrounding knowledge.

## Initial MVP

1. A Fabric mod that creates the agent room and graph observatory.
2. Load a small, static JSON knowledge graph into nodes and glowing edges.
3. Display a few simulated agents with names, task labels, basic state changes, and clickable details.
4. Highlight the nodes and edges connected to a selected agent.
5. Add a lightweight local event stream so agent position, status, and graph focus can update in real time.

## What is runnable today

This first vertical slice compiles as a Fabric mod for **Minecraft Java 26.3**, the latest stable version when this scaffold was created. It starts a local-only integration bridge on `127.0.0.1:8787`, seeds a small example workspace, and exposes an in-game command interface while the custom room and rendering layer is under construction.

In a world with the mod installed, run:

```text
/blockagents start
/blockagents status
```

`/blockagents start` is the single first-run action: it seeds the sample team when needed, validates the ground under and around the command player, builds the installation, then opens Goat's dashboard. Stand near the middle of a **level 31×11 patch of solid, dry ground** with six blocks of open air above it. The build creates an oak entry gateway, a developer workspace, Goat's central control point, a team-message lectern, and the glass-walled graph observatory around that location.

If the spot is unsuitable, the game names the first issue—unsafe ground, uneven terrain, or the exact relative clearance obstruction—and changes nothing. `/blockagents build` is the explicit advanced/rebuild alias; after a successful build it refreshes the same recorded installation rather than claiming a new patch. The footprint and every block placed by the mod are saved with the world. A refresh stops before changing any block that no longer matches the recorded generated state, so a player's later construction is never overwritten.

The workspace populates up to four current agents as stationary named villagers at color-coded stations: green working, red blocked, yellow reviewing, blue complete, and gray idle. **Right-click a workspace villager** to open its notebook and focus *your* observatory on that agent—no command required. The focus starts with the agent's published graph-focus IDs, falls back to its ticket and task, then includes one bounded relationship hop. Related nodes and links stay bright; unrelated items dim while preserving their project/file/task colors. The **Full graph** notebook button clears only your installation's saved selection. The observatory has six stable, ID-sorted node slots, so a bridge refresh cannot shuffle the room because of map iteration order.

Near Goat, a physical ticket board presents five readable, text-labeled columns: **Ready**, **Active**, **Blocked**, **Review**, and **Accepted**. Each has at most three ID-sorted cards, and every card says its adapter-reported status in words; colors are only a secondary cue. The board's `ADAPTER REPORTED` label is intentional: Block Agent Spaces shows status supplied by a local orchestrator and never claims it performed a Git merge, accepted a ticket, or ran an agent. Right-click any part of the board to open Goat's notebook and focus the graph room on Goat.

### Five-minute first run

1. Put the mod jar in the Minecraft Fabric `mods` folder and launch or join a world.
2. Find a level 31×11 patch of solid ground with open air above it, then stand near its middle.
3. Run `/blockagents start`. It seeds the demo, validates the patch, builds the complete space around you, and opens Goat's dashboard.
4. Point a local orchestrator at `http://127.0.0.1:8787` and publish agent and graph events.
5. Right-click an agent to open its notebook and focus your graph room. Write in the notebook and select **Send**; your note is visibly labelled **You • sent** and enters the local bridge outbox. Select **Refresh** to retrieve a separately-labelled reply after the configured adapter has published one. Use **Full graph** to clear the selection. Bridge updates are reflected in the installed space automatically after a short quiet moment; use `/blockagents build` only when you want an explicit manual refresh.

### Copy-paste bridge quickstart

The repository includes a Python standard-library demo—no package install and no particular agent system required. With Minecraft open in one terminal, run:

```bash
python3 examples/bridge_demo.py
```

The script checks the bridge, publishes a task, two graph nodes, an edge, and a `Builder` agent, then waits. In Minecraft, right-click Builder, write a note in the notebook, select **Send**, then select **Refresh**. The sample publishes a separate `DEMO REPLY` from Builder; it never echoes your text or claims a real coding agent acted. Use it as a minimal reference for adapting any local orchestrator; it does not depend on or imply an integration with a particular agent product.

### Communicate from inside Minecraft

Once agents are published, these commands make the world an active workspace rather than a passive display:

```text
/blockagents inspect builder
/blockagents message builder Please share progress on the graph renderer.
/blockagents refresh
```

`inspect` resolves either an agent ID or its display name and shows its state, current task, latest message, and linked graph elements. `message` records a bounded player-to-agent message in the same local state exposed at `/v1/messages`, ready for a local orchestrator to poll. It rejects unknown names, empty text, and messages over 400 characters. `refresh` redraws the current player's existing installation from the newest bridge state without looking for or altering another build location, then reports the number of agent stations, graph nodes, and glowing links refreshed. The normal interaction path is the clickable agent notebook; these commands remain useful for server operators and troubleshooting. A player note is never displayed as an agent response: only an adapter-authored message from that specific agent to `minecraft-player` appears as an incoming note.

### Local integration bridge

External agent orchestrators can publish data to the running mod through HTTP. The bridge starts when the Minecraft server/world starts, only listens on localhost, and is intended for trusted processes on the same computer. It accepts simple flat JSON payloads and returns the current state for visualizers or debugging.

Mutating endpoints accept `POST` only; malformed or incomplete domain payloads return a clear `400` response rather than changing state. Other request failures are returned as a generic error without exposing server details.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Check that the bridge is running. |
| `GET` | `/v1/snapshot` | Get agents, tasks, graph nodes, and graph edges. |
| `GET` | `/v1/events?after=<cursor>` | Read ordered outbox events after a cursor. |
| `POST` | `/v1/events/ack` | Store a consumer acknowledgement for a cursor. |
| `GET` / `POST` | `/v1/agents` | Read or update NPC agent status. |
| `GET` / `POST` | `/v1/tasks` | Read or update task data. |
| `GET` / `POST` | `/v1/graph/nodes` | Read or update knowledge graph nodes. |
| `GET` / `POST` | `/v1/graph/edges` | Read or update graph relationships. |
| `GET` / `POST` | `/v1/messages` | Read or send messages between orchestrator and agents. |

For example, while Minecraft is running:

```bash
curl http://127.0.0.1:8787/health
curl -X POST http://127.0.0.1:8787/v1/agents \
  -H 'Content-Type: application/json' \
  -d '{"id":"researcher","displayName":"Researcher","state":"working","taskId":"find-apis","detail":"Mapping the event API","graphFocus":["project","event-api"]}'
curl -X POST http://127.0.0.1:8787/v1/graph/nodes \
  -H 'Content-Type: application/json' \
  -d '{"id":"event-api","label":"Event API","type":"note"}'
```

#### Ordered outbox contract

`GET /v1/events?after=0` returns an object with ordered event records and a `nextCursor`:

```json
{
  "events": [{"id": 1, "type": "message.created", "subjectId": "minecraft-...", "createdAt": "..."}],
  "nextCursor": 1
}
```

Event IDs are monotonically increasing for the life of the running world/server. Save `nextCursor` after handling each response and pass it back as `after` on the next request; this avoids reprocessing older events. Consumers can explicitly record progress with:

```bash
curl -X POST http://127.0.0.1:8787/v1/events/ack \
  -H 'Content-Type: application/json' \
  -d '{"consumer":"my-local-orchestrator","cursor":1}'
```

The event stream is an outbox signal, not a copy of every payload. When it reports `message.created`, fetch `/v1/messages` to read the message body; when it reports an agent, task, node, or edge update, fetch the matching endpoint or a snapshot. The in-memory event history retains the newest 200 events, so consumers should poll and persist their own cursor while the world is running.

### Automatic in-world refresh

The local bridge only changes `WorldState`; it never writes Minecraft blocks from an HTTP worker thread. Each bridge update is coalesced for a few server ticks, then the Minecraft server thread refreshes installed Block Agent Spaces locations. Managed agent stations, tagged NPC labels and positions, graph nodes and links, the conversation lectern, and the Goat status sign update together. Stale tagged NPCs and obsolete graph blocks are cleaned up as part of that reconciliation.

The Goat control point visibly says either `DEMO DATA • BRIDGE READY`, `DEMO DATA • BRIDGE OFFLINE`, `RESTORED LOCAL DATA`, or `LIVE LOCAL UPDATES`. “Live” means a local process has posted to this mod's loopback bridge during this server run; it does not claim that any particular agent product is connected. If a player changes any recorded generated block, automatic and manual refreshes skip that installation rather than overwrite the change.

### Restart-safe state

The world saves the generated-installation ownership metadata plus a bounded snapshot of agent, ticket, graph, conversation, bridge-event cursor, and display-source state. Each installation also saves its selected agent focus. On restart, the latest saved snapshot is restored before demo data is seeded, then the normal safe reconciler redraws only managed space. A restored world is labeled `RESTORED LOCAL DATA` until the local adapter publishes a fresh update. If the selected agent no longer exists, the reconciler clears that selection only after its normal ownership check succeeds; a player-edited installation remains untouched and keeps its choice for a later safe refresh.

This is presentation continuity, not a replacement for an external Goat adapter's source of truth. The mod never stores pairing tokens, bridge configuration, arbitrary absolute filesystem paths, or unbounded message/event logs. The adapter should republish authoritative workspaces, Git state, ticket history, and any missed events after reconnecting.

See [the example payloads](examples/bridge-payloads.json) for each domain object. The initial HTTP event feed is deliberately compatible with polling; a true WebSocket/SSE transport can be added behind the same `WorldState` service without changing the game-facing domain model.

## Development setup

1. Install JDK 25 (the current Minecraft 26.3/Fabric toolchain requirement).
2. Clone this repository.
3. Run `./gradlew build` to compile and test.
4. Run `./gradlew runClient` to open a development Minecraft client.

The built mod jar is placed in `build/libs`. The project uses official Mojang mappings because modern Minecraft releases no longer need the older remapping model.

## Proposed technical direction

- Minecraft Java Edition with Fabric, targeting the latest supported game version.
- Custom entities or villager-based NPCs for the agent representations.
- A local event bridge for agent/task updates and JSON for early graph data.
- Minecraft blocks, particles, light, and UI interactions for a native visual language rather than an overlay dashboard.

## Status

The initial runnable scaffold is complete: a Fabric project, domain model, local bridge, sample data, command-based onboarding/debug views, tests, and GitHub Actions build verification are included. The next delivery is the visual layer: generate the workspace and glass observatory in-world, represent agents with NPC entities, and render selectable nodes and glowing graph edges.
