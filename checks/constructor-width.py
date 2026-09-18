#!/usr/bin/env python3
"""V4-93 — a primary constructor's WIDTH is billed, because detekt does not bill it.

WHY THIS EXISTS. gateway/detekt.yml:38-43 configures LongParameterList with
`constructorThreshold: 8`, and then turns it off for the two shapes this tree actually
uses:

    LongParameterList:
      active: true
      functionThreshold: 6
      constructorThreshold: 8
      ignoreDefaultParameters: true      # every collaborator bundle defaults its seams
      ignoreDataClasses: true            # every config/wire type is a data class

So the widest constructors in the tree are billed by NOTHING. Measured 2026-09-17:
HeadDeps takes 25 parameters, ResponsesQuirks 23, CodeModeRecord 22,
CodeModeRecordSnapshot 22, LaunchSpec 21, QuirksConfig 21, TurnMeta 19, TurnDrive 17,
ControlServer 17, ManagedHead 17 — fourteen constructors above twelve parameters, and
detekt reports zero LongParameterList findings on every one. That is not detekt being
wrong: the two ignores are there because a defaulted seam and a data-class field are
CHEAP individually. What they cannot see is the total, and the total is the coupling.

TWO WIDTHS, because they fail differently.
  · PARAMETERS — more than MAX_PARAMS (12). A constructor this wide has no call site a
    reader can check: every argument is positional-or-named against a list nobody holds in
    their head, and adding one more is free. Data classes are IN SCOPE here, deliberately,
    against detekt's ignore: QuirksConfig and TurnMeta are exactly the shapes the ignore
    hides.
  · SUBSYSTEMS — more than MAX_SUBSYSTEMS (6) distinct `splice.*` packages named by the
    parameter TYPES. This is the architectural half, and it is the one a data class rarely
    trips: it counts how many parts of the system a single constructor has to know at once.
    HeadDeps names 8 (splice.core.model, splice.core.prompt, splice.core.util,
    splice.core.version, splice.gateway.compact, splice.gateway.perf, splice.gateway.usage,
    splice.spi); TurnDrive names 7. Same definition of `subsystem` as
    checks/concentration.py — a distinct `splice.<pkg>` an import line resolves to — so the
    two oracles cannot disagree about what a subsystem is.
A constructor over EITHER width is an offender, named with the number that put it there.

THE DENOMINATOR COMES FROM THE SOURCE (§24). Every `class` declaration with a primary
constructor under gateway/*/src/main is parsed on disk — top-level and nested, every
modifier spelling (`data`, `value`, `sealed`, `inner`, `annotation`, `private`). 746
constructors today. A class added tomorrow is in scope with no edit to this file. Three
guards refuse a vacuous pass: a parse yielding zero constructors FAILS, a class whose
constructor text cannot be walked FAILS by name, and `--selftest` re-derives the class
denominator from ast-grep's Kotlin AST so a spelling this file's regex misses cannot
disappear from both (the DR-51 `fun interface` dodge, one file over).

WHY ITS OWN PARSER. The comment- and string-aware paren walk and the top-level comma split
are lifted in behaviour from checks/config/quirks-keys-documented.py, which lifted them from
checks/config/shared-quirks-no-vendor-defaults.py. That is the repo's existing idiom for this
job and the reason is the same: KDoc is interleaved BETWEEN parameters all over this tree
(HeadDeps has eight such blocks), so a naive paren walk either stops early or runs past the
constructor, and a naive comma split swallows every parameter after the first `Map<String,
QuirksConfig>`. The declaration matcher requires the paren to follow the class NAME (with
optional type parameters and an explicit `constructor` keyword), never merely to appear
somewhere on the line: `class X : Super(a, b, ...)` is a supertype call, and admitting it
would bill a class for arguments it passes rather than parameters it takes. Proven against
ast-grep's own `primary_constructor` nodes in selftest arm 13, count for count.

THE RATCHET. Fourteen constructors are over the width today, so `12 or bust` cannot be the
gate leg without finishing the fix row first. What IS enforceable is the DIRECTION, in the
idiom checks/concentration.py uses for the HIGH band and checks/public-surface.py for the
public surface:
  · GROWTH fails — a constructor that crosses a width and is not recorded, or a RECORDED one
    that got WIDER than its recorded number, is red by name on the commit that does it. The
    second half matters more than the first: without it, HeadDeps could go from 25 to 40
    parameters under a green gate.
  · A STALE OR PADDED ENTRY fails — an entry naming a class that is gone, one that no longer
    offends, or one recorded ABOVE the measured width. A baseline held above the measurement
    is unearned room for the next regression to hide in; checks/concentration.py records that
    exact defect twice (the 6.14 UpstreamClient ceiling against a file measuring 2.79).
  · IT CANNOT BE SATISFIED BY WEAKENING. Raising MAX_PARAMS would be a dated one-line diff
    reading as what it is; growing the baseline likewise. Shrinking it is the remedy the gate
    itself prints.

NOT CAUGHT, stated here rather than discovered later.
  · SECONDARY constructors and factory functions. Only the PRIMARY constructor is measured.
    A 30-argument `constructor(...)` in a class body, or a `fun of(...)` taking the same
    bundle, is invisible here; detekt's functionThreshold 6 does bill plain functions, which
    is why the hole is narrow rather than wide.
  · A BUNDLE ONE LEVEL DOWN. Replacing 25 parameters with one `HeadDeps` parameter satisfies
    both widths without reducing coupling — the parameter count moves, the knowledge does
    not. That is the shape checks/concentration.py's `concerns` term measures, and it is why
    these two oracles are read together rather than either alone.
  · A TYPE NAMED BY AN ALIAS OR A STAR IMPORT. Subsystems are resolved through the file's own
    single-type import lines, so a type reached by `import splice.x.*` or a typealias
    resolves to no subsystem and is simply not counted. Measured 2026-09-17: no splice
    package is star-imported anywhere in production, so the hole is empty today.

SELFTEST. `--selftest` builds temp trees and proves BOTH directions plus the boring cases:
GREEN on a 12-parameter constructor, on a 6-subsystem one, on a class with no constructor and
on a recorded offender; RED BY NAME on a synthetic 13-parameter constructor (including one
spelled `data class` with every parameter defaulted — the exact pair detekt ignores), on a
7-subsystem one, on a recorded offender that got wider, on a baseline entry recorded above the
measurement, on a stale entry, on an undated baseline, and on a tree whose parse yields no
constructors at all.
"""
from __future__ import annotations

import argparse
import io
import json
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

SRC_GLOB = "gateway/*/src/main"
BASELINE_REL = "checks/config/constructor-width-baseline.json"

# The two widths. Deliberately visible constants: a threshold nobody can read is the same
# defect as the detekt ignores above.
MAX_PARAMS = 12
MAX_SUBSYSTEMS = 6

# V4-122 item 8': THE CONFIG-RECORD BUDGET, a THIRD width for a shape neither of the two above
# describes.
#
# WHY A CONFIG RECORD NEEDS ITS OWN BUDGET. This instrument exists to catch COLLABORATOR sprawl — a
# constructor whose width means it wires too many things and has no call site a human can read. A
# @Serializable record whose every parameter is a defaulted `val` and which names no subsystem is
# not that: its parameters are TOML KEYS. QuirksConfig carries twenty-five of them and every one is
# a vendor deformation the config surface has to name, already gated three other ways (the
# quirks-keys-documented wall, the oracle pin, and the disposition block in
# config/splice.example.toml). Measuring it against MAX_PARAMS made the row's own subject look like
# debt and produced a fix — group the keys to get back under 12-adjacent arithmetic — that would
# have cost a custom TOML serializer to buy one number. The code was never the debt; the instrument
# was counting a config surface as a dependency list.
#
# WHAT THIS BUDGET IS FOR, and what it is not. Its job on a config record is to catch a DUMPING
# GROUND — a record that has stopped being one vendor's quirks and become wherever new keys are
# thrown — not to cap a vendor surface, which grows one key per deformation by nature. 32 leaves
# QuirksConfig seven keys of room and still bites long before a dumping ground. A record that
# exceeds it should be SPLIT by subject, not compressed into fewer, wider keys.
MAX_CONFIG_KEYS = 32

# A class declaration whose name is followed (before any newline) by the primary-constructor
# paren. `^[ \t]*` admits nested classes; the modifier set admits every Kotlin spelling,
# including `annotation` and `value`, so the census cannot become a dodge list (DR-51).
CLASS_DECL = re.compile(
    r"^[ \t]*(?:(?:public|internal|private|protected|sealed|data|abstract|open|value|enum|inner"
    r"|annotation|expect|actual)[ \t]+)*"
    r"class[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*(?:<[^<>\n]*>)?[ \t]*"
    # An annotated or visibility-qualified explicit primary constructor: `class X @Inject
    # internal constructor(...)`. All optional, and all BEFORE the paren.
    r"(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)\n]*\))?[ \t]*)*"
    r"(?:(?:private|protected|internal|public)[ \t]+)*(?:constructor[ \t]*)?\(",
    re.MULTILINE,
)
# Same shape as checks/concentration.py's SPLICE_IMPORT, so `subsystem` means one thing in
# this repo: the `splice.<pkg>` a single-type import line resolves to.
SPLICE_IMPORT = re.compile(r"^import (splice\.[A-Za-z0-9_.]+)\.([A-Za-z0-9_]+)\s*$", re.MULTILINE)
PARAM_NAME = re.compile(r"\b(?:val|var)?\s*([A-Za-z_][A-Za-z0-9_]*)\s*:")
TYPE_NAME = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")
RECORDED = re.compile(r"^\d{4}-\d{2}-\d{2}$")


# ── the parser (behaviour lifted from checks/config/quirks-keys-documented.py) ─────────

def extract_constructor(source: str, start: int) -> str | None:
    """The primary-constructor text inside the parens at [start], or None.

    Comment- and string-aware: KDoc is interleaved between parameters all over this tree, and
    a naive paren walk would either stop at the first `)` inside a default value or run past
    the constructor entirely."""
    i = start
    depth = 0
    in_string = False
    quote = ""
    escape = False
    body_start = None
    while i < len(source):
        ch = source[i]
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
        if ch == "/" and i + 1 < len(source) and source[i + 1] == "/":
            nl = source.find("\n", i)
            i = len(source) if nl < 0 else nl
            continue
        if ch == "/" and i + 1 < len(source) and source[i + 1] == "*":
            end = source.find("*/", i + 2)
            i = len(source) if end < 0 else end + 2
            continue
        if ch == "(":
            depth += 1
            if depth == 1:
                body_start = i + 1
            i += 1
            continue
        if ch == ")":
            depth -= 1
            if depth == 0 and body_start is not None:
                return source[body_start:i]
            i += 1
            continue
        i += 1
    return None


def split_params(body: str) -> list[str]:
    """Split a constructor body on TOP-LEVEL commas, dropping comments.

    Top-level is what makes `Map<String, QuotaTracker>` one parameter rather than two, and
    dropping comments is what keeps a KDoc sentence containing a comma from minting one. See
    ANGLE BRACKETS ARE NOT BRACKETS below for the lambda-default bug the lifted version had,
    and selftest arm 13 for the ast-grep count that found it."""
    parts: list[str] = []
    buf: list[str] = []
    depth = 0
    angle = 0
    in_string = False
    quote = ""
    escape = False
    i = 0
    while i < len(body):
        ch = body[i]
        if in_string:
            buf.append(ch)
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
            buf.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < len(body) and body[i + 1] == "/":
            nl = body.find("\n", i)
            i = len(body) if nl < 0 else nl
            continue
        if ch == "/" and i + 1 < len(body) and body[i + 1] == "*":
            end = body.find("*/", i + 2)
            i = len(body) if end < 0 else end + 2
            continue
        if ch in "({[":
            depth += 1
            buf.append(ch)
            i += 1
            continue
        if ch in ")}]":
            depth -= 1
            buf.append(ch)
            i += 1
            continue
        # ANGLE BRACKETS ARE NOT BRACKETS (2026-09-17). Counting `<`/`>` as depth the way the
        # lifted parser does is correct for a pure-schema file and WRONG for Kotlin at large:
        # `->` and a `>` comparison each decremented the depth, so every parameter after a
        # lambda default fell out of the count. Measured against ast-grep's own
        # `class_parameter` nodes, eight constructors were UNDERCOUNTED — SessionRegistry read
        # 3 parameters where the grammar counts 7 — and undercounting is the direction that
        # hides an offender. A `<` only opens a type argument list when it IMMEDIATELY follows
        # an identifier (`Map<`), never across whitespace (`a < b`); a `>` only closes one when
        # a list is open and the character is not the tail of `->` or `>=`.
        if ch == "<" and i > 0 and (body[i - 1].isalnum() or body[i - 1] in "_>") and body[i + 1 : i + 2] != "=":
            angle += 1
            buf.append(ch)
            i += 1
            continue
        if ch == ">" and angle > 0 and body[i - 1 : i] != "-" and body[i + 1 : i + 2] != "=":
            angle -= 1
            buf.append(ch)
            i += 1
            continue
        if ch == "," and depth == 0 and angle == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if buf:
        parts.append("".join(buf))
    return [part for part in (raw.strip() for raw in parts) if part]


class Constructor:
    def __init__(
        self,
        name: str,
        rel: str,
        line: int,
        params: int,
        subsystems: list[str],
        config_record: bool = False,
    ) -> None:
        self.name = name
        self.rel = rel
        self.line = line
        self.params = params
        self.subsystems = subsystems
        self.config_record = config_record

    @property
    def id(self) -> str:
        """Baseline identity: the class name plus its file. Two classes in this tree share a
        simple name often enough (`Success`, `Nested`) that the name alone is not an identity,
        and the file is what makes a moved class read as a move rather than as a new offender."""
        return f"{self.rel} {self.name}"

    def over(self) -> list[str]:
        # A CONFIG RECORD is measured against the config budget ALONE. It cannot reach the other two
        # by construction — no subsystem is part of its definition — so grading it on params would
        # be grading TOML keys against a collaborator limit, which is the defect this shape fixes.
        if self.config_record:
            if self.params > MAX_CONFIG_KEYS:
                return [f"{self.params} config keys (max {MAX_CONFIG_KEYS})"]
            return []
        reasons = []
        if self.params > MAX_PARAMS:
            reasons.append(f"{self.params} parameters (max {MAX_PARAMS})")
        if len(self.subsystems) > MAX_SUBSYSTEMS:
            reasons.append(
                f"{len(self.subsystems)} subsystems (max {MAX_SUBSYSTEMS}): {', '.join(self.subsystems)}"
            )
        return reasons


# A parameter that is a `val` WITH a default — annotations allowed in front. `[^=]*` is the type,
# so the `=` it must reach is the default's, never an `=` inside a type expression.
DEFAULTED_VAL = re.compile(
    r"^\s*(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)\n]*\))?\s*)*"
    r"val\s+[A-Za-z_][A-Za-z0-9_]*\s*:[^=]*="
)


def is_config_record(text: str, class_start: int, params: list[str], subsystems: list[str]) -> bool:
    """A CONFIG RECORD — the shape [MAX_CONFIG_KEYS] governs. All three clauses are required, and
    each is doing work rather than decorating a heuristic:

    @Serializable — the class is a wire or config SURFACE, which is what the annotation says. A
    plain class with the same shape is a value object and is graded at the ordinary widths; that is
    the property the selftest's arm B pins, because without it the exemption would be reachable by
    deleting one annotation.

    EVERY parameter a defaulted `val` — a key the config may omit. One parameter without a default
    means the class has a REQUIRED collaborator, so it is wiring something and the ordinary budgets
    are the right ones. `var` is excluded for the same reason: mutable state is not a key.

    NO SUBSYSTEM — a record that names a `splice.*` type is wiring that type, whatever else it looks
    like.
    """
    if subsystems or not params:
        return False
    if not all(DEFAULTED_VAL.match(param) for param in params):
        return False
    for line in reversed(text[:class_start].rstrip("\n").split("\n")):
        stripped = line.strip()
        if not stripped.startswith("@"):
            break
        if stripped.startswith("@Serializable"):
            return True
    return False


def measure_file(rel: str, text: str) -> tuple[list[Constructor], list[str]]:
    problems: list[str] = []
    imports = {name: package for package, name in SPLICE_IMPORT.findall(text)}
    found: list[Constructor] = []
    for match in CLASS_DECL.finditer(text):
        body = extract_constructor(text, match.end() - 1)
        line = text[: match.start()].count("\n") + 1
        if body is None:
            problems.append(
                f"{rel}:{line}: {match.group(1)}'s primary constructor could not be walked — "
                "no width from this run can be trusted"
            )
            continue
        params = split_params(body)
        if not params:
            continue
        subsystems = sorted({imports[name] for param in params for name in TYPE_NAME.findall(param) if name in imports})
        config_record = is_config_record(text, match.start(), params, subsystems)
        found.append(Constructor(match.group(1), rel, line, len(params), subsystems, config_record))
    return found, problems


def collect(root: pathlib.Path) -> tuple[list[Constructor], list[str]]:
    constructors: list[Constructor] = []
    problems: list[str] = []
    for directory in sorted(root.glob(SRC_GLOB)):
        for path in sorted(directory.rglob("*.kt")):
            found, issues = measure_file(
                str(path.relative_to(root)), path.read_text(encoding="utf-8", errors="replace")
            )
            constructors.extend(found)
            problems.extend(issues)
    if not constructors:
        problems.append(
            f"parsed 0 primary constructors under {SRC_GLOB} — refusing to pass vacuously, because "
            "a green over an empty denominator is what this wall exists to prevent"
        )
    return constructors, problems


def offenders_of(constructors: list[Constructor]) -> list[Constructor]:
    return sorted((c for c in constructors if c.over()), key=lambda c: (-c.params, -len(c.subsystems), c.id))


# ── the baseline ──────────────────────────────────────────────────────────────────────

BASELINE_LAW = (
    "V4-93 ratchet. One entry per primary constructor already over a width on the recorded date, "
    f"with the numbers MEASURED that day. The gate fails when an unrecorded constructor crosses "
    f"{MAX_PARAMS} parameters or {MAX_SUBSYSTEMS} subsystems, when a recorded one gets WIDER than "
    "its entry, when an entry is recorded ABOVE the measurement (padding), and when an entry stops "
    "offending or names a class that is gone. Re-measure with "
    "`python3 checks/constructor-width.py --write-baseline`; the diff is the record of what moved."
)


def read_baseline(root: pathlib.Path) -> tuple[dict[str, dict], str, list[str]]:
    path = root / BASELINE_REL
    if not path.exists():
        return {}, "", [
            f"{BASELINE_REL}: missing — the ratchet has no baseline to grade against. Write one with "
            "`python3 checks/constructor-width.py --write-baseline`."
        ]
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return {}, "", [f"{BASELINE_REL}: is not valid JSON ({error}) — a baseline nobody can parse grades nothing"]
    recorded = str(data.get("recorded", ""))
    problems: list[str] = []
    if not RECORDED.match(recorded):
        problems.append(
            f"{BASELINE_REL}: `recorded` is {recorded!r} — every baseline carries the ISO date it was "
            "measured, exactly as checks/concentration.py's RATCHET_RECORDED does; an undated baseline "
            "is how the next regression hides."
        )
    entries = data.get("offenders")
    if not isinstance(entries, dict):
        problems.append(f"{BASELINE_REL}: `offenders` must be an object keyed '<file> <ClassName>'")
        return {}, recorded, problems
    for key, value in entries.items():
        if not isinstance(value, dict) or not isinstance(value.get("params"), int) or not isinstance(
            value.get("subsystems"), int
        ):
            problems.append(
                f"{BASELINE_REL}: entry {key!r} must carry integer `params` and `subsystems` — an entry "
                "with no measured numbers records nothing and can neither grow nor shrink"
            )
    return entries, recorded, problems


def write_baseline(root: pathlib.Path, offenders: list[Constructor], today: str) -> None:
    path = root / BASELINE_REL
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "recorded": today,
                "max_params": MAX_PARAMS,
                "max_subsystems": MAX_SUBSYSTEMS,
                "law": BASELINE_LAW,
                "offenders": {
                    c.id: {"params": c.params, "subsystems": len(c.subsystems)}
                    for c in sorted(offenders, key=lambda c: c.id)
                },
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def ratchet(root: pathlib.Path) -> int:
    constructors, problems = collect(root)
    offenders = offenders_of(constructors)
    baseline, recorded, baseline_problems = read_baseline(root)
    problems = list(problems) + baseline_problems
    measured = {c.id: c for c in offenders}

    print(f"CONSTRUCTOR WIDTH RATCHET — baseline recorded {recorded or '(none)'}")
    print(f"  {'primary constructors':<30} measured {len(constructors):>4}")
    print(
        f"  {f'over {MAX_PARAMS} params / {MAX_SUBSYSTEMS} subsystems':<30} "
        f"measured {len(measured):>4}   baseline {len(baseline):>4}   [GATED]"
    )
    for c in offenders:
        print(f"    {c.name:26s} params {c.params:3d}  subsystems {len(c.subsystems):2d}  {c.rel}:{c.line}")

    for key in sorted(set(measured) - set(baseline)):
        c = measured[key]
        problems.append(
            f"GROWTH: {c.rel}:{c.line} {c.name} is over a constructor width and nothing records it — "
            f"{'; '.join(c.over())}. detekt cannot see this (gateway/detekt.yml:38-43 ignores data "
            f"classes and defaulted parameters), which is why this wall exists. Take the bundle apart, "
            f"or record it with `python3 checks/constructor-width.py --write-baseline` — a dated diff "
            f"saying the tree got wider."
        )
    for key in sorted(set(measured) & set(baseline)):
        c = measured[key]
        was = baseline[key]
        if not isinstance(was, dict) or not isinstance(was.get("params"), int):
            continue
        if c.params > was["params"] or len(c.subsystems) > was["subsystems"]:
            problems.append(
                f"WIDENED: {c.rel}:{c.line} {c.name} grew past its recorded width — params "
                f"{was['params']} -> {c.params}, subsystems {was['subsystems']} -> {len(c.subsystems)}. "
                f"A recorded offender is DEBT, not permission to keep adding parameters."
            )
        elif c.params < was["params"] or len(c.subsystems) < was["subsystems"]:
            problems.append(
                f"PADDED: {c.rel}:{c.line} {c.name} measures params {c.params} / subsystems "
                f"{len(c.subsystems)} but its entry records {was['params']} / {was['subsystems']}. "
                f"Re-measure with `--write-baseline`. A baseline held above the measurement is "
                f"unearned room for the next regression to hide in — the same defect as a ceiling "
                f"recorded above its file's measured ratio."
            )
    for key in sorted(set(baseline) - set(measured)):
        problems.append(
            f"STALE: the baseline lists {key!r}, which is no longer over any width (taken apart, "
            f"renamed, or deleted) — drop the entry with `--write-baseline`, so the list keeps meaning "
            f"'known debt'."
        )

    if problems:
        print(f"\nFAIL: constructor-width ratchet — {len(problems)} problem(s):", file=sys.stderr)
        for problem in problems:
            print("  x " + problem, file=sys.stderr)
        return 1
    print(
        f"\nOK: constructor-width ratchet holds — the {len(measured)} wide constructor(s) are exactly "
        f"the {recorded} baseline, none has widened, and nothing listed there has stopped offending"
    )
    return 0


def report(root: pathlib.Path) -> int:
    constructors, problems = collect(root)
    for problem in problems:
        print("  UNTRUSTED: " + problem)
    offenders = offenders_of(constructors)
    print(
        f"constructor-width: {len(constructors)} primary constructor(s), {len(offenders)} over "
        f"{MAX_PARAMS} parameters or {MAX_SUBSYSTEMS} subsystems"
    )
    for c in offenders:
        print(f"  {c.name:26s} params {c.params:3d}  subsystems {len(c.subsystems):2d}  {c.rel}:{c.line}")
        for reason in c.over():
            print(f"      over: {reason}")
    return 1 if problems else 0


# ── selftest ──────────────────────────────────────────────────────────────────────────

def kt_params(n: int, *, data: bool, defaulted: bool) -> str:
    kind = "data class" if data else "class"
    tail = " = 0" if defaulted else ""
    body = ",\n".join(f"    val p{i}: Int{tail}" for i in range(n))
    return f"package splice.selftest\n\npublic {kind} Selftest{'Data' if data else 'Plain'}{n}(\n{body},\n)\n"


def kt_subsystems(n: int) -> str:
    imports = "\n".join(f"import splice.sub{i}.Type{i}" for i in range(n))
    params = ",\n".join(f"    val p{i}: Type{i}" for i in range(n))
    return f"package splice.selftest\n\n{imports}\n\npublic class SelftestWide{n}(\n{params},\n)\n"


def write_kt(root: pathlib.Path, rel: str, text: str) -> None:
    path = root / "gateway" / "zz-selftest" / "src" / "main" / "kotlin" / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def baseline_fixture(root: pathlib.Path, entries: dict[str, dict], recorded: str = "2026-09-17") -> None:
    path = root / BASELINE_REL
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps({"recorded": recorded, "law": BASELINE_LAW, "offenders": entries}, indent=2) + "\n",
        encoding="utf-8",
    )


SELFTEST_REL = "gateway/zz-selftest/src/main/kotlin/Fixture.kt"


def selftest() -> int:
    failures: list[str] = []

    def arm(label: str, build, expect_red: str | None) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            build(root)
            captured = io.StringIO()
            stdout, stderr = sys.stdout, sys.stderr
            sys.stdout = sys.stderr = captured
            try:
                code = ratchet(root)
            finally:
                sys.stdout, sys.stderr = stdout, stderr
            text = captured.getvalue()
            if expect_red is None:
                if code != 0:
                    failures.append(f"{label} — must be GREEN, got exit {code}: {text.strip()[:240]}")
            elif code == 0:
                failures.append(f"{label} — MUST be RED, exited 0: {text.strip()[:240]}")
            elif expect_red not in text:
                failures.append(
                    f"{label} — red for the wrong reason (expected {expect_red!r}): {text.strip()[:240]}"
                )

    # 1. exactly at the limits: 12 parameters, 6 subsystems. GREEN, and that is the boundary.
    def at_limit(root: pathlib.Path) -> None:
        write_kt(root, "Fixture.kt", kt_params(MAX_PARAMS, data=True, defaulted=True))
        write_kt(root, "Wide.kt", kt_subsystems(MAX_SUBSYSTEMS))
        baseline_fixture(root, {})
    arm(f"1. {MAX_PARAMS} parameters and {MAX_SUBSYSTEMS} subsystems are AT the limit, not over", at_limit, None)

    # 2. THE MUTATION THIS ROW REQUIRES, in the exact shape detekt ignores: a data class whose
    #    every parameter is defaulted. One parameter over.
    def one_over(root: pathlib.Path) -> None:
        write_kt(root, "Fixture.kt", kt_params(MAX_PARAMS + 1, data=True, defaulted=True))
        baseline_fixture(root, {})
    arm("2. GROWTH — a 13-parameter defaulted DATA class (detekt's two ignores together)", one_over, "GROWTH")
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        one_over(root)
        constructors, _ = collect(root)
        hit = [c for c in offenders_of(constructors) if c.name.endswith(str(MAX_PARAMS + 1))]
        if not hit:
            failures.append(f"2. the synthetic wide class must be named: {[c.name for c in offenders_of(constructors)]}")
        elif hit[0].params != MAX_PARAMS + 1:
            failures.append(f"2. the parameter count must be exact, got {hit[0].params}")

    # 3. one subsystem over, with the parameter count comfortably inside.
    def subsystem_over(root: pathlib.Path) -> None:
        write_kt(root, "Wide.kt", kt_subsystems(MAX_SUBSYSTEMS + 1))
        baseline_fixture(root, {})
    arm("3. GROWTH — 7 subsystems in a 7-parameter constructor", subsystem_over, "subsystems")

    # 4. the same tree with the offender RECORDED at its measured width: the ratchet holds.
    def recorded_ok(root: pathlib.Path) -> None:
        one_over(root)
        baseline_fixture(root, {f"{SELFTEST_REL} SelftestData{MAX_PARAMS + 1}": {"params": MAX_PARAMS + 1, "subsystems": 0}})
    arm("4. a RECORDED offender at its measured width is not growth", recorded_ok, None)

    # 5. a recorded offender that got WIDER — the arm without which a baseline is a licence.
    def widened(root: pathlib.Path) -> None:
        write_kt(root, "Fixture.kt", kt_params(MAX_PARAMS + 5, data=True, defaulted=True))
        baseline_fixture(root, {f"{SELFTEST_REL} SelftestData{MAX_PARAMS + 5}": {"params": MAX_PARAMS + 1, "subsystems": 0}})
    arm("5. WIDENED — a recorded offender that grew past its entry", widened, "WIDENED")

    # 6. an entry recorded ABOVE the measurement is padding, and padding fails.
    def padded(root: pathlib.Path) -> None:
        one_over(root)
        baseline_fixture(root, {f"{SELFTEST_REL} SelftestData{MAX_PARAMS + 1}": {"params": 99, "subsystems": 0}})
    arm("6. PADDED — an entry recorded above the measured width", padded, "PADDED")

    # 7. a stale entry — the class is gone.
    def stale(root: pathlib.Path) -> None:
        at_limit(root)
        baseline_fixture(root, {"gateway/zz-selftest/src/main/kotlin/Gone.kt WasWideOnce": {"params": 20, "subsystems": 0}})
    arm("7. STALE — an entry naming a constructor that is no longer wide", stale, "STALE")

    # 8. an undated baseline is a hard error, not a pass.
    def undated(root: pathlib.Path) -> None:
        at_limit(root)
        baseline_fixture(root, {}, recorded="")
    arm("8. an undated baseline is a hard error", undated, "recorded")

    # 9. THE BORING CASES (§24), which are the ones that get waved through.
    def empty(root: pathlib.Path) -> None:
        baseline_fixture(root, {})
    arm("9. a tree with no constructors at all must REFUSE, not pass vacuously", empty, "vacuously")

    def no_constructor(root: pathlib.Path) -> None:
        write_kt(root, "Fixture.kt", "package splice.selftest\n\npublic class SelftestNoCtor\npublic object SelftestObject\n")
        baseline_fixture(root, {})
    arm("10. a class with no primary constructor is out of the denominator, not an offender", no_constructor, "vacuously")

    def one_constructor(root: pathlib.Path) -> None:
        write_kt(root, "Fixture.kt", "package splice.selftest\n\npublic class SelftestOne(val p0: Int)\n")
        baseline_fixture(root, {})
    arm("11. the one-item tree grades green WITH its count", one_constructor, None)

    # 12. KDoc between parameters must not break the walk — the reason this file has its own parser.
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        write_kt(
            root,
            "Fixture.kt",
            "package splice.selftest\n\nimport splice.sub0.Type0\n\n"
            "public data class SelftestKdoc(\n"
            "    val a: Type0,\n"
            "    /** A KDoc, with a comma and a ) in it, between parameters. */\n"
            '    val b: Map<String, Int> = mapOf("x" to 1),\n'
            "    val c: Int = 0,\n"
            ")\n",
        )
        constructors, problems = collect(root)
        found = [c for c in constructors if c.name == "SelftestKdoc"]
        if problems or not found:
            failures.append(f"12. KDoc arm — the constructor must parse: {problems}")
        elif found[0].params != 3 or found[0].subsystems != ["splice.sub0"]:
            failures.append(
                f"12. KDoc arm — want 3 params / ['splice.sub0'], got {found[0].params} / {found[0].subsystems}"
            )

    # 13. THE DENOMINATOR FROM OUTSIDE THIS FILE'S REGEXES. Every class_declaration ast-grep
    #     finds in production whose text opens a primary-constructor paren must be seen by
    #     CLASS_DECL — so a spelling the regex misses cannot disappear from both (DR-51).
    failures.extend(ast_denominator())

    if failures:
        print("constructor-width SELFTEST FAIL:")
        for failure in failures:
            print("  x " + failure)
        return 1
    print(
        "constructor-width SELFTEST OK — the limits themselves, a recorded offender, a class with no "
        "constructor and an interleaved KDoc are green; a 13-parameter defaulted data class, a "
        "7-subsystem constructor, a widened entry, a padded entry, a stale entry, an undated baseline "
        "and an empty denominator are all red; and the class census agrees with ast-grep's AST"
    )
    return 0


def ast_denominator() -> list[str]:
    """Every primary constructor ast-grep's Kotlin grammar finds must be measured, with the SAME
    parameter count.

    THE DENOMINATOR FROM OUTSIDE THIS FILE (§24). A regex checked against its own output cannot
    fail for a spelling it does not admit — that is exactly how `fun interface` went 92 files
    unbilled in checks/concentration.py (DR-51), and how a `@Suppress`-annotated or
    explicit-`constructor` declaration would go unbilled here. tree-sitter-kotlin has a
    `primary_constructor` node, so the census is graded against the GRAMMAR, and `class_parameter`
    nodes give the parameter count a second, independent time. checks/concentration-selftest.sh
    arm 7b does the same thing for annotation classes."""
    import shutil
    import subprocess
    import collections

    if shutil.which("ast-grep") is None:
        return ["13. AST denominator — ast-grep is unavailable, so the external denominator cannot run"]
    # RELATIVE targets with cwd=ROOT, so ast-grep emits the same repo-relative paths
    # collect() records — an absolute-vs-relative mismatch would read as "the regex misses
    # every constructor in the tree", which is a loud failure but the wrong one.
    targets = [str(p.relative_to(ROOT)) for p in sorted(ROOT.glob(SRC_GLOB))]
    if not targets:
        return ["13. AST denominator — no production source roots found"]

    def nodes(kind: str) -> list[dict] | str:
        result = subprocess.run(
            ["ast-grep", "run", "--kind", kind, "--lang", "kotlin", "--json=compact", *targets],
            capture_output=True,
            text=True,
            cwd=ROOT,
        )
        if result.returncode != 0:
            return f"ast-grep --kind {kind} failed: {result.stderr.strip()[:200]}"
        return json.loads(result.stdout or "[]")

    ctors = nodes("primary_constructor")
    params = nodes("class_parameter")
    if isinstance(ctors, str):
        return [f"13. AST denominator — {ctors}"]
    if isinstance(params, str):
        return [f"13. AST denominator — {params}"]
    if not ctors:
        return ["13. AST denominator — zero AST primary constructors; refusing a vacuous pass"]

    # A class_parameter can only occur inside a primary constructor, so byte containment is an
    # unambiguous attribution rather than a heuristic.
    ast_count: collections.Counter[tuple[str, int]] = collections.Counter()
    spans = [
        (node["file"], node["range"]["byteOffset"]["start"], node["range"]["byteOffset"]["end"],
         node["range"]["start"]["line"] + 1)
        for node in ctors
    ]
    for param in params:
        start = param["range"]["byteOffset"]["start"]
        for file, lo, hi, line in spans:
            if param["file"] == file and lo <= start < hi:
                ast_count[(file, line)] += 1
                break

    measured, problems = collect(ROOT)
    if problems:
        return [f"13. AST denominator — the tree measurement is untrusted: {problems[0]}"]
    mine = {(c.rel, c.line): c for c in measured}
    missed: list[str] = []
    miscounted: list[str] = []
    for file, _lo, _hi, line in spans:
        key = (file, line)
        if ast_count.get(key, 0) == 0:
            # A no-parameter primary constructor (`class X()`) is deliberately out of the
            # denominator here and in collect(); nothing to compare.
            continue
        if key not in mine:
            missed.append(f"{file}:{line}")
        elif mine[key].params != ast_count[key]:
            miscounted.append(f"{file}:{line} AST {ast_count[key]} vs measured {mine[key].params}")
    out: list[str] = []
    if missed:
        out.append(
            f"13. AST denominator — {len(missed)} AST primary constructor(s) are invisible to "
            f"CLASS_DECL, so their width is billed by nothing: {missed[:6]}"
        )
    if miscounted:
        out.append(
            f"13. AST parameter count — {len(miscounted)} constructor(s) disagree with the grammar: "
            f"{miscounted[:6]}"
        )
    if not out:
        extra = [f"{c.rel}:{c.line}" for key, c in mine.items() if key not in {(f, l) for f, _, _, l in spans}]
        if extra:
            out.append(
                f"13. AST denominator — CLASS_DECL invents {len(extra)} constructor(s) the grammar "
                f"does not have (a supertype call read as a primary constructor): {extra[:6]}"
            )
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?")
    parser.add_argument("--ratchet", action="store_true", help="gate leg: fail on growth, widening, padding or a stale entry")
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    root = pathlib.Path(args.root).resolve() if args.root else ROOT
    if not root.exists():
        print(f"constructor-width: {root} does not exist", file=sys.stderr)
        return 2

    if args.write_baseline:
        import datetime

        constructors, problems = collect(root)
        if problems:
            print("refusing to write a baseline from an untrusted measurement:", file=sys.stderr)
            for problem in problems:
                print("  x " + problem, file=sys.stderr)
            return 2
        today = datetime.date.today().isoformat()
        offenders = offenders_of(constructors)
        write_baseline(root, offenders, today)
        print(
            f"wrote {BASELINE_REL}: {len(offenders)} wide constructor(s) of {len(constructors)}, "
            f"recorded {today}. READ THE DIFF — it is the record of what moved."
        )
        return 0

    if args.json:
        constructors, problems = collect(root)
        print(
            json.dumps(
                {
                    "constructors": len(constructors),
                    "max_params": MAX_PARAMS,
                    "max_subsystems": MAX_SUBSYSTEMS,
                    "problems": problems,
                    "offenders": [
                        {
                            "name": c.name,
                            "file": c.rel,
                            "line": c.line,
                            "params": c.params,
                            "subsystems": c.subsystems,
                        }
                        for c in offenders_of(constructors)
                    ],
                },
                indent=2,
            )
        )
        return 1 if problems else 0

    if args.ratchet:
        return ratchet(root)
    return report(root)


if __name__ == "__main__":
    sys.exit(main())
