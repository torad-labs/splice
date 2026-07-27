#!/usr/bin/env python3
"""WALL for INF-01 — every campaign item must name an enforcing wall.

The campaign's own worklist, made into an item so the ledger owns it. Before this existed the gate
reported "79 walls to build" while the LEDGER had zero items about walls: work visible only in a
gate's output is work nobody is assigned (2026-07-26 self-review finding #5).

EXIT 0 = zero rows in wall_registry.toml have wall = "".  EXIT 1 = the worklist is non-empty.
--selftest = positive control (gate check C6).
"""
from __future__ import annotations
import pathlib, sys, tomllib

ROOT = pathlib.Path(__file__).resolve().parents[4]
REG = ROOT / "dev/campaigns/proxy-hardening/walls/wall_registry.toml"


def detect(rows: list[dict]) -> list[str]:
    if not rows:
        return ["wall_registry.toml has no rows — refusing to pass vacuously"]
    return [str(r.get("id")) for r in rows if not str(r.get("wall", "")).strip()]


def selftest() -> int:
    fails = []
    if not detect([{"id": "A", "wall": ""}, {"id": "B", "wall": "x.py"}]):
        fails.append("a registry with an unwalled row must be RED")
    if detect([{"id": "A", "wall": "x.py"}, {"id": "B", "wall": "y.py"}]):
        fails.append("a fully-walled registry must be GREEN")
    if not detect([]):
        fails.append("an empty registry must be RED, never a vacuous pass")
    if fails:
        print("INF-01 SELFTEST FAIL:"); [print("  " + f) for f in fails]; return 1
    print("INF-01 SELFTEST OK — red while any row is unwalled, green when all are, red on empty")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    rows = tomllib.loads(REG.read_text(encoding="utf-8")).get("item", []) if REG.exists() else []
    un = detect(rows)
    print(f"INF-01: {len(rows)} registry rows | unwalled {len(un)}")
    if un:
        print(f"INF-01 WALL RED: {len(un)} item(s) still have no wall: {', '.join(un[:12])}"
              + (" …" if len(un) > 12 else ""))
        return 1
    print("INF-01 WALL GREEN: every campaign item names an enforcing wall.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
