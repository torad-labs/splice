#!/usr/bin/env python3
"""An OpenAI-compatible chat-completions upstream for the fresh-machine e2e.

Serves exactly what the openai-chat dialect needs from a vendor: a streaming
/chat/completions that emits role + content deltas, a finish_reason, a usage
chunk and [DONE]; a non-stream /chat/completions; and /models. Deterministic
content so the wire probe's assertions are about the PROXY, never the model.

Scenarios ride in the request text as `SCENARIO:<name>` (any message, any
position), so the harness selects them from a plain `claude -p` prompt:
  hold        sleep MOCK_CHAT_HOLD_S (default 30) before answering, which keeps
              a session registered while another head lists its peers
  listagents  if the request offers a `ListAgents` tool, answer with one call
              to it; once the tool result comes back (role "tool" messages),
              answer "PEERS: <tool result>" so the caller prints what the
              tool actually returned inside its head
Anything else answers the fixed reply. The server is threaded: a `hold` must
not block the other head's turn.

Usage: mock_chat.py <port>   — prints {"port": N} once listening, then serves.
"""
import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPLY_WORDS = ["Hello", " from", " the", " chat", " mock", ".", " 1,", " 2,", " 3", " END"]
MODELS = ["mock-chat", "mock-chat-2"]
USAGE = {"prompt_tokens": 12, "completion_tokens": 10, "total_tokens": 22}
HOLD_SECONDS = float(os.environ.get("MOCK_CHAT_HOLD_S", "30"))
SCENARIO = re.compile(rb"SCENARIO:([a-z_]+)")


def scenario(raw):
    m = SCENARIO.search(raw)
    return m.group(1).decode() if m else "basic"


def tool_text(message):
    content = message.get("content", "")
    if isinstance(content, list):
        return "".join(p.get("text", "") if isinstance(p, dict) else str(p) for p in content)
    return str(content)


class Handler(BaseHTTPRequestHandler):
    server_version = "splice-e2e-mock-chat/1"

    def log_message(self, format, *args):  # noqa: A002 — quiet: the receipt carries the verdicts
        del format, args

    def _json(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            self._json(200, {"object": "list", "data": [{"id": m, "object": "model"} for m in MODELS]})
        else:
            self._json(404, {"error": {"message": "no such route", "type": "invalid_request_error"}})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b""
        try:
            req = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self._json(400, {"error": {"message": "bad json", "type": "invalid_request_error"}})
            return
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._json(404, {"error": {"message": "no such route", "type": "invalid_request_error"}})
            return
        if self.headers.get("Authorization", "") != "Bearer mock-chat-key":
            self._json(401, {"error": {"message": "bad key", "type": "authentication_error"}})
            return
        model = req.get("model", "mock-chat")
        stream = bool(req.get("stream"))
        scen = scenario(raw)
        if scen == "hold":
            time.sleep(HOLD_SECONDS)
        if scen == "listagents":
            results = [m for m in req.get("messages", []) if m.get("role") == "tool"]
            if results:
                self._reply(model, stream, ["PEERS: "] + [tool_text(m) for m in results])
                return
            offered = [t.get("function", {}).get("name") for t in req.get("tools", []) if isinstance(t, dict)]
            if "ListAgents" in offered:
                self._tool_call(model, stream, "call_la1", "ListAgents", "{}")
                return
            self._reply(model, stream, ["NO ListAgents TOOL OFFERED; tools=" + ",".join(n for n in offered if n)])
            return
        self._reply(model, stream, REPLY_WORDS)

    # ── replies ──────────────────────────────────────────────────────────────────────────────
    def _completion(self, model, message, finish):
        return {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": model,
            "choices": [{"index": 0, "message": message, "finish_reason": finish}],
            "usage": USAGE,
        }

    def _sse_head(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

    def _chunk(self, model, delta, finish=None, usage=None):
        obj = {
            "id": "chatcmpl-mock",
            "object": "chat.completion.chunk",
            "model": model,
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
        }
        if usage is not None:
            obj["usage"] = usage
        self.wfile.write(f"data: {json.dumps(obj)}\n\n".encode())
        self.wfile.flush()

    def _done(self):
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def _reply(self, model, stream, words):
        if not stream:
            self._json(200, self._completion(model, {"role": "assistant", "content": "".join(words)}, "stop"))
            return
        self._sse_head()
        self._chunk(model, {"role": "assistant", "content": ""})
        for word in words:
            self._chunk(model, {"content": word})
        self._chunk(model, {}, finish="stop", usage=USAGE)
        self._done()

    def _tool_call(self, model, stream, call_id, name, arguments):
        call = {"id": call_id, "type": "function", "function": {"name": name, "arguments": arguments}}
        if not stream:
            message = {"role": "assistant", "content": None, "tool_calls": [call]}
            self._json(200, self._completion(model, message, "tool_calls"))
            return
        self._sse_head()
        self._chunk(model, {"role": "assistant", "content": None})
        opened = {"index": 0, "id": call_id, "type": "function", "function": {"name": name, "arguments": ""}}
        self._chunk(model, {"tool_calls": [opened]})
        self._chunk(model, {"tool_calls": [{"index": 0, "function": {"arguments": arguments}}]})
        self._chunk(model, {}, finish="tool_calls", usage=USAGE)
        self._done()


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(json.dumps({"port": server.server_address[1]}), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
