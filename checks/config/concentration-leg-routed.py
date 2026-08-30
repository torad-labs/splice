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

WHY THIS TOKENIZES INSTEAD OF SUBSTRING-MATCHING (2026-08-18). The first revision of this guard
asked `ORACLE in body`, `"--ratchet" in body.split()` and `ln.startswith("run ")` — raw substring
tests over UNPARSED text. A single `#` defeats every one of them, because the required substrings
go on matching once they sit in a shell COMMENT, i.e. in the part of the line that never executes.
Both halves were bypassed, and both were REPRODUCED against the old guard before this rewrite:

    "gate:concentration": "true # python3 checks/concentration.py --ratchet --max-ratio 1.8"

      -> `bash checks/config-guard.sh` printed `concentration-leg-routed: PASS` and exited 0, while
         `npm run --silent gate:concentration` exited 0 having produced NO OUTPUT AT ALL: the only
         command that ran was `true`. The oracle was gone and every surface still said green.

    run "concentration"  true  # gate:concentration disabled pending investigation

      -> the forward half passed, because the line does not start with `#` and does start with
         `run `, and `gate:concentration` is present — in the comment. The leg ran `true`.

A guard that reads the text a shell throws away is grading a string, not a command. So both halves
now strip comments and TOKENIZE — shlex in POSIX mode, which drops everything from an unquoted `#`
to end of line exactly as the shell does, while a `#` inside quotes stays data — and then assert on
the resulting argv. The question changed from "does this text contain the right words" to "does the
command that actually runs invoke the oracle". The inverse assertion now pins the complete argv:
a real interpreter, the oracle as argv[1], exactly one --ratchet, exactly one numeric --max-ratio,
and no trailing/control tokens that could mask the exit. The forward assertion is that a `run`
line's COMMAND tokenizes to the npm invocation, not that the line
happens to begin with a prefix.

An untokenizable line (unbalanced quotes) is skipped rather than trusted, so every half of this
guard FAILS CLOSED: nothing that cannot be parsed is ever counted as evidence that something is
routed.

KNOWN LIMIT, stated rather than discovered later: this guards the leg, not itself. Deleting the
`concentration leg routed` line from checks/config-guard.sh removes this check. That regress is now
caught one surface up — checks/concentration-selftest.sh runs both bypasses above as fixtures and
also deletes the leg from a throwaway copy of checks/gate.sh — but the selftest's own routing is
where the regress stops, for the same reason rule-routing.sh's does: one more level of guard, in
the gate, is what the repo buys; beyond that the answer is code review, not another script.

Run: `python3 checks/config/concentration-leg-routed.py`, and as part of `bash checks/config-guard.sh`.
"""
from __future__ import annotations

import json
import pathlib
import re
import shlex
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = "gate:concentration"
ORACLE = "checks/concentration.py"

# argv[0] of the leg must actually run a .py file. Asserting the interpreter POSITIVELY is the
# generalisation of "argv[0] is not `true`": blacklisting one no-op leaves `:`, and leaves
# `echo python3 checks/concentration.py --ratchet --max-ratio 1.8`, which satisfies every
# name-and-flag check in this file while executing nothing.
PYTHON = re.compile(r"^python(3(\.\d+)?)?$")

# The command half of the leg line in checks/gate.sh — `run <label> npm run [flags] gate:concentration`.
# Pinned as tokens, so a leg whose command is `true` cannot pass by carrying the script name in a
# trailing comment.
LEG_RUNNER = ("npm", "run")


def tokenize(line: str) -> list[str] | None:
    """The argv a POSIX shell would actually execute for `line`, or None if it does not tokenize.

    `comments=True` is the entire point: shlex discards an unquoted `#` and everything after it,
    exactly as the shell does, while a `#` inside quotes survives as data. None (rather than []) on
    unbalanced quotes keeps callers fail-closed — a line nobody can parse is never evidence.
    """
    try:
        return shlex.split(line, comments=True)
    except ValueError:
        return None


def max_ratio_of(argv: list[str]) -> float | None:
    """The numeric value of --max-ratio in argv, or None if it is absent or not a number.

    Both spellings, because argparse accepts both and a guard that only knows one of them is a
    guard the next author trips over for no reason.
    """
    for index, token in enumerate(argv):
        if token == "--max-ratio":
            value = argv[index + 1] if index + 1 < len(argv) else None
        elif token.startswith("--max-ratio="):
            value = token.split("=", 1)[1]
        else:
            continue
        if value is None:
            return None
        try:
            return float(value)
        except ValueError:
            return None
    return None


def exact_oracle_argv_problem(argv: list[str]) -> str | None:
    """Why argv is not exactly `python ORACLE --ratchet --max-ratio N`, or None."""
    if len(argv) < 2 or argv[1] != ORACLE:
        return f"the oracle must be argv[1], got {argv[1:2]!r}"

    ratchets = 0
    ratios = 0
    unexpected: list[str] = []
    index = 2
    while index < len(argv):
        token = argv[index]
        if token == "--ratchet":
            ratchets += 1
            index += 1
        elif token == "--max-ratio":
            ratios += 1
            if index + 1 >= len(argv):
                unexpected.append(token)
                index += 1
            else:
                try:
                    float(argv[index + 1])
                except ValueError:
                    unexpected.extend(argv[index : index + 2])
                index += 2
        elif token.startswith("--max-ratio="):
            ratios += 1
            try:
                float(token.split("=", 1)[1])
            except ValueError:
                unexpected.append(token)
            index += 1
        else:
            unexpected.append(token)
            index += 1

    if ratchets != 1 or ratios != 1 or unexpected:
        return (
            f"expected one --ratchet and one numeric --max-ratio with no other argv; "
            f"saw ratchet={ratchets}, max-ratio={ratios}, unsupported or trailing token(s)={unexpected!r}"
        )
    return None


def inverse_problems() -> list[str]:
    """package.json's gate:concentration really invokes the ratcheting oracle."""
    found: list[str] = []
    body = json.loads((ROOT / "package.json").read_text(encoding="utf-8")).get("scripts", {}).get(SCRIPT)
    if body is None:
        found.append(
            f"package.json declares no '{SCRIPT}' script, but checks/gate.sh runs one — the leg "
            f"would fail loudly today, and the moment it does not, concentration is ungated."
        )
        return found

    argv = tokenize(body)
    if argv is None:
        found.append(
            f"'{SCRIPT}' does not tokenize as a shell command (it is defined as: {body!r}) — "
            f"unbalanced quotes. A definition this guard cannot parse is not a definition it will "
            f"vouch for."
        )
        return found
    if not argv:
        found.append(
            f"'{SCRIPT}' executes NOTHING (it is defined as: {body!r}) — once shell comments are "
            f"stripped no command is left, so the leg exits 0 without the oracle ever running."
        )
        return found

    if not PYTHON.match(argv[0].rsplit("/", 1)[-1]):
        found.append(
            f"'{SCRIPT}' does not run a python interpreter — argv[0] is {argv[0]!r} (it is defined "
            f"as: {body!r}). Whatever follows, the oracle is not what executes; this is the "
            f"`true # <the real command>` bypass, where every required word survives in a comment "
            f"the shell discards."
        )
    if ORACLE not in argv:
        found.append(
            f"'{SCRIPT}' does not invoke {ORACLE} — it executes {argv!r} (defined as: {body!r}). "
            f"The leg reports on something other than the concentration oracle."
        )
    if "--ratchet" not in argv:
        found.append(
            f"'{SCRIPT}' does not pass --ratchet — it executes {argv!r} (defined as: {body!r}). "
            f"Without it the oracle prints a table and exits 0 no matter what the census says, and "
            f"`npm run gate` still shows the leg green."
        )
    if max_ratio_of(argv) is None:
        found.append(
            f"'{SCRIPT}' does not pass a numeric --max-ratio — it executes {argv!r} (defined as: "
            f"{body!r}). --ratchet without one exits 2 with 'a threshold that is not stated at the "
            f"call site is not auditable from the gate's own output'."
        )
    exact_problem = exact_oracle_argv_problem(argv)
    if exact_problem is not None:
        found.append(
            f"'{SCRIPT}' is not the exact ratchet command: {exact_problem}. It executes {argv!r} "
            f"(defined as: {body!r}); shell control operators or trailing commands can mask the "
            f"oracle's exit status."
        )
    return found


def forward_problems() -> list[str]:
    """checks/gate.sh really runs that script, through `run`, so its exit code is captured."""
    gate = (ROOT / "checks/gate.sh").read_text(encoding="utf-8")
    routed = []
    for line in gate.splitlines():
        argv = tokenize(line)
        if not argv or argv[0] != "run":
            continue
        command = argv[2:]  # argv[1] is run()'s label; everything after it is the command
        if tuple(command[:2]) == LEG_RUNNER and command[-1:] == [SCRIPT]:
            routed.append(line.strip())
    if routed:
        return []

    # Name the near-misses. The whole point of this rewrite is that a line CONTAINING the script
    # name proves nothing, so the failure has to show the reader the difference between the text
    # and the command.
    mentions = [line.strip() for line in gate.splitlines() if SCRIPT in line]
    detail = ""
    if mentions:
        shown = " | ".join(repr(m) for m in mentions[:3])
        detail = (
            f" '{SCRIPT}' does appear on {len(mentions)} line(s), so the substring test this guard "
            f"used to run would pass: {shown} — but none of them TOKENIZES to a `run` leg whose "
            f"command is `npm run ... {SCRIPT}`. A mention inside a shell comment is not a routing."
        )
    return [
        f"checks/gate.sh does not run '{SCRIPT}' through `run` as `npm run ... {SCRIPT}` — a leg "
        f"invoked any other way has its exit code masked, and a leg only mentioned in a comment is "
        f"not routed at all. This is the .rules/kotlin failure, one surface up.{detail}"
    ]


def problems() -> list[str]:
    return inverse_problems() + forward_problems()


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
