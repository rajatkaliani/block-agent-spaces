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

## Proposed technical direction

- Minecraft Java Edition with Fabric, targeting the latest supported game version.
- Custom entities or villager-based NPCs for the agent representations.
- A local event bridge for agent/task updates and JSON for early graph data.
- Minecraft blocks, particles, light, and UI interactions for a native visual language rather than an overlay dashboard.

## Status

This repository is at the concept and MVP-design stage. The next step is to scaffold the Fabric mod and build the first static agent room and graph observatory.
