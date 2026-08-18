#!/usr/bin/env python3
"""checks/config/concentration-leg-routed.py — the concentration leg is ROUTED, and its definition
still invokes the ratchet with a threshold.

checks/rule-routing.sh exists for the analogous defect one surface down: a wall that is PRESENT but
wired to nothing, which is how .rules/kotlin sat dormant for a month under a green gate. The
concentration leg has the same hole in package.json. `checks/gate.sh` runs
`npm run --silent gate:concentration` and reports the leg green on exit 0, so the entire leg is
defanged by a ONE-LINE edit:

    "gate:concentration": "python3 checks/concentration.py --top 5"

That exits 0 unconditionally, and `npm run gate` keeps printing a green "concentration" leg over an
oracle that is no longer grading anything. The gate's own output cannot distinguish the two states —
which is the definition of a fake green.

Two directions, exactly as rule-routing.sh checks both:

  forward — checks/gate.sh actually RUNS the gate:concentration script, through `run` so its real
            exit code is captured (a mention in a comment is not a routing)
  inverse — that script invokes checks/concentration.py with --ratchet AND a numeric --max-ratio

Neither half is sufficient alone: a routed script that does not ratchet is the defang above, and a
correct script that nothing runs is the 2026-07-16 dormant-pack scar.

KNOWN LIMIT, stated rather than discovered later: this guards the leg, not itself. Deleting the
`concentration leg routed` line from checks/config-guard.sh removes this check. That regress stops
here for the same reason rule-routing.sh's does — one level of guard, in the gate, is what the
repo buys; beyond that the answer is code review, not another script.

Run: `python3 checks/config/concentration-leg-routed.py`, and as part of `bash checks/config-guard.sh`.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = "gate:concentration"
ORACLE = "checks/concentration.py"
MAX_RATIO = re.compile(r"--max-ratio[=\s]+\d+(\.\d+)?")


def problems() -> list[str]:
    found: list[str] = []

    body = json.loads((ROOT / "package.json").read_text(encoding="utf-8")).get("scripts", {}).get(SCRIPT)
    if body is None:
        found.append(
            f"package.json declares no '{SCRIPT}' script, but checks/gate.sh runs one — the leg "
            f"would fail loudly today, and the moment it does not, concentration is ungated."
        )
    else:
        if ORACLE not in body:
            found.append(
                f"'{SCRIPT}' does not invoke {ORACLE} (it runs: {body!r}) — the leg reports on "
                f"something other than the concentration oracle."
            )
        if "--ratchet" not in body.split():
            found.append(
                f"'{SCRIPT}' does not pass --ratchet (it runs: {body!r}) — without it the oracle "
                f"prints a table and exits 0 no matter what the census says, and `npm run gate` "
                f"still shows the leg green."
            )
        if not MAX_RATIO.search(body):
            found.append(
                f"'{SCRIPT}' does not pass a numeric --max-ratio (it runs: {body!r}) — --ratchet "
                f"without one exits 2 with 'a threshold that is not stated at the call site is not "
                f"auditable from the gate's own output'."
            )

    gate = (ROOT / "checks/gate.sh").read_text(encoding="utf-8")
    mentions = [ln.strip() for ln in gate.splitlines() if SCRIPT in ln and not ln.strip().startswith("#")]
    if not any(ln.startswith("run ") for ln in mentions):
        found.append(
            f"checks/gate.sh does not run '{SCRIPT}' through `run` — a leg invoked any other way "
            f"has its exit code masked, and a leg only mentioned in a comment is not routed at all. "
            f"This is the .rules/kotlin failure, one surface up."
        )
    return found


def main() -> int:
    found = problems()
    if found:
        print(f"concentration-leg-routed: FAIL ({len(found)} problem(s))", file=sys.stderr)
        for problem in found:
            print(f"  ✗ {problem}", file=sys.stderr)
        return 1
    print("concentration-leg-routed: PASS — gate.sh runs the leg, and the leg ratchets against a stated threshold")
    return 0


if __name__ == "__main__":
    sys.exit(main())
