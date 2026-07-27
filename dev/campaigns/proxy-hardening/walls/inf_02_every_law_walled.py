#!/usr/bin/env python3
"""WALL for INF-02 — every campaign standing law must name a mechanical enforcer.

The campaign's own worklist, made into an item so the ledger owns it. Before this existed the gate
reported "79 walls to build" while the LEDGER had zero items about walls: work visible only in a
gate's output is work nobody is assigned (2026-07-26 self-review finding #5).

EXIT 0 = zero rows in law_registry.toml have wall = "".  EXIT 1 = the worklist is non-empty.
--selftest = positive control (gate check C6).
"""
from __future__ import annotations
import pathlib, sys, tomllib

ROOT = pathlib.Path(__file__).resolve().parents[4]
REG = ROOT / "dev/campaigns/proxy-hardening/walls/law_registry.toml"


def detect(rows: list[dict]) -> list[str]:
    if not rows:
        return ["law_registry.toml has no rows — refusing to pass vacuously"]
    return [str(r.get("tag")) for r in rows if not str(r.get("wall", "")).strip()]


def selftest() -> int:
    fails = []
    if not detect([{"tag": "A", "wall": ""}, {"tag": "B", "wall": "x.py"}]):
        fails.append("a registry with an unwalled row must be RED")
    if detect([{"tag": "A", "wall": "x.py"}, {"tag": "B", "wall": "y.py"}]):
        fails.append("a fully-walled registry must be GREEN")
    if not detect([]):
        fails.append("an empty registry must be RED, never a vacuous pass")
    if fails:
        print("INF-02 SELFTEST FAIL:"); [print("  " + f) for f in fails]; return 1
    print("INF-02 SELFTEST OK — red while any row is unwalled, green when all are, red on empty")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    rows = tomllib.loads(REG.read_text(encoding="utf-8")).get("law", []) if REG.exists() else []
    un = detect(rows)
    print(f"INF-02: {len(rows)} law rows | unlawed {len(un)}")
    if un:
        print(f"INF-02 WALL RED: {len(un)} item(s) still have no wall: {', '.join(un[:12])}"
              + (" …" if len(un) > 12 else ""))
        return 1
    print("INF-02 WALL GREEN: every campaign item names an enforcing wall.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
