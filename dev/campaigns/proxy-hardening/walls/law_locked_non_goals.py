#!/usr/bin/env python3
"""LAW ENFORCER — LOCKED-NON-GOALS.

THE LAW  splice is single-user, loopback-only, no TLS, no remote access, no multi-user/RBAC; the
JVM daemon stays; server/ is legacy-and-dying; reasoning replay stays default-off. §11 of the audit
carries a 22-row ledger of ideas the sweep FOUND in the landscape and deliberately did not propose.

WHY A WALL  These are settled operator decisions. The failure mode is not someone arguing for them
openly — it is a locked non-goal drifting back in as an innocuous-looking item during a later wave,
because 83 items are too many for anyone to re-read. #924: you don't review your way out of drift.

POLARITY NOTE — this is a LAW enforcer, not an item wall. It must be GREEN today (no item currently
proposes a locked non-goal) and going red means a law was BROKEN. That is the opposite of an item
wall, which must be red until its gap is closed.

EXIT 0 = no item proposes a locked non-goal.  EXIT 1 = one does.
--selftest = positive control: proves the scan separates a violating item from a clean one, and
             does not fire on the laws/§11 text that legitimately NAMES the non-goals.
"""
from __future__ import annotations

import pathlib
import re
import sys
import tomllib

ROOT = pathlib.Path(__file__).resolve().parents[4]
BOARD = ROOT / "dev/campaigns/proxy-hardening.toml"

# Each rule: (label, pattern that indicates PROPOSING it, pattern that exonerates as a mention)
BANNED = [
    ("multi-user/RBAC", r"\b(add|introduce|implement|support)\b[^.]{0,60}\b(multi-?user|RBAC|tenant)\b"),
    ("TLS/remote", r"\b(add|introduce|implement|expose|enable)\b[^.]{0,60}\b(TLS|HTTPS listener|remote access|bind 0\.0\.0\.0)\b"),
    ("non-JVM rewrite", r"\b(rewrite|port|migrate)\b[^.]{0,60}\b(in|to)\s+(Go|Rust|Bun|GraalVM|KMP)\b"),
    # NOTE: no trailing \b after `server/` — `/` is a non-word char, so `\bserver/\b` never matches
    # (caught by this wall's own positive control, 2026-07-26).
    ("legacy server/ work", r"\b(fix|extend|refactor|improve)\b[^.]{0,40}\bserver/"),
    ("replay on by default", r"\breplay\w*\b[^.]{0,40}\b(on by default|default-on|enable by default)\b"),
]
# A title may legitimately QUOTE a non-goal when recording that it was rejected.
EXONERATE = re.compile(r"locked non-goal|deliberately not proposed|wontfix|RECONSIDER:|§11", re.I)


def detect(items: list[dict]) -> list[str]:
    """items: [{id, title}] — pure, so the selftest feeds it directly."""
    out: list[str] = []
    for it in items:
        title = str(it.get("title", ""))
        if EXONERATE.search(title):
            continue
        for label, pat in BANNED:
            if re.search(pat, title, re.I):
                out.append(f"{it.get('id')}: proposes a LOCKED NON-GOAL ({label}). "
                           "Settled operator decision — do not re-open it as an item.")
                break
    return out


def selftest() -> int:
    fails = []

    def case(name, items, want_red):
        got = detect(items)
        if want_red and not got:
            fails.append(f"{name}: must be RED")
        if not want_red and got:
            fails.append(f"{name}: must be GREEN, got {got}")

    case("clean item", [{"id": "NF-01", "title": "clamp the 429 cooldown horizon"}], False)
    case("proposes multi-user", [{"id": "X-01", "title": "add multi-user support to the control plane"}], True)
    case("proposes TLS", [{"id": "X-02", "title": "enable TLS on the head listener"}], True)
    case("proposes rust rewrite", [{"id": "X-03", "title": "rewrite the daemon in Rust for speed"}], True)
    case("proposes legacy work", [{"id": "X-04", "title": "refactor server/ stream handling"}], True)
    case("mentions but rejects", [{"id": "X-05",
        "title": "multi-user was found in the sweep and is a locked non-goal — not proposed"}], False)
    case("RECONSIDER prefix is allowed", [{"id": "X-06",
        "title": "RECONSIDER: cross-head failover — implement multi-user style dispatch"}], False)
    case("empty ledger", [], False)

    if fails:
        print("LOCKED-NON-GOALS SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("LOCKED-NON-GOALS SELFTEST OK — fires on proposals, stays quiet on rejections/RECONSIDER mentions")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    items = tomllib.loads(BOARD.read_text(encoding="utf-8")).get("items", [])
    problems = detect(items)
    print(f"LOCKED-NON-GOALS: scanned {len(items)} items")
    if problems:
        print("LAW VIOLATED — an item proposes a locked non-goal:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("LAW HONORED: no item proposes a locked non-goal.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
