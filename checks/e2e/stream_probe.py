#!/usr/bin/env python3
"""Wire-level SSE probe for one splice head — the tier-1 e2e check.

Drives a REAL streaming turn against a live head port and validates the Anthropic SSE
contract byte-for-byte as a client would experience it:

  ordering   message_start first, ping anywhere, block start/delta/stop pairing by index,
             message_delta (with stop_reason) then message_stop LAST, nothing after,
             no `error` event, SSE comments (`: ping` keepalives) tolerated
  streaming  deltas actually arrive incrementally (a proxy that buffers the whole reply
             into one flush is a streaming regression even when the bytes are correct)
  latency    TTFB / first-delta / total / max inter-event gap against env-tunable budgets

Prints a one-line JSON summary (machine-readable for the orchestrator) and exits 0/1.
Stdlib only — no dependencies.
"""
import argparse
import http.client
import json
import sys
import time


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--head", required=True, help="head key (labels the summary)")
    p.add_argument("--port", type=int, required=True)
    p.add_argument("--model", required=True, help="full discovery model id")
    p.add_argument("--prompt", default="Count from 1 to 30, comma separated, then say END.")
    p.add_argument("--max-tokens", type=int, default=256)
    p.add_argument("--ttfb-ms", type=int, default=20_000)
    p.add_argument("--first-delta-ms", type=int, default=45_000)
    p.add_argument("--total-ms", type=int, default=120_000)
    p.add_argument("--gap-ms", type=int, default=30_000)
    p.add_argument("--min-deltas", type=int, default=1)
    return p.parse_args()


class SseCollector:
    """Incremental SSE parser: records (t_ms, event, data_json|None) + comment/chunk stats."""

    def __init__(self):
        self.buf = ""
        self.cur_event = None
        self.cur_data = []
        self.events = []       # (t_ms, name, dict|None)
        self.comments = 0
        self.chunks_with_events = 0

    def feed(self, t_ms, text):
        events_before = len(self.events)
        self.buf += text
        while "\n" in self.buf:
            line, self.buf = self.buf.split("\n", 1)
            line = line.rstrip("\r")
            self._line(t_ms, line)
        if len(self.events) > events_before:
            self.chunks_with_events += 1

    def _line(self, t_ms, line):
        if line.startswith(":"):
            self.comments += 1
            return
        if line.startswith("event: "):
            self.cur_event = line[len("event: "):]
            return
        if line.startswith("data: "):
            self.cur_data.append(line[len("data: "):])
            return
        if line == "" and self.cur_event is not None:
            payload = "\n".join(self.cur_data)
            try:
                data = json.loads(payload) if payload else None
            except json.JSONDecodeError:
                data = {"_unparseable": payload[:200]}
            self.events.append((t_ms, self.cur_event, data))
            self.cur_event, self.cur_data = None, []


def validate(events, comments, chunks_with_events, args, timings):
    v = []
    names = [e[1] for e in events]
    if not events:
        return ["no SSE events received at all"]

    for t, name, data in events:
        if name == "error":
            v.append(f"error event on the wire: {json.dumps(data)[:300]}")

    non_ping = [n for n in names if n != "ping"]
    if not non_ping or non_ping[0] != "message_start":
        v.append(f"first substantive event is {non_ping[0] if non_ping else 'absent'}, not message_start")
    if names.count("message_start") != 1:
        v.append(f"message_start count = {names.count('message_start')} (want exactly 1)")
    if "message_stop" not in names:
        v.append("no message_stop — stream did not end cleanly")
    elif names[-1] != "message_stop":
        v.append(f"events AFTER message_stop: {names[names.index('message_stop') + 1:]}")

    open_blocks, pairing_ok = set(), True
    for t, name, data in events:
        idx = (data or {}).get("index")
        if name == "content_block_start":
            if idx in open_blocks:
                v.append(f"content_block_start for already-open index {idx}")
                pairing_ok = False
            open_blocks.add(idx)
        elif name == "content_block_delta" and idx not in open_blocks:
            v.append(f"delta for non-open block index {idx}")
            pairing_ok = False
        elif name == "content_block_stop":
            if idx not in open_blocks:
                v.append(f"content_block_stop for non-open index {idx}")
                pairing_ok = False
            open_blocks.discard(idx)
    if pairing_ok and open_blocks and "message_stop" in names:
        v.append(f"blocks still open at message_stop: {sorted(open_blocks)}")

    stops = [d for t, n, d in events if n == "message_delta"]
    if not stops or not (stops[-1] or {}).get("delta", {}).get("stop_reason"):
        v.append("message_delta with a stop_reason missing before message_stop")

    deltas = names.count("content_block_delta")
    if deltas < args.min_deltas:
        v.append(f"only {deltas} content_block_delta events (want >= {args.min_deltas})")
    if deltas >= 4 and chunks_with_events < 2:
        v.append("whole response arrived in ONE read chunk — proxy is buffering, not streaming")

    for label, cap in (("ttfb_ms", args.ttfb_ms), ("first_delta_ms", args.first_delta_ms),
                       ("total_ms", args.total_ms), ("max_gap_ms", args.gap_ms)):
        val = timings.get(label)
        if val is not None and val > cap:
            v.append(f"{label}={val} exceeds budget {cap}")
    return v


def main():
    args = parse_args()
    body = json.dumps({
        "model": args.model,
        "stream": True,
        "max_tokens": args.max_tokens,
        "messages": [{"role": "user", "content": args.prompt}],
    })
    conn = http.client.HTTPConnection("127.0.0.1", args.port, timeout=60)
    t0 = time.monotonic()
    conn.request("POST", "/v1/messages", body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    ttfb_ms = int((time.monotonic() - t0) * 1000)

    col = SseCollector()
    event_times = []
    deadline = time.monotonic() + args.total_ms / 1000 + 30
    status_ok = resp.status == 200
    ctype = resp.getheader("Content-Type", "")
    while time.monotonic() < deadline:
        chunk = resp.read1(65536)
        if not chunk:
            break
        t_ms = int((time.monotonic() - t0) * 1000)
        before = len(col.events)
        col.feed(t_ms, chunk.decode("utf-8", errors="replace"))
        event_times.extend(t for t, _, _ in col.events[before:])
        if col.events and col.events[-1][1] == "message_stop":
            break
    conn.close()

    total_ms = event_times[-1] if event_times else int((time.monotonic() - t0) * 1000)
    first_delta_ms = next((t for t, n, _ in col.events if n == "content_block_delta"), None)
    max_gap_ms = max(
        (b - a for a, b in zip(event_times, event_times[1:])),
        default=0,
    )
    timings = {
        "ttfb_ms": ttfb_ms,
        "first_delta_ms": first_delta_ms,
        "total_ms": total_ms,
        "max_gap_ms": max_gap_ms,
    }
    violations = validate(col.events, col.comments, col.chunks_with_events, args, timings)
    if not status_ok:
        violations.insert(0, f"HTTP {resp.status}")
    if "text/event-stream" not in ctype:
        violations.insert(0, f"Content-Type '{ctype}' is not text/event-stream")

    summary = {
        "head": args.head,
        "model": args.model,
        "ok": not violations,
        **timings,
        "events": len(col.events),
        "deltas": sum(1 for _, n, _ in col.events if n == "content_block_delta"),
        "keepalive_comments": col.comments,
        "chunks_with_events": col.chunks_with_events,
        "violations": violations,
    }
    print(json.dumps(summary))
    sys.exit(0 if summary["ok"] else 1)


if __name__ == "__main__":
    main()
