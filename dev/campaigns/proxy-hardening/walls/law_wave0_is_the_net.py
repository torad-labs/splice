#!/usr/bin/env python3
"""LAW ENFORCER — WAVE-0-IS-THE-NET.

THE LAW  CX-19 (the response-side oracle) is the only regression net for W4-correctness-walls and
W5-overflow-skew. Nothing in those waves lands ahead of it.

WHY A WALL  This is a dependency the plan states in prose and nothing checks. The failure is
quiet and expensive: a W4 dialect change lands, the suite is green because nothing pins the emitted
SSE, and the regression surfaces weeks later in a live session. #924 — make the ordering
structural, not a sentence someone has to remember.

POLARITY NOTE — a LAW enforcer, not an item wall. GREEN today (nothing in W4/W5 is done); red means
the ordering was broken.

EXIT 0 = no W4/W5 item is done/verified while CX-19 is unfinished.  EXIT 1 = the net was skipped.
--selftest = positive control.
"""
from __future__ import annotations

import pathlib
import sys
import tomllib

ROOT = pathlib.Path(__file__).resolve().parents[4]
BOARD = ROOT / "dev/campaigns/proxy-hardening.toml"

NET = "CX-19"
GATED_PHASES = {"W4-correctness-walls", "W5-overflow-skew"}
DONE = {"done", "verified"}


def detect(items: list[dict]) -> list[str]:
    """items: [{id, phase, status}] — pure."""
    net = next((i for i in items if i.get("id") == NET), None)
    if net is None:
        return [f"{NET} is not in the ledger — the regression net for {', '.join(sorted(GATED_PHASES))} "
                "has vanished; refusing to pass vacuously"]
    if str(net.get("status")) in DONE:
        return []
    landed = [i for i in items if str(i.get("phase")) in GATED_PHASES and str(i.get("status")) in DONE]
    return [f"{i['id']} [{i['phase']}] is {i['status']} while {NET} is still {net.get('status')} — "
            "it landed with no response-side regression net" for i in landed]


def selftest() -> int:
    fails = []

    def case(name, items, want_red):
        got = detect(items)
        if want_red and not got:
            fails.append(f"{name}: must be RED")
        if not want_red and got:
            fails.append(f"{name}: must be GREEN, got {got}")

    net_todo = {"id": NET, "phase": "W0-net", "status": "todo"}
    net_done = {"id": NET, "phase": "W0-net", "status": "verified"}
    w4_done = {"id": "CX-01", "phase": "W4-correctness-walls", "status": "done"}
    w4_todo = {"id": "CX-01", "phase": "W4-correctness-walls", "status": "todo"}
    w8_done = {"id": "JW-09", "phase": "W8-operator-surface", "status": "verified"}

    case("nothing landed yet", [net_todo, w4_todo], False)
    case("W4 landed before the net", [net_todo, w4_done], True)
    case("W4 landed after the net", [net_done, w4_done], False)
    case("ungated phase may land anytime", [net_todo, w8_done], False)
    case("net missing entirely", [w4_todo], True)

    if fails:
        print("WAVE-0 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("WAVE-0 SELFTEST OK — red when a gated-phase item lands ahead of the net, green once the net "
          "is verified, ungated phases unaffected, missing net is red")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    items = tomllib.loads(BOARD.read_text(encoding="utf-8")).get("items", [])
    problems = detect(items)
    net = next((i for i in items if i.get("id") == NET), {})
    print(f"WAVE-0-IS-THE-NET: {NET} is '{net.get('status', 'absent')}'; "
          f"gated phases = {', '.join(sorted(GATED_PHASES))}")
    if problems:
        print("LAW VIOLATED — the regression net was skipped:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("LAW HONORED: no gated-phase item has landed ahead of the net.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
