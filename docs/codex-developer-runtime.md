# Codex developer runtime

This optional local adapter lets Goat dispatch a clearly requested development
job from Block Agent Spaces to the locally installed Codex CLI. It is a small,
standard-library example rather than a background agent swarm.

Goat remains deterministic: it receives the request, visualizes the truthful
ticket state, and waits for a separate review decision. Minecraft is a local
visualization and messaging surface; it is not a Git host, a source of truth
for review, or a place to put credentials.

## What it does

1. Polls the local bridge's ordered event feed and stores a private replay
   cursor. Pairing tokens are only sent as local mutation headers and are never
   printed or written to the state file.
2. Handles only an explicit player-to-Goat request in this exact format:

   ```text
   codex: ticket-123 | Add the requested focused change and test it.
   ```

3. Publishes `assigned`, then one truthful terminal state:

   - `awaiting_review` only after Codex exits successfully **and** the
     configured local build/test command succeeds.
   - `blocked` for dry runs, missing Codex, failed authentication/CLI jobs,
     Git/worktree trouble, failed build/tests, a timeout, or an interrupted
     recorded launch.

It never automatically merges, pushes, removes a worktree, deletes a branch,
or says that a review/acceptance happened. Those remain explicit Goat and Git
hosting actions.

## Fast safe start

Use a running Minecraft world with the Block Agent Spaces local bridge. If the
bridge is paired, provide its existing pairing token only to this process:

```bash
export BLOCK_AGENT_SPACES_BRIDGE_TOKEN='your-local-pairing-token'
python3 examples/codex_developer_adapter.py --repo /path/to/repository
```

This is **dry-run mode**. It validates bridge communication and visualizes what
would happen, but cannot create a worktree, invoke Codex, change source files,
or consume Codex usage. Run its offline checks at any time:

```bash
python3 examples/codex_developer_adapter.py --self-test
```

## Enable a real local job deliberately

First install the Codex CLI and authenticate it locally using its normal local
login flow. The adapter neither accepts nor stores an API key. Confirm the CLI
works in a terminal before connecting it to Minecraft.

Then start the adapter with **both** opt-ins. They are intentionally separate:

```bash
export BLOCK_AGENT_SPACES_CODEX_EXECUTE=1
export BLOCK_AGENT_SPACES_CODEX_ALLOW_WORKTREE=1
python3 examples/codex_developer_adapter.py \
  --repo /absolute/path/to/repository \
  --worktree-root "$HOME/.local/share/block-agent-spaces/codex-worktrees"
```

The adapter creates at most one new, isolated worktree at a bounded path under
the configured worktree root for each accepted ticket ID. It invokes
`codex exec` with a workspace-write sandbox and non-interactive approval mode,
then runs `./gradlew test` by default. Set
`BLOCK_AGENT_SPACES_CODEX_TEST_COMMAND` to a simple space-separated local test
command when this repository uses something else. No shell is used to execute
that value.

Actual Codex usage begins only after both opt-ins are set and an explicit
Minecraft request arrives. Routine status changes, bridge polling, notebook
views, and the graph consume no Codex usage.

## Expected Minecraft flow

1. Open Goat's notebook and send `codex: ticket-123 | ...`.
2. The ticket board shows `assigned`, then `in_progress` only when a real local
   job begins.
3. It reaches `awaiting_review` only after the Codex process and configured
   test command succeed. Goat can then assign a reviewer and make a separate,
   explicit acceptance decision.
4. A failure is visible as `blocked`, with a concise safe reason. Inspect the
   bounded local worktree/artifacts before deciding whether to retry with a new
   ticket request.

If Codex is absent or not authenticated, the ticket blocks; the adapter does
not fall back to another model or silently retry. If a process times out or the
adapter is interrupted after recording a launch, it blocks rather than running
the same job twice.

## Local boundaries

- The bridge and adapter use loopback HTTP only.
- Never place the bridge token, account credentials, or API keys in messages,
  tickets, the Minecraft chat, or repository files.
- Do not aim the worktree root at the repository itself. The adapter refuses
  paths that escape its configured root and never removes an existing ticket
  workspace for you.
- Treat `awaiting_review` as a handoff, not an approval or merge.

For the Codex CLI's non-interactive job pattern, see the official [Codex
iterative repair example](https://developers.openai.com/cookbook/examples/codex/build_iterative_repair_loops_with_codex).
