# Block Agent Spaces

Block Agent Spaces is a visual control surface for an agent development team and its knowledge graph. It makes agent state, tickets, conversations, code revisions, and relationships inspectable together. Minecraft Java hosts the interface; local adapters and Git remain the sources of truth.

## What you can do

- Click an agent to open its live notebook: see its task and review state, send a message, and distinguish your sent note from an actual adapter reply. The Codex adapter gives no-token status answers in developer notebooks and labels unsupported free-form notes honestly.
- Explore a black-canvas knowledge graph: search, pan, zoom, select nodes, follow links, and open agent notebooks from the directory.
- Ask Goat for reported status without a model call, or request a Codex development ticket from its notebook.
- Watch a ticket move through assignment, isolated development, and review handoff. Goat's **Inspect ticket** button shows the local commit and changed-file summary. The optional Codex adapter runs tests, creates a guarded local ticket commit, and requires a clean worktree before it reports `awaiting_review`. It never merges or pushes automatically.

## Quick start

Requires Minecraft Java **26.3**, Fabric, and JDK 25 for development.

```bash
./gradlew build
./gradlew runClient
```

In a world, stand on a level 31×11 patch of solid ground with clear space above it, then run `/blockagents start`. Right-click an NPC for its notebook or open the graph from there. `runClient` enables an explicit local demo bridge; packaged builds require a pairing token for bridge writes.

For a no-token local demonstration, start one adapter in another terminal:

```bash
python3 examples/bridge_demo.py
```

For Goat status answers and guarded Codex tickets, use `python3 examples/codex_developer_adapter.py --repo .` instead. It starts in **dry-run mode**: ticket requests visibly block without creating a worktree or using Codex. See [Codex runtime setup](docs/codex-developer-runtime.md) to opt into real jobs. Do not run the demo and Codex adapters together; both may answer notebook messages.

## Integration boundary

```text
Agent notebooks + knowledge graph + ticket board
                  ↕
       loopback bridge (127.0.0.1:8787)
                  ↕
        local adapters + Git worktrees
```

The bridge publishes bounded agent, task, graph, and message state plus an ordered event feed. Adapters consume player messages and publish authoritative updates. A sent message is never displayed as if it were an agent answer; a reply appears only after an adapter publishes one. The built-in sample state and demo adapters do not run a coding model.

For the data and workflow contract, see [Goat-led workflow](docs/goat-led-workflow.md). For payload examples, see [bridge-payloads.json](examples/bridge-payloads.json). The built mod JAR is in `build/libs`.
