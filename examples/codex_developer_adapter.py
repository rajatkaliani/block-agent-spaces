#!/usr/bin/env python3
"""A deliberately conservative Codex developer runtime for Block Agent Spaces.

This is an *external* Goat adapter, not part of the Minecraft mod.  It watches
the loopback bridge for a Minecraft message to Goat in this form::

    codex: ticket-123 | Add a concise, testable change to the graph legend.

Dry-run is the default.  A dry run publishes an honest ``assigned`` then
``blocked`` state and never creates a Git worktree or starts Codex.  To permit
real local work, the person running the adapter must set both of these exact
environment values in its process:

    BLOCK_AGENT_SPACES_CODEX_EXECUTE=1
    BLOCK_AGENT_SPACES_CODEX_ALLOW_WORKTREE=1

The real path creates one bounded, per-ticket Git worktree and invokes
``codex exec`` non-interactively.  It never calls ``git merge``, ``git push``,
``git worktree remove``, or any branch deletion command.  A successful job is
only reported as ``awaiting_review``; review and acceptance remain Goat's
separate, explicit responsibility.

Run ``python3 examples/codex_developer_adapter.py --self-test`` for offline
standard-library tests.  See ``docs/codex-developer-runtime.md`` before
enabling execute mode.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
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
STATE_ENVIRONMENT = "BLOCK_AGENT_SPACES_CODEX_STATE"
WORKTREE_ROOT_ENVIRONMENT = "BLOCK_AGENT_SPACES_CODEX_WORKTREE_ROOT"
EXECUTE_ENVIRONMENT = "BLOCK_AGENT_SPACES_CODEX_EXECUTE"
WORKTREE_OPT_IN_ENVIRONMENT = "BLOCK_AGENT_SPACES_CODEX_ALLOW_WORKTREE"
TEST_COMMAND_ENVIRONMENT = "BLOCK_AGENT_SPACES_CODEX_TEST_COMMAND"
CONSUMER = "codex-developer-runtime"
GOAT_ID = "goat"
DEVELOPER_ID = "codex-builder"
MAX_HANDLED_IDS = 2_048
TICKET_PATTERN = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
REQUEST_PATTERN = re.compile(r"^\s*codex\s*:\s*([a-zA-Z0-9-]{1,64})\s*\|\s*(.+?)\s*$", re.DOTALL)


class AdapterError(RuntimeError):
    """A configuration or bridge error that is safe to show to a local user."""


class StateError(AdapterError):
    """The local replay-protection state is invalid or cannot be persisted."""


class JobError(AdapterError):
    """A bounded job failed; its concise reason can be shown in Minecraft."""


def default_state_file() -> Path:
    configured = os.environ.get(STATE_ENVIRONMENT)
    if configured:
        return Path(configured).expanduser()
    state_home = os.environ.get("XDG_STATE_HOME")
    root = Path(state_home).expanduser() if state_home else Path.home() / ".local" / "state"
    return root / "block-agent-spaces" / "codex-developer-runtime.json"


def default_worktree_root() -> Path:
    configured = os.environ.get(WORKTREE_ROOT_ENVIRONMENT)
    if configured:
        return Path(configured).expanduser()
    data_home = os.environ.get("XDG_DATA_HOME")
    root = Path(data_home).expanduser() if data_home else Path.home() / ".local" / "share"
    return root / "block-agent-spaces" / "codex-worktrees"


def bounded_child(root: Path, *parts: str) -> Path:
    """Build a path under a configured root; ticket IDs never become paths unchecked."""
    resolved_root = root.expanduser().resolve()
    candidate = resolved_root.joinpath(*parts).resolve()
    if candidate != resolved_root and resolved_root not in candidate.parents:
        raise JobError("configured worktree location is outside its allowed root")
    return candidate


@dataclass
class RuntimeState:
    cursor: int = 0
    handled_message_ids: list[str] = field(default_factory=list)
    launched_jobs: dict[str, str] = field(default_factory=dict)

    def handled(self, message_id: str) -> bool:
        return message_id in self.handled_message_ids

    def mark_handled(self, message_id: str) -> None:
        if message_id not in self.handled_message_ids:
            self.handled_message_ids.append(message_id)
        if len(self.handled_message_ids) > MAX_HANDLED_IDS:
            del self.handled_message_ids[:-MAX_HANDLED_IDS]
        self.launched_jobs.pop(message_id, None)

    def payload(self) -> dict[str, Any]:
        # Deliberately only cursor and opaque message/ticket identifiers.
        # No pairing token, prompt text, Codex output, or source code is stored.
        return {
            "schemaVersion": 1,
            "cursor": self.cursor,
            "handledMessageIds": self.handled_message_ids,
            "launchedJobs": self.launched_jobs,
        }


class StateStore:
    """Atomic, user-private durable state for replay protection."""

    def __init__(self, path: Path):
        self.path = path

    def load(self) -> RuntimeState:
        if not self.path.exists():
            return RuntimeState()
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            cursor = raw.get("cursor")
            handled = raw.get("handledMessageIds")
            launched = raw.get("launchedJobs")
            valid_ids = isinstance(handled, list) and all(isinstance(item, str) and item for item in handled)
            valid_jobs = isinstance(launched, dict) and all(
                isinstance(message_id, str) and message_id and isinstance(ticket, str) and TICKET_PATTERN.fullmatch(ticket)
                for message_id, ticket in launched.items()
            )
            if raw.get("schemaVersion") != 1 or not isinstance(cursor, int) or cursor < 0 or not valid_ids or not valid_jobs:
                raise StateError("Codex runtime state is invalid; inspect it and choose a new --state-file")
            return RuntimeState(cursor, handled[-MAX_HANDLED_IDS:], dict(launched))
        except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
            if isinstance(error, StateError):
                raise
            raise StateError("Codex runtime state cannot be read; refusing to replay jobs") from error

    def save(self, state: RuntimeState) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix=".codex-runtime-", suffix=".tmp", dir=self.path.parent)
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
            temporary.unlink(missing_ok=True)
            raise StateError("Codex runtime state cannot be saved; refusing to advance the event cursor") from error


class Bridge(Protocol):
    def health(self) -> dict[str, Any]: ...
    def events_after(self, cursor: int) -> dict[str, Any]: ...
    def messages(self) -> list[dict[str, Any]]: ...
    def post(self, path: str, payload: dict[str, Any]) -> Any: ...


class BridgeClient:
    """Loopback-only bridge client; the pairing token is mutation-header only."""

    def __init__(self, base_url: str, token: str):
        if not base_url.startswith("http://127.0.0.1:") and not base_url.startswith("http://localhost:"):
            raise AdapterError("bridge URL must be a loopback HTTP address")
        self.base_url = base_url.rstrip("/")
        self._token = token

    def _request(self, path: str, method: str = "GET", payload: dict[str, Any] | None = None) -> Any:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        headers = {"Content-Type": "application/json"} if body is not None else {}
        if body is not None and self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        try:
            with urlopen(Request(self.base_url + path, data=body, method=method, headers=headers), timeout=3) as response:
                return json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            # Never print remote body: a proxy must not reflect sensitive headers.
            raise AdapterError(f"bridge returned HTTP {error.code}") from error
        except URLError as error:
            raise AdapterError("cannot reach the local bridge; open a Minecraft world with the mod first") from error

    def health(self) -> dict[str, Any]:
        result = self._request("/health")
        return require_object(result, "health response")

    def events_after(self, cursor: int) -> dict[str, Any]:
        return require_object(self._request(f"/v1/events?after={cursor}"), "event response")

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


def validate_bridge(health: dict[str, Any], token_configured: bool) -> str:
    if health.get("status") != "ok" or health.get("bridge") != "block-agent-spaces":
        raise AdapterError("the local service is not a Block Agent Spaces bridge")
    mode = health.get("mutationMode")
    if mode == "explicit_demo":
        return mode
    if mode == "paired" and token_configured:
        return mode
    if mode == "paired":
        raise AdapterError(f"bridge is paired; set {TOKEN_ENVIRONMENT} for this adapter")
    raise AdapterError("bridge requires pairing or explicit demo mode")


@dataclass(frozen=True)
class TicketRequest:
    message_id: str
    ticket_id: str
    prompt: str


def parse_request(message: dict[str, Any]) -> TicketRequest | None:
    """Accept only explicit player-to-Goat commands; ordinary chat is untouched."""
    if message.get("from") != "minecraft-player" or str(message.get("to", "")).strip().lower() != GOAT_ID:
        return None
    message_id = message.get("id")
    match = REQUEST_PATTERN.fullmatch(str(message.get("body", "")))
    if not isinstance(message_id, str) or not message_id or not match:
        return None
    ticket_id = match.group(1).lower()
    prompt = " ".join(match.group(2).split())[:2_000]
    if not TICKET_PATTERN.fullmatch(ticket_id) or not prompt:
        return None
    return TicketRequest(message_id, ticket_id, prompt)


def stable_id(prefix: str, message_id: str, phase: str) -> str:
    digest = hashlib.sha256(message_id.encode("utf-8")).hexdigest()[:32]
    return f"{prefix}-{phase}-{digest}"


def execution_enabled(environment: dict[str, str] | None = None) -> bool:
    environment = os.environ if environment is None else environment
    return environment.get(EXECUTE_ENVIRONMENT) == "1" and environment.get(WORKTREE_OPT_IN_ENVIRONMENT) == "1"


def concise_failure(error: BaseException) -> str:
    if isinstance(error, subprocess.TimeoutExpired):
        return "Timed out; no review or merge was attempted. Inspect the local job artifacts before retrying."
    if isinstance(error, FileNotFoundError):
        return "Codex CLI is not installed or is not on PATH. Install it and authenticate locally before retrying."
    if isinstance(error, JobError):
        return str(error)[:360]
    return "The local Codex job failed before review. Inspect its local artifacts; no review or merge was attempted."


RunCommand = Callable[[list[str], Path, int], subprocess.CompletedProcess[str]]


def run_command(command: list[str], cwd: Path, timeout: int) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, text=True, capture_output=True, timeout=timeout, check=False)


class CodexJobRunner:
    """Creates a bounded isolated worktree and runs one non-interactive Codex job."""

    def __init__(self, repository: Path, worktree_root: Path, timeout_seconds: int, command: RunCommand = run_command):
        self.repository = repository.expanduser().resolve()
        self.worktree_root = worktree_root.expanduser().resolve()
        self.timeout_seconds = timeout_seconds
        self.command = command

    def workspace_for(self, ticket_id: str) -> Path:
        return bounded_child(self.worktree_root, ticket_id)

    def artifacts_for(self, ticket_id: str) -> Path:
        return bounded_child(self.worktree_root, "artifacts", ticket_id)

    def _check_repository(self) -> None:
        if not self.repository.is_dir():
            raise JobError("configured repository does not exist")
        result = self.command(["git", "-C", str(self.repository), "rev-parse", "--is-inside-work-tree"], self.repository, 20)
        if result.returncode != 0 or result.stdout.strip() != "true":
            raise JobError("configured repository is not a Git working tree")

    def _create_worktree(self, request: TicketRequest) -> Path:
        self._check_repository()
        workspace = self.workspace_for(request.ticket_id)
        if workspace.exists():
            raise JobError("ticket workspace already exists; this adapter never removes or reuses it automatically")
        self.worktree_root.mkdir(parents=True, exist_ok=True)
        branch = f"agent/block-agent-spaces/{request.ticket_id}"
        result = self.command(
            ["git", "-C", str(self.repository), "worktree", "add", "-b", branch, str(workspace), "HEAD"],
            self.repository,
            self.timeout_seconds,
        )
        if result.returncode != 0:
            raise JobError("Git could not create the isolated ticket worktree; no Codex job was started")
        return workspace

    def _codex_command(self, prompt: str, last_message: Path) -> list[str]:
        codex = shutil.which("codex")
        if not codex:
            raise FileNotFoundError("codex")
        guarded_prompt = (
            "You are the developer for one isolated ticket workspace. "
            "Implement only the requested change, run relevant tests, and leave a concise final summary. "
            "Do not merge, push, delete a branch or worktree, alter remotes, or create additional worktrees. "
            "Do not claim review or acceptance.\n\n"
            f"Ticket {prompt}"
        )
        return [
            codex, "exec", "--sandbox", "workspace-write", "--ask-for-approval", "never",
            "--output-last-message", str(last_message), guarded_prompt,
        ]

    def run(self, request: TicketRequest) -> None:
        workspace = self._create_worktree(request)
        artifacts = self.artifacts_for(request.ticket_id)
        artifacts.mkdir(parents=True, exist_ok=True)
        last_message = bounded_child(artifacts, "codex-last-message.txt")
        result = self.command(self._codex_command(request.prompt, last_message), workspace, self.timeout_seconds)
        if result.returncode != 0:
            raise JobError("Codex CLI stopped without completing the ticket. Authenticate locally if prompted, then inspect the local job artifacts.")
        test_text = os.environ.get(TEST_COMMAND_ENVIRONMENT, "./gradlew test").strip()
        test_command = test_text.split()
        if not test_command:
            raise JobError("test command is empty; no review can be requested")
        test_result = self.command(test_command, workspace, self.timeout_seconds)
        if test_result.returncode != 0:
            raise JobError("Configured build/tests failed in the isolated workspace; no review or merge was attempted.")


class CodexDeveloperAdapter:
    """Polls the ordered bridge feed and publishes only factual workflow states."""

    def __init__(self, bridge: Bridge, store: StateStore, runner: CodexJobRunner | None, execute: bool):
        self.bridge, self.store, self.runner, self.execute = bridge, store, runner, execute
        self.state = store.load()

    def _publish(self, request: TicketRequest, phase: str, detail: str) -> None:
        state = "BLOCKED" if phase == "blocked" else "REVIEWING" if phase == "awaiting_review" else "WORKING"
        review = "awaiting_review" if phase == "awaiting_review" else "not_requested"
        workspace = f"isolated ticket workspace: {request.ticket_id}" if self.execute else "not created (dry run)"
        branch = f"agent/block-agent-spaces/{request.ticket_id}" if self.execute else "not created (dry run)"
        self.bridge.post("/v1/tasks", {
            "id": request.ticket_id, "title": f"Codex ticket {request.ticket_id}",
            "description": detail, "status": phase,
        })
        self.bridge.post("/v1/agents", {
            "id": DEVELOPER_ID, "displayName": "Codex Builder", "state": state,
            "taskId": request.ticket_id, "ticketId": request.ticket_id,
            "workspace": workspace, "branch": branch, "reviewStatus": review,
            "acceptanceStatus": "not_accepted", "detail": detail, "graphFocus": [request.ticket_id],
        })
        self.bridge.post("/v1/messages", {
            "id": stable_id("codex-runtime", request.message_id, phase), "from": DEVELOPER_ID,
            "to": GOAT_ID, "body": f"Codex ticket {request.ticket_id}: {phase}. {detail}",
        })

    def _finish(self, request: TicketRequest) -> None:
        self.state.mark_handled(request.message_id)
        self.store.save(self.state)

    def _run_request(self, request: TicketRequest) -> None:
        # A durable launch marker is written before a tool call. A restart
        # cannot secretly launch Codex twice; it visibly blocks for inspection.
        if request.message_id in self.state.launched_jobs:
            self._publish(request, "blocked", "A prior Codex run was interrupted before its result was recorded; it was not retried automatically.")
            self._finish(request)
            return
        self._publish(request, "assigned", "Goat assigned a bounded Codex developer job.")
        if not self.execute:
            self._publish(request, "blocked", "Dry run: no worktree was created and Codex was not invoked. Set both execute opt-ins to run local work.")
            self._finish(request)
            return
        self.state.launched_jobs[request.message_id] = request.ticket_id
        self.store.save(self.state)
        self._publish(request, "in_progress", "An isolated worktree was requested; Codex is running locally.")
        try:
            if self.runner is None:
                raise JobError("no Codex job runner is configured")
            self.runner.run(request)
        except (JobError, FileNotFoundError, subprocess.TimeoutExpired, OSError) as error:
            self._publish(request, "blocked", concise_failure(error))
        else:
            self._publish(request, "awaiting_review", "Codex completed and configured build/tests passed. Review, acceptance, merge, and push have not occurred.")
        self._finish(request)

    def poll_once(self) -> int:
        feed = self.bridge.events_after(self.state.cursor)
        events, next_cursor = feed.get("events"), feed.get("nextCursor")
        if not isinstance(events, list) or not isinstance(next_cursor, int) or next_cursor < self.state.cursor:
            raise AdapterError("bridge returned an invalid ordered event feed")
        if any(isinstance(event, dict) and event.get("type") == "message.created" for event in events):
            for message in self.bridge.messages():
                request = parse_request(message)
                if request and not self.state.handled(request.message_id):
                    self._run_request(request)
        self.state.cursor = next_cursor
        self.store.save(self.state)
        self.bridge.post("/v1/events/ack", {"consumer": CONSUMER, "cursor": next_cursor})
        return len(events)


class FakeBridge:
    """Offline double for dry-run and replay tests."""

    def __init__(self):
        self.feed: dict[str, Any] = {"events": [], "nextCursor": 0}
        self.message_list: list[dict[str, Any]] = []
        self.posts: list[tuple[str, dict[str, Any]]] = []

    def health(self) -> dict[str, Any]:
        return {"status": "ok", "bridge": "block-agent-spaces", "mutationMode": "explicit_demo"}

    def events_after(self, cursor: int) -> dict[str, Any]:
        return self.feed

    def messages(self) -> list[dict[str, Any]]:
        return list(self.message_list)

    def post(self, path: str, payload: dict[str, Any]) -> Any:
        self.posts.append((path, dict(payload)))
        if path == "/v1/messages":
            self.message_list.append(dict(payload))
        return {"accepted": True}


class CodexDeveloperAdapterTest(unittest.TestCase):
    def test_request_requires_explicit_syntax_and_address(self) -> None:
        self.assertEqual(parse_request({"id": "m1", "from": "minecraft-player", "to": "goat", "body": "codex: Ticket-1 | Add tests"}).ticket_id, "ticket-1")
        self.assertIsNone(parse_request({"id": "m2", "from": "minecraft-player", "to": "builder", "body": "codex: x | work"}))
        self.assertIsNone(parse_request({"id": "m3", "from": "minecraft-player", "to": "goat", "body": "please code this"}))

    def test_execute_mode_needs_both_environment_opt_ins(self) -> None:
        self.assertFalse(execution_enabled({}))
        self.assertFalse(execution_enabled({EXECUTE_ENVIRONMENT: "1"}))
        self.assertTrue(execution_enabled({EXECUTE_ENVIRONMENT: "1", WORKTREE_OPT_IN_ENVIRONMENT: "1"}))

    def test_default_dry_run_never_calls_runner_and_publishes_truthful_block(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = FakeBridge()
            bridge.feed = {"events": [{"type": "message.created"}], "nextCursor": 1}
            bridge.message_list = [{"id": "m1", "from": "minecraft-player", "to": "goat", "body": "codex: ticket-1 | Add a test"}]
            adapter = CodexDeveloperAdapter(bridge, StateStore(Path(directory) / "state.json"), None, execute=False)
            adapter.poll_once()
            task_states = [payload["status"] for path, payload in bridge.posts if path == "/v1/tasks"]
            self.assertEqual(task_states, ["assigned", "blocked"])
            self.assertTrue(any("Dry run" in payload["body"] for path, payload in bridge.posts if path == "/v1/messages"))

    def test_interrupted_launch_is_not_restarted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = FakeBridge()
            bridge.feed = {"events": [{"type": "message.created"}], "nextCursor": 1}
            bridge.message_list = [{"id": "m1", "from": "minecraft-player", "to": "goat", "body": "codex: ticket-1 | Add a test"}]
            state = RuntimeState(launched_jobs={"m1": "ticket-1"})
            store = StateStore(Path(directory) / "state.json")
            store.save(state)
            adapter = CodexDeveloperAdapter(bridge, store, None, execute=True)
            adapter.poll_once()
            messages = [payload["body"] for path, payload in bridge.posts if path == "/v1/messages"]
            self.assertTrue(any("not retried automatically" in message for message in messages))

    def test_state_is_private_and_never_contains_token_or_prompt(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "state.json"
            StateStore(state_file).save(RuntimeState(cursor=2, handled_message_ids=["message"], launched_jobs={"run": "ticket-1"}))
            serialized = state_file.read_text(encoding="utf-8")
            self.assertNotIn("token", serialized.lower())
            self.assertNotIn("prompt", serialized.lower())
            self.assertEqual(state_file.stat().st_mode & 0o777, 0o600)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the cautious Block Agent Spaces Codex developer runtime.")
    parser.add_argument("--repo", default=".", help="Git repository for isolated ticket worktrees (default: current directory)")
    parser.add_argument("--state-file", help="private local replay state path")
    parser.add_argument("--worktree-root", help="bounded local root for ticket worktrees and artifacts")
    parser.add_argument("--interval", type=float, default=1.5, help="idle poll interval in seconds")
    parser.add_argument("--timeout", type=int, default=900, help="per-command timeout in seconds")
    parser.add_argument("--once", action="store_true", help="poll once, then exit")
    parser.add_argument("--self-test", action="store_true", help="run offline standard-library tests")
    arguments = parser.parse_args()
    if arguments.interval <= 0 or arguments.timeout <= 0:
        parser.error("--interval and --timeout must be positive")
    return arguments


def run(arguments: argparse.Namespace) -> None:
    token = os.environ.get(TOKEN_ENVIRONMENT, "").strip()
    bridge = BridgeClient(os.environ.get("BLOCK_AGENT_SPACES_BRIDGE_URL", DEFAULT_BRIDGE_URL), token)
    mode = validate_bridge(bridge.health(), bool(token))
    execute = execution_enabled()
    state_file = Path(arguments.state_file).expanduser() if arguments.state_file else default_state_file()
    worktree_root = Path(arguments.worktree_root).expanduser() if arguments.worktree_root else default_worktree_root()
    runner = CodexJobRunner(Path(arguments.repo), worktree_root, arguments.timeout) if execute else None
    adapter = CodexDeveloperAdapter(bridge, StateStore(state_file), runner, execute)
    print(f"Connected to Block Agent Spaces bridge in {mode.replace('_', ' ')} mode.")
    print("Codex runtime is in EXECUTE mode." if execute else "Codex runtime is in DRY-RUN mode; no worktree or Codex job can start.")
    print("Send Goat: codex: ticket-id | bounded task description. Press Ctrl-C to stop.")
    while True:
        count = adapter.poll_once()
        if arguments.once:
            return
        if count == 0:
            time.sleep(arguments.interval)


if __name__ == "__main__":
    arguments = parse_arguments()
    if arguments.self_test:
        unittest.main(argv=[sys.argv[0]])
    else:
        try:
            run(arguments)
        except (AdapterError, StateError) as error:
            print(f"Codex developer runtime stopped: {error}", file=sys.stderr)
            raise SystemExit(1) from error
