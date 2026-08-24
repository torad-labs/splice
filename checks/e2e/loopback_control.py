#!/usr/bin/env python3
"""Loopback control+head for heads-e2e-selftest.sh.

Two sockets, never a real daemon and never a vendor. The control plane answers
/health and /api/heads so heads-e2e.sh will not cold-start splice.jar. The head
answers /v1/models, /v1/messages (Anthropic SSE with `event:` lines, two
deltas), and /v1/messages/count_tokens. Authorization on BOTH planes is
appended to --record as JSONL so a skip/FATAL arm that still probes is visible.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def _sse() -> bytes:
    # Two deltas: stream_probe's buffering check only fires at >= 4 in one chunk.
    frames = [
        ("message_start", '{"type":"message_start","message":{"usage":{"input_tokens":2}}}'),
        ("content_block_start",
         '{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}'),
        ("content_block_delta",
         '{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"1, "}}'),
        ("content_block_delta",
         '{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"2 END"}}'),
        ("content_block_stop", '{"type":"content_block_stop","index":0}'),
        ("message_delta",
         '{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}'),
        ("message_stop", '{"type":"message_stop"}'),
    ]
    return "".join(f"event: {n}\ndata: {d}\n\n" for n, d in frames).encode()


class Loopback:
    def __init__(self, record: pathlib.Path, head_key: str, head_port: int) -> None:
        self.record = record
        self.head_key = head_key
        self.head_port = head_port
        self.lock = threading.Lock()
        self.record.write_text("")

    def append(self, plane: str, path: str, authorization: str | None) -> None:
        row = {"plane": plane, "path": path, "authorization": authorization or ""}
        line = json.dumps(row) + "\n"
        with self.lock:
            with self.record.open("a", encoding="utf-8") as fh:
                fh.write(line)


def _handler(loop: Loopback, plane: str) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format: str, *args: object) -> None:  # noqa: A002
            del format, args

        def do_GET(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler API
            path = self.path.split("?", 1)[0]
            loop.append(plane, path, self.headers.get("Authorization"))
            if plane == "control" and path == "/health":
                self._json(200, {"ok": True})
                return
            if plane == "control" and path == "/api/heads":
                self._json(200, {"heads": [{
                    "key": loop.head_key,
                    "label": loop.head_key,
                    "port": loop.head_port,
                    "healthy": True,
                    "authKind": "client",
                }]})
                return
            if plane == "head" and path == "/v1/models":
                self._json(200, {"data": [{"id": f"{loop.head_key}--claude-haiku-4-5"}]})
                return
            self._json(404, {"error": "not found"})

        def do_POST(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler API
            path = self.path.split("?", 1)[0]
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
            loop.append(plane, path, self.headers.get("Authorization"))
            if plane == "head" and path == "/v1/messages":
                body = _sse()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if plane == "head" and path == "/v1/messages/count_tokens":
                self._json(200, {"input_tokens": 3})
                return
            self._json(404, {"error": "not found"})

        def _json(self, status: int, payload: object) -> None:
            raw = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

    return Handler


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--record", required=True)
    p.add_argument("--ready-file", required=True)
    p.add_argument("--head-key", default="claude-splice")
    args = p.parse_args()

    # Bind the head first so /api/heads can advertise the real port. Handlers
    # are swapped in after both sockets exist so READY is only written once
    # serve_forever is running — otherwise the selftest races a refused /health
    # and heads-e2e.sh cold-starts the installed splice.jar.
    placeholder = Loopback(pathlib.Path(args.record), args.head_key, 0)
    head_srv = ThreadingHTTPServer(("127.0.0.1", 0), _handler(placeholder, "head"))
    loop = Loopback(pathlib.Path(args.record), args.head_key, head_srv.server_address[1])
    head_srv.RequestHandlerClass = _handler(loop, "head")
    control_srv = ThreadingHTTPServer(("127.0.0.1", 0), _handler(loop, "control"))

    threading.Thread(target=head_srv.serve_forever, daemon=True).start()
    threading.Thread(target=control_srv.serve_forever, daemon=True).start()
    pathlib.Path(args.ready_file).write_text(
        f"CONTROL={control_srv.server_address[1]}\nHEAD={head_srv.server_address[1]}\n",
        encoding="utf-8",
    )
    print(
        f"READY control={control_srv.server_address[1]} head={head_srv.server_address[1]}",
        flush=True,
    )
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        return 0
    finally:
        head_srv.shutdown()
        control_srv.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
