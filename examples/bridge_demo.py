#!/usr/bin/env python3
"""Zero-dependency Block Agent Spaces bridge example.

Run this while a Minecraft world with the mod is open. It publishes one small
workspace, then waits for a player to use /blockagents message builder <text>.
The message is read from the ordered outbox and acknowledged with a cursor.
"""

import json
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE_URL = "http://127.0.0.1:8787"


def request(path, method="GET", payload=None):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"} if body else {}
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
    print("Health:", request("/health"))
    publish_example()
    print("Published demo state. In Minecraft run: /blockagents message builder hello from Minecraft")
    print("Then run /blockagents refresh to update the installation.")

    cursor = 0
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        feed = request(f"/v1/events?after={cursor}")
        cursor = feed["nextCursor"]
        if any(event["type"] == "message.created" for event in feed["events"]):
            messages = request("/v1/messages")
            minecraft_messages = [message for message in messages if message["from"] == "minecraft-player" and message["to"] == "builder"]
            if minecraft_messages:
                message = minecraft_messages[-1]
                print("Minecraft said:", message["body"])
                print("Acknowledgement:", post("/v1/events/ack", {"consumer": "python-demo", "cursor": cursor}))
                return
        time.sleep(2)
    print("No Minecraft message arrived within 90 seconds. Re-run this script after sending one.")


if __name__ == "__main__":
    main()
