#!/usr/bin/env python3
"""Idle watch for campaign seats (vendored from grailseeker-bot .dev/campaigns/idle-watch.ts,
2026-09-18, ported to python and joined to the ledger).

A transition detector, not a timer: it emits one line when a seat changes state, nothing
otherwise. State comes from the seat's own transcript (found by its customTitle in the
transcript dirs given): idle = last assistant entry ended its turn (stop_reason end_turn), no
user entry after it, file quiet for at least IDLE seconds; working = a tool is running or input
is pending. The ledger join decides what an idle seat means:

  STALL <seat> <ID>: <detail>   idle while holding an in_flight row (context excuse, silent stop
                                after a mistake, or a report sent to the wrong place)
  FREE  <seat>: <detail>        idle with no in_flight row (done rows to stage, next row to give)
  BUSY  <seat>: resumed         back to work after an idle line
  LOST  <seat>: no transcript   once, when a seat cannot be located

Usage: python3 idle-watch.py <ledger.toml> <idle-secs> <poll-secs> <dir1,dir2,...> <seat>...
"""
from __future__ import annotations

import glob
import json
import os
import re
import sys
import time

ledger = sys.argv[1]
idle = int(sys.argv[2])
poll = int(sys.argv[3])
dirs = [d for d in sys.argv[4].split(",") if os.path.isdir(d)]
seats = sys.argv[5:]
RECENT_S = 24 * 3600  # a seat's transcript was written today; skip the archive
HEAD_BYTES = 64 * 1024  # the custom-title entry sits at the head of the transcript


def file_for(seat: str) -> str | None:
    needle = f'"customTitle":"{seat}"'.encode()
    now = time.time()
    hits: list[str] = []
    for d in dirs:
        for p in glob.glob(os.path.join(d, "*.jsonl")):
            try:
                if now - os.stat(p).st_mtime > RECENT_S:
                    continue
                with open(p, "rb") as fh:
                    if needle in fh.read(HEAD_BYTES):
                        hits.append(p)
            except OSError:
                continue
    if not hits:
        return None
    hits.sort(key=lambda p: os.stat(p).st_mtime, reverse=True)
    return hits[0]


def tail_lines(p: str, n: int = 60) -> list[str]:
    with open(p, "rb") as fh:
        fh.seek(0, os.SEEK_END)
        size = fh.tell()
        chunk = min(size, 512 * 1024)
        fh.seek(size - chunk)
        data = fh.read().decode("utf-8", "replace")
    return data.rstrip("\n").split("\n")[-n:]


def state(p: str) -> tuple[str, str]:
    last_assistant: dict | None = None
    user_after = False
    for line in tail_lines(p):
        try:
            j = json.loads(line)
        except ValueError:
            continue
        t = j.get("type")
        if t == "assistant":
            last_assistant = {"stop": (j.get("message") or {}).get("stop_reason")}
            user_after = False
        elif t == "user" and last_assistant is not None:
            user_after = True
    age = int(time.time() - os.stat(p).st_mtime)
    if last_assistant is None:
        return "unknown", f"no assistant entry in tail, quiet {age}s"
    if user_after:
        return "working", f"input pending, quiet {age}s"
    if last_assistant["stop"] == "tool_use":
        return "working", f"tool running, quiet {age}s"
    if age >= idle:
        return "idle", f"turn ended ({last_assistant['stop'] or '?'}), quiet {age}s"
    return "settling", f"turn ended {age}s ago"


ITEM_RE = re.compile(r'^\[\[items\]\]\s*$', re.M)


def in_flight_by_seat() -> dict[str, str]:
    """seat -> row id for every in_flight row, from the row's last CLAIM note (read-only)."""
    try:
        with open(ledger, encoding="utf-8") as fh:
            text = fh.read()
    except OSError:
        return {}
    out: dict[str, str] = {}
    blocks = ITEM_RE.split(text)[1:]
    for b in blocks:
        rid = re.search(r'^id = "([^"]+)"', b, re.M)
        st = re.search(r'^status = "([^"]+)"', b, re.M)
        owners = re.findall(r'CLAIM: owner=(\S+)', b)
        if rid and st and st.group(1) == "in_flight" and owners:
            out[owners[-1]] = rid.group(1)
    return out


last: dict[str, str] = {}
lost: set[str] = set()
files: dict[str, str] = {}
# A seat that goes idle and STAYS idle used to produce exactly one line and then silence,
# because every print here fires on a TRANSITION. So silence meant two different things —
# "every seat is busy" and "a seat has been idle for twenty minutes and you have forgotten" —
# and the orchestrator could not tell them apart. That is campaign law 23 (a check must be
# able to say it did not run) pointed at the watcher itself: measured 2026-09-18, the operator
# noticed an idle seat seventeen minutes before this script would have mentioned it again.
# An idle seat is now re-announced on a widening interval, so silence means one thing.
idle_since: dict[str, float] = {}
next_nag: dict[str, float] = {}
NAG_FIRST = 300.0   # five minutes after the first STALL/FREE
NAG_MAX = 1800.0    # then doubling, capped at half an hour, so a parked seat never goes quiet
while True:
    holding = in_flight_by_seat()
    for seat in seats:
        p = files.get(seat)
        if p is None or not os.path.exists(p):
            p = file_for(seat)
            if p is None:
                if seat not in lost:
                    print(f"LOST  {seat}: no transcript found", flush=True)
                    lost.add(seat)
                continue
            files[seat] = p
            lost.discard(seat)
        s, detail = state(p)
        prev = last.get(seat)
        now = time.time()
        if s != "idle":
            idle_since.pop(seat, None)
            next_nag.pop(seat, None)
        elif seat not in idle_since:
            idle_since[seat] = now
            next_nag[seat] = now + NAG_FIRST
        # Still idle and the interval has elapsed: say so again, with how long it has been.
        elif now >= next_nag.get(seat, float("inf")):
            mins = int((now - idle_since[seat]) // 60)
            row = holding.get(seat)
            where = f"{row}" if row else "no row"
            print(f"STILL {seat} {where}: idle {mins}m", flush=True)
            gap = min((next_nag[seat] - idle_since[seat]) * 2, NAG_MAX)
            next_nag[seat] = now + gap
        if s == "idle" and prev != "idle":
            row = holding.get(seat)
            if row:
                print(f"STALL {seat} {row}: {detail}", flush=True)
            else:
                print(f"FREE  {seat}: {detail}", flush=True)
        if s == "working" and prev == "idle":
            print(f"BUSY  {seat}: resumed", flush=True)
        if s in ("idle", "working"):
            last[seat] = s
    time.sleep(poll)
