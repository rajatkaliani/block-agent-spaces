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
/blockagents onboarding
/blockagents build
/blockagents status
/blockagents agents
/blockagents graph
```

`onboarding` gives the shortest possible introduction and reports whether the local bridge is live. `/blockagents build` is the main first-run command: it finds a small clear volume above nearby open ground, then creates an elevated agent workspace, central corridor, and glass-walled graph observatory. It refuses to overwrite non-air blocks, and repeating it refreshes the same installation for that player during the game session.

The workspace populates up to four current agents as stationary named villagers at color-coded stations: green working, red blocked, yellow reviewing, blue complete, and gray idle. The observatory turns up to six graph nodes into floating colored blocks and links known relationships with glowing End Rod paths. `agents` surfaces each agent's full current detail and task, while `graph` provides a compact textual inspection view. `/blockagents seed` restores the sample workspace at any time.

### Five-minute first run

1. Put the mod jar in the Minecraft Fabric `mods` folder and launch or join a world.
2. Run `/blockagents onboarding` to confirm the local bridge is connected.
3. Run `/blockagents build` while standing under open sky. The mod builds only inside a clear elevated volume; it will not replace existing blocks.
4. Point a local orchestrator at `http://127.0.0.1:8787` and publish agent and graph events.
5. Run `/blockagents build` again to refresh the world installation, then use `/blockagents agents` or `/blockagents graph` for details.

### Local integration bridge

External agent orchestrators can publish data to the running mod through HTTP. The bridge starts when the Minecraft server/world starts, only listens on localhost, and is intended for trusted processes on the same computer. It accepts simple flat JSON payloads and returns the current state for visualizers or debugging.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Check that the bridge is running. |
| `GET` | `/v1/snapshot` | Get agents, tasks, graph nodes, and graph edges. |
| `GET` | `/v1/events` | Poll recent state-change events. |
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
