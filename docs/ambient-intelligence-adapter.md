# Ambient intelligence adapter

`examples/ambient_intelligence_adapter.py` is an optional local observer for
Block Agent Spaces. It reads the existing local bridge event feed, sees message
activity, and produces a deliberately small insight:

- a fixed category: `status`, `question`, `blocker`, `request`, `review`, or
  `other`;
- a concise status summary; and
- a non-binding follow-up flag: `none`, `surface_to_goat`, or `check_later`.

The observer is not a lead agent. It **cannot** assign tickets, create
workspaces, execute code, merge code, approve a review, or replace Goat. Git,
agent execution, ticket authority, and Goat’s real routing remain external
orchestrator and adapter responsibilities.

## Quickstart

1. Start a Minecraft world with the mod’s local bridge running.
2. Run the fallback-only observer—no model, download, package installation, or
   API key is required:

   ```bash
   python3 examples/ambient_intelligence_adapter.py
   ```

3. Send or receive an agent/Minecraft message. The observer prints one JSON
   summary for each new message. It only watches the local bridge.
4. Add `--publish` if you also want each bounded observation added to the
   in-game conversation log as an `ambient-observer` note to Goat. These notes
   are explicitly non-binding.

The example stores its cursor and deduplication set only in memory. Restarting
it can cause it to inspect existing messages again. A production adapter must
persist those values before acknowledging an outbox cursor.

## Optional local-model mode

The default deterministic rules are always available. To request a better
short summary, point the adapter at a **locally hosted** OpenAI-compatible
chat-completions endpoint. Choose any small model your local server already
hosts; this project does not download, install, or prescribe a model.

```bash
export BLOCK_AGENT_AMBIENT_MODEL_URL='http://127.0.0.1:PORT/v1/chat/completions'
export BLOCK_AGENT_AMBIENT_MODEL='your-local-model-identifier'
export BLOCK_AGENT_AMBIENT_TIMEOUT_SECONDS='2'
python3 examples/ambient_intelligence_adapter.py --publish
```

The endpoint URL must be loopback-only (`127.0.0.1`, `localhost`, or `::1`).
No external API key is read or sent. If the endpoint is unreachable, too slow,
returns malformed JSON, or proposes a value outside the fixed schema, the
adapter silently falls back to transparent deterministic rules.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `BLOCK_AGENT_SPACES_URL` | `http://127.0.0.1:8787` | Local mod bridge URL; must be loopback-only. |
| `BLOCK_AGENT_AMBIENT_MODEL_URL` | empty | Optional local compatible chat-completions URL; must be loopback-only. |
| `BLOCK_AGENT_AMBIENT_MODEL` | empty | Model identifier understood by that local server. |
| `BLOCK_AGENT_AMBIENT_TIMEOUT_SECONDS` | `2` | Maximum model request time before deterministic fallback. |

The example uses only bridge endpoints already supplied by the mod:
`GET /v1/events?after=`, `GET /v1/messages`, `POST /v1/events/ack`, and—only
with `--publish`—`POST /v1/messages`.

## Checks

Run the offline test suite, including a fake compatible model response:

```bash
python3 examples/ambient_intelligence_adapter.py --self-test
```

Or run a syntax check:

```bash
python3 -m py_compile examples/ambient_intelligence_adapter.py
```
