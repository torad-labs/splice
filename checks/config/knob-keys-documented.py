#!/usr/bin/env python3
"""V4-87 — every operator-facing KNOB KEY has a disposition in the documentation.

WHY THIS EXISTS. Knob.kt is the finite knob surface: the keys an operator may write in
`[defaults]`, in `[heads.<key>.overrides]`, in state `config.json`, or PATCH through
/mgmt/config. Its documentation lives in ANOTHER file — the example config an operator
copies — and nothing paired the two. The 2026-09-17 architecture audit (C rows 1, 5, 12)
counted the gap by hand and found class (b): 17 knobs an operator can set that the
example config never names. A hand count closes the instance; this closes the class — a
knob added tomorrow is in scope with no edit to this file, and a knob retired while the
doc still describes it is red the same day.

This is the KNOB twin of checks/config/quirks-keys-documented.py (V4-44), whose shape,
guards and selftest idiom it mirrors deliberately: same three dispositions, same
fail-closed-on-a-vacuous-parse rule, same red-BY-NAME message. The two checkers are
separate files rather than one parameterised one because the denominators are parsed out
of structurally different Kotlin — a data-class primary constructor there, an enum-entry
argument list here — and sharing would mean editing a wall outside this row's fence.

DENOMINATOR, from the SOURCE, never a hand list. Every entry of the `Knob` enum in
Knob.kt is parsed on disk and its FIRST constructor argument — the `key` property — is
the TOML key. 33 keys today (the audit's row said 32; the source says 33, and the source
is the denominator). Three guards refuse a vacuous pass:
  - the enum declaration must be found: a moved or renamed enum fails rather than
    yielding an empty denominator that passes;
  - a parse yielding zero keys is a failure, not a pass;
  - the parsed entry count must equal the number of `KnobKind.` mentions in the
    comment-stripped file — every entry passes exactly one `KnobKind`, so a disagreement
    means the parser and the source no longer see the same enum and NO key list from that
    run can be trusted.

TWO OPERATOR SPELLINGS, ONE KNOB. `Knob.key` is camelCase, which is literally what
`[defaults]`, `[heads.<k>.overrides]`, state config.json and a /mgmt/config PATCH key off.
The `[daemon]` table is the exception: it deserializes through DaemonConfig, whose
@SerialName spells the same knob snake_case — `showReasoning` is written `show_reasoning`
there, `controlPort` is `control_port`. Both are the operator's spelling, so either
satisfies this wall, and the snake form is TRANSLITERATED from the key rather than mapped
by hand. Without this, six knobs the example config does document (show_reasoning, summary,
effort, replay_reasoning, mirror_reasoning, control_port) read as undocumented — a wall
that reds a documented key teaches the reader to ignore it.

DISPOSITION. Every parsed key must be accounted for, in one of two forms:
  documented — the key appears as a TOML key token (`key = ...`) in config/splice.example.toml
               in EITHER spelling, line-leading or inside an inline table, commented or
               live. Commented is a normal spelling in that file: it shows a key and its
               default without changing the daily-driven config;
  retired    — a machine-readable marker, `# retired: <key> — <reason>`, whose reason must
               be non-empty: a retirement with no written reason is an absence wearing a
               label and fails BY NAME like any other absence.
Absence is not a disposition. An undocumented key fails by name, naming its enum entry, so
the fix is obvious without reading the type.

MEASURED ON THE TREE at authoring time (2026-09-17, `report .`): 33 keys, 10 with a
disposition, 23 red by name. The audit's row predicted 32 keys and 17 undocumented; the
source says 33 and 23, and the source is the denominator.

WHAT IS NOT A DISPOSITION SURFACE, and why it matters. README.md is not a knob reference.
Knob.kt's own KDoc is not documentation of the knob to an OPERATOR — counting the source
as its own documentation is the tautology §24 exists to forbid (a check whose denominator
and numerator come from the same file cannot fail). And the runtime doctor/status maps
that print knob values are reports, not documentation. The surface is exactly the one file
an operator copies to write a config.

NOT CAUGHT, and why.
  "default + unit + semantics". The row asks the doc line to carry the default, the unit
  and what the knob does. Only the KEY TOKEN is machine-checkable; prose quality is not.
  What would catch part of it: comparing the documented value against the enum's `default`
  literal. Not built here because the example config deliberately shows non-default values
  in places (maxInflight = "8" on the kimi head is the point of that example), so a
  value-equality check would be wrong in exactly the cases the file is teaching.
  A key documented in the WRONG place — and this one is LIVE, not hypothetical. The check
  proves the key token exists in the surface, not that it sits under a knob table. Two of
  the ten green keys are green by coincidence: `port` matches the commented head port at
  config/splice.example.toml:219, and `pinnedModel` matches the head field `pinned_model`
  at :221. Neither line documents the daemon knob of that name. They are named here rather
  than filtered because a section-aware scan is the fix and it is a different wall: the
  knob layers are five (`[daemon]`, `[defaults]`, `[heads.*.overrides]`, state config.json,
  PATCH) and binding a key token to the right one of them is more machinery than the drift
  it would catch on a 33-key surface a reviewer can read. Whoever documents the 23 red keys
  should document these two properly in the same pass.
  A key documented only in a THIRD file. The surface list is one path, written below;
  adding a surface is a change to this checker too.

SELFTEST. --selftest builds a temp tree and proves BOTH directions: GREEN on the compliant
form (live key, commented key, reasoned retirement) and GREEN WITH A COUNT on the boring
one-key tree; RED naming a synthetic key appended to a temp copy of the source (the
mutation this row requires), RED on a retirement carrying no reason, RED on a key named
only in a runtime report map, RED on the empty enum (refusing to pass vacuously), RED when
the enum has been renamed out from under the parser, and RED when the parsed entry count
disagrees with the file's `KnobKind.` count.
"""
from __future__ import annotations

import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]

# The denominator. Fixed path on purpose: a checker that silently loses its source is a
# checker that passes.
SOURCE_REL = "gateway/core/src/main/kotlin/splice/core/config/Knob.kt"

# The one file an operator copies to write a config. See NOT CAUGHT for what a second
# surface would mean.
SURFACES = ("config/splice.example.toml",)

ENUM_DECL = re.compile(r"\benum\s+class\s+Knob\b")
ENTRY_HEAD = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*\(", re.DOTALL)
KIND_MENTION = re.compile(r"\bKnobKind\.")


class KnobKey:
    """One parsed enum entry: its TOML key and the entry that declares it."""

    def __init__(self, entry: str, key: str) -> None:
        self.entry = entry
        self.key = key


def strip_comments(source: str) -> str:
    """Remove // and /* */ comments, respecting string literals.

    Only the KnobKind-count guard reads this. A raw count that included a commented
    mention — Knob.kt's own header says "live in KnobKind.kt", which contains the token —
    would red the wall for a doc change, which is the wrong signal.
    """
    out: list[str] = []
    i = 0
    in_string = False
    quote = ""
    escape = False
    while i < len(source):
        ch = source[i]
        if in_string:
            out.append(ch)
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
            out.append(ch)
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
        out.append(ch)
        i += 1
    return "".join(out)


def _walk(source: str, start: int, opener: str, closer: str) -> tuple[int, int] | None:
    """Return (body_start, body_end) for the balanced pair beginning at or after start.

    Comment- and string-aware: Knob.kt interleaves prose comments between entries, and
    those comments contain both parens and braces.
    """
    i = start
    depth = 0
    body_start = None
    in_string = False
    quote = ""
    escape = False
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
        if ch == opener:
            depth += 1
            if depth == 1:
                body_start = i + 1
            i += 1
            continue
        if ch == closer:
            depth -= 1
            if depth == 0 and body_start is not None:
                return body_start, i
            i += 1
            continue
        i += 1
    return None


def split_top_level(body: str, separator: str = ",") -> list[str]:
    """Split on top-level separators, dropping comments."""
    parts: list[str] = []
    buf: list[str] = []
    depth = 0
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
        if ch == separator and depth == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if buf:
        parts.append("".join(buf))
    return parts


def parse_knobs(source: str, label: str) -> tuple[list[KnobKey], list[str]]:
    """Return (keys, problems). problems is non-empty only on a parse that cannot be
    trusted, never on a merely undocumented key."""
    problems: list[str] = []
    decl = ENUM_DECL.search(source)
    if decl is None:
        return [], [
            f"{label}: no `enum class Knob` declaration found — the knob enum has moved or "
            "been renamed, so this run has no denominator and must not pass"
        ]
    ctor = _walk(source, decl.end(), "(", ")")
    if ctor is None:
        return [], [f"{label}: the Knob primary constructor could not be parsed"]
    body_span = _walk(source, ctor[1], "{", "}")
    if body_span is None:
        return [], [f"{label}: the Knob enum body could not be parsed"]
    body = source[body_span[0] : body_span[1]]
    # A Kotlin enum's entries end at the first top-level `;`; members follow. Knob.kt has
    # no members today, so this is the shape-proofing, not a live branch.
    entries_text = split_top_level(body, ";")[0]

    keys: list[KnobKey] = []
    for raw in split_top_level(entries_text, ","):
        head = ENTRY_HEAD.match(raw)
        if head is None:
            continue
        entry = head.group(1)
        args_span = _walk(raw, head.end() - 1, "(", ")")
        if args_span is None:
            problems.append(f"{label}: {entry} argument list could not be parsed")
            continue
        args = split_top_level(raw[args_span[0] : args_span[1]], ",")
        if not args:
            problems.append(f"{label}: {entry} declares no arguments — where is its key?")
            continue
        literal = re.match(r'^\s*"([^"]*)"\s*$', args[0])
        if literal is None:
            problems.append(
                f"{label}: {entry}'s first argument is not a string literal "
                f"({args[0].strip()!r}) — the key cannot be read from the source"
            )
            continue
        keys.append(KnobKey(entry, literal.group(1)))

    if not keys:
        return keys, problems
    kinds = len(KIND_MENTION.findall(strip_comments(source)))
    if kinds != len(keys):
        problems.append(
            f"{label}: parsed {len(keys)} enum entries but the file holds {kinds} "
            "`KnobKind.` mentions — every entry passes exactly one kind, so the parser and "
            "the source disagree and no key list from this run can be trusted"
        )
    return keys, problems


def snake(key: str) -> str:
    """The knob's OTHER operator-facing spelling.

    A knob is written camelCase in `[defaults]`, in `[heads.<k>.overrides]`, in state
    config.json and in a /mgmt/config PATCH — those layers key straight off `Knob.key`.
    The `[daemon]` table is different: it deserializes through DaemonConfig, whose
    @SerialName spells the same knob snake_case (`showReasoning` -> `show_reasoning`,
    `controlPort` -> `control_port`). Both are the operator's spelling of ONE knob, so a
    key documented under either satisfies this wall. Derived by transliteration, never a
    hand map, so a knob added tomorrow needs no edit here.
    """
    return re.sub(r"(?<!^)(?=[A-Z])", "_", key).lower()


def key_token(key: str) -> re.Pattern[str]:
    """`key =` anywhere, in EITHER spelling: line-leading live key, commented key, or
    inline-table entry. The lookbehind keeps `port` from matching `grokPort =` or
    `control_port =`, and the two spellings are alternated rather than loosened into one
    fuzzy pattern so `maxInflight` can never be satisfied by an unrelated `max_inflight`
    that does not exist."""
    spellings = {key, snake(key)}
    alternation = "|".join(re.escape(spelling) for spelling in sorted(spellings))
    return re.compile(r"(?<![\w-])(?:" + alternation + r")\s*=")


def retired_reason(text: str, key: str) -> tuple[bool, str]:
    """Return (marker present, reason). An empty reason means the marker is present and
    the disposition is NOT."""
    pattern = re.compile(
        r"^[ \t]*#[ \t]*retired:[ \t]*" + re.escape(key) + r"(?![A-Za-z0-9_-])(.*)$",
        re.MULTILINE,
    )
    match = pattern.search(text)
    if match is None:
        return False, ""
    return True, match.group(1).strip().lstrip("—:-").strip()


def surface_texts(root: pathlib.Path) -> tuple[dict[str, str], list[str]]:
    texts: dict[str, str] = {}
    problems: list[str] = []
    for rel in SURFACES:
        path = root / rel
        if not path.exists():
            problems.append(
                f"{rel}: disposition surface missing — a surface that cannot be read "
                "cannot document anything"
            )
            continue
        texts[rel] = path.read_text(encoding="utf-8")
    return texts, problems


def dispositions(root: pathlib.Path) -> dict[str, str]:
    """key -> where it is documented, for `report`. Empty when nothing documents it."""
    source_path = root / SOURCE_REL
    if not source_path.exists():
        return {}
    keys, _ = parse_knobs(source_path.read_text(encoding="utf-8"), SOURCE_REL)
    texts, _ = surface_texts(root)
    found: dict[str, str] = {}
    for knob in keys:
        for rel, text in texts.items():
            marked, reason = retired_reason(text, knob.key)
            if marked and reason:
                found[knob.key] = f"{rel}: retired"
            else:
                match = key_token(knob.key).search(text)
                if match is not None:
                    line = text.count("\n", 0, match.start()) + 1
                    found[knob.key] = f"{rel}:{line} ({match.group(0).rstrip('= ').strip()})"
    return found


def audit(root: pathlib.Path) -> list[str]:
    problems: list[str] = []
    source_path = root / SOURCE_REL
    if not source_path.exists():
        return [
            f"{SOURCE_REL}: missing — the knob enum IS the denominator, so its absence "
            "cannot pass"
        ]
    keys, parse_problems = parse_knobs(source_path.read_text(encoding="utf-8"), SOURCE_REL)
    problems.extend(parse_problems)
    if not keys:
        problems.append(
            f"{SOURCE_REL}: parsed 0 knob keys — refusing to pass vacuously, because a "
            "green over an empty denominator is what this wall exists to prevent"
        )
        return problems

    texts, surface_problems = surface_texts(root)
    problems.extend(surface_problems)

    for knob in keys:
        documented = False
        # attempted: a retirement marker was written for this key. It keeps an unreasoned
        # retirement to ONE problem — the specific, actionable one — rather than also
        # reporting the same key as undocumented.
        attempted = False
        for rel, text in texts.items():
            marked, reason = retired_reason(text, knob.key)
            if marked:
                attempted = True
                if reason:
                    documented = True
                    continue
                problems.append(
                    f"{rel}: {knob.key} is retired with NO reason — a retirement without "
                    "a written reason is an absence wearing a label"
                )
                continue
            if key_token(knob.key).search(text):
                documented = True
        if not documented and not attempted:
            problems.append(
                f"NO DISPOSITION: {knob.key} (Knob.{knob.entry}) is documented nowhere in "
                f"{' or '.join(SURFACES)}; document it there with its default, its unit and "
                f"what it does, or retire it with `# retired: {knob.key} — <reason>`"
            )
    return problems


# ── selftest fixtures ─────────────────────────────────────────────────────────────────

COMPLIANT_SOURCE = '''package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
    public val default: Any?,
    public val restartRequired: Boolean = false,
) {
    PORT("port", KnobKind.NUMBER, listOf("CODEX_PROXY_PORT"), 3099L, restartRequired = true),
    // A prose comment between entries, with (parens) and {braces} a naive walk would choke on.
    MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 12L),
    DEBUG(
        "debug",
        KnobKind.BOOL,
        listOf("CLAUDEX_DEBUG", "CODEX_PROXY_DEBUG"),
        false,
        restartRequired = true,
    ),
    SHOW_REASONING("showReasoning", KnobKind.STRING, listOf("CLAUDEX_SHOW_REASONING"), "text"),
    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), 3100L, restartRequired = true),
}
'''

# Every sanctioned spelling in one tree: port / maxInflight live camelCase, debug
# commented, showReasoning ONLY in its snake_case `[daemon]` spelling, grokPort retired
# with a reason.
COMPLIANT_DOC = '''[daemon]
show_reasoning = "text"      # "text" | "thinking" | "off"

[defaults]
port = "3099"
maxInflight = "12"
# debug = "false"   # daemon-wide verbose logging
# retired: grokPort — the grok head now takes its port from [heads.*.port]
'''

RETIRED_NOREASON_DOC = COMPLIANT_DOC.replace(
    "# retired: grokPort — the grok head now takes its port from [heads.*.port]",
    "# retired: grokPort —",
)

# grokPort named only by a runtime report map: a report is not documentation.
RUNTIME_MAP_DOC = '''[daemon]
show_reasoning = "text"

[defaults]
port = "3099"
maxInflight = "12"
# debug = "false"
'''

RUNTIME_MAP = '''private fun shape(c: Config) = buildJsonObject {
    put("grokPort", c.grokPort)
}
'''

# The boring case: one knob, documented. A wall that cannot count to one cannot count.
BORING_SOURCE = '''package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
) {
    PORT("port", KnobKind.NUMBER),
}
'''

BORING_DOC = '''[defaults]
port = "3099"
'''

EMPTY_SOURCE = '''package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
) {
}
'''

MOVED_SOURCE = COMPLIANT_SOURCE.replace("enum class Knob(", "enum class Tuning(")

# An extra `KnobKind.` the entry parser cannot attribute: the two counts disagree.
DRIFT_SOURCE = COMPLIANT_SOURCE + '''
public val orphanKind: KnobKind = KnobKind.STRING
'''

FAKE_KEY_ENTRY = (
    '    FAKE_NEW_KNOB("fakeNewKnob", KnobKind.BOOL, listOf("CLAUDEX_FAKE"), false),\n}'
)


def write_tree(root: pathlib.Path, source: str, doc: str, runtime: str = "") -> None:
    (root / pathlib.Path(SOURCE_REL).parent).mkdir(parents=True, exist_ok=True)
    (root / SOURCE_REL).write_text(source, encoding="utf-8")
    (root / pathlib.Path(SURFACES[0]).parent).mkdir(parents=True, exist_ok=True)
    (root / SURFACES[0]).write_text(doc + runtime, encoding="utf-8")


def selftest() -> int:
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)

        write_tree(root, COMPLIANT_SOURCE, COMPLIANT_DOC)
        hits = audit(root)
        if hits:
            failures.append(
                "compliant tree must be GREEN (live key, commented key, reasoned "
                "retirement): " + "; ".join(hits)
            )
        live = dispositions(root)
        if len(live) != 5:
            failures.append(
                f"report must account for all 5 fixture keys, got {len(live)}: {sorted(live)}"
            )
        if "showReasoning" not in live:
            failures.append(
                "a knob documented ONLY in its snake_case [daemon] spelling must count as "
                f"documented, got: {sorted(live)}"
            )

        # The snake_case alternative must not loosen into a fuzzy match: deleting the ONE
        # line that documents showReasoning reds it by name again.
        write_tree(root, COMPLIANT_SOURCE, COMPLIANT_DOC.replace('show_reasoning = "text"', ""))
        hits = audit(root)
        if not any("NO DISPOSITION" in hit and "showReasoning" in hit for hit in hits):
            failures.append(
                f"removing the only snake_case doc line must be RED by name, got: {hits}"
            )

        # The BORING case: exactly one knob, and the count must come out as one.
        write_tree(root, BORING_SOURCE, BORING_DOC)
        hits = audit(root)
        if hits:
            failures.append(f"the one-knob tree must be GREEN, got: {hits}")
        keys, problems = parse_knobs(BORING_SOURCE, "boring")
        if problems or len(keys) != 1 or keys[0].key != "port":
            failures.append(
                f"the one-knob tree must parse to exactly 1 key named port, got "
                f"{[k.key for k in keys]} problems={problems}"
            )

        # The mutation the row requires: a fake key appended to a temp copy of the source.
        mutated = COMPLIANT_SOURCE.replace(
            '    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), 3100L, '
            "restartRequired = true),\n}",
            '    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), 3100L, '
            "restartRequired = true),\n" + FAKE_KEY_ENTRY,
        )
        if mutated == COMPLIANT_SOURCE:
            failures.append("the mutation did not apply — the fake key never reached the temp copy")
        else:
            write_tree(root, mutated, COMPLIANT_DOC)
            hits = audit(root)
            if not any("fakeNewKnob" in hit for hit in hits):
                failures.append(f"synthetic fake key must be RED BY NAME, got: {hits}")

        write_tree(root, COMPLIANT_SOURCE, RETIRED_NOREASON_DOC)
        hits = audit(root)
        if not any("grokPort" in hit and "NO reason" in hit for hit in hits):
            failures.append(f"a retirement with an empty reason must be RED by name, got: {hits}")
        if sum(1 for hit in hits if "grokPort" in hit) != 1:
            failures.append(
                f"an unreasoned retirement is ONE problem, not a duplicate pair, got: {hits}"
            )

        write_tree(root, COMPLIANT_SOURCE, RUNTIME_MAP_DOC)
        hits = audit(root)
        if not any("NO DISPOSITION" in hit and "grokPort" in hit for hit in hits):
            failures.append(f"a key with no disposition at all must be RED by name, got: {hits}")

        write_tree(root, COMPLIANT_SOURCE, RUNTIME_MAP_DOC, runtime=RUNTIME_MAP)
        hits = audit(root)
        if not any("NO DISPOSITION" in hit and "grokPort" in hit for hit in hits):
            failures.append(
                f"a key named only in a runtime report map is not documented, got: {hits}"
            )

        write_tree(root, EMPTY_SOURCE, COMPLIANT_DOC)
        hits = audit(root)
        if not any("refusing to pass vacuously" in hit for hit in hits):
            failures.append(f"an enum with no entries must be RED, got: {hits}")

        write_tree(root, MOVED_SOURCE, COMPLIANT_DOC)
        hits = audit(root)
        if not any("moved or" in hit for hit in hits):
            failures.append(f"a renamed/moved enum must be RED, got: {hits}")

        write_tree(root, DRIFT_SOURCE, COMPLIANT_DOC)
        hits = audit(root)
        if not any("disagree" in hit for hit in hits):
            failures.append(
                f"a KnobKind mention the entry parser cannot attribute must be RED, got: {hits}"
            )

        write_tree(root, COMPLIANT_SOURCE, COMPLIANT_DOC)
        (root / SURFACES[0]).unlink()
        hits = audit(root)
        if not any("disposition surface missing" in hit for hit in hits):
            failures.append(f"a missing surface must be RED, got: {hits}")

    if failures:
        print("knob-keys-documented SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        "knob-keys-documented SELFTEST OK — a live key, a commented key and a reasoned "
        "retirement are green, and the one-knob tree is green with a count of 1; a "
        "synthetic key added to a temp source copy, an unreasoned retirement, a key named "
        "only in a runtime report map, an empty enum, a renamed enum, a KnobKind count "
        "that disagrees with the file, and a missing surface are all red by name"
    )
    return 0


def report(root: pathlib.Path) -> None:
    source_path = root / SOURCE_REL
    keys, problems = parse_knobs(source_path.read_text(encoding="utf-8"), SOURCE_REL)
    live = dispositions(root)
    print(f"knob-keys-documented: {len(keys)} knob keys parsed from {SOURCE_REL}")
    for problem in problems:
        print(f"  UNTRUSTED: {problem}")
    for knob in keys:
        where = live.get(knob.key, "NO DISPOSITION")
        print(f"  {knob.key:24s} Knob.{knob.entry:24s} {where}")
    missing = [k.key for k in keys if k.key not in live]
    print(f"  documented {len(live)}/{len(keys)}; undocumented {len(missing)}: {', '.join(missing)}")


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    root = ROOT
    for arg in sys.argv[1:]:
        if arg not in {"check", "report", "--selftest"} and not arg.startswith("-"):
            root = pathlib.Path(arg)
            break
    if not root.exists():
        print("knob-keys-documented: tree missing", file=sys.stderr)
        return 1
    root = root.resolve()
    if "report" in sys.argv:
        report(root)
        return 0
    problems = audit(root)
    if problems:
        print("knob-keys-documented RED:")
        for problem in problems:
            print("  " + problem)
        return 1
    print(
        "knob-keys-documented GREEN: every knob key in Knob.kt has a disposition in "
        "config/splice.example.toml"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
