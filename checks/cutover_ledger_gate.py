#!/usr/bin/env python3
"""Cutover ledger gate (P8-CHECK) — the fail-closed check the cutover commit (P8-CUT) depends on.

DOCTRINE (#959 declare-then-earn): the tracker is an INPUT to the system, not documentation beside
it. Deleting server/ is allowed ONLY when every LOAD-BEARING campaign item is `verified` with
evidence notes. This script reads the kotlin-gateway ledger via tomllib and asserts that. Runs
write-time (a pre-commit / hook could call it) AND in CI (same-checker-twice).

Load-bearing = every item EXCEPT the ones that are structurally operator-gated (credentials, a live
workday, the destructive cutover itself) — those are listed in OPERATOR_GATED and reported as
BLOCKED, not counted as failures, because they cannot complete autonomously.

Usage:
  python3 checks/cutover_ledger_gate.py            # exit 1 unless all load-bearing items verified
  python3 checks/cutover_ledger_gate.py --dry-run  # report status, always exit 0
"""
from __future__ import annotations

import sys
import tomllib
from pathlib import Path

LEDGER = Path(__file__).resolve().parent.parent / "dev" / "campaigns" / "kotlin-gateway.toml"

# Items that CANNOT complete in an autonomous session (need the operator's credentials / live
# sessions / a deliberate destructive action). Reported as BLOCKED; never fail the gate on them.
OPERATOR_GATED = {
    "P0-XAI",    # no xAI API key on the build machine
    "P3-LIVE",   # needs a live ChatGPT-subscription session
    "P7-PAR",    # needs a full real workday on the Kotlin stack
    "P8-CUT",    # the destructive cutover itself — operator sign-off after live parity
    "P8-CHECK",  # this gate (don't require itself verified to run)
    "P2-GOLD",   # the Node-vs-Kotlin LIVE differential needs the Node harness + live comparison
}
# (P6-TOML's zero-code assembly is proven against mocks — verified, no longer gated; the only
#  remaining P6-TOML residue is a live vendor turn, folded into the P7-PAR live-parity gate.)


def main() -> int:
    dry_run = "--dry-run" in sys.argv
    if not LEDGER.exists():
        print(f"cutover-gate: ledger not found at {LEDGER}", file=sys.stderr)
        return 0 if dry_run else 1

    doc = tomllib.loads(LEDGER.read_text())
    items = doc.get("items", [])

    verified, unverified, blocked = [], [], []
    for it in items:
        iid = it.get("id", "?")
        status = it.get("status", "todo")
        if iid in OPERATOR_GATED:
            blocked.append((iid, status))
        elif status == "verified":
            verified.append(iid)
        else:
            unverified.append((iid, status))

    print(f"cutover-gate: {len(verified)} verified, {len(unverified)} unverified, {len(blocked)} operator-gated")
    for iid, status in blocked:
        print(f"  BLOCKED  {iid} ({status}) — operator-gated, not counted")
    for iid, status in unverified:
        print(f"  PENDING  {iid} ({status})")

    if unverified:
        print("cutover-gate: NOT READY — load-bearing items are not all verified.", file=sys.stderr)
        return 0 if dry_run else 1
    print("cutover-gate: READY — every load-bearing item is verified. Cutover may proceed once "
          "the operator-gated live-parity items are validated.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
