#!/usr/bin/env python3
"""WALL for INF-04 — no item may remain in W12-unscheduled.

CX-05, CX-06, CX-10 and CX-17 have full subsections in §8 of the audit but were assigned to NO wave
in its §10 plan. They were parked in W12-unscheduled so they stay visible rather than being silently
lost. Slotting them is an OPERATOR decision (CX-17 depends on CX-01 in W4).

EXIT 0 = W12-unscheduled is empty.  EXIT 1 = items are still unslotted.
--selftest = positive control (gate check C6).
"""
from __future__ import annotations
import pathlib, sys, tomllib

ROOT = pathlib.Path(__file__).resolve().parents[4]
BOARD = ROOT / "dev/campaigns/proxy-hardening.toml"
PARK = "W12-unscheduled"


def detect(items: list[dict]) -> list[str]:
    if not items:
        return ["ledger has no items — refusing to pass vacuously"]
    return [str(i.get("id")) for i in items if str(i.get("phase")) == PARK]


def selftest() -> int:
    fails = []
    if not detect([{"id": "CX-05", "phase": PARK}, {"id": "NF-01", "phase": "W1"}]):
        fails.append("a parked item must be RED")
    if detect([{"id": "CX-05", "phase": "W4-correctness-walls"}]):
        fails.append("a fully-slotted ledger must be GREEN")
    if not detect([]):
        fails.append("an empty ledger must be RED, never a vacuous pass")
    if fails:
        print("INF-04 SELFTEST FAIL:"); [print("  " + f) for f in fails]; return 1
    print("INF-04 SELFTEST OK — red while any item sits in W12, green once all are slotted, red on empty")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    items = tomllib.loads(BOARD.read_text(encoding="utf-8")).get("items", [])
    parked = detect(items)
    print(f"INF-04: {len(items)} items | parked in {PARK}: {len(parked)}")
    if parked:
        print(f"INF-04 WALL RED: unslotted — {', '.join(parked)}. Operator decision; §10 omitted them.")
        return 1
    print(f"INF-04 WALL GREEN: {PARK} is empty.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
