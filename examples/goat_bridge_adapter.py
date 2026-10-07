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

Run ``python3 examples/goat_bridge_adapter.py --self-test`` for offline tests.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
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


class GoatBridgeAdapter:
    """Consumes player-to-Goat messages and emits conservative, visible confirmations."""

    def __init__(self, bridge: Bridge, store: StateStore):
        self.bridge = bridge
        self.store = store
        self.state = store.load()

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
        # Agent updates are upserts. They are deliberately modest: this
        # adapter never pretends that a ticket, branch, review, or merge exists.
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
            self.bridge.post("/v1/messages", {
                "id": reply_id,
                "from": GOAT_ID,
                "to": "minecraft-player",
                "body": "Goat recorded your message for the configured external orchestrator. This example does not run agents, create tickets, or perform Git work.",
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
    adapter = GoatBridgeAdapter(client, StateStore(state_file))
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
        self.posts: list[tuple[str, dict[str, Any]]] = []

    def health(self) -> dict[str, Any]:
        return {"status": "ok", "bridge": "block-agent-spaces", "mutationMode": self.mode}

    def events_after(self, cursor: int) -> dict[str, Any]:
        return self.feed

    def messages(self) -> list[dict[str, Any]]:
        return list(self.message_list)

    def post(self, path: str, payload: dict[str, Any]) -> Any:
        self.posts.append((path, payload))
        if path == "/v1/messages":
            self.message_list.append(dict(payload))
        return {"accepted": True}


class GoatBridgeAdapterTest(unittest.TestCase):
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
