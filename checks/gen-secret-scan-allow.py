#!/usr/bin/env python3
"""Generate .github/secret-scan-allow.txt from .github/secret-scan-allow.toml.

WHY THIS EXISTS (brain concept #924 — "you make drift not compile").

The allowlist is consumed as `grep -vEf`, so every line of it is a live regex, prose included.
That format lies: it looks like a config file with `#` comments, and every prior — human and
model — says `#` is inert. Three hazards shipped on that prior in PR #81, two of them into the
SAME FILE in the SAME PR hours apart, because the first was fixed as an instance rather than as
a class. Canaries and review CAUGHT them; nothing PREVENTED them.

This generator moves all three from detected to inexpressible:

    hazard                          before                    after
    unanchored entry                hand-written, hopefully   generator applies ^...$
    prose as a live regex           `#` looks like a comment  no prose slot exists at all
    invalid ERE breaks the file     silent until CI           generation fails

The remaining hand-edit risk — someone editing the .txt directly — is closed by `--check`, which
the gate runs (the same regenerate-and-diff idiom already used for webui/dist).

Usage:
    python3 checks/gen-secret-scan-allow.py            # write the .txt
    python3 checks/gen-secret-scan-allow.py --check    # verify the committed .txt is current
"""
from __future__ import annotations

import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / ".github" / "secret-scan-allow.toml"
OUT = ROOT / ".github" / "secret-scan-allow.txt"

# A hit reaches the allowlist as `grep -nIE` output: `<line>:<content>`. Every emitted pattern is
# bound to a WHOLE such line, so an exemption can never match a line that merely CONTAINS the
# declaration — which is how appending a credential to it bypassed the scan.
PREFIX = r"^[0-9]+:[[:space:]]*"
SUFFIX = r"[[:space:]]*$"

# The one prose line in the output. Anchored at ^# so it is inert: a hit always begins with a
# DIGIT, so this can never match one. It is generated rather than typed for exactly the reason
# this whole script exists.
HEADER = "^# GENERATED from secret-scan-allow.toml by checks/gen-secret-scan-allow.py. DO NOT EDIT."


def die(msg: str) -> None:
    print(f"secret-scan-allow: {msg}", file=sys.stderr)
    raise SystemExit(1)


def valid_ere(pattern: str) -> str | None:
    """Return grep's error for an invalid ERE, or None. grep is the authority here, not Python's
    `re` — the consumer is grep, and the two dialects disagree (an unbalanced `(` is fatal to grep
    and merely different in Python)."""
    proc = subprocess.run(
        ["grep", "-E", "--", pattern],
        input=b"x\n",
        capture_output=True,
    )
    # 0 = matched, 1 = no match; both mean the pattern compiled. 2 = bad regex.
    return proc.stderr.decode().strip() or "invalid extended regex" if proc.returncode > 1 else None


def render() -> str:
    if not SRC.exists():
        die(f"missing source: {SRC}")
    data = tomllib.loads(SRC.read_text())
    entries = data.get("exemption", [])
    if not entries:
        die("no [[exemption]] entries — refusing to emit an empty allowlist")

    lines = [HEADER]
    for i, entry in enumerate(entries, 1):
        pattern = entry.get("pattern")
        reason = (entry.get("reason") or "").strip()
        if not pattern:
            die(f"exemption {i}: missing `pattern`")
        if not reason:
            die(f"exemption {i}: missing `reason` — an unexplained exemption is not reviewable")
        # Anchoring belongs to the generator. A pattern that brings its own would let an entry
        # widen its own reach, which is hazard 1 all over again.
        if pattern.startswith("^") or pattern.endswith("$"):
            die(f"exemption {i}: `pattern` must not carry anchors; the generator adds them")
        if "\n" in pattern:
            die(f"exemption {i}: `pattern` must be a single line")

        line = f"{PREFIX}{pattern}{SUFFIX}"
        for candidate, what in ((pattern, "pattern"), (line, "generated line")):
            err = valid_ere(candidate)
            if err:
                die(f"exemption {i}: {what} is not a valid ERE ({err}): {candidate}")
        lines.append(line)

    return "\n".join(lines) + "\n"


def main() -> int:
    text = render()
    if "--check" in sys.argv:
        current = OUT.read_text() if OUT.exists() else ""
        if current != text:
            print(
                "secret-scan-allow.txt is STALE or hand-edited.\n"
                "  It is generated from .github/secret-scan-allow.toml.\n"
                "  Run: python3 checks/gen-secret-scan-allow.py",
                file=sys.stderr,
            )
            return 1
        print(f"  secret-scan allowlist: generated output matches ({len(text.splitlines())} lines)")
        return 0
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)} ({len(text.splitlines())} lines)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
