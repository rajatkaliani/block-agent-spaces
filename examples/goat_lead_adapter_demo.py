#!/usr/bin/env python3
"""A zero-dependency, orchestrator-neutral Goat lead adapter demonstration.

Start a Minecraft world with Block Agent Spaces, then run:

    python3 examples/goat_lead_adapter_demo.py

In the game, send a message addressed to Goat (through the current UI or the
bridge's existing message mechanism). The demo polls the bridge's ordered
outbox, reads that Minecraft-originated message, and publishes a visible
triage/delegation/review simulation using only existing local endpoints.

The bridge is a visualization and message boundary. This demo does not run an
LLM, execute code, create Git worktrees, create remote tickets, review diffs,
or merge branches. A production orchestrator and its Git/agent adapters own
those operations and should replace the deterministic response below.

Run ``python3 examples/goat_lead_adapter_demo.py --self-test`` for an offline
standard-library behavioral test, or ``python3 -m py_compile
examples/goat_lead_adapter_demo.py`` for a syntax check.
"""

from __future__ import annotations

import json
import sys
import time
import unittest
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


BASE_URL = "http://127.0.0.1:8787"
CONSUMER = "goat-lead-demo"
GOAT_ID = "goat"
DEVELOPER_ID = "builder"
REVIEWER_ID = "reviewer"
DEMO_TASK_ID = "demo-goat-triage"


def request(path: str, method: str = "GET", payload: dict[str, Any] | None = None) -> Any:
    """Call one endpoint exposed by the current local Block Agent Spaces bridge."""
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"} if body is not None else {}
    try:
        with urlopen(Request(BASE_URL + path, data=body, method=method, headers=headers), timeout=3) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        detail = error.read().decode("utf-8")
        raise RuntimeError(f"bridge returned {error.code}: {detail}") from error
    except URLError as error:
        raise RuntimeError("cannot reach the local bridge; open a Minecraft world with the mod first") from error


def post(path: str, payload: dict[str, Any]) -> Any:
    return request(path, "POST", payload)


def is_player_message_to_goat(message: dict[str, Any]) -> bool:
    """Only route a player message that explicitly targets Goat."""
    return message.get("from") == "minecraft-player" and message.get("to", "").lower() == GOAT_ID


def compact_player_request(message: dict[str, Any]) -> str:
    """Keep an adapter-supplied summary bounded before placing it in a state detail."""
    return " ".join(str(message.get("body", "")).split())[:160] or "No request text supplied"


@dataclass
class GoatLeadDemo:
    """State intentionally stays in memory; a real adapter persists it durably."""

    cursor: int = 0
    handled_message_ids: set[str] | None = None

    def __post_init__(self) -> None:
        if self.handled_message_ids is None:
            self.handled_message_ids = set()

    def publish_team_presence(self) -> None:
        """Publish a small visual team using the bridge's existing agents/tasks/nodes APIs."""
        post("/v1/tasks", {
            "id": DEMO_TASK_ID,
            "title": "Goat triage demonstration",
            "description": "A local adapter visualizes a player request; no external ticket is created.",
            "status": "waiting_for_player",
        })
        post("/v1/agents", {
            "id": GOAT_ID,
            "displayName": "Goat",
            "state": "IDLE",
            "taskId": DEMO_TASK_ID,
            "ticketId": DEMO_TASK_ID,
            "workspace": "lead-control-room",
            "branch": "adapter-owned",
            "reviewStatus": "not_requested",
            "acceptanceStatus": "not_applicable",
            "detail": "Lead adapter waiting for a Minecraft message addressed to Goat",
            "graphFocus": [DEMO_TASK_ID, "demo-goat", "demo-builder", "demo-review"],
        })
        post("/v1/agents", {
            "id": DEVELOPER_ID,
            "displayName": "Builder",
            "state": "IDLE",
            "taskId": "",
            "ticketId": "",
            "workspace": "not-created-by-demo",
            "branch": "not-created-by-demo",
            "reviewStatus": "not_requested",
            "acceptanceStatus": "pending",
            "detail": "Available; a production adapter creates isolated workspaces and branches",
            "graphFocus": ["demo-builder"],
        })
        post("/v1/agents", {
            "id": REVIEWER_ID,
            "displayName": "Reviewer",
            "state": "IDLE",
            "taskId": "",
            "ticketId": "",
            "workspace": "not-created-by-demo",
            "branch": "not-created-by-demo",
            "reviewStatus": "not_requested",
            "acceptanceStatus": "pending",
            "detail": "Available; this demo does not inspect code or approve a merge",
            "graphFocus": ["demo-review"],
        })
        post("/v1/graph/nodes", {"id": DEMO_TASK_ID, "label": "Goat triage demo", "type": "task"})
        post("/v1/graph/nodes", {"id": "demo-goat", "label": "Goat lead", "type": "agent"})
        post("/v1/graph/nodes", {"id": "demo-builder", "label": "Builder", "type": "agent"})
        post("/v1/graph/nodes", {"id": "demo-review", "label": "Reviewer", "type": "agent"})
        post("/v1/graph/edges", {
            "id": "demo-goat-triages", "sourceId": "demo-goat", "targetId": DEMO_TASK_ID,
            "relationship": "triages",
        })

    def triage(self, player_message: dict[str, Any]) -> None:
        """Publish visual progress; production adapters replace this with real routing."""
        request_summary = compact_player_request(player_message)
        post("/v1/tasks", {
            "id": DEMO_TASK_ID,
            "title": "Goat triage demonstration",
            "description": f"Player request received: {request_summary}",
            "status": "triaged_demo_only",
        })
        post("/v1/agents", {
            "id": GOAT_ID,
            "displayName": "Goat",
            "state": "WORKING",
            "taskId": DEMO_TASK_ID,
            "ticketId": DEMO_TASK_ID,
            "workspace": "lead-control-room",
            "branch": "adapter-owned",
            "reviewStatus": "routing_demo",
            "acceptanceStatus": "not_applicable",
            "detail": "Triaged the player request and published a visual delegation",
            "graphFocus": [DEMO_TASK_ID, "demo-goat", "demo-builder", "demo-review"],
        })
        post("/v1/agents", {
            "id": DEVELOPER_ID,
            "displayName": "Builder",
            "state": "WORKING",
            "taskId": DEMO_TASK_ID,
            "ticketId": DEMO_TASK_ID,
            "workspace": "adapter-responsibility: isolated workspace",
            "branch": "adapter-responsibility: per-ticket branch",
            "reviewStatus": "not_requested",
            "acceptanceStatus": "pending",
            "detail": "Delegation visualized. No workspace, branch, code, or ticket was actually created.",
            "graphFocus": [DEMO_TASK_ID, "demo-builder"],
        })
        post("/v1/agents", {
            "id": REVIEWER_ID,
            "displayName": "Reviewer",
            "state": "REVIEWING",
            "taskId": DEMO_TASK_ID,
            "ticketId": DEMO_TASK_ID,
            "workspace": "adapter-responsibility: review workspace",
            "branch": "adapter-responsibility: review ref",
            "reviewStatus": "awaiting_change_request",
            "acceptanceStatus": "pending",
            "detail": "Review slot reserved; no diff was inspected and no merge was approved.",
            "graphFocus": [DEMO_TASK_ID, "demo-review"],
        })
        post("/v1/graph/edges", {
            "id": "demo-builder-owns", "sourceId": "demo-builder", "targetId": DEMO_TASK_ID,
            "relationship": "assigned_to",
        })
        post("/v1/graph/edges", {
            "id": "demo-reviewer-awaits", "sourceId": "demo-review", "targetId": DEMO_TASK_ID,
            "relationship": "awaits_review",
        })

        # These records are the visual conversation and lifecycle trace. The
        # current message contract supports only from/to/body, so structured
        # metadata stays clearly labeled in the bounded human-readable body.
        post("/v1/messages", {
            "id": f"goat-ack-{player_message['id']}", "from": GOAT_ID, "to": "minecraft-player",
            "body": "DEMO TRIAGE | Goat recorded your request. Builder is visually assigned and Reviewer is reserved; a production adapter performs real tickets, worktrees, reviews, and merges.",
        })
        post("/v1/messages", {
            "id": f"goat-delegates-{player_message['id']}", "from": GOAT_ID, "to": DEVELOPER_ID,
            "body": f"DEMO DELEGATION | task={DEMO_TASK_ID} | request={request_summary} | Git workspace/branch creation is an adapter responsibility.",
        })
        post("/v1/messages", {
            "id": f"goat-review-{player_message['id']}", "from": GOAT_ID, "to": REVIEWER_ID,
            "body": f"DEMO REVIEW QUEUE | task={DEMO_TASK_ID} | wait for a real adapter to submit a change request; do not treat this as approval.",
        })

    def poll_once(self) -> int:
        """Read ordered events, then obtain message bodies through /v1/messages."""
        feed = request(f"/v1/events?after={self.cursor}")
        events = feed["events"]
        self.cursor = feed["nextCursor"]
        if not any(event["type"] == "message.created" for event in events):
            return 0

        routed = 0
        for message in request("/v1/messages"):
            message_id = str(message.get("id", ""))
            if message_id and message_id not in self.handled_message_ids and is_player_message_to_goat(message):
                self.handled_message_ids.add(message_id)
                self.triage(message)
                routed += 1

        # Ack only after every visible response has been posted. A production
        # adapter stores its cursor and handled message IDs durably.
        post("/v1/events/ack", {"consumer": CONSUMER, "cursor": self.cursor})
        return routed


def run() -> None:
    print("Health:", request("/health"))
    demo = GoatLeadDemo()
    demo.publish_team_presence()
    print("Goat demo published. Send a Minecraft message addressed to 'goat'.")
    print("This visualizes lead triage only; it does not execute agents or Git work.")
    while True:
        routed = demo.poll_once()
        if routed:
            print(f"Goat visualized {routed} player request(s).")
        time.sleep(1.5)


class GoatLeadDemoTest(unittest.TestCase):
    def test_only_player_messages_addressed_to_goat_are_routable(self) -> None:
        self.assertTrue(is_player_message_to_goat({"from": "minecraft-player", "to": "GoAt"}))
        self.assertFalse(is_player_message_to_goat({"from": "minecraft-player", "to": "builder"}))
        self.assertFalse(is_player_message_to_goat({"from": "orchestrator", "to": "goat"}))

    def test_request_summary_collapses_and_bounds_whitespace(self) -> None:
        self.assertEqual(compact_player_request({"body": "  plan\n the\tgraph  "}), "plan the graph")
        self.assertEqual(len(compact_player_request({"body": "x" * 200})), 160)


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        unittest.main(argv=[sys.argv[0]])
    else:
        run()
