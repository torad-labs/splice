#!/usr/bin/env python3
"""An OpenAI-compatible chat-completions upstream for the fresh-machine e2e.

Serves exactly what the openai-chat dialect needs from a vendor: a streaming
/chat/completions that emits role + content deltas, a finish_reason, a usage
chunk and [DONE]; a non-stream /chat/completions; and /models. Deterministic
content so the wire probe's assertions are about the PROXY, never the model.

Usage: mock_chat.py <port>   — prints {"port": N} once listening, then serves.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

REPLY_WORDS = ["Hello", " from", " the", " chat", " mock", ".", " 1,", " 2,", " 3", " END"]


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
            self._json(200, {"object": "list", "data": [{"id": "mock-chat", "object": "model"}]})
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
        if not req.get("stream"):
            self._json(
                200,
                {
                    "id": "chatcmpl-mock",
                    "object": "chat.completion",
                    "model": model,
                    "choices": [
                        {
                            "index": 0,
                            "message": {"role": "assistant", "content": "".join(REPLY_WORDS)},
                            "finish_reason": "stop",
                        },
                    ],
                    "usage": {"prompt_tokens": 12, "completion_tokens": 10, "total_tokens": 22},
                },
            )
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        def chunk(delta, finish=None, usage=None):
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

        chunk({"role": "assistant", "content": ""})
        for word in REPLY_WORDS:
            chunk({"content": word})
        chunk({}, finish="stop", usage={"prompt_tokens": 12, "completion_tokens": 10, "total_tokens": 22})
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    server = HTTPServer(("127.0.0.1", port), Handler)
    print(json.dumps({"port": server.server_address[1]}), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
