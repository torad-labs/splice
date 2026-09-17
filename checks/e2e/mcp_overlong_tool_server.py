#!/usr/bin/env python3
"""checks/e2e/mcp_overlong_tool_server.py — a REAL minimal stdio MCP server for the heads e2e.

WHY THIS FILE EXISTS. The tier-2 drive is supposed to carry the operator's actual tool surface,
because that surface is what 400d claude-muse ("name must be at most 64 characters, got 68").
The first version of the arm planted `python3 -c "raise SystemExit(0)"` in .mcp.json: it exits
before the first byte of the stdio handshake, so Claude Code registered ZERO tools and the
over-long name never reached the wire. A server that does not answer `initialize` is not a tool
surface — it is a config file nobody read.

WHAT IT IS. Newline-delimited JSON-RPC 2.0 over stdin/stdout (the MCP stdio transport — no
Content-Length framing), standard library only, one tool. Claude Code composes the wire name as
mcp__<server-key-from-.mcp.json>__<advertised tool name>, so the over-length lives in the
COMPOSITION: this server advertises the short half (TOOL_NAME) and heads-e2e.sh owns the long
half (OVERLONG_MCP_SERVER). heads-e2e-selftest.sh pins the two halves to each other and to the
68 characters the operator's 400 reported — rename either and the gate goes red.

THE RECEIPT. Every method served is appended as one JSONL row to $SPLICE_E2E_MCP_LOG (absolute
path, planted by heads-e2e.sh into the scratch dir). That file is the only observable proof that
Claude Code really spawned this server and really pulled its tools; heads-e2e.sh's
mcp_surface_ok() reads it, and the selftest drives this server directly and reads it too.
Unset SPLICE_E2E_MCP_LOG simply disables the receipt — the server still serves.

Protocol version is ECHOED back to the client. This server has no version-specific behaviour, and
echoing is the one answer that cannot fail negotiation against whatever Claude Code ships next.
"""

import json
import os
import sys
import time

TOOL_NAME = "read_process_output"
SERVER_NAME = "splice-e2e-overlong-tool"
SERVER_VERSION = "1.0.0"
FALLBACK_PROTOCOL = "2025-06-18"
METHOD_NOT_FOUND = -32601

TOOL = {
    "name": TOOL_NAME,
    "description": "e2e stand-in for the operator's over-long MCP tool; returns a fixed string.",
    "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
}


def receipt(method, **extra):
    """One JSONL row per served method. Best-effort by design: the harness asserts on this file,
    but a disk problem here must not take the MCP server (and with it the billed turn) down."""
    path = os.environ.get("SPLICE_E2E_MCP_LOG")
    if not path:
        return
    row = dict(ts=int(time.time() * 1000), method=method, **extra)
    try:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(row) + "\n")
            handle.flush()
    except OSError:
        pass


def reply(msg_id, result=None, error=None):
    body = {"jsonrpc": "2.0", "id": msg_id}
    if error is None:
        body["result"] = result
    else:
        body["error"] = error
    sys.stdout.write(json.dumps(body) + "\n")
    sys.stdout.flush()


def handle(msg):
    method = msg.get("method")
    msg_id = msg.get("id")
    params = msg.get("params") or {}

    if method == "initialize":
        asked = params.get("protocolVersion")
        version = asked if isinstance(asked, str) and asked else FALLBACK_PROTOCOL
        receipt(method, protocol=version)
        reply(msg_id, {
            "protocolVersion": version,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
        })
        return

    if method == "tools/list":
        # `tools` is the load-bearing field of the whole receipt: it records the name this server
        # actually advertised, not the name the harness hoped it would.
        receipt(method, tools=[TOOL_NAME])
        reply(msg_id, {"tools": [TOOL]})
        return

    if method == "tools/call":
        called = params.get("name")
        receipt(method, tool=called)
        if called != TOOL_NAME:
            reply(msg_id, error={"code": METHOD_NOT_FOUND, "message": "no such tool: %r" % called})
            return
        reply(msg_id, {"content": [{"type": "text", "text": "splice e2e tool ok"}], "isError": False})
        return

    if method == "ping":
        receipt(method)
        reply(msg_id, {})
        return

    # Notifications (no id) are acknowledged only in the receipt — answering one is a protocol
    # violation. `notifications/initialized` is the third leg of the handshake and arrives here.
    if msg_id is None:
        receipt(method or "<no method>")
        return

    receipt(method or "<no method>", unsupported=True)
    reply(msg_id, error={"code": METHOD_NOT_FOUND, "message": "unsupported method: %r" % method})


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(msg, dict):
            handle(msg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
