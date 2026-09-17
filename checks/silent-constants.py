#!/usr/bin/env python3
"""V4-88 — a numeric constant carries the reason it is that number. RATCHET.

WHY THIS EXISTS (ARCH-AUDIT 2026-09-17, class (d)). Naming a magic number is half the job. This
tree does the naming well — 619 numeric `const val` declarations in main sources, almost all with
a readable name — and then leaves the number itself unexplained:

    private const val HTTP_TOO_MANY = 429            // fine: the name IS the reason
    private const val MAX_TEXT_BYTES = 65_536        // why 64 KiB? nobody wrote it down
    private const val PROACTIVE_WINDOW_MS = 300_000L // why five minutes? nobody wrote it down

The second and third are where a value silently rots. The number was chosen against something —
a provider's observed limit, a surveyed harness floor, a herd-starvation window — and once that
reasoning is gone, the next session either leaves a wrong number alone because it looks
deliberate, or changes a right one because it looks arbitrary. The tree already knows how to do
this properly; Knob.UPSTREAM_RETRIES carries "4 attempts matches the surveyed harness floor
(codex 4, gemini/Claude Code higher); the old default of 2 with ~200ms total backoff still failed
turns on 2-3s blips (G4b)". That is the standard. 491 declarations do not meet it.

A wall that failed the build on all 491 would be reverted by lunchtime, so this is a RATCHET: the
census is recorded, the gate fails on GROWTH, and the number is meant to fall. It is a debt meter
with a one-way valve, not a style rule.

DENOMINATOR, FROM THE SOURCE (§24), never a hand list. Every `const val` under
gateway/*/src/main/**/*.kt is parsed off disk; the ones whose value is a numeric literal (or an
arithmetic expression over numeric literals — `64 * 1024`, `8L * 24 * 60 * 60 * 1000`) are the
denominator. 1184 declarations today, 619 numeric. A file added tomorrow is in scope with no edit
to this file. Three guards refuse a vacuous pass: zero source files is a failure, a parsed count
that disagrees with the count of `const val` lines in the source is a failure, and a baseline
whose own recorded denominator no longer matches the measured one is reported as drifted.

WHAT COUNTS AS A REASON, and why it is not a comment-length check. The comment must be ADJACENT —
the contiguous `//` or KDoc block immediately above the declaration, or a trailing `//` on the
same line. Contiguity matters: a blank line between a comment and a declaration means the comment
belongs to whatever is above it, and crediting it here would let one explained constant launder
the six unexplained ones beneath it. Then either:
  - it opens with `why:` — the explicit spelling, for a number whose reason is short; or
  - it holds at least MIN_REASON_WORDS words — a sentence. Measured on this tree the distribution
    is bimodal (489 declarations with NO adjacent comment at all, 109 with twelve words or more,
    almost nothing between), so the threshold is not a knife-edge: moving it from 2 to 6 moves the
    census by 4 declarations out of 619.
A name is not a reason. `HTTP_TOO_MANY = 429` is perfectly clear and still counts as silent,
because the ratchet's job is to make the tree's explained fraction rise, and exempting
"self-evident" names means hand-judging 619 constants — the hand list this wall exists to avoid.
The cost is that some entries in the baseline are cheap to retire; that is the intended shape.

THE RATCHET, both directions (mirroring checks/concentration.py's two arms):
  GROWTH — the measured total above the baseline total, or any FILE above its baseline count, is
           a regression and fails BY NAME. A new file with silent constants fails by name too.
  STALE  — a baseline entry held ABOVE its file's measured count, or naming a file that no longer
           exists or no longer has silent constants, fails. A baseline above the measured count is
           unearned room for the next regression to hide in, so retiring a constant means lowering
           the baseline in the same commit. That is the point of the valve.

WHAT IS NOT CAUGHT, stated rather than implied.
  An INLINE numeric literal (`if (status == 429)`, `delay(250)`). The subject here is the
  declaration that names a number; a literal with no name is a different wall, and this tree's
  detekt config already carries MagicNumber.
  A reason that is WRONG, or one that restates the name in four words ("the max text bytes cap").
  A word count cannot read. What it can do is make the absence of any attempt mechanical, and put
  the pressure of a falling baseline behind it.
  Non-numeric constants — strings, booleans, chars. A wire word documents itself; a magnitude does
  not.
  Test and testFixtures sources. Main sources are the subject, per the row.

SELFTEST. `--selftest` proves BOTH directions out of tree: GREEN on a compliant fixture (a `why:`
one-liner, a KDoc sentence, a trailing comment) and on the BORING cases (a tree with one numeric
const and a baseline of 1; a tree with no numeric consts and a baseline of 0 — both green WITH
their count); RED BY NAME on a synthetic silent constant added to a temp copy, on a new file
carrying one, on a baseline entry held above the measured count, on a baseline naming a vanished
file, on a comment separated from its declaration by a blank line, on a parse yielding zero
declarations, and on a parsed count that disagrees with the source.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

MAIN_GLOB = "gateway/*/src/main/**/*.kt"
BASELINE_REL = "checks/config/silent-constants-baseline.json"

# A sentence. See WHAT COUNTS AS A REASON for the measured insensitivity of this threshold.
MIN_REASON_WORDS = 4

DECL = re.compile(
    r"^[ \t]*(?:(?:public|internal|private|protected)\s+)?const\s+val\s+"
    r"([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[^=]+?)?\s*=[ \t]*(.*)$"
)
CONST_VAL_LINE = re.compile(r"\bconst\s+val\b")
NUM_TOKEN = re.compile(
    r"^(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*(?:\.[0-9_]+)?(?:[eE][-+]?[0-9]+)?)[LlFfDdUu]*$"
)
ARITH_SPLIT = re.compile(r"\s*(?:\*|/|\+|-|%|shl|shr|and|or|\(|\))\s*")
WORD = re.compile(r"[A-Za-z]{2,}")
WHY_MARKER = re.compile(r"^\s*why:", re.I)


class Silent:
    def __init__(self, name: str, rel: str, line: int, value: str) -> None:
        self.name = name
        self.rel = rel
        self.line = line
        self.value = value

    @property
    def where(self) -> str:
        return f"{self.rel}:{self.line}"


def strip_line_comment(text: str) -> tuple[str, str]:
    """(code, comment) — split at a `//` that is not inside a string literal."""
    in_string = False
    quote = ""
    escape = False
    i = 0
    while i < len(text):
        ch = text[i]
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == quote:
                in_string = False
            i += 1
            continue
        if ch in "\"'":
            in_string = True
            quote = ch
            i += 1
            continue
        if ch == "/" and i + 1 < len(text) and text[i + 1] == "/":
            return text[:i], text[i + 2 :]
        i += 1
    return text, ""


def is_numeric(raw: str) -> bool:
    """A numeric literal, or arithmetic over numeric literals. Strings/booleans/chars are out."""
    code, _ = strip_line_comment(raw)
    code = code.strip().rstrip(",")
    if not code:
        return False
    parts = [part for part in ARITH_SPLIT.split(code) if part.strip()]
    return bool(parts) and all(NUM_TOKEN.match(part) for part in parts)


def adjacent_reason(lines: list[str], index: int) -> str:
    """The CONTIGUOUS comment block above lines[index], plus its own trailing comment."""
    parts: list[str] = []
    j = index - 1
    while j >= 0:
        stripped = lines[j].strip()
        if stripped.startswith(("//", "*", "/*", "/**")) or stripped.endswith("*/"):
            parts.append(re.sub(r"^/\*+|^\*+/?|^//|\*/$", "", stripped))
            j -= 1
            continue
        break
    parts.reverse()
    _, trailing = strip_line_comment(lines[index])
    if trailing:
        parts.append(trailing)
    return " ".join(part.strip() for part in parts).strip()


def has_reason(comment: str) -> bool:
    return bool(comment) and (bool(WHY_MARKER.match(comment)) or len(WORD.findall(comment)) >= MIN_REASON_WORDS)


def scan(root: pathlib.Path) -> tuple[list[Silent], int, list[str]]:
    """(silent declarations, numeric denominator, problems that make the run untrustworthy)."""
    problems: list[str] = []
    files = sorted(root.glob(MAIN_GLOB))
    if not files:
        return [], 0, [f"no main sources matched {MAIN_GLOB} under {root} — the denominator is absent"]
    silent: list[Silent] = []
    numeric = 0
    parsed = 0
    raw_total = 0
    for path in files:
        rel = str(path.relative_to(root))
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            stripped = line.strip()
            if not stripped.startswith(("//", "*")) and CONST_VAL_LINE.search(line):
                raw_total += 1
            match = DECL.match(line)
            if match is None:
                continue
            parsed += 1
            value = match.group(2).strip()
            if not value:
                value = lines[i + 1].strip() if i + 1 < len(lines) else ""
            if not is_numeric(value):
                continue
            numeric += 1
            if not has_reason(adjacent_reason(lines, i)):
                silent.append(Silent(match.group(1), rel, i + 1, strip_line_comment(value)[0].strip()))
    if parsed == 0:
        problems.append(
            f"parsed 0 const declarations from {len(files)} main source file(s) — refusing to pass "
            "vacuously, because a green over an empty denominator is what this ratchet exists to prevent"
        )
    if raw_total != parsed:
        problems.append(
            f"parsed {parsed} declarations but the tree holds {raw_total} `const val` lines — the "
            "parser and the source disagree, so no census from this run can be trusted"
        )
    return silent, numeric, problems


def load_baseline(root: pathlib.Path) -> tuple[dict, list[str]]:
    path = root / BASELINE_REL
    if not path.exists():
        return {}, [f"{BASELINE_REL}: missing — the ratchet has no recorded census to hold the tree to"]
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return {}, [f"{BASELINE_REL}: unreadable ({exc}) — a ratchet that cannot read its baseline cannot gate"]
    for key in ("recorded", "total", "denominator", "files"):
        if key not in data:
            return {}, [f"{BASELINE_REL}: missing required key {key!r}"]
    return data, []


def ratchet(root: pathlib.Path) -> tuple[int, list[str]]:
    """(exit code, report lines). Prints the baseline, the measurement, and both arms, always."""
    out: list[str] = []
    silent, numeric, problems = scan(root)
    baseline, baseline_problems = load_baseline(root)
    problems = problems + baseline_problems
    if problems:
        out.append("FAIL: silent-constants ratchet — the instrument is untrustworthy:")
        out.extend(f"  ✗ {problem}" for problem in problems)
        return 2, out

    measured: dict[str, int] = {}
    for item in silent:
        measured[item.rel] = measured.get(item.rel, 0) + 1
    recorded: dict[str, int] = dict(baseline["files"])

    out.append(
        f"SILENT-CONSTANTS RATCHET — baseline recorded {baseline['recorded']}, "
        f"reason = `// why:` or a sentence of {MIN_REASON_WORDS}+ words, adjacent"
    )
    out.append(
        f"  {'numeric const val':<24} denominator {baseline['denominator']:>4}   measured {numeric:>4}"
    )
    out.append(
        f"  {'silent (no reason)':<24} baseline    {baseline['total']:>4}   measured {len(silent):>4}   [GATED]"
    )
    out.append(f"  {'files carrying them':<24} baseline    {len(recorded):>4}   measured {len(measured):>4}")

    failures: list[str] = []

    if numeric != baseline["denominator"]:
        out.append(
            f"  NOTE: the numeric denominator moved {baseline['denominator']} -> {numeric}; the gate "
            "reads the silent COUNT, not the ratio, so this is reported and not gated"
        )

    growth = sorted(rel for rel, count in measured.items() if count > recorded.get(rel, 0))
    for rel in growth:
        was = recorded.get(rel, 0)
        names = ", ".join(f"{item.name} = {item.value} ({item.where})" for item in silent if item.rel == rel)
        failures.append(
            f"GROWTH: {rel} carries {measured[rel]} silent numeric const(s), baseline {was} — {names}. "
            "Give the new one an adjacent reason (`// why: ...` or a sentence), or lower another "
            f"entry in {BASELINE_REL} in the same commit"
        )

    for rel, count in sorted(recorded.items()):
        now = measured.get(rel, 0)
        if now < count:
            failures.append(
                f"STALE: {BASELINE_REL} claims {count} silent const(s) in {rel} but only {now} "
                "remain — lower the entry to the measured count. A baseline held above the "
                "measurement is unearned room for the next regression to hide in"
            )
        if not (root / rel).exists():
            failures.append(
                f"STALE: {BASELINE_REL} names {rel}, which no longer exists — delete the entry"
            )

    if len(silent) > baseline["total"]:
        failures.append(
            f"GROWTH: the tree total rose {baseline['total']} -> {len(silent)}"
        )
    elif len(silent) < baseline["total"]:
        failures.append(
            f"STALE: the tree total fell {baseline['total']} -> {len(silent)}, and "
            f"{BASELINE_REL} still claims {baseline['total']} — record the win by lowering it"
        )

    if failures:
        out.append("")
        out.append(f"FAIL: silent-constants ratchet — {len(failures)} problem(s):")
        out.extend(f"  ✗ {failure}" for failure in failures)
        return 1, out

    out.append("")
    out.append(
        f"OK: silent-constants ratchet holds — {len(silent)} silent numeric const(s) across "
        f"{len(measured)} file(s), exactly the {baseline['recorded']} baseline"
    )
    return 0, out


def record(root: pathlib.Path) -> dict:
    """The baseline document for the tree as it stands. Used to author the JSON, never by the gate."""
    silent, numeric, problems = scan(root)
    if problems:
        raise SystemExit("; ".join(problems))
    files: dict[str, int] = {}
    for item in silent:
        files[item.rel] = files.get(item.rel, 0) + 1
    return {
        "_note": (
            "AUTHORED BY checks/silent-constants.py --record, MEASURED never estimated. Every entry "
            "is a numeric `const val` in main sources with no adjacent reason comment (see that "
            "file's WHAT COUNTS AS A REASON). The gate fails on GROWTH and on a STALE entry held "
            "above the measurement, so lowering an entry is how a fix is recorded. This file is a "
            "debt inventory with a one-way valve; it is meant to shrink to nothing."
        ),
        "recorded": "2026-09-17",
        "denominator": numeric,
        "total": len(silent),
        "files": dict(sorted(files.items())),
    }


def top(root: pathlib.Path, count: int) -> None:
    silent, numeric, problems = scan(root)
    for problem in problems:
        print(f"  UNTRUSTED: {problem}")
    per_file: dict[str, int] = {}
    for item in silent:
        per_file[item.rel] = per_file.get(item.rel, 0) + 1
    print(f"silent-constants: {len(silent)} of {numeric} numeric const val carry no adjacent reason")
    for rel, n in sorted(per_file.items(), key=lambda kv: (-kv[1], kv[0]))[:count]:
        print(f"  {n:>3}  {rel}")
        for item in silent:
            if item.rel == rel:
                print(f"         {item.line:>4}  {item.name} = {item.value}")


# ── selftest ──────────────────────────────────────────────────────────────────────────────────

COMPLIANT = """package splice.a

// why: the provider's observed ceiling
private const val MAX_TEXT_BYTES = 65_536

/** Five minutes is one client-retry cycle, so a recovered account resumes without operator help. */
private const val PROACTIVE_WINDOW_MS = 300_000L

private const val CHUNK = 64 * 1024 // 64 KiB matches the transport's own buffer, measured 2026-09
"""

ONE_SILENT = 'package splice.a\n\nprivate const val LONELY = 7\n'

NO_NUMERIC = """package splice.a

// a wire word documents itself
private const val FIELD_ROLE = "role"
"""

TWO_SILENT = 'package splice.a\n\nprivate const val ONE = 7\n\nprivate const val TWO = 9\n'

# A comment separated from its declaration by a blank line belongs to whatever is above it.
DETACHED = """package splice.a

// why: this explains the constant above, not the one below
internal fun f(): Int = 1

private const val ORPHANED = 41
"""

EMPTY = 'package splice.a\n\ninternal fun f(): Int = 7\n'

DRIFT = """package splice.a

// why: readable
private const val OK = 1

@Suppress("MagicNumber") private const val ANNOTATED = 3
"""


def write_tree(root: pathlib.Path, files: dict[str, str], baseline: dict | None) -> None:
    if (root / "gateway").exists():
        for path in sorted((root / "gateway").rglob("*.kt")):
            path.unlink()
    for rel, body in files.items():
        module, name = rel.split("/", 1)
        target = root / "gateway" / module / "src/main/kotlin/splice" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding="utf-8")
    path = root / BASELINE_REL
    path.parent.mkdir(parents=True, exist_ok=True)
    if baseline is None:
        if path.exists():
            path.unlink()
        return
    path.write_text(json.dumps(baseline, indent=2), encoding="utf-8")


def base(total: int, denominator: int, files: dict[str, int]) -> dict:
    return {"recorded": "selftest", "total": total, "denominator": denominator, "files": files}


def selftest() -> int:
    failures: list[str] = []

    def run(label: str, files: dict[str, str], baseline: dict | None) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_tree(root, files, baseline)
            code, lines = ratchet(root)
            return code, "\n".join(lines)

    def expect_green(label: str, files: dict[str, str], baseline: dict | None, *needles: str) -> None:
        code, text = run(label, files, baseline)
        if code != 0:
            failures.append(f"{label} must be GREEN, got exit {code}:\n{text}")
        for needle in needles:
            if needle not in text:
                failures.append(f"{label} must report {needle!r}, got:\n{text}")

    def expect_red(label: str, files: dict[str, str], baseline: dict | None, *needles: str) -> None:
        code, text = run(label, files, baseline)
        if code == 0:
            failures.append(f"{label} must be RED, got exit 0:\n{text}")
        for needle in needles:
            if needle not in text:
                failures.append(f"{label} must be RED naming {needle!r}, got:\n{text}")

    expect_green(
        "the compliant fixture (why:, a KDoc sentence, a trailing sentence)",
        {"app/A.kt": COMPLIANT},
        base(0, 3, {}),
        "measured    0",
    )
    expect_green(
        "the BORING case: one silent const, baseline 1",
        {"app/A.kt": ONE_SILENT},
        base(1, 1, {"gateway/app/src/main/kotlin/splice/A.kt": 1}),
        "measured    1",
    )
    expect_green(
        "the BORING case: no numeric const at all, baseline 0",
        {"app/A.kt": NO_NUMERIC},
        base(0, 0, {}),
        "denominator    0   measured    0",
    )

    expect_red(
        "a synthetic silent const added to an explained file",
        {"app/A.kt": COMPLIANT + "\nprivate const val SNEAKED = 13\n"},
        base(0, 3, {}),
        "GROWTH",
        "SNEAKED",
    )
    expect_red(
        "a NEW file carrying a silent const",
        {"app/A.kt": COMPLIANT, "core/B.kt": ONE_SILENT},
        base(0, 3, {}),
        "GROWTH",
        "LONELY",
    )
    expect_red(
        "a baseline entry held ABOVE the measurement",
        {"app/A.kt": ONE_SILENT},
        base(2, 1, {"gateway/app/src/main/kotlin/splice/A.kt": 2}),
        "STALE",
        "only 1",
    )
    expect_red(
        "a baseline naming a file that no longer exists",
        {"app/A.kt": ONE_SILENT},
        base(1, 1, {"gateway/app/src/main/kotlin/splice/A.kt": 1, "gateway/core/src/main/kotlin/splice/Gone.kt": 0}),
        "STALE",
        "no longer exists",
    )
    expect_red(
        "a comment detached from its declaration by a blank line is not a reason",
        {"app/A.kt": DETACHED},
        base(0, 1, {}),
        "GROWTH",
        "ORPHANED",
    )
    expect_red(
        "the tree total falling without the baseline following it",
        {"app/A.kt": ONE_SILENT},
        base(2, 2, {"gateway/app/src/main/kotlin/splice/A.kt": 1}),
        "STALE",
        "fell 2 -> 1",
    )
    expect_red(
        "a tree with no const at all refuses to pass vacuously",
        {"app/A.kt": EMPTY},
        base(0, 0, {}),
        "refusing to pass vacuously",
    )
    expect_red(
        "a `const val` the parser cannot read is a parser/source disagreement",
        {"app/A.kt": DRIFT},
        base(0, 1, {}),
        "the parser and the source disagree",
    )
    expect_red(
        "a missing baseline cannot gate",
        {"app/A.kt": ONE_SILENT},
        None,
        "has no recorded census",
    )

    # The two-item control: the ratchet must count, not merely detect. A file with two silent
    # constants and a baseline of one is GROWTH even though the file was already on the list.
    expect_red(
        "a file already on the list gaining a second silent const",
        {"app/A.kt": TWO_SILENT},
        base(1, 2, {"gateway/app/src/main/kotlin/splice/A.kt": 1}),
        "GROWTH",
        "carries 2 silent",
    )

    if failures:
        print("silent-constants SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure.replace("\n", "\n      "))
        return 1
    print(
        "silent-constants SELFTEST OK — `why:`, a KDoc sentence and a trailing sentence are "
        "green; one silent const against a baseline of one is green WITH its count, and so is a "
        "tree with no numeric const; a synthetic silent const, a new file carrying one, a second "
        "one in a file already listed, a detached comment, a baseline held above the measurement, "
        "a baseline naming a vanished file, a total that fell, an empty parse, a parser/source "
        "disagreement and a missing baseline are all red by name"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="numeric constants carry their reason (ratchet)")
    parser.add_argument("--ratchet", action="store_true", help="gate leg: fail on growth or a stale entry")
    parser.add_argument("--top", type=int, default=0, help="print the worst N files and their constants")
    parser.add_argument("--record", action="store_true", help="print the baseline JSON for this tree")
    parser.add_argument("--selftest", action="store_true", help="red-green proof, out of tree")
    parser.add_argument("--root", default=None, help="tree to measure (default: the repo root)")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve() if args.root else ROOT
    if not root.exists():
        print(f"silent-constants: {root} does not exist", file=sys.stderr)
        return 2

    if args.record:
        print(json.dumps(record(root), indent=2))
        return 0
    if args.top:
        top(root, args.top)
        return 0
    if args.ratchet:
        code, lines = ratchet(root)
        print("\n".join(lines))
        return code

    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
