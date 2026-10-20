#!/usr/bin/env python3
"""A resilient, zero-dependency Block Agent Spaces Goat bridge adapter.

Quick start (the token value is deliberately never printed or saved):

  1. Start Minecraft with the bridge configured with a pairing token, for
     example via ``-Dblockagentspaces.bridge.token=...``.
  2. Export that same value only into this adapter process:
       export BLOCK_AGENT_SPACES_BRIDGE_TOKEN='your-pairing-token'
  3. Run ``python3 examples/goat_bridge_adapter.py``.
  4. Click Goat in Minecraft and send a short message.

The adapter stores its cursor and handled message IDs in a private local state
file so a restart does not replay visible replies.  Override its location with
``--state-file`` or ``BLOCK_AGENT_SPACES_ADAPTER_STATE``.  The default is a
per-user local state directory, not this repository.

For an intentionally unauthenticated local demo, start the bridge explicitly
with ``blockagentspaces.bridge.demo=true``.  The adapter detects and labels
that mode; it does not silently treat a pairing-required bridge as a demo.

This is an integration boundary example, not an agent runtime.  It receives
Minecraft-to-Goat messages and publishes only supported agent state and
messages.  It does not execute a model, create tickets, create Git worktrees,
review code, or merge changes.

Goat can answer a small set of status questions from the bridge snapshot
without spending model tokens: ``status``, ``what is Builder doing?``, or
``status ticket-123``. These are read-only summaries of published state, not
independent verification of the external agent's work.

Run ``python3 examples/goat_bridge_adapter.py --self-test`` for offline tests.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import time
import unittest
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Protocol
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


DEFAULT_BRIDGE_URL = "http://127.0.0.1:8787"
TOKEN_ENVIRONMENT = "BLOCK_AGENT_SPACES_BRIDGE_TOKEN"
STATE_ENVIRONMENT = "BLOCK_AGENT_SPACES_ADAPTER_STATE"
CONSUMER = "goat-bridge-adapter"
GOAT_ID = "goat"
MAX_HANDLED_IDS = 2_048


class AdapterError(RuntimeError):
    """A safe-to-display adapter configuration or bridge error."""


class StateError(AdapterError):
    """The local replay-protection state cannot be trusted."""


def default_state_file() -> Path:
    """Choose a user-local state location without writing runtime data to Git."""
    configured = os.environ.get(STATE_ENVIRONMENT)
    if configured:
        return Path(configured).expanduser()
    state_home = os.environ.get("XDG_STATE_HOME")
    root = Path(state_home).expanduser() if state_home else Path.home() / ".local" / "state"
    return root / "block-agent-spaces" / "goat-bridge-adapter.json"


@dataclass
class AdapterState:
    """Only durable replay metadata; credentials and message bodies never belong here."""

    cursor: int = 0
    handled_message_ids: list[str] = field(default_factory=list)

    def has_handled(self, message_id: str) -> bool:
        return message_id in self.handled_message_ids

    def mark_handled(self, message_id: str) -> None:
        if message_id in self.handled_message_ids:
            return
        self.handled_message_ids.append(message_id)
        if len(self.handled_message_ids) > MAX_HANDLED_IDS:
            del self.handled_message_ids[:-MAX_HANDLED_IDS]

    def payload(self) -> dict[str, Any]:
        return {
            "schemaVersion": 1,
            "cursor": self.cursor,
            "handledMessageIds": self.handled_message_ids,
        }


class StateStore:
    """Atomically persists enough state to make feed replay safe after a restart."""

    def __init__(self, path: Path):
        self.path = path

    def load(self) -> AdapterState:
        if not self.path.exists():
            return AdapterState()
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            if raw.get("schemaVersion") != 1:
                raise StateError("adapter state has an unsupported schema version")
            cursor = raw.get("cursor")
            handled = raw.get("handledMessageIds")
            if not isinstance(cursor, int) or cursor < 0:
                raise StateError("adapter state has an invalid cursor")
            if not isinstance(handled, list) or any(not isinstance(item, str) or not item for item in handled):
                raise StateError("adapter state has invalid handled message IDs")
            return AdapterState(cursor, handled[-MAX_HANDLED_IDS:])
        except (OSError, json.JSONDecodeError, TypeError) as error:
            raise StateError("adapter state cannot be read; choose a new --state-file after inspecting it") from error

    def save(self, state: AdapterState) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix=".goat-bridge-", suffix=".tmp", dir=self.path.parent)
        temporary = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                json.dump(state.payload(), output, separators=(",", ":"), sort_keys=True)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, 0o600)
            os.replace(temporary, self.path)
            os.chmod(self.path, 0o600)
        except OSError as error:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
            raise StateError("adapter state cannot be saved; refusing to advance the event cursor") from error


class Bridge(Protocol):
    def health(self) -> dict[str, Any]: ...
    def events_after(self, cursor: int) -> dict[str, Any]: ...
    def messages(self) -> list[dict[str, Any]]: ...
    def snapshot(self) -> dict[str, Any]: ...
    def post(self, path: str, payload: dict[str, Any]) -> Any: ...


class BridgeClient:
    """Small loopback client. The token is used only in mutation headers."""

    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self._token = token

    def _headers(self, mutation: bool) -> dict[str, str]:
        headers = {"Content-Type": "application/json"} if mutation else {}
        if mutation and self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        return headers

    def _request(self, path: str, method: str = "GET", payload: dict[str, Any] | None = None) -> Any:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        headers = self._headers(body is not None)
        try:
            with urlopen(Request(self.base_url + path, data=body, method=method, headers=headers), timeout=3) as response:
                return json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            # Do not include a server response in errors: proxies must never
            # get an opportunity to reflect credentials into a terminal log.
            raise AdapterError(f"bridge returned HTTP {error.code}") from error
        except URLError as error:
            raise AdapterError("cannot reach the local bridge; open a Minecraft world with the mod first") from error

    def health(self) -> dict[str, Any]:
        result = self._request("/health")
        return require_object(result, "health response")

    def events_after(self, cursor: int) -> dict[str, Any]:
        result = self._request(f"/v1/events?after={cursor}")
        return require_object(result, "event response")

    def messages(self) -> list[dict[str, Any]]:
        result = self._request("/v1/messages")
        if not isinstance(result, list) or any(not isinstance(message, dict) for message in result):
            raise AdapterError("bridge returned an invalid message list")
        return result

    def snapshot(self) -> dict[str, Any]:
        result = require_object(self._request("/v1/snapshot"), "snapshot")
        if any(not isinstance(result.get(key), list) for key in ("agents", "tasks")):
            raise AdapterError("bridge returned an invalid agent/task snapshot")
        return result

    def post(self, path: str, payload: dict[str, Any]) -> Any:
        return self._request(path, "POST", payload)


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise AdapterError(f"bridge returned an invalid {label}")
    return value


def bridge_mode(health: dict[str, Any], token_configured: bool) -> str:
    """Validate the bridge's advertised mode before any mutation is attempted."""
    if health.get("status") != "ok" or health.get("bridge") != "block-agent-spaces":
        raise AdapterError("the local service is not a Block Agent Spaces bridge")
    mode = health.get("mutationMode")
    if mode == "explicit_demo":
        return "explicit_demo"
    if mode == "paired" and token_configured:
        return "paired"
    if mode == "paired":
        raise AdapterError(f"bridge is paired; set {TOKEN_ENVIRONMENT} for this adapter")
    if mode == "pairing_required":
        raise AdapterError("bridge requires pairing; configure a bridge token or explicitly enable demo mode")
    raise AdapterError("bridge reported an unsupported mutation mode")


def player_message_to_goat(message: dict[str, Any]) -> bool:
    return (
        message.get("from") == "minecraft-player"
        and isinstance(message.get("to"), str)
        and message["to"].strip().lower() == GOAT_ID
        and isinstance(message.get("id"), str)
        and bool(message["id"])
    )


def acknowledgement_id(incoming_id: str) -> str:
    """A bounded deterministic output ID survives replay without leaking input text."""
    digest = hashlib.sha256(incoming_id.encode("utf-8")).hexdigest()[:40]
    return f"goat-ack-{digest}"


def status_subject(body: str) -> str | None:
    """Recognize a deliberately small set of read-only status questions."""
    normalized = " ".join(body.lower().strip().rstrip("?.!").split())
    if normalized in {"status", "team status", "what's happening", "what is happening", "what are the agents doing"}:
        return "team"
    match = re.fullmatch(r"(?:what is |what's )(.+?) doing", normalized)
    if match:
        return match.group(1)
    match = re.fullmatch(r"(?:status of |status for |status )(.+)", normalized)
    return match.group(1) if match else None


def status_reply(snapshot: dict[str, Any], subject: str) -> str:
    """Summarize published bridge facts without hallucinating work or replies."""
    agents = [item for item in snapshot["agents"] if isinstance(item, dict)]
    tasks = [item for item in snapshot["tasks"] if isinstance(item, dict)]

    def agent_line(agent: dict[str, Any]) -> str:
        name = str(agent.get("displayName") or agent.get("id") or "Agent")[:60]
        state = str(agent.get("state") or "unknown").lower()[:32]
        ticket = str(agent.get("ticketId") or agent.get("taskId") or "")[:80]
        review = str(agent.get("reviewStatus") or "")[:40]
        detail = str(agent.get("detail") or "")[:220]
        fields = [f"{name}: {state}"]
        if ticket:
            fields.append(f"ticket {ticket}")
        if review and review not in {"not_requested", "not_applicable"}:
            fields.append(f"review {review}")
        if detail:
            fields.append(detail)
        return " · ".join(fields)

    if subject == "team":
        active = [agent for agent in agents if str(agent.get("state", "")).upper() != "IDLE"]
        recent = [task for task in tasks if str(task.get("status", "")).lower() not in {"done", "accepted"}]
        lines = ["Reported bridge status (no agent run triggered):"]
        lines.extend(agent_line(agent) for agent in active[:4])
        lines.extend(f"{str(task.get('id') or 'ticket')[:80]}: {str(task.get('status') or 'unknown')[:40]}" for task in recent[:4])
        if len(lines) == 1:
            lines.append("No active agents or tickets have been published yet.")
        if len(active) > 4 or len(recent) > 4:
            lines.append(f"Showing 4 of {len(active)} active agents and 4 of {len(recent)} open tickets at most.")
        return "\n".join(lines)

    requested = subject.casefold()
    for agent in agents:
        if requested in {str(agent.get("id", "")).casefold(), str(agent.get("displayName", "")).casefold()}:
            return "Reported bridge status: " + agent_line(agent)
    for task in tasks:
        if requested == str(task.get("id", "")).casefold():
            return f"Reported ticket {str(task.get('id'))[:80]}: {str(task.get('status') or 'unknown')[:40]} · {str(task.get('title') or '')[:160]}"
    return f"No published agent or ticket matches {subject[:80]}. Open the graph's Agents tab or ask 'status' for the current roster."


def ordinary_goat_reply(bridge: Bridge, body: str) -> str:
    subject = status_subject(body)
    if subject is not None:
        return status_reply(bridge.snapshot(), subject)
    return "Goat recorded your message for the configured external orchestrator. This example does not run agents, create tickets, or perform Git work. Ask 'status' for a no-token state summary."


class GoatBridgeAdapter:
    """Consumes player-to-Goat messages and emits conservative, visible confirmations."""

    def __init__(self, bridge: Bridge, store: StateStore):
        self.bridge = bridge
        self.store = store
        self.state = store.load()

    def ignore_existing_messages(self) -> None:
        """Baseline message IDs before polling; new messages remain eligible."""
        for message in self.bridge.messages():
            if player_message_to_goat(message):
                self.state.mark_handled(message["id"])
        self.store.save(self.state)

    def poll_once(self) -> int:
        feed = self.bridge.events_after(self.state.cursor)
        events = feed.get("events")
        next_cursor = feed.get("nextCursor")
        if not isinstance(events, list) or not isinstance(next_cursor, int) or next_cursor < self.state.cursor:
            raise AdapterError("bridge returned an invalid ordered event feed")

        if any(isinstance(event, dict) and event.get("type") == "message.created" for event in events):
            messages = self.bridge.messages()
            published_ids = {message.get("id") for message in messages if isinstance(message.get("id"), str)}
            for message in messages:
                if player_message_to_goat(message) and not self.state.has_handled(message["id"]):
                    self._handle_player_message(message, published_ids)

        # Save before the remote acknowledgement. If the process stops between
        # these operations, replay is harmless and the bridge acknowledgement
        # will be retried; no reply is emitted twice.
        self.state.cursor = next_cursor
        self.store.save(self.state)
        self.bridge.post("/v1/events/ack", {"consumer": CONSUMER, "cursor": next_cursor})
        return len(events)

    def _handle_player_message(self, message: dict[str, Any], published_ids: set[str]) -> None:
        incoming_id = message["id"]
        reply_id = acknowledgement_id(incoming_id)
        body = str(message.get("body", ""))
        ticket_request = body.strip().lower().startswith("codex:")
        subject = None if ticket_request else status_subject(body)
        # Agent updates are upserts. They are deliberately modest: this
        # adapter never pretends that a ticket, branch, review, or merge exists.
        if not ticket_request and subject is None:
            self.bridge.post("/v1/agents", {
                "id": GOAT_ID,
                "displayName": "Goat",
                "state": "WORKING",
                "taskId": "",
                "ticketId": "",
                "workspace": "external-orchestrator",
                "branch": "",
                "reviewStatus": "not_applicable",
                "acceptanceStatus": "not_applicable",
                "detail": "Received a player message; waiting for the external orchestrator.",
                "graphFocus": [],
            })
        if reply_id not in published_ids:
            if ticket_request:
                reply = "Goat received the ticket request. A running Codex developer adapter can process it; check the ticket board for progress."
            else:
                reply = ordinary_goat_reply(self.bridge, body)
            self.bridge.post("/v1/messages", {
                "id": reply_id,
                "from": GOAT_ID,
                "to": "minecraft-player",
                "body": reply,
            })
        # Mark completion only after the deterministic visible acknowledgement
        # is confirmed or found in the bridge. A restart therefore does not
        # duplicate the lectern/notebook conversation.
        self.state.mark_handled(incoming_id)
        self.store.save(self.state)


def run(arguments: argparse.Namespace) -> None:
    token = os.environ.get(TOKEN_ENVIRONMENT, "").strip()
    client = BridgeClient(os.environ.get("BLOCK_AGENT_SPACES_BRIDGE_URL", DEFAULT_BRIDGE_URL), token)
    mode = bridge_mode(client.health(), bool(token))
    state_file = Path(arguments.state_file).expanduser() if arguments.state_file else default_state_file()
    fresh_state = not state_file.exists()
    adapter = GoatBridgeAdapter(client, StateStore(state_file))
    if fresh_state:
        adapter.ignore_existing_messages()
    print(f"Connected to Block Agent Spaces bridge in {mode.replace('_', ' ')} mode.")
    print("Goat adapter is watching for Minecraft messages. Press Ctrl-C to stop.")
    while True:
        event_count = adapter.poll_once()
        if arguments.once:
            return
        if event_count == 0:
            time.sleep(arguments.interval)


class FakeBridge:
    """Offline bridge double used to prove replay and pairing behavior."""

    def __init__(self, mode: str = "explicit_demo"):
        self.mode = mode
        self.feed: dict[str, Any] = {"events": [], "nextCursor": 0}
        self.message_list: list[dict[str, Any]] = []
        self.snapshot_data: dict[str, Any] = {"agents": [], "tasks": []}
        self.posts: list[tuple[str, dict[str, Any]]] = []

    def health(self) -> dict[str, Any]:
        return {"status": "ok", "bridge": "block-agent-spaces", "mutationMode": self.mode}

    def events_after(self, cursor: int) -> dict[str, Any]:
        return self.feed

    def messages(self) -> list[dict[str, Any]]:
        return list(self.message_list)

    def snapshot(self) -> dict[str, Any]:
        return self.snapshot_data

    def post(self, path: str, payload: dict[str, Any]) -> Any:
        self.posts.append((path, payload))
        if path == "/v1/messages":
            self.message_list.append(dict(payload))
        return {"accepted": True}


class GoatBridgeAdapterTest(unittest.TestCase):
    def test_first_start_ignores_old_message_but_answers_new_one(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = FakeBridge()
            bridge.feed = {"events": [{"type": "message.created"}], "nextCursor": 2}
            bridge.message_list = [{"id": "old", "from": "minecraft-player", "to": "goat", "body": "hello earlier"}]
            adapter = GoatBridgeAdapter(bridge, StateStore(Path(directory) / "state.json"))
            adapter.ignore_existing_messages()
            bridge.message_list.append({"id": "new", "from": "minecraft-player", "to": "goat", "body": "status"})
            adapter.poll_once()
            replies = [payload for path, payload in bridge.posts if path == "/v1/messages"]
            self.assertEqual(1, len(replies))
            self.assertEqual(acknowledgement_id("new"), replies[0]["id"])

    def test_status_questions_use_published_facts_without_overwriting_goat(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = FakeBridge()
            bridge.feed = {"events": [{"id": 1, "type": "message.created"}], "nextCursor": 1}
            bridge.message_list = [{"id": "status-1", "from": "minecraft-player", "to": "goat", "body": "What is Builder doing?"}]
            bridge.snapshot_data = {"agents": [{"id": "builder", "displayName": "Builder", "state": "WORKING", "ticketId": "ticket-7", "detail": "Writing graph tests"}], "tasks": []}
            GoatBridgeAdapter(bridge, StateStore(Path(directory) / "state.json")).poll_once()
            self.assertFalse(any(path == "/v1/agents" for path, _ in bridge.posts))
            replies = [payload["body"] for path, payload in bridge.posts if path == "/v1/messages"]
            self.assertEqual(1, len(replies))
            self.assertIn("ticket ticket-7", replies[0])
            self.assertIn("Writing graph tests", replies[0])

    def test_team_and_ticket_status_are_bounded_and_honest(self) -> None:
        snapshot = {"agents": [{"id": "goat", "displayName": "Goat", "state": "REVIEWING", "reviewStatus": "awaiting_review"}],
                    "tasks": [{"id": "ticket-7", "title": "Graph tests", "status": "awaiting_review"}]}
        self.assertEqual("team", status_subject("What's happening?"))
        self.assertIn("Goat: reviewing", status_reply(snapshot, "team"))
        self.assertIn("awaiting_review", status_reply(snapshot, "ticket-7"))
        self.assertIn("No published agent", status_reply(snapshot, "missing"))

    def test_pairing_token_is_sent_only_for_mutations(self) -> None:
        client = BridgeClient("http://127.0.0.1:8787", "pairing-secret")
        self.assertEqual(client._headers(False), {})
        self.assertEqual(client._headers(True), {
            "Content-Type": "application/json",
            "Authorization": "Bearer pairing-secret",
        })

    def test_bridge_mode_distinguishes_demo_and_pairing(self) -> None:
        self.assertEqual(bridge_mode(FakeBridge("explicit_demo").health(), False), "explicit_demo")
        self.assertEqual(bridge_mode(FakeBridge("paired").health(), True), "paired")
        with self.assertRaises(AdapterError):
            bridge_mode(FakeBridge("pairing_required").health(), True)
        with self.assertRaises(AdapterError):
            bridge_mode(FakeBridge("paired").health(), False)

    def test_restart_replay_does_not_duplicate_visible_reply(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "adapter-state.json"
            bridge = FakeBridge()
            bridge.feed = {"events": [{"id": 1, "type": "message.created"}], "nextCursor": 1}
            bridge.message_list = [{"id": "player-1", "from": "minecraft-player", "to": "goat", "body": "hello"}]
            first = GoatBridgeAdapter(bridge, StateStore(state_file))
            first.poll_once()
            replies = [payload for path, payload in bridge.posts if path == "/v1/messages"]
            self.assertEqual(len(replies), 1)
            self.assertEqual(replies[0]["id"], acknowledgement_id("player-1"))

            second = GoatBridgeAdapter(bridge, StateStore(state_file))
            second.poll_once()
            replies_after_restart = [payload for path, payload in bridge.posts if path == "/v1/messages"]
            self.assertEqual(len(replies_after_restart), 1)

    def test_state_is_private_and_never_serializes_a_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "adapter-state.json"
            StateStore(state_file).save(AdapterState(cursor=9, handled_message_ids=["message-1"]))
            content = state_file.read_text(encoding="utf-8")
            self.assertNotIn("token", content.lower())
            self.assertEqual(StateStore(state_file).load().cursor, 9)
            self.assertEqual(state_file.stat().st_mode & 0o777, 0o600)

    def test_only_minecraft_messages_explicitly_addressed_to_goat_are_handled(self) -> None:
        self.assertTrue(player_message_to_goat({"id": "1", "from": "minecraft-player", "to": " Goat "}))
        self.assertFalse(player_message_to_goat({"id": "2", "from": "minecraft-player", "to": "builder"}))
        self.assertFalse(player_message_to_goat({"id": "3", "from": "adapter", "to": "goat"}))

    def test_ticket_acknowledgement_does_not_overwrite_runtime_goat_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = FakeBridge()
            bridge.feed = {"events": [{"id": 1, "type": "message.created"}], "nextCursor": 1}
            bridge.message_list = [{"id": "ticket-request", "from": "minecraft-player", "to": "goat", "body": "codex: ticket-1 | Add tests"}]
            GoatBridgeAdapter(bridge, StateStore(Path(directory) / "state.json")).poll_once()
            self.assertFalse(any(path == "/v1/agents" for path, _ in bridge.posts))
            replies = [payload for path, payload in bridge.posts if path == "/v1/messages"]
            self.assertEqual(1, len(replies))
            self.assertIn("running Codex developer adapter", replies[0]["body"])


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the resilient Block Agent Spaces Goat bridge adapter.")
    parser.add_argument("--state-file", help="private local replay state path (defaults to a user state directory)")
    parser.add_argument("--interval", type=float, default=1.5, help="idle poll interval in seconds")
    parser.add_argument("--once", action="store_true", help="poll once, then exit")
    parser.add_argument("--self-test", action="store_true", help="run offline standard-library tests")
    args = parser.parse_args()
    if args.interval <= 0:
        parser.error("--interval must be positive")
    return args


if __name__ == "__main__":
    arguments = parse_arguments()
    if arguments.self_test:
        unittest.main(argv=[sys.argv[0]])
    else:
        try:
            run(arguments)
        except (AdapterError, StateError) as error:
            print(f"Goat adapter stopped: {error}", file=sys.stderr)
            raise SystemExit(1) from error
