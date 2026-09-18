#!/usr/bin/env python3
"""V4-88 — a constant that has ONE MEANING has one declaration site, and no comment is
load-bearing for equality.

WHY THIS EXISTS (ARCH-AUDIT 2026-09-17, audit B rows 4/9/11 and C rows 7/11). Kotlin main sources
in this tree carry no `companion` blocks (kt-no-companion-objects), so every constant is a
file-scope `const val`. That style is right, and it has one failure mode: a value needed in a
second file gets a second `const val` instead of an import, and nothing afterwards keeps the two
equal. The audit found the mature form of that drift — HeadAdmission.kt's MAX_CLIENT_HOLD_MS and
RateLimitCooldown.kt's MAX_RATE_LIMIT_COOLDOWN_MS are 120_000L each, and the only thing holding
them equal is a PROSE COMMENT that says "The two must stay equal". A comment is not a wall. When
one of those two numbers moves, the gateway tells the client to come back at a time the cooldown
has not finished, and every test stays green.

WHY THE FIRST VERSION OF THIS WALL WAS WRONG, recorded because the correction is the design
(orchestrator review 2026-09-17, V4-88 REDO). It reported all 144 duplicated names as errors
"by name". Sampling those findings killed the premise: FIELD_CONTENT = "content" in five dialect
files, the CliStyle / MultiSelectPrompt escape codes, FIELD_ID = "id" in codemode versus
responses — these are FILE-LOCAL names for DIFFERENT wires whose values coincide. Making one
import the other would ADD cross-module coupling, which is the opposite of what the audit is for.
COLLISION was the same story (CONNECT_TIMEOUT_MS in unrelated clients, per-provider CLIENT_IDs).
Deriving the denominator from the source (§24) is necessary and not sufficient: the predicate over
it still has to be the right one, or a wall with 144 correct-looking errors gets suppressed and
the 5 real ones go with it. So the classes are now graded by how certain the drift is:

  STRICT — red always, no baseline, no allowlist, no escape. These are the shapes where the code
           itself says the values must agree:
    EQUAL-BY-COMMENT  a const whose adjacent comment ASSERTS an equality obligation ("must stay
                      equal", "must match", "keep in sync", "mirroring X") AND names another
                      const. The obligation is real and the enforcement is prose — the scar above.
    KNOB-SHADOW       a const whose value equals a Knob's declared default and whose name is
                      within one qualifier token of that Knob's. The Knob IS the operator-facing
                      single source; a local literal default forks it, so `splice config` and the
                      code disagree the moment the Knob moves.
    NAMED-SCAR        a COPY/COLLISION name on the list below, each carrying a written reason why
                      it is ONE meaning. Red regardless of the baseline. This list is the fix
                      row's checklist, and it is the one hand-authored thing in this file — so it
                      is small, reasoned per entry, and cross-checked: an entry naming something
                      that is no longer duplicated fails as STALE, exactly like a baseline entry.

  RATCHET — recorded, then held. Certainty here is low per finding and high in aggregate: most of
           the 144 are fine, and the tree should not grow more of them while nobody is looking.
    COPY       the same const NAME in 2+ files with the same NORMALISED value.
    COLLISION  the same const NAME in 2+ files with DIFFERENT values — one name, two meanings.
    `--ratchet` (the gate form) fails on any COPY/COLLISION group NOT in
    checks/config/const-single-source-baseline.json, on any group that has SPREAD to a new file,
    and on any STALE entry (fixed, or naming a file that no longer holds it). Bare invocation
    prints the whole inventory; `--census` prints the counts.

DENOMINATOR, FROM THE SOURCE (§24), never a hand list. Every `const val` under
gateway/*/src/main/**/*.kt is parsed off disk (1184 today), and the Knob plane is parsed out of
Knob.kt's enum entries (33 entries, 14 with a numeric default). A file added tomorrow, or a Knob
added tomorrow, is in scope with no edit to this checker. Three guards refuse a vacuous pass:
zero source files is a failure, zero parsed consts is a failure, and the parsed count must equal
the count of `const val` lines in the comment-stripped tree — a parser that has drifted off the
source cannot be trusted to report an absence.

VALUES ARE COMPARED NORMALISED, not textually, and that is not cosmetic. Measured on this tree, a
textual comparison filed these as "different values" and would have mis-classed every one of them
as COLLISION: BOLD/RED/GREEN/DIM/CYAN/RESET/YELLOW (a raw ESC byte in one file, "\\u001B" in the
other — the SAME string), MILLIS_PER_SECOND (1000L vs 1_000L), MS_PER_S (1000 / 1000L / 1_000L),
BYTE_MASK (0xFF vs 0xff), TTL_MS (30 * 60 * 1000L vs 30L * 60 * 1000). Normalisation decodes
\\uXXXX escapes, drops digit separators and numeric type suffixes, lowercases hex digits, and
collapses whitespace — so two spellings of one value are one value.

WHAT IS NOT CAUGHT, stated rather than implied.
  A duplicate with a DIFFERENT name and the same value — RATE_LIMITED vs RATE_LIMIT_STATUS vs
  HTTP_TOO_MANY, all 429. Value-only matching over 619 numeric consts is mostly noise (every
  `= 8` in the tree would pair with every other), so the HTTP status family — the one place where
  that shape was dense and dangerous — gets its own structural wall instead:
  .rules/kotlin-splice/kt-http-status-single-source.yml. The general case stays open by choice.
  A same-meaning duplicate nobody has noticed yet. NAMED-SCAR is a list of the ones the audit
  named; a ninth one sits in the ratchet baseline until a human reads it and promotes it. That is
  the honest bound of a graded wall: the ratchet stops the tree growing, the list drives the fix.
  A multi-line declaration's value is read from its continuation line, but an expression spanning
  three or more lines is normalised as its first two. Seven declarations continue today, all of
  them strings; none is numeric.
  Non-`const` `val` declarations. The `const` modifier is what makes a value a compile-time
  constant with a declaration site worth single-sourcing; a computed `val` is a different subject.
  A Knob shadow more than one qualifier away from its Knob's name (a local `TIMEOUT_MS` against
  Knob.UPSTREAM_TIMEOUT_MS). The token bound is what keeps that detector from pairing every
  `= 0L` in the tree with Knob.USAGE_WARN_TOKENS_5H; see knob_shadows() for the measurement.
  An equality comment whose counterpart is a SINGLE-token name, or is not a const at all —
  ClientAuthProvider.kt:21's "MUST stay equal to [AuthKind.Client.wire]" names an enum property,
  and a test (LaunchSpecClientAuthTest) already pins it, so that obligation is walled elsewhere.

WHY ITS OWN PARSER rather than importing one: no checker under checks/ imports another (see
checks/config/quirks-keys-documented.py's own note on the same decision) — a shared parser makes
one wall's widening another wall's silent behaviour change, and each wall is supposed to be
readable on its own. The parsing here is line-based and comment/string aware.

SELFTEST. `--selftest` builds temp trees and proves BOTH directions: GREEN on a compliant tree
(one declaration + an import), on the BORING cases (exactly one const; consts but none of the
shapes), on an explanatory comment that asserts nothing, on a token-unrelated Knob twin, and on a
BASELINED copy under --ratchet; RED BY NAME on a synthetic duplicate NOT in the baseline, on a
baselined group that spread to a new file, on a STALE baseline entry, on a NAMED-SCAR copy even
when it IS baselined, on a synthetic COLLISION, on a must-stay-equal comment pair, on a Knob
shadow, on a NAMED-SCAR entry that no longer describes a real duplicate, on a parse yielding zero
consts, and on a parsed count that disagrees with the source.
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
KNOB_REL = "gateway/core/src/main/kotlin/splice/core/config/Knob.kt"
BASELINE_REL = "checks/config/const-single-source-baseline.json"

# THE NAMED SCARS — the duplicated names the 2026-09-17 audit identified as ONE meaning, each with
# the reason it is one. Red regardless of the ratchet baseline; this list IS the fix row's
# checklist. It is the only hand-authored list in this file, so it is cross-checked: an entry that
# no longer names a real COPY/COLLISION fails as STALE, and a reason left blank fails by name.
# Adding a name here is a claim that the two declarations must agree — write why, or do not add it.
# V4-122 EMPTIED THIS LIST, and that is the goal rather than an omission: every one of the six
# names it held — DEFAULT_MAX_CONTINUATIONS, ERR_BODY_CAP, BACKOFF_BASE_MS, EPOCH_MILLIS_FLOOR,
# ERR_SNIPPET, DEPTH_CAP — had its duplication RESOLVED rather than baselined, so each entry became
# STALE by this file's own rule and was deleted. The list is the fix row's checklist, and a
# checklist that keeps ticked items is unearned room. It stays here, empty, because the mechanism is
# what enforces the class: adding a name is a claim that two declarations must agree.
NAMED_SCARS: dict[str, str] = {}

DECL = re.compile(
    r"^[ \t]*(?:(?:public|internal|private|protected)\s+)?const\s+val\s+"
    r"([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[^=]+?)?\s*=[ \t]*(.*)$"
)
CONST_VAL_LINE = re.compile(r"\bconst\s+val\b")

# A bare numeric token: decimal, hex, or float, with optional digit separators and type suffix.
NUM_TOKEN = re.compile(
    r"^(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*(?:\.[0-9_]+)?(?:[eE][-+]?[0-9]+)?)[LlFfDdUu]*$"
)

# An equality obligation written in prose. Each of these is a sentence a human wrote instead of a
# wall; the detector needs one of them AND a named counterpart before it fires.
EQUALITY_PHRASES = (
    re.compile(r"must\s+(?:stay|remain|be\s+kept)\s+(?:equal|identical|the\s+same|in\s+sync)", re.I),
    re.compile(r"must\s+match", re.I),
    re.compile(r"must\s+(?:be\s+)?the\s+same\s+as", re.I),
    re.compile(r"kept?\s+in\s+sync", re.I),
    re.compile(r"in\s+sync\s+with", re.I),
    re.compile(r"same\s+value\s+as", re.I),
    re.compile(r"mirror(?:s|ing|ed)?\b", re.I),
)
# The counterpart an equality comment must NAME, as a multi-token ALL_CAPS identifier or a
# Knob.NAME. Multi-token is what separates an identifier from prose: detekt's TopLevelPropertyNaming
# makes every package-scope const in this tree SCREAMING_SNAKE, and these comments are written in
# English that capitalises words for emphasis — "the CLIENT-FACING deadline", "MUST stay equal".
# Measured: requiring an underscore drops CLIENT and KIND (prose) from two findings' counterpart
# lists while keeping MAX_RATE_LIMIT_COOLDOWN_MS and RETENTION_MS (the real ones).
NAMED_CONST = re.compile(r"\b(?:Knob\.)?([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\b")

UNICODE_ESCAPE = re.compile(r"\\[uU]([0-9a-fA-F]{4})")


class Const:
    """One parsed declaration: where it is, what it is called, what it is worth."""

    def __init__(self, name: str, rel: str, line: int, raw: str, comment: str) -> None:
        self.name = name
        self.rel = rel
        self.line = line
        self.raw = raw
        self.value = normalise(raw)
        self.comment = comment

    @property
    def where(self) -> str:
        return f"{self.rel}:{self.line}"


def strip_line_comment(text: str) -> str:
    """Drop a trailing `//` comment, respecting string literals — a URL's `//` is not a comment."""
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
            return text[:i]
        i += 1
    return text


def normalise(raw: str) -> str:
    """One value, one spelling. See the docstring's VALUES ARE COMPARED NORMALISED."""
    text = strip_line_comment(raw).strip().rstrip(",")
    text = UNICODE_ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), text)
    # digit separators and numeric type suffixes are spelling, not value
    text = re.sub(r"(?<=[0-9])_(?=[0-9])", "", text)
    text = re.sub(r"(?<=[0-9])[LlFfDdUu]+\b", "", text)
    text = re.sub(r"0[xX]([0-9a-fA-F]+)", lambda m: "0x" + m.group(1).lower(), text)
    return re.sub(r"\s+", " ", text).strip()


def comment_above(lines: list[str], index: int) -> str:
    """The contiguous comment block immediately above lines[index], plus its trailing comment.

    Contiguity is the point: a blank line between a comment and a declaration means the comment
    belongs to whatever is above it, and reading it as this declaration's reason would credit the
    wrong constant.
    """
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
    trailing = lines[index]
    cut = strip_line_comment(trailing)
    if len(cut) < len(trailing):
        parts.append(trailing[len(cut) + 2 :])
    return " ".join(part.strip() for part in parts).strip()


def parse_file(rel: str, text: str) -> tuple[list[Const], int]:
    """(declarations, raw `const val` line count) for one file."""
    lines = text.splitlines()
    found: list[Const] = []
    raw_count = 0
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(("//", "*")):
            continue
        if CONST_VAL_LINE.search(line):
            raw_count += 1
        match = DECL.match(line)
        if match is None:
            continue
        value = match.group(2).strip()
        if not value:
            # continuation form: `const val X =` with the value on the next line
            value = lines[i + 1].strip() if i + 1 < len(lines) else ""
        found.append(Const(match.group(1), rel, i + 1, value, comment_above(lines, i)))
    return found, raw_count


def parse_tree(root: pathlib.Path) -> tuple[list[Const], list[str]]:
    """Every main-source declaration, plus the problems that make a report untrustworthy."""
    problems: list[str] = []
    consts: list[Const] = []
    raw_total = 0
    files = sorted(root.glob(MAIN_GLOB))
    if not files:
        return [], [f"no main sources matched {MAIN_GLOB} under {root} — the denominator is absent"]
    for path in files:
        parsed, raw_count = parse_file(str(path.relative_to(root)), path.read_text(encoding="utf-8"))
        consts.extend(parsed)
        raw_total += raw_count
    if not consts:
        problems.append(
            f"parsed 0 const declarations from {len(files)} main source file(s) — refusing to pass "
            "vacuously, because a green over an empty denominator is what this wall exists to prevent"
        )
    if raw_total != len(consts):
        problems.append(
            f"parsed {len(consts)} declarations but the tree holds {raw_total} `const val` lines — "
            "the parser and the source disagree, so no finding or absence from this run can be trusted"
        )
    return consts, problems


def balanced(text: str, start: int) -> str | None:
    """The text inside the parens opening at `start`, string- and comment-aware."""
    depth = 0
    body_start = None
    i = start
    in_string = False
    quote = ""
    escape = False
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
            newline = text.find("\n", i)
            i = len(text) if newline < 0 else newline
            continue
        if ch == "(":
            depth += 1
            if depth == 1:
                body_start = i + 1
        elif ch == ")":
            depth -= 1
            if depth == 0 and body_start is not None:
                return text[body_start:i]
        i += 1
    return None


def split_top_level(body: str) -> list[str]:
    """Split on top-level commas, string-aware."""
    parts: list[str] = []
    buf: list[str] = []
    depth = 0
    in_string = False
    quote = ""
    escape = False
    for ch in body:
        if in_string:
            buf.append(ch)
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == quote:
                in_string = False
            continue
        if ch in "\"'":
            in_string = True
            quote = ch
            buf.append(ch)
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
            continue
        buf.append(ch)
    if buf:
        parts.append("".join(buf))
    return [part.strip() for part in parts]


def knob_defaults(root: pathlib.Path) -> tuple[dict[str, str], list[str]]:
    """Knob name -> normalised numeric default, parsed from the enum entries."""
    path = root / KNOB_REL
    if not path.exists():
        return {}, []
    text = path.read_text(encoding="utf-8")
    entries = list(re.finditer(r"^    ([A-Z][A-Z0-9_]*)\(", text, re.M))
    if not entries:
        return {}, [f"{KNOB_REL}: parsed 0 Knob entries — the Knob plane cannot be checked"]
    out: dict[str, str] = {}
    for match in entries:
        body = balanced(text, match.end() - 1)
        if body is None:
            continue
        positional = [
            arg
            for arg in split_top_level(body)
            if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*\s*=[^=]", arg)
        ]
        if len(positional) < 4:
            continue
        default = normalise(re.sub(r"//[^\n]*", "", positional[3]))
        if NUM_TOKEN.match(default):
            out[match.group(1)] = default
    return out, []


def tokens(name: str) -> set[str]:
    """Significant name tokens: DEFAULT_ is a role marker and single letters carry no meaning."""
    return {
        part
        for part in name.split("_")
        if len(part) > 1 and part not in {"DEFAULT", "THE", "VAL"}
    }


# ── detectors ─────────────────────────────────────────────────────────────────────────────────


class Group:
    """One duplicated NAME: its class, its declarations, and the files it spans."""

    def __init__(self, kind: str, name: str, members: list[Const]) -> None:
        self.kind = kind
        self.name = name
        self.members = members

    @property
    def key(self) -> str:
        """The baseline key: class + name. Sorted files are the VALUE, so a spread is visible."""
        return f"{self.kind} {self.name}"

    @property
    def files(self) -> list[str]:
        return sorted({const.rel for const in self.members})

    @property
    def sites(self) -> str:
        return ", ".join(const.where for const in sorted(self.members, key=lambda c: (c.rel, c.line)))

    @property
    def values(self) -> list[str]:
        return sorted({const.value for const in self.members})

    def describe(self) -> str:
        if self.kind == "COPY":
            return (
                f"{self.name} = {self.values[0]} is declared in {len(self.files)} files ({self.sites})"
            )
        return (
            f"{self.name} is declared in {len(self.files)} files with {len(self.values)} different "
            f"values {self.values} — one name, two meanings ({self.sites})"
        )


def duplicates(consts: list[Const]) -> list[Group]:
    """Every name declared in 2+ FILES, classed COPY (one normalised value) or COLLISION."""
    by_name: dict[str, list[Const]] = {}
    for const in consts:
        by_name.setdefault(const.name, []).append(const)
    out: list[Group] = []
    for name, members in sorted(by_name.items()):
        if len({const.rel for const in members}) < 2:
            continue
        kind = "COPY" if len({const.value for const in members}) == 1 else "COLLISION"
        out.append(Group(kind, name, members))
    return out


def equality_comments(consts: list[Const]) -> list[tuple[Const, str, list[str]]]:
    """(const, phrase, counterparts) for consts whose adjacent comment ASSERTS an equality."""
    names = {const.name for const in consts}
    out: list[tuple[Const, str, list[str]]] = []
    for const in consts:
        if not const.comment:
            continue
        hit = next((p.pattern for p in EQUALITY_PHRASES if p.search(const.comment)), None)
        if hit is None:
            continue
        named = [
            found
            for found in dict.fromkeys(NAMED_CONST.findall(const.comment))
            if found != const.name and found in names
        ]
        if named:
            out.append((const, hit, named))
    return out


def knob_shadows(consts: list[Const], knobs: dict[str, str]) -> list[tuple[Const, str, str]]:
    """(const, knob, value) where a local literal default forks a Knob's operator-facing default."""
    out: list[tuple[Const, str, str]] = []
    for const in consts:
        if not NUM_TOKEN.match(const.value):
            continue
        local = tokens(const.name)
        if not local:
            continue
        for knob, default in sorted(knobs.items()):
            knob_tokens = tokens(knob)
            # Subset AND within one qualifier. The bare subset test pairs any const whose name is
            # built only of generic unit words with any knob that also carries them: measured, it
            # filed UpgradeProcess.kt's DEFAULT_TIMEOUT_MS = 300_000L (a `gh attestation verify`
            # subprocess budget, tokens {TIMEOUT, MS}) against Knob.FIRST_BYTE_TIMEOUT_MS
            # ({FIRST, BYTE, TIMEOUT, MS}) — same number, unrelated subject. Two names for ONE
            # value differ by at most one qualifier (DEFAULT_WARN_PCT vs USAGE_WARN_PCT, +USAGE;
            # DEFAULT_MAX_TIER_N vs FOLD_MAX_TIER, +FOLD; FOLD_DEFAULT_MAX_CONTINUE vs
            # FOLD_MAX_CONTINUE, +nothing), so that is the bound.
            if const.value == default and local <= knob_tokens and len(knob_tokens - local) <= 1:
                out.append((const, knob, default))
                break
    return out


# ── the strict plane ──────────────────────────────────────────────────────────────────────────


def strict_problems(consts: list[Const], knobs: dict[str, str], groups: list[Group]) -> list[str]:
    """EQUAL-BY-COMMENT, KNOB-SHADOW and NAMED-SCAR. No baseline reaches any of these."""
    problems: list[str] = []

    for const, phrase, named in equality_comments(consts):
        problems.append(
            f"EQUAL-BY-COMMENT: {const.where} {const.name} = {const.value} — its comment asserts "
            f"an equality (/{phrase}/) with {', '.join(named)}. A comment is not a wall: make one "
            "of them the declaration and import it"
        )

    for const, knob, value in knob_shadows(consts, knobs):
        problems.append(
            f"KNOB-SHADOW: {const.where} {const.name} = {value} duplicates Knob.{knob}'s default "
            f"({value}) — the Knob is the operator-facing single source; read it instead of "
            "re-declaring its default"
        )

    by_name = {group.name: group for group in groups}
    for name, reason in sorted(NAMED_SCARS.items()):
        group = by_name.get(name)
        if group is None:
            problems.append(
                f"NAMED-SCAR STALE: {name} is on the NAMED_SCARS list in {pathlib.Path(__file__).name} "
                "but is no longer declared in 2+ files — delete the entry. A named scar held past "
                "its fix is unearned room for the next duplicate to hide in"
            )
            continue
        if not reason.strip():
            problems.append(
                f"NAMED-SCAR: {name} is listed with NO reason — a named scar without a written "
                "reason is an absence wearing a label; say why it is one meaning, or remove it"
            )
            continue
        problems.append(
            f"NAMED-SCAR ({group.kind}): {group.describe()} — {reason}. One meaning, so one "
            "declaration: red regardless of the ratchet baseline"
        )

    return problems


# ── the ratchet plane ─────────────────────────────────────────────────────────────────────────


def load_baseline(root: pathlib.Path) -> tuple[dict, list[str]]:
    path = root / BASELINE_REL
    if not path.exists():
        return {}, [f"{BASELINE_REL}: missing — the ratchet has no recorded census to hold the tree to"]
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return {}, [f"{BASELINE_REL}: unreadable ({exc}) — a ratchet that cannot read its baseline cannot gate"]
    for key in ("recorded", "total", "denominator", "groups"):
        if key not in data:
            return {}, [f"{BASELINE_REL}: missing required key {key!r}"]
    return data, []


def ratchet_problems(groups: list[Group], baseline: dict) -> list[str]:
    """GROWTH and STALE over the COPY/COLLISION plane, keyed by (class, name) with sorted files."""
    problems: list[str] = []
    recorded: dict[str, list[str]] = {key: sorted(value) for key, value in baseline["groups"].items()}
    measured = {group.key: group for group in groups}

    for key, group in sorted(measured.items()):
        # a NAMED_SCARS name is handled by the strict plane; reporting it twice buries the reason
        if group.name in NAMED_SCARS:
            continue
        was = recorded.get(key)
        if was is None:
            problems.append(
                f"GROWTH ({group.kind}): {group.describe()} — not in the baseline. Give it one "
                f"declaration and an import, or record it in {BASELINE_REL} with the reason it is "
                "two independent values"
            )
            continue
        now = group.files
        if now == was:
            continue
        spread = [rel for rel in now if rel not in was]
        if spread:
            problems.append(
                f"GROWTH ({group.kind}): {group.name} has SPREAD to {', '.join(spread)} — the "
                f"baseline records {len(was)} file(s), the tree now has {len(now)} ({group.sites})"
            )
        else:
            problems.append(
                f"STALE ({group.kind}): {group.name} now spans {len(now)} file(s) but "
                f"{BASELINE_REL} records {len(was)} — {', '.join(rel for rel in was if rel not in now)} "
                "no longer declares it; lower the entry to record the win"
            )

    for key, was in sorted(recorded.items()):
        name = key.split(" ", 1)[1] if " " in key else key
        if name in NAMED_SCARS:
            problems.append(
                f"STALE: {BASELINE_REL} records {key}, but {name} is on the NAMED_SCARS strict "
                "list — a name cannot be both baselined and strict; delete the baseline entry"
            )
            continue
        if key not in measured:
            problems.append(
                f"STALE: {BASELINE_REL} records {key} in {len(was)} file(s), but the tree no "
                "longer declares it in 2+ files — delete the entry. A baseline held above the "
                "measurement is unearned room for the next duplicate to hide in"
            )

    return problems


def record(root: pathlib.Path) -> dict:
    """The baseline document for the tree as it stands. Authors the JSON; never read by the gate."""
    consts, problems = parse_tree(root)
    if problems:
        raise SystemExit("; ".join(problems))
    groups = [group for group in duplicates(consts) if group.name not in NAMED_SCARS]
    return {
        "_note": (
            "AUTHORED BY checks/const-single-source.py --record, MEASURED never estimated. One entry "
            "per duplicated const NAME, keyed '<CLASS> <NAME>' with the sorted files that declare "
            "it. COPY = same normalised value in 2+ files; COLLISION = one name, different values. "
            "These are the LOW-certainty classes: the 2026-09-17 review established that most of "
            "them are file-local names for different wires whose values coincide, and that making "
            "one import the other would ADD coupling. So they are held, not failed: --ratchet fails "
            "on a group that is NOT here, on a group that has SPREAD to a new file, and on a STALE "
            "entry. The high-certainty classes (EQUAL-BY-COMMENT, KNOB-SHADOW, NAMED-SCAR) are "
            "STRICT and no entry here can reach them — a NAMED_SCARS name recorded here is itself "
            "an error."
        ),
        "recorded": "2026-09-17",
        "denominator": len(consts),
        "total": len(groups),
        "groups": {group.key: group.files for group in sorted(groups, key=lambda g: g.key)},
    }


# ── entry points ──────────────────────────────────────────────────────────────────────────────


def instrument(root: pathlib.Path) -> tuple[list[Const], dict[str, str], list[Group], list[str]]:
    """Everything every mode needs, plus the problems that make the whole run untrustworthy."""
    consts, problems = parse_tree(root)
    if problems:
        return [], {}, [], problems
    knobs, knob_problems = knob_defaults(root)
    return consts, knobs, duplicates(consts), knob_problems


def check(root: pathlib.Path) -> tuple[int, list[str]]:
    """The full inventory: strict findings AND every COPY/COLLISION, baseline ignored."""
    out: list[str] = []
    consts, knobs, groups, problems = instrument(root)
    if problems:
        out.append("FAIL: const-single-source — the instrument is untrustworthy:")
        out.extend(f"  ✗ {problem}" for problem in problems)
        return 2, out

    strict = strict_problems(consts, knobs, groups)
    held = [group for group in groups if group.name not in NAMED_SCARS]

    out.append(f"STRICT — {len(strict)} finding(s), no baseline reaches these:")
    out.extend(f"  ✗ {problem}" for problem in strict)
    out.append("")
    out.append(f"RATCHETED — {len(held)} duplicated name(s), held by {BASELINE_REL}:")
    for group in held:
        out.append(f"  · {group.kind}: {group.describe()}")
    return (1 if strict else 0), out


def ratchet(root: pathlib.Path) -> tuple[int, list[str]]:
    """The GATE form: strict findings always, plus growth/stale over the held plane."""
    out: list[str] = []
    consts, knobs, groups, problems = instrument(root)
    baseline, baseline_problems = load_baseline(root)
    problems = problems + baseline_problems
    if problems:
        out.append("FAIL: const-single-source ratchet — the instrument is untrustworthy:")
        out.extend(f"  ✗ {problem}" for problem in problems)
        return 2, out

    strict = strict_problems(consts, knobs, groups)
    held = [group for group in groups if group.name not in NAMED_SCARS]
    moved = ratchet_problems(groups, baseline)

    out.append(
        f"CONST SINGLE SOURCE — baseline recorded {baseline['recorded']}; STRICT classes are not "
        "baselined at all"
    )
    out.append(
        f"  {'const declarations':<26} denominator {baseline['denominator']:>5}   measured {len(consts):>5}"
    )
    out.append(
        f"  {'STRICT findings':<26} expected        0   measured {len(strict):>5}   [GATED, no baseline]"
    )
    out.append(
        f"  {'COPY/COLLISION names':<26} baseline    {baseline['total']:>5}   measured {len(held):>5}   [RATCHETED]"
    )
    out.append(f"  {'NAMED_SCARS':<26} listed      {len(NAMED_SCARS):>5}")

    if len(consts) != baseline["denominator"]:
        out.append(
            f"  NOTE: the declaration denominator moved {baseline['denominator']} -> {len(consts)}; "
            "the gate reads the per-group entries, not the total, so this is reported and not gated"
        )

    failures = strict + moved
    if failures:
        out.append("")
        out.append(f"FAIL: const-single-source — {len(failures)} problem(s):")
        out.extend(f"  ✗ {failure}" for failure in failures)
        return 1, out

    out.append("")
    out.append(
        f"OK: const-single-source — 0 strict findings, and the {len(held)} held duplicate(s) are "
        f"exactly the {baseline['recorded']} baseline"
    )
    return 0, out


def census(root: pathlib.Path) -> None:
    consts, knobs, groups, problems = instrument(root)
    for problem in problems:
        print(f"  UNTRUSTED: {problem}")
    if problems:
        return
    files = len({const.rel for const in consts})
    scars = [group for group in groups if group.name in NAMED_SCARS]
    held = [group for group in groups if group.name not in NAMED_SCARS]
    print(f"const-single-source: {len(consts)} const declarations across {files} main source files")
    print(f"  Knob plane: {len(knobs)} entries with a numeric default")
    print("  STRICT:")
    print(f"    EQUAL-BY-COMMENT  {len(equality_comments(consts)):>3} declarations")
    print(f"    KNOB-SHADOW       {len(knob_shadows(consts, knobs)):>3} declarations")
    print(f"    NAMED-SCAR        {len(scars):>3} of {len(NAMED_SCARS)} listed names, found in the tree")
    print("  RATCHETED:")
    print(f"    COPY              {len([g for g in held if g.kind == 'COPY']):>3} names")
    print(f"    COLLISION         {len([g for g in held if g.kind == 'COLLISION']):>3} names")


# ── selftest ──────────────────────────────────────────────────────────────────────────────────

KNOB_SOURCE = """package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
    public val default: Any?,
    public val restartRequired: Boolean = false,
) {
    USAGE_WARN_PCT("usageWarnPct", KnobKind.NUMBER, listOf("SPLICE_USAGE_WARN_PCT"), 80L),
    FOLD_MAX_TIER(
        "foldMaxTier",
        KnobKind.NUMBER,
        listOf("CLAUDEX_FOLD_MAX_TIER"),
        // a comment between the args, which a naive positional split would count as one
        6L,
        restartRequired = true,
    ),
}
"""

# The compliant tree: one declaration per value, the second file imports it, and a comment that
# merely EXPLAINS a number (no equality claim) is not a finding.
COMPLIANT_A = """package splice.a

import splice.b.SHARED_CEILING_MS

// 120s starves a herd but lets a recovering account resume inside one client-retry cycle.
internal const val LOCAL_ONLY_MS = 45_000L

internal fun hold(): Long = SHARED_CEILING_MS
"""

COMPLIANT_B = """package splice.b

internal const val SHARED_CEILING_MS = 120_000L
"""

# Exactly one const in the whole tree: the BORING case. Nothing to pair with, and the wall must
# say so with a count rather than going green on an empty denominator.
BORING = """package splice.only

private const val ONE = 7
"""

EMPTY = """package splice.nothing

internal fun f(): Int = 7
"""

# The parser-drift fixture: an ANNOTATED const. The line holds `const val`, so the raw count sees
# it, and DECL — which admits a visibility modifier and nothing else — does not. The guard must
# refuse the whole run rather than report an absence it cannot vouch for.
DRIFT = """package splice.drift

private const val OK = 1

@Suppress("MagicNumber") private const val ANNOTATED = 3
"""

DUP_A = 'package splice.a\n\nprivate const val SEAM_WIDTH = 8\n'
DUP_B = 'package splice.b\n\nprivate const val SEAM_WIDTH = 8\n'
DUP_C = 'package splice.c\n\nprivate const val SEAM_WIDTH = 8\n'
# same name, different value — and spelled so the normaliser must do its job for the COPY twin
DUP_SPELLING_A = 'package splice.a\n\nprivate const val MS_PER_S = 1000L\n'
DUP_SPELLING_B = 'package splice.b\n\nprivate const val MS_PER_S = 1_000\n'
COLLIDE_A = 'package splice.a\n\nprivate const val SEAM_BOUND = 10\n'
COLLIDE_B = 'package splice.b\n\nprivate const val SEAM_BOUND = 200\n'
# a NAMED_SCARS name, so the strict plane must fire even when the baseline records it
SCAR_A = 'package splice.a\n\nprivate const val ERR_BODY_CAP = 8\n'
SCAR_B = 'package splice.b\n\nprivate const val ERR_BODY_CAP = 8\n'

EQUAL_COMMENT = """package splice.a

private const val MAX_RATE_LIMIT_COOLDOWN_MS = 120_000L

// Mirrors :provider-spi's MAX_RATE_LIMIT_COOLDOWN_MS, which is private to that module.
// The two must stay equal — the cooldown ceiling is when this gateway next lets a request through.
private const val MAX_CLIENT_HOLD_MS = 120_000L
"""

# An adjacent comment that explains a number without asserting an equality: must stay GREEN, or
# the detector is just a comment-length check.
EXPLAINED_ONLY = """package splice.a

// 4 attempts matches the surveyed harness floor; the old default of 2 still failed turns on blips.
private const val UPSTREAM_ATTEMPTS = 4
"""

KNOB_SHADOW_SRC = """package splice.a

private const val DEFAULT_WARN_PCT = 80
"""

# Same value as a Knob default but sharing no name token: must stay GREEN (see WHAT IS NOT CAUGHT).
KNOB_UNRELATED = """package splice.a

private const val RETRY_SLOTS = 80
"""

A_KT = "gateway/app/src/main/kotlin/splice/A.kt"
B_KT = "gateway/core/src/main/kotlin/splice/B.kt"
C_KT = "gateway/control/src/main/kotlin/splice/C.kt"


def write_tree(root: pathlib.Path, files: dict[str, str], baseline: dict | None, knob: bool = True) -> None:
    if (root / "gateway").exists():
        for path in sorted((root / "gateway").rglob("*.kt")):
            path.unlink()
    for rel, body in files.items():
        module, name = rel.split("/", 1)
        target = root / "gateway" / module / "src/main/kotlin/splice" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding="utf-8")
    if knob:
        path = root / KNOB_REL
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(KNOB_SOURCE, encoding="utf-8")
    path = root / BASELINE_REL
    path.parent.mkdir(parents=True, exist_ok=True)
    if baseline is None:
        if path.exists():
            path.unlink()
        return
    path.write_text(json.dumps(baseline, indent=2), encoding="utf-8")


def base(groups: dict[str, list[str]], denominator: int = 0) -> dict:
    return {"recorded": "selftest", "total": len(groups), "denominator": denominator, "groups": groups}


def shipped_list_problems() -> list[str]:
    """The SHIPPED NAMED_SCARS list, checked without a tree: every entry reasoned and well-named.

    The fixture plane below neutralises NAMED_SCARS so a temp tree that does not happen to contain
    the real tree's duplicates is not reported STALE eight times over — the STALE arm is a claim
    about the REAL list against the REAL tree, and checks/const-single-source-selftest.sh is where
    it is proven (its control runs the shipped list against the shipped source). This function is
    what stops that neutralising from also hiding an UNREASONED shipped entry.

    AN EMPTY LIST IS A VALID STATE, and the guard that used to reject it was wrong in a way V4-122
    demonstrated rather than argued: it said an empty list makes the strict plane toothless, but the
    strict plane's teeth are the SOURCE-derived classes — EQUAL-BY-COMMENT, KNOB-SHADOW — which fire
    whether or not this list has entries. On the run that emptied it, EQUAL-BY-COMMENT still reddened
    the tree with the list empty, which is the proof. Worse, the guard made the goal unreachable: a
    row that FIXES every scar must delete its entries (a kept one reds as STALE), so 'all scars
    fixed' and 'list non-empty' could not both hold. The two arms that remain still catch the failure
    the guard was reaching for: a stale entry reds, and a blank reason reds.
    """
    problems: list[str] = []
    for name, reason in sorted(NAMED_SCARS.items()):
        if not reason.strip():
            problems.append(f"shipped NAMED_SCARS[{name}] carries no reason")
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
            problems.append(f"shipped NAMED_SCARS[{name}] is not a const name")
    return problems


def selftest() -> int:  # noqa: C901 — one linear fixture list; splitting it hides the sequence
    failures: list[str] = []
    global NAMED_SCARS  # noqa: PLW0603 — the NAMED_SCARS fixtures ARE part of what is under test

    # Proven BEFORE the list is neutralised for the fixture plane. See shipped_list_problems().
    failures.extend(shipped_list_problems())
    shipped = NAMED_SCARS
    NAMED_SCARS = {}

    def run(mode, files: dict[str, str], baseline: dict | None) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write_tree(root, files, baseline)
            code, lines = mode(root)
            return code, "\n".join(lines)

    def expect(mode, label: str, want_green: bool, files: dict[str, str], baseline: dict | None, *needles: str) -> None:
        code, text = run(mode, files, baseline)
        if want_green and code != 0:
            failures.append(f"{label} must be GREEN, got exit {code}:\n{text}")
        if not want_green and code == 0:
            failures.append(f"{label} must be RED, got exit 0:\n{text}")
        for needle in needles:
            if needle not in text:
                failures.append(f"{label} must report {needle!r}, got:\n{text}")

    empty = base({})

    # ── the strict plane: no baseline reaches it ───────────────────────────────────────────────
    expect(ratchet, "the compliant tree (one declaration + an import)", True,
           {"app/A.kt": COMPLIANT_A, "core/B.kt": COMPLIANT_B}, empty)
    expect(ratchet, "the BORING case: exactly one const in the tree", True,
           {"app/A.kt": BORING}, empty, "measured     1")
    expect(ratchet, "a comment that EXPLAINS without asserting an equality", True,
           {"app/A.kt": EXPLAINED_ONLY}, empty)
    expect(ratchet, "a value equal to a Knob default but sharing no name token", True,
           {"app/A.kt": KNOB_UNRELATED}, empty)
    expect(ratchet, "the scar: a comment asserting the two must stay equal", False,
           {"app/A.kt": EQUAL_COMMENT}, empty, "EQUAL-BY-COMMENT", "MAX_CLIENT_HOLD_MS")
    expect(ratchet, "a local default shadowing a Knob default", False,
           {"app/A.kt": KNOB_SHADOW_SRC}, empty, "KNOB-SHADOW", "USAGE_WARN_PCT")

    # ── the ratchet plane ─────────────────────────────────────────────────────────────────────
    dup_files = {"app/A.kt": DUP_A, "core/B.kt": DUP_B}
    dup_key = "COPY SEAM_WIDTH"
    dup_baseline = base({dup_key: [A_KT, B_KT]})

    expect(ratchet, "1. a synthetic COPY that is NOT in the baseline", False,
           dup_files, empty, "GROWTH (COPY)", "SEAM_WIDTH", "not in the baseline")
    expect(ratchet, "2. the SAME COPY, recorded in the baseline", True,
           dup_files, dup_baseline, "exactly the selftest baseline")
    expect(ratchet, "3. a baselined COPY that SPREAD to a third file", False,
           {**dup_files, "control/C.kt": DUP_C}, dup_baseline, "GROWTH (COPY)", "SPREAD", C_KT)
    expect(ratchet, "4. a STALE baseline entry (the duplicate is gone)", False,
           {"app/A.kt": DUP_A}, dup_baseline, "STALE", dup_key, "no longer declares it in 2+ files")
    expect(ratchet, "5. a baselined COPY that lost one of three files", False,
           dup_files, base({dup_key: [A_KT, B_KT, C_KT]}), "STALE (COPY)", "lower the entry")
    expect(ratchet, "6. two spellings of ONE value are a COPY, not a COLLISION", False,
           {"app/A.kt": DUP_SPELLING_A, "core/B.kt": DUP_SPELLING_B}, empty, "GROWTH (COPY)", "MS_PER_S")
    expect(ratchet, "7. a synthetic COLLISION not in the baseline", False,
           {"app/A.kt": COLLIDE_A, "core/B.kt": COLLIDE_B}, empty, "GROWTH (COLLISION)", "SEAM_BOUND")
    expect(ratchet, "8. a COLLISION recorded in the baseline", True,
           {"app/A.kt": COLLIDE_A, "core/B.kt": COLLIDE_B},
           base({"COLLISION SEAM_BOUND": [A_KT, B_KT]}))

    # ── NAMED-SCAR outranks the baseline ──────────────────────────────────────────────────────
    # The entry is FIXTURE-ONLY now, and it has to be: V4-122 fixed every name the shipped list
    # held, so there is no shipped entry left to borrow and the old code — shipped["ERR_BODY_CAP"] —
    # raises KeyError. That is the instrument telling the truth about its own list rather than a
    # fixture bug: what these cases prove is the RULE (a listed name outranks the baseline), and the
    # shipped list's own well-formedness is checked separately, by the ratchet reding a STALE entry
    # or a blank reason.
    scar_files = {"app/A.kt": SCAR_A, "core/B.kt": SCAR_B}
    NAMED_SCARS = {"ERR_BODY_CAP": "one error-body truncation width; fixture reason"}
    expect(ratchet, "9. a NAMED-SCAR copy is RED with an empty baseline", False,
           scar_files, empty, "NAMED-SCAR (COPY)", "ERR_BODY_CAP", "regardless of the ratchet baseline")
    expect(ratchet, "10. a NAMED-SCAR copy is STILL RED when the baseline records it", False,
           scar_files, base({"COPY ERR_BODY_CAP": [A_KT, B_KT]}),
           "NAMED-SCAR (COPY)", "ERR_BODY_CAP", "cannot be both baselined and strict")
    # ...and the same tree with the name NOT on the list falls through to the ratchet, baselined
    # and green — the other half of "the list is what makes it strict".
    NAMED_SCARS = {}
    expect(ratchet, "10b. the same copy, NOT on the list, is held by the baseline", True,
           scar_files, base({"COPY ERR_BODY_CAP": [A_KT, B_KT]}))
    NAMED_SCARS = {}

    # ── the instrument's own guards ────────────────────────────────────────────────────────────
    expect(ratchet, "11. a tree with no const at all refuses to pass vacuously", False,
           {"app/A.kt": EMPTY}, empty, "refusing to pass vacuously")
    expect(ratchet, "12. a `const val` the parser cannot read is a parser/source disagreement", False,
           {"app/A.kt": DRIFT}, empty, "the parser and the source disagree")
    expect(ratchet, "13. a missing baseline cannot gate", False,
           {"app/A.kt": DUP_A}, None, "has no recorded census")

    # ── `check` is the inventory, not the gate: it ignores the baseline entirely ───────────────
    code, text = run(check, dup_files, empty)
    if code != 0 or "RATCHETED — 1 duplicated name(s)" not in text or "SEAM_WIDTH" not in text:
        failures.append(f"`check` must list an unbaselined COPY and still exit 0, got exit {code}:\n{text}")
    code, text = run(check, {"app/A.kt": EQUAL_COMMENT}, empty)
    if code != 1 or "STRICT — 1 finding(s)" not in text:
        failures.append(f"`check` must exit 1 on a STRICT finding, got exit {code}:\n{text}")

    # ── a NAMED_SCARS entry that describes nothing, and one with no reason ─────────────────────
    NAMED_SCARS = {"NEVER_DUPLICATED": "a reason for a duplicate that does not exist"}
    expect(ratchet, "14. a NAMED_SCARS entry naming no real duplicate is STALE", False,
           {"app/A.kt": BORING}, empty, "NAMED-SCAR STALE", "NEVER_DUPLICATED")
    NAMED_SCARS = {"SEAM_WIDTH": "   "}
    expect(ratchet, "15. a NAMED_SCARS entry with an empty reason is RED by name", False,
           dup_files, empty, "NAMED-SCAR", "SEAM_WIDTH", "NO reason")
    NAMED_SCARS = shipped

    if failures:
        print("const-single-source SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure.replace("\n", "\n      "))
        return 1
    print(
        "const-single-source SELFTEST OK — STRICT: a must-stay-equal comment and a Knob-default "
        "shadow are red with any baseline, an explanatory comment and a token-unrelated Knob twin "
        "are green. RATCHET: an unbaselined COPY/COLLISION, a group that spread to a new file, a "
        "stale entry and a partially-fixed entry are red; the same groups recorded in the baseline "
        "are green. NAMED-SCAR outranks the baseline in both directions, and a listed name that "
        "describes no real duplicate — or carries no reason — is red by name. `check` lists the "
        "held plane without gating it. An empty parse, a parser/source disagreement and a missing "
        "baseline are untrustworthy rather than passing."
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="a constant with ONE MEANING has one declaration site (strict + ratchet)"
    )
    parser.add_argument("--ratchet", action="store_true", help="gate leg: strict findings + growth/stale")
    parser.add_argument("--census", action="store_true", help="counts per class, no gating")
    parser.add_argument("--record", action="store_true", help="print the ratchet baseline for this tree")
    parser.add_argument("--selftest", action="store_true", help="red-green proof, out of tree")
    parser.add_argument("--root", default=None, help="tree to measure (default: the repo root)")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    root = pathlib.Path(args.root).resolve() if args.root else ROOT
    if not root.exists():
        print(f"const-single-source: {root} does not exist", file=sys.stderr)
        return 2

    if args.record:
        print(json.dumps(record(root), indent=2))
        return 0
    if args.census:
        census(root)
        return 0
    if args.ratchet:
        code, lines = ratchet(root)
        print("\n".join(lines))
        return code

    code, lines = check(root)
    print("\n".join(lines))
    return code


if __name__ == "__main__":
    sys.exit(main())
