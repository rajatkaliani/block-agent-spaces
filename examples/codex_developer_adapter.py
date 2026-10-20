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
``codex exec`` non-interactively. The adapter commits a validated ticket diff
outside the sandbox, because the sandbox protects Git metadata. It never calls ``git merge``, ``git push``,
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
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from goat_bridge_adapter import acknowledgement_id, ordinary_goat_reply, player_message_to_goat


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
    def snapshot(self) -> dict[str, Any]: ...
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


@dataclass(frozen=True)
class JobResult:
    base_revision: str
    head_revision: str


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
            "Implement only the requested change and run relevant tests. The trusted local adapter will make the ticket commit after validation. "
            "Do not create commits, add secrets or generated files, or touch Git metadata. Leave a concise final summary. "
            "Do not merge, push, delete a branch or worktree, alter remotes, or create additional worktrees. "
            "Do not claim review or acceptance.\n\n"
            f"Ticket {prompt}"
        )
        return [
            codex, "exec", "--sandbox", "workspace-write", "--config", "approval_policy=never",
            "--output-last-message", str(last_message), guarded_prompt,
        ]

    def _git_revision(self, workspace: Path) -> str:
        result = self.command(["git", "rev-parse", "HEAD"], workspace, 20)
        revision = result.stdout.strip()
        if result.returncode != 0 or not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", revision):
            raise JobError("Git could not report a valid ticket revision")
        return revision

    def _changed_paths(self, workspace: Path) -> list[str]:
        paths: set[str] = set()
        for command in (
            ["git", "diff", "--name-only", "-z"],
            ["git", "diff", "--cached", "--name-only", "-z"],
            ["git", "ls-files", "--others", "--exclude-standard", "-z"],
        ):
            result = self.command(command, workspace, 20)
            if result.returncode != 0:
                raise JobError("Git could not enumerate ticket changes for review")
            paths.update(path for path in result.stdout.split("\0") if path)
        if not paths:
            raise JobError("Codex finished without ticket changes; no review request was created")
        if len(paths) > 100:
            raise JobError("Ticket changed more than 100 files; inspect the worktree before committing")
        for path in paths:
            candidate = workspace / path
            name = candidate.name.lower()
            if (candidate.is_symlink() or not candidate.resolve().is_relative_to(workspace)
                    or any(part in {".git", ".codex", "build", "dist", "target", "__pycache__"} for part in Path(path).parts)
                    or name.startswith(".env") or name in {".npmrc", ".pypirc", "id_rsa", "id_ed25519"}
                    or name.endswith((".pem", ".p12", ".pfx", ".log", ".class", ".jar", ".pyc", ".zip"))):
                raise JobError("Ticket includes a protected or unsafe path; inspect the worktree before committing")
            if candidate.is_file() and candidate.stat().st_size > 1_000_000:
                raise JobError("Ticket includes a file larger than 1 MB; inspect the worktree before committing")
        return sorted(paths)

    def _commit_ticket_changes(self, workspace: Path, request: TicketRequest) -> None:
        paths = self._changed_paths(workspace)
        added = self.command(["git", "add", "--", *paths], workspace, 20)
        if added.returncode != 0:
            raise JobError("Git could not stage ticket changes; no review request was created")
        whitespace = self.command(["git", "diff", "--cached", "--check"], workspace, 20)
        if whitespace.returncode != 0:
            raise JobError("Ticket has staged whitespace errors; no commit or review request was created")
        committed = self.command([
            "git", "-c", "user.name=Block Agent Spaces", "-c", "user.email=block-agent-spaces@local.invalid",
            "commit", "-m", f"feat: implement {request.ticket_id}",
        ], workspace, self.timeout_seconds)
        if committed.returncode != 0:
            raise JobError("Git could not commit the validated ticket changes; no review request was created")

    def _review_evidence(self, workspace: Path, base_revision: str) -> JobResult:
        status = self.command(["git", "status", "--porcelain=v1"], workspace, 20)
        if status.returncode != 0:
            raise JobError("Git could not verify the ticket workspace after tests")
        if status.stdout.strip():
            raise JobError("Ticket worktree has uncommitted changes; commit or clean them before review")
        head_revision = self._git_revision(workspace)
        if head_revision == base_revision:
            raise JobError("Codex finished without a new ticket commit; no review request was created")
        return JobResult(base_revision, head_revision)

    def run(self, request: TicketRequest) -> JobResult:
        workspace = self._create_worktree(request)
        base_revision = self._git_revision(workspace)
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
        self._commit_ticket_changes(workspace, request)
        return self._review_evidence(workspace, base_revision)


class CodexDeveloperAdapter:
    """Polls the ordered bridge feed and publishes only factual workflow states."""

    def __init__(self, bridge: Bridge, store: StateStore, runner: CodexJobRunner | None, execute: bool):
        self.bridge, self.store, self.runner, self.execute = bridge, store, runner, execute
        self.state = store.load()

    def _publish(self, request: TicketRequest, phase: str, detail: str, evidence: JobResult | None = None) -> None:
        state = "BLOCKED" if phase == "blocked" else "COMPLETE" if phase == "awaiting_review" else "WORKING"
        review = "awaiting_review" if phase == "awaiting_review" else "not_requested"
        workspace = (f"isolated ticket workspace: {request.ticket_id}" if phase == "awaiting_review" else
                     "may exist; inspect locally" if self.execute and phase == "blocked" else
                     "requested, not confirmed" if self.execute else "not created (dry run)")
        branch = (f"agent/block-agent-spaces/{request.ticket_id}" if phase == "awaiting_review" else
                  "not confirmed" if self.execute else "not created (dry run)")
        for node in (
            {"id": "agent-goat", "label": "Goat", "type": "agent"},
            {"id": "agent-codex-builder", "label": "Codex Builder", "type": "agent"},
            {"id": request.ticket_id, "label": f"Ticket {request.ticket_id}", "type": "ticket"},
        ):
            self.bridge.post("/v1/graph/nodes", node)
        for edge in (
            {"id": f"goat-assigns-{request.ticket_id}", "sourceId": "agent-goat", "targetId": request.ticket_id, "relationship": "assigns"},
            {"id": f"builder-develops-{request.ticket_id}", "sourceId": "agent-codex-builder", "targetId": request.ticket_id, "relationship": "develops"},
        ):
            self.bridge.post("/v1/graph/edges", edge)
        if evidence is not None:
            for revision in (evidence.base_revision, evidence.head_revision):
                self.bridge.post("/v1/graph/nodes", {
                    "id": f"revision-{revision[:12]}", "label": f"Revision {revision[:12]}", "type": "history",
                })
            for kind, revision, relationship in (
                ("base", evidence.base_revision, "branched_from"),
                ("head", evidence.head_revision, "proposes"),
            ):
                self.bridge.post("/v1/graph/edges", {
                    "id": f"ticket-{kind}-{request.ticket_id}", "sourceId": request.ticket_id,
                    "targetId": f"revision-{revision[:12]}", "relationship": relationship,
                })
        self.bridge.post("/v1/tasks", {
            "id": request.ticket_id, "title": f"Codex ticket {request.ticket_id}",
            "description": detail, "status": phase,
        })
        self.bridge.post("/v1/agents", {
            "id": DEVELOPER_ID, "displayName": "Codex Builder", "state": state,
            "taskId": request.ticket_id, "ticketId": request.ticket_id,
            "workspace": workspace, "branch": branch, "reviewStatus": review,
            "acceptanceStatus": "not_accepted", "detail": detail, "graphFocus": ["agent-codex-builder", request.ticket_id],
        })
        self.bridge.post("/v1/agents", {
            "id": GOAT_ID, "displayName": "Goat", "state": "REVIEWING" if phase == "awaiting_review" else "WORKING",
            "taskId": request.ticket_id, "ticketId": request.ticket_id,
            "workspace": "control-room", "branch": "", "reviewStatus": review,
            "acceptanceStatus": "pending", "detail": f"Coordinating {request.ticket_id}: {phase}. {detail}",
            "graphFocus": ["agent-goat", request.ticket_id],
        })
        self.bridge.post("/v1/messages", {
            "id": stable_id("codex-runtime", request.message_id, phase), "from": DEVELOPER_ID,
            "to": GOAT_ID, "body": f"Codex ticket {request.ticket_id}: {phase}. {detail}",
        })
        self.bridge.post("/v1/messages", {
            "id": stable_id("goat-report", request.message_id, phase), "from": GOAT_ID,
            "to": "minecraft-player", "body": f"Ticket {request.ticket_id}: {phase}. {detail}",
        })

    def _finish(self, request: TicketRequest) -> None:
        self.state.mark_handled(request.message_id)
        self.store.save(self.state)

    def _reply_without_job(self, message: dict[str, Any], published_ids: set[str]) -> None:
        message_id = message["id"]
        reply_id = acknowledgement_id(message_id)
        if reply_id not in published_ids:
            self.bridge.post("/v1/messages", {
                "id": reply_id, "from": GOAT_ID, "to": "minecraft-player",
                "body": ordinary_goat_reply(self.bridge, str(message.get("body", ""))),
            })
        self.state.mark_handled(message_id)
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
            evidence = self.runner.run(request)
        except (JobError, FileNotFoundError, subprocess.TimeoutExpired, OSError) as error:
            self._publish(request, "blocked", concise_failure(error))
        else:
            self._publish(request, "awaiting_review",
                          f"Adapter committed ticket revision {evidence.head_revision[:12]} from {evidence.base_revision[:12]}; configured tests passed. Review, acceptance, merge, and push have not occurred.",
                          evidence)
        self._finish(request)

    def poll_once(self) -> int:
        feed = self.bridge.events_after(self.state.cursor)
        events, next_cursor = feed.get("events"), feed.get("nextCursor")
        if not isinstance(events, list) or not isinstance(next_cursor, int) or next_cursor < self.state.cursor:
            raise AdapterError("bridge returned an invalid ordered event feed")
        if any(isinstance(event, dict) and event.get("type") == "message.created" for event in events):
            messages = self.bridge.messages()
            published_ids = {message.get("id") for message in messages if isinstance(message.get("id"), str)}
            for message in messages:
                if not player_message_to_goat(message) or self.state.handled(message["id"]):
                    continue
                request = parse_request(message)
                if request:
                    self._run_request(request)
                else:
                    self._reply_without_job(message, published_ids)
        self.state.cursor = next_cursor
        self.store.save(self.state)
        self.bridge.post("/v1/events/ack", {"consumer": CONSUMER, "cursor": next_cursor})
        return len(events)


class FakeBridge:
    """Offline double for dry-run and replay tests."""

    def __init__(self):
        self.feed: dict[str, Any] = {"events": [], "nextCursor": 0}
        self.message_list: list[dict[str, Any]] = []
        self.snapshot_data: dict[str, Any] = {"agents": [], "tasks": []}
        self.posts: list[tuple[str, dict[str, Any]]] = []

    def health(self) -> dict[str, Any]:
        return {"status": "ok", "bridge": "block-agent-spaces", "mutationMode": "explicit_demo"}

    def events_after(self, cursor: int) -> dict[str, Any]:
        return self.feed

    def messages(self) -> list[dict[str, Any]]:
        return list(self.message_list)

    def snapshot(self) -> dict[str, Any]:
        return self.snapshot_data

    def post(self, path: str, payload: dict[str, Any]) -> Any:
        self.posts.append((path, dict(payload)))
        if path == "/v1/messages":
            self.message_list.append(dict(payload))
        return {"accepted": True}


class CodexDeveloperAdapterTest(unittest.TestCase):
    def test_ticket_commit_is_isolated_and_rejects_secret_like_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory).resolve()
            subprocess.run(["git", "init", "-q"], cwd=workspace, check=True, capture_output=True)
            source = workspace / "app.py"
            source.write_text("print('base')\n", encoding="utf-8")
            subprocess.run(["git", "add", "app.py"], cwd=workspace, check=True, capture_output=True)
            subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@local.invalid", "commit", "-qm", "test: base"],
                           cwd=workspace, check=True, capture_output=True)
            runner = CodexJobRunner(workspace, workspace / "worktrees", 5)
            base = runner._git_revision(workspace)
            source.write_text("print('changed')\n", encoding="utf-8")
            request = TicketRequest("message-1", "ticket-1", "Change output")
            runner._commit_ticket_changes(workspace, request)
            result = runner._review_evidence(workspace, base)
            self.assertNotEqual(base, result.head_revision)
            self.assertEqual("feat: implement ticket-1", subprocess.run(
                ["git", "log", "-1", "--format=%s"], cwd=workspace, check=True, capture_output=True, text=True).stdout.strip())
            (workspace / ".env.local").write_text("SECRET=fixture\n", encoding="utf-8")
            with self.assertRaisesRegex(JobError, "unsafe path"):
                runner._commit_ticket_changes(workspace, TicketRequest("message-2", "ticket-2", "More work"))

    def test_single_runtime_answers_goat_status_without_launching_job(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bridge = FakeBridge()
            bridge.feed = {"events": [{"type": "message.created"}], "nextCursor": 1}
            bridge.message_list = [{"id": "status-1", "from": "minecraft-player", "to": "goat", "body": "status ticket-7"}]
            bridge.snapshot_data = {"agents": [], "tasks": [{"id": "ticket-7", "title": "Improve graph", "status": "awaiting_review"}]}
            adapter = CodexDeveloperAdapter(bridge, StateStore(Path(directory) / "state.json"), None, execute=False)
            adapter.poll_once()
            replies = [payload for path, payload in bridge.posts if path == "/v1/messages"]
            self.assertEqual(1, len(replies))
            self.assertIn("awaiting_review", replies[0]["body"])
            self.assertFalse(any(path in {"/v1/tasks", "/v1/agents"} for path, _ in bridge.posts))
            adapter.poll_once()
            self.assertEqual(1, len([payload for path, payload in bridge.posts if path == "/v1/messages"]))

    def test_codex_command_uses_supported_noninteractive_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            runner = CodexJobRunner(Path(directory), Path(directory) / "worktrees", 5)
            with patch("shutil.which", return_value="/usr/local/bin/codex"):
                command = runner._codex_command("Add a test", Path(directory) / "last-message.txt")
            self.assertEqual(command[:6], ["/usr/local/bin/codex", "exec", "--sandbox", "workspace-write", "--config", "approval_policy=never"])
            self.assertNotIn("--ask-for-approval", command)

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
            self.assertTrue(any(path == "/v1/graph/nodes" and payload["id"] == "ticket-1" for path, payload in bridge.posts))
            self.assertTrue(any(path == "/v1/graph/edges" and payload["relationship"] == "assigns" for path, payload in bridge.posts))
            reports = [payload for path, payload in bridge.posts if path == "/v1/messages" and payload["from"] == GOAT_ID and payload["to"] == "minecraft-player"]
            self.assertEqual(2, len(reports))
            self.assertTrue(any("blocked" in report["body"] for report in reports))
            goat_updates = [payload for path, payload in bridge.posts if path == "/v1/agents" and payload["id"] == GOAT_ID]
            self.assertTrue(all(update["state"] == "WORKING" for update in goat_updates))

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

    def test_success_hands_builder_to_review_and_reports_to_player(self) -> None:
        class SuccessfulRunner:
            def run(self, request: TicketRequest) -> JobResult:
                self.request = request
                return JobResult("a" * 40, "b" * 40)

        with tempfile.TemporaryDirectory() as directory:
            bridge = FakeBridge()
            bridge.feed = {"events": [{"type": "message.created"}], "nextCursor": 1}
            bridge.message_list = [{"id": "m1", "from": "minecraft-player", "to": "goat", "body": "codex: ticket-1 | Add a test"}]
            runner = SuccessfulRunner()
            CodexDeveloperAdapter(bridge, StateStore(Path(directory) / "state.json"), runner, execute=True).poll_once()
            self.assertEqual(["assigned", "in_progress", "awaiting_review"],
                             [payload["status"] for path, payload in bridge.posts if path == "/v1/tasks"])
            builder = [payload for path, payload in bridge.posts if path == "/v1/agents" and payload["id"] == DEVELOPER_ID][-1]
            goat = [payload for path, payload in bridge.posts if path == "/v1/agents" and payload["id"] == GOAT_ID][-1]
            self.assertEqual("COMPLETE", builder["state"])
            self.assertEqual("REVIEWING", goat["state"])
            self.assertTrue(any(path == "/v1/messages" and payload["from"] == GOAT_ID and "awaiting_review" in payload["body"]
                                for path, payload in bridge.posts))
            self.assertTrue(any(path == "/v1/graph/edges" and payload["relationship"] == "proposes"
                                for path, payload in bridge.posts))

    def test_review_evidence_requires_a_clean_new_commit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = "a" * 40
            head = "b" * 40
            outputs = {"head": base, "status": ""}

            def fake_command(command: list[str], cwd: Path, timeout: int) -> subprocess.CompletedProcess[str]:
                value = outputs["status"] if "status" in command else outputs["head"]
                return subprocess.CompletedProcess(command, 0, stdout=value)

            runner = CodexJobRunner(Path(directory), Path(directory) / "worktrees", 5, fake_command)
            with self.assertRaisesRegex(JobError, "without a new ticket commit"):
                runner._review_evidence(Path(directory), base)
            outputs["head"] = head
            outputs["status"] = " M src/example.py"
            with self.assertRaisesRegex(JobError, "uncommitted changes"):
                runner._review_evidence(Path(directory), base)
            outputs["status"] = ""
            self.assertEqual(JobResult(base, head), runner._review_evidence(Path(directory), base))

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
    print("Send Goat a status question or request a ticket in its notebook. Press Ctrl-C to stop.")
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
