#!/usr/bin/env python3
"""Optional, bounded ambient intelligence for a local Block Agent Spaces world.

The adapter observes the existing local bridge outbox and classifies message
activity into a tiny fixed schema. It can ask a *locally hosted*,
OpenAI-compatible chat-completions endpoint for a terse status summary, but it
always validates that result and falls back to deterministic rules when the
model is missing, slow, unreachable, or invalid.

This is intentionally not an agent runner or a Goat replacement. It cannot
assign tickets, create workspaces, inspect code, approve reviews, merge code,
or make autonomous project decisions. Those actions belong to an external
orchestrator and its Git/agent adapters.

See docs/ambient-intelligence-adapter.md for configuration and local quickstart.
Run ``python3 examples/ambient_intelligence_adapter.py --self-test`` offline.
"""

from __future__ import annotations

import json
import os
import socket
import sys
import time
import unittest
from dataclasses import dataclass, field
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


BRIDGE_URL = os.environ.get("BLOCK_AGENT_SPACES_URL", "http://127.0.0.1:8787").rstrip("/")
MODEL_URL = os.environ.get("BLOCK_AGENT_AMBIENT_MODEL_URL", "").rstrip("/")
MODEL_NAME = os.environ.get("BLOCK_AGENT_AMBIENT_MODEL", "")
TIMEOUT_SECONDS = float(os.environ.get("BLOCK_AGENT_AMBIENT_TIMEOUT_SECONDS", "2"))
CONSUMER_NAME = "ambient-intelligence-demo"
OBSERVER_ID = "ambient-observer"

ALLOWED_CATEGORIES = frozenset({"status", "question", "blocker", "request", "review", "other"})
ALLOWED_FLAGS = frozenset({"none", "surface_to_goat", "check_later"})


@dataclass(frozen=True)
class AmbientInsight:
    category: str
    summary: str
    follow_up: str
    source: str

    def as_json(self) -> dict[str, str]:
        return {
            "category": self.category,
            "summary": self.summary,
            "follow_up": self.follow_up,
            "source": self.source,
        }


def normalized_text(value: Any, limit: int = 180) -> str:
    return " ".join(str(value or "").split())[:limit]


def is_loopback_url(value: str) -> bool:
    """Accept only a local server; this example never sends messages to the Internet."""
    parsed = urlparse(value)
    return parsed.scheme in {"http", "https"} and parsed.hostname in {"127.0.0.1", "localhost", "::1"}


def request_json(base_url: str, path: str, method: str = "GET", payload: dict[str, Any] | None = None, timeout: float = 3) -> Any:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"} if body is not None else {}
    try:
        with urlopen(Request(base_url + path, data=body, method=method, headers=headers), timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        raise RuntimeError(f"request returned {error.code}: {error.read().decode('utf-8')}") from error
    except (URLError, socket.timeout) as error:
        raise RuntimeError("local service is unavailable") from error


def post_json(url: str, payload: dict[str, Any], timeout: float) -> Any:
    body = json.dumps(payload).encode("utf-8")
    request = Request(url, data=body, method="POST", headers={"Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except (HTTPError, URLError, socket.timeout, json.JSONDecodeError) as error:
        raise RuntimeError("local model response unavailable") from error


def deterministic_insight(message: dict[str, Any]) -> AmbientInsight:
    """Safe local fallback: classify from a few transparent keywords only."""
    body = normalized_text(message.get("body"))
    lowered = body.lower()
    if any(word in lowered for word in ("blocked", "blocker", "cannot", "can't", "failed", "failure", "stuck")):
        category, follow_up = "blocker", "surface_to_goat"
    elif any(word in lowered for word in ("review", "approve", "approval", "change request", "pull request")):
        category, follow_up = "review", "surface_to_goat"
    elif "?" in body:
        category, follow_up = "question", "check_later"
    elif any(word in lowered for word in ("please", "can you", "need", "request", "assign")):
        category, follow_up = "request", "check_later"
    elif any(word in lowered for word in ("done", "complete", "progress", "working", "updated")):
        category, follow_up = "status", "none"
    else:
        category, follow_up = "other", "none"
    return AmbientInsight(category, body or "No message text", follow_up, "rules")


def validate_model_insight(value: Any) -> AmbientInsight | None:
    """Reject extra fields and every value outside the small fixed schema."""
    if not isinstance(value, dict) or set(value) != {"category", "summary", "follow_up"}:
        return None
    category, summary, follow_up = value["category"], value["summary"], value["follow_up"]
    if category not in ALLOWED_CATEGORIES or follow_up not in ALLOWED_FLAGS:
        return None
    if not isinstance(summary, str):
        return None
    summary = normalized_text(summary)
    if not summary:
        return None
    return AmbientInsight(category, summary, follow_up, "local-model")


def model_insight(message: dict[str, Any], send: Callable[[str, dict[str, Any], float], Any] = post_json) -> AmbientInsight | None:
    """Ask a compatible local model, returning None for any unavailable/invalid response."""
    if not MODEL_URL or not MODEL_NAME or not is_loopback_url(MODEL_URL):
        return None
    prompt = {
        "from": normalized_text(message.get("from"), 60),
        "to": normalized_text(message.get("to"), 60),
        "body": normalized_text(message.get("body")),
    }
    payload = {
        "model": MODEL_NAME,
        "temperature": 0,
        "max_tokens": 100,
        "messages": [
            {
                "role": "system",
                "content": (
                    "Classify one activity message. Return JSON only with exactly: "
                    "category (status|question|blocker|request|review|other), "
                    "summary (brief, factual), follow_up (none|surface_to_goat|check_later). "
                    "The follow_up is non-binding; do not claim to take actions."
                ),
            },
            {"role": "user", "content": json.dumps(prompt)},
        ],
    }
    try:
        response = send(MODEL_URL, payload, TIMEOUT_SECONDS)
        content = response["choices"][0]["message"]["content"]
        return validate_model_insight(json.loads(content))
    except (KeyError, IndexError, TypeError, ValueError, RuntimeError, json.JSONDecodeError):
        return None


def classify(message: dict[str, Any], send: Callable[[str, dict[str, Any], float], Any] = post_json) -> AmbientInsight:
    return model_insight(message, send) or deterministic_insight(message)


@dataclass
class AmbientObserver:
    """In-memory demo state; a production adapter persists cursor and deduplication IDs."""

    cursor: int = 0
    handled_message_ids: set[str] = field(default_factory=set)
    publish: bool = False

    def publish_note(self, message: dict[str, Any], insight: AmbientInsight) -> None:
        """Optionally make a bounded observation visible in the in-game conversation log."""
        source_id = normalized_text(message.get("id"), 80) or "unknown"
        request_json(BRIDGE_URL, "/v1/messages", "POST", {
            "id": f"ambient-{source_id}",
            "from": OBSERVER_ID,
            "to": "goat",
            "body": (
                f"Ambient note (non-binding): {insight.category}; {insight.summary}. "
                f"Suggested follow-up: {insight.follow_up}."
            )[:400],
        })

    def poll_once(self) -> list[tuple[dict[str, Any], AmbientInsight]]:
        feed = request_json(BRIDGE_URL, f"/v1/events?after={self.cursor}")
        self.cursor = int(feed["nextCursor"])
        if not any(event.get("type") == "message.created" for event in feed.get("events", [])):
            return []

        observations: list[tuple[dict[str, Any], AmbientInsight]] = []
        for message in request_json(BRIDGE_URL, "/v1/messages"):
            message_id = str(message.get("id", ""))
            if not message_id or message_id in self.handled_message_ids or message.get("from") == OBSERVER_ID:
                continue
            self.handled_message_ids.add(message_id)
            insight = classify(message)
            observations.append((message, insight))
            if self.publish:
                self.publish_note(message, insight)

        request_json(BRIDGE_URL, "/v1/events/ack", "POST", {"consumer": CONSUMER_NAME, "cursor": self.cursor})
        return observations


def run() -> None:
    if not is_loopback_url(BRIDGE_URL):
        raise SystemExit("BLOCK_AGENT_SPACES_URL must be a loopback URL")
    if MODEL_URL and not is_loopback_url(MODEL_URL):
        raise SystemExit("BLOCK_AGENT_AMBIENT_MODEL_URL must be a loopback URL")
    publish = "--publish" in sys.argv
    observer = AmbientObserver(publish=publish)
    print("Ambient observer is watching the local bridge.")
    print("Model mode:", "local compatible endpoint" if MODEL_URL and MODEL_NAME else "deterministic fallback")
    print("Notes are", "published to Goat" if publish else "printed only (use --publish to add log notes)")
    while True:
        for message, insight in observer.poll_once():
            print(json.dumps({"messageId": message.get("id"), **insight.as_json()}, ensure_ascii=False))
        time.sleep(1.5)


class AmbientIntelligenceTest(unittest.TestCase):
    def test_validates_exact_model_schema(self) -> None:
        parsed = validate_model_insight({"category": "blocker", "summary": "Build is blocked", "follow_up": "surface_to_goat"})
        self.assertEqual(parsed, AmbientInsight("blocker", "Build is blocked", "surface_to_goat", "local-model"))
        self.assertIsNone(validate_model_insight({"category": "blocker", "summary": "x", "follow_up": "bad"}))
        self.assertIsNone(validate_model_insight({"category": "status", "summary": "x", "follow_up": "none", "extra": True}))

    def test_uses_fake_model_response_when_valid(self) -> None:
        def fake_send(_url: str, _payload: dict[str, Any], _timeout: float) -> dict[str, Any]:
            return {"choices": [{"message": {"content": json.dumps({
                "category": "review", "summary": "Awaiting review", "follow_up": "surface_to_goat"
            })}}]}

        previous_url, previous_name = os.environ.get("BLOCK_AGENT_AMBIENT_MODEL_URL"), os.environ.get("BLOCK_AGENT_AMBIENT_MODEL")
        # Model settings are module constants, so temporarily adjust them for this offline test.
        global MODEL_URL, MODEL_NAME
        old_url, old_name = MODEL_URL, MODEL_NAME
        MODEL_URL, MODEL_NAME = "http://127.0.0.1:9999/v1/chat/completions", "local-test"
        try:
            result = classify({"from": "builder", "to": "goat", "body": "Ready"}, fake_send)
        finally:
            MODEL_URL, MODEL_NAME = old_url, old_name
            if previous_url is None:
                os.environ.pop("BLOCK_AGENT_AMBIENT_MODEL_URL", None)
            else:
                os.environ["BLOCK_AGENT_AMBIENT_MODEL_URL"] = previous_url
            if previous_name is None:
                os.environ.pop("BLOCK_AGENT_AMBIENT_MODEL", None)
            else:
                os.environ["BLOCK_AGENT_AMBIENT_MODEL"] = previous_name
        self.assertEqual(result.source, "local-model")
        self.assertEqual(result.category, "review")

    def test_falls_back_without_model(self) -> None:
        self.assertEqual(deterministic_insight({"body": "I am blocked on the build"}).category, "blocker")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        unittest.main(argv=[sys.argv[0]])
    else:
        run()
