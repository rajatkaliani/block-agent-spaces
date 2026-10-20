#!/usr/bin/env python3
"""Zero-dependency Block Agent Spaces bridge example.

Run this while a Minecraft world with the mod is open. It publishes one small
workspace, then waits for a player to message Builder through the clickable
notebook. The message is read from the ordered outbox and receives one clearly
labelled deterministic demo reply; it never repeats the player's text.
"""

import hashlib
import json
import os
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE_URL = "http://127.0.0.1:8787"
TOKEN = os.environ.get("BLOCK_AGENT_SPACES_BRIDGE_TOKEN", "").strip()


def request(path, method="GET", payload=None):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"} if body else {}
    if body and TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    try:
        with urlopen(Request(BASE_URL + path, data=body, method=method, headers=headers), timeout=3) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        raise RuntimeError(f"bridge returned {error.code}: {error.read().decode('utf-8')}") from error
    except URLError as error:
        raise RuntimeError("cannot reach the local bridge; open a Minecraft world with the mod first") from error


def post(path, payload):
    return request(path, "POST", payload)


def publish_example():
    post("/v1/tasks", {"id": "demo-task", "title": "Read a Minecraft message", "description": "Demo loop", "status": "in_progress"})
    post("/v1/graph/nodes", {"id": "demo-project", "label": "Bridge demo", "type": "project"})
    post("/v1/graph/nodes", {"id": "demo-task", "label": "Read a Minecraft message", "type": "task"})
    post("/v1/graph/edges", {"id": "demo-edge", "sourceId": "demo-task", "targetId": "demo-project", "relationship": "belongs_to"})
    post("/v1/agents", {
        "id": "builder", "displayName": "Builder", "state": "working", "taskId": "demo-task",
        "detail": "Waiting for a Minecraft message", "graphFocus": ["demo-project", "demo-task"]
    })


def main():
    health = request("/health")
    if health.get("bridge") != "block-agent-spaces" or health.get("status") != "ok":
        raise RuntimeError("localhost:8787 is not a Block Agent Spaces bridge")
    mode = health.get("mutationMode")
    if mode == "pairing_required" or (mode == "paired" and not TOKEN):
        raise RuntimeError("Bridge pairing is required. Set BLOCK_AGENT_SPACES_BRIDGE_TOKEN for both Minecraft and this script, or use the explicit development demo mode.")
    if mode not in ("paired", "explicit_demo"):
        raise RuntimeError(f"unsupported bridge mutation mode: {mode}")
    # Ignore historical messages from previous runs.
    cursor = request("/v1/events?after=0")["nextCursor"]
    publish_example()
    print("Published demo state. In Minecraft, right-click Builder and send a note from the notebook.")
    print("The notebook will show the labelled demo reply automatically.")

    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        feed = request(f"/v1/events?after={cursor}")
        cursor = feed["nextCursor"]
        new_message_ids = {event.get("subjectId") for event in feed["events"] if event.get("type") == "message.created"}
        if new_message_ids:
            messages = request("/v1/messages")
            minecraft_messages = [message for message in messages
                                  if message.get("id") in new_message_ids
                                  and message.get("from") == "minecraft-player"
                                  and message.get("to") == "builder"]
            if minecraft_messages:
                message = minecraft_messages[-1]
                print("Minecraft said:", message["body"])
                post("/v1/messages", {
                    "id": "builder-demo-reply-" + hashlib.sha256(message["id"].encode("utf-8")).hexdigest()[:32],
                    "from": "builder",
                    "to": "minecraft-player",
                    "body": "DEMO REPLY | Builder received your note in the local outbox. This sample does not run a coding agent or change a ticket.",
                })
                print("Acknowledgement:", post("/v1/events/ack", {"consumer": "python-demo", "cursor": cursor}))
                return
        time.sleep(2)
    print("No Minecraft message arrived within 90 seconds. Re-run this script after sending one.")


if __name__ == "__main__":
    main()
