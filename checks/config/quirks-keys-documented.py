#!/usr/bin/env python3
"""V4-44 — every operator-facing quirk KEY has a disposition in the documentation.

WHY THIS EXISTS. QuirksConfig.kt is the finite quirk surface: the keys an operator may
write under [providers.X.quirks]. Its documentation lives in OTHER files — the example
config an operator copies, and the `splice add PROFILE` emitter — and nothing paired
the two. A key added to the type but documented nowhere, or a key retired while the doc
still describes it, drifts in silence. This is the shape that let six of DeepSeek's nine
accepted block types stay dropped for a whole campaign: a hand-authored list checked
against another hand-authored list, agreeing with each other and disagreeing with
reality. Sweeping the keys once closes the instance; this closes the class.

DENOMINATOR, from the SOURCE, never a hand list. The primary constructor of every data
class in QuirksConfig.kt is parsed on disk, and each parameter yields its TOML key
(@SerialName when present, else the property name). 28 keys today across two classes,
7 of them in the nested tool_surface table. A key added tomorrow is in scope with no
edit to this file. Two guards refuse a vacuous pass: the parsed @SerialName count must
equal the count in the comment-stripped file (parser drift), and a parse yielding zero
keys is a failure rather than a pass.

WHY ITS OWN PARSER rather than the one in shared-quirks-no-vendor-defaults.py: that
checker's class pattern is `data class (\\w+Quirks)`, which matches neither QuirksConfig
nor ToolSurfaceConfig, and widening it means editing a wall outside this row's fence.
The two functions below are lifted from it verbatim in behaviour — comment- and
string-aware construction, so the KDoc interleaved between QuirksConfig's parameters
cannot swallow a parameter.

DISPOSITION. Every parsed key must be accounted for, in one of three forms:
  documented — the key appears as a TOML key token (`key = ...`) in a surface file,
               line-leading or inside an inline table, commented or live. Commented is
               the normal spelling here: these docs show a key and its default without
               changing the daily-driven config;
  tabled     — the key's own nested table is declared (`[....tool_surface]`), which is
               how TOML documents a sub-table and how the example explains it;
  retired    — a machine-readable marker, `# retired: <key> — <reason>`, and the reason
               must be non-empty: a retirement with no written reason is an absence
               wearing a label and fails BY NAME like any other absence.
Absence is not a disposition. An undocumented key fails by name, naming its class and
property, so the fix is obvious without reading the type.

WHAT IS NOT A DISPOSITION SURFACE, and why it matters. README.md is not a quirk
reference (409 lines; across all 28 keys it mentions store and code_mode, incidentally).
And gateway/app/src/main/kotlin/splice/app/cli/DoctorReportShape.kt names 16 quirk keys
in a RUNTIME doctor map — it is a report, not documentation, and counting it would make
this wall green with no documentation work at all. The surfaces are exactly the two
files an operator reads to write a quirk.

NOT CAUGHT, and why.
  A key documented in the WRONG place. The check proves the key token exists in a
  surface, not that it sits under its own table. `enabled` is a generic name, so an
  unrelated `enabled =` line anywhere would satisfy it. What would catch it: a
  section-aware scan binding each key to its owning table path (quirks,
  quirks.tool_surface). Not built here because the inline-table spelling
  (`quirks = { mfjs = true, ... }`) makes the section walk more intricate than the
  drift it would catch.
  A key documented only in a THIRD file — a new profile emitter, or a docs/ page. The
  surface list is two paths, written below. A new emitter is invisible here until its
  path is added, so adding an emitter is a change to this checker too.

SELFTEST. --selftest builds a temp tree and proves BOTH directions: GREEN on the
compliant form (key token, inline key, table header, retired marker with a reason);
RED naming a synthetic key appended to a temp copy of the source (the mutation this row
requires); RED on a retirement carrying no reason; RED on a key named only in a runtime
map; RED refusing to pass when the parse yields no keys; RED when the parsed @SerialName
count disagrees with the file.
"""
from __future__ import annotations

import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]

# The denominator. Fixed path on purpose: a checker that silently loses its source is a
# checker that passes.
SOURCE_REL = "gateway/core/src/main/kotlin/splice/core/topology/QuirksConfig.kt"

# The two files an operator reads to write a quirk: the copyable example config and the
# `splice add PROFILE` emitter. See NOT CAUGHT for what a third surface would mean.
SURFACES = (
    "config/splice.example.toml",
    "gateway/app/src/main/kotlin/splice/app/cli/AddProfileCatalog.kt",
)

DATA_CLASS = re.compile(r"(?:public\s+)?data class\s+(\w+)\s*\(", re.MULTILINE)
SERIAL_NAME = re.compile(r'@SerialName\(\s*"([^"]+)"\s*\)')
PARAM = re.compile(
    r"\bval\s+(\w+)\s*:\s*([^=]+?)(?:\s*=\s*(.*))?\s*$",
    re.DOTALL,
)


class QuirkKey:
    """One parsed constructor parameter: its TOML key, its table, its property."""

    def __init__(self, klass: str, table: str, key: str, prop: str) -> None:
        self.klass = klass
        self.table = table
        self.key = key
        self.prop = prop

    @property
    def owner(self) -> str:
        """The TOML table path a config would write this key under."""
        return self.table


def strip_comments(source: str) -> str:
    """Remove // and /* */ comments, respecting string literals.

    Only the @SerialName count guard reads this. A raw count that included a commented
    annotation would red the wall for a doc change, which is the wrong signal.
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


def extract_constructor(source: str, start: int) -> str | None:
    """Return the primary-constructor text inside the parens at start, or None.

    start is the index of the opening paren. Comment- and string-aware: QuirksConfig
    interleaves a KDoc block between parameters, and a naive paren walk would either
    stop early or run past the constructor.
    """
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
    """Split a constructor body on top-level commas, dropping comments."""
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
        if ch in "({[<":
            depth += 1
            buf.append(ch)
            i += 1
            continue
        if ch in ")}]>":
            depth -= 1
            buf.append(ch)
            i += 1
            continue
        if ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if buf:
        parts.append("".join(buf))
    return parts


def table_for(klass: str) -> str:
    """The TOML table a class's keys are written under: QuirksConfig -> quirks,
    ToolSurfaceConfig -> quirks.tool_surface. Derived, never a hand list, so a third
    data class in the file is placed without editing this function."""
    snake = re.sub(r"(?<!^)(?=[A-Z])", "_", klass).lower()
    snake = re.sub(r"_config$", "", snake)
    return f"quirks.{snake}" if snake != "quirks" else "quirks"


def parse_source(source: str, label: str) -> tuple[list[QuirkKey], list[str]]:
    """Return (keys, problems). problems is non-empty only on a parse that cannot be
    trusted, never on a merely undocumented key."""
    problems: list[str] = []
    keys: list[QuirkKey] = []
    classes = 0
    serials_parsed = 0
    for match in DATA_CLASS.finditer(source):
        body = extract_constructor(source, match.end() - 1)
        if body is None:
            problems.append(f"{label}: {match.group(1)} constructor could not be parsed")
            continue
        classes += 1
        klass = match.group(1)
        table = table_for(klass)
        for raw in split_params(body):
            text = raw.strip()
            if not text:
                continue
            param = PARAM.search(text)
            if param is None:
                continue
            prop, _, _ = param.group(1), param.group(2), param.group(3)
            named = SERIAL_NAME.search(text)
            key = named.group(1) if named else prop
            if named:
                serials_parsed += 1
            keys.append(QuirkKey(klass, table, key, prop))
    if classes == 0:
        problems.append(f"{label}: no data class found — the denominator is absent")
    serials_raw = len(SERIAL_NAME.findall(strip_comments(source)))
    if serials_raw != serials_parsed:
        problems.append(
            f"{label}: parsed {serials_parsed} @SerialName keys but the file holds "
            f"{serials_raw} — the parser and the source disagree, so no key list from "
            "this run can be trusted"
        )
    return keys, problems


def key_token(key: str) -> re.Pattern[str]:
    """`key =` anywhere: line-leading live key, commented key, or inline-table entry.
    The lookbehind keeps `defer` from matching `defer_prefixes =`."""
    return re.compile(r"(?<![\w-])" + re.escape(key) + r"\s*=")


def table_header(key: str) -> re.Pattern[str]:
    """`[providers.X.quirks.tool_surface]` as the disposition for the tool_surface key:
    the TOML-native way to document a sub-table."""
    return re.compile(
        r"^[ \t]*\[\[?[^\[\]\n]*\." + re.escape(key) + r"[ \t]*\]\]?[ \t]*$",
        re.MULTILINE,
    )


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


def dispositions(root: pathlib.Path) -> dict[str, str]:
    """key -> where it is documented, for `report`. Empty when nothing documents it."""
    source_path = root / SOURCE_REL
    if not source_path.exists():
        return {}
    keys, _ = parse_source(source_path.read_text(encoding="utf-8"), SOURCE_REL)
    texts = {rel: (root / rel).read_text(encoding="utf-8") for rel in SURFACES if (root / rel).exists()}
    found: dict[str, str] = {}
    for quirk in keys:
        for rel, text in texts.items():
            if retired_reason(text, quirk.key)[0]:
                found[quirk.key] = f"{rel}: retired"
            elif key_token(quirk.key).search(text):
                found[quirk.key] = f"{rel}: key"
            elif table_header(quirk.key).search(text):
                found[quirk.key] = f"{rel}: table header"
    return found


def audit(root: pathlib.Path) -> list[str]:
    problems: list[str] = []
    source_path = root / SOURCE_REL
    if not source_path.exists():
        return [f"{SOURCE_REL}: missing — the quirk source IS the denominator, so its absence cannot pass"]
    keys, parse_problems = parse_source(source_path.read_text(encoding="utf-8"), SOURCE_REL)
    problems.extend(parse_problems)
    if not keys:
        problems.append(
            f"{SOURCE_REL}: parsed 0 quirk keys — refusing to pass vacuously, because a "
            "green over an empty denominator is what this wall exists to prevent"
        )
        return problems

    texts: dict[str, str] = {}
    for rel in SURFACES:
        path = root / rel
        if not path.exists():
            problems.append(f"{rel}: disposition surface missing — a surface that cannot be read cannot document anything")
            continue
        texts[rel] = path.read_text(encoding="utf-8")

    for quirk in keys:
        documented = False
        # attempted: a retirement marker was written for this key. It keeps an
        # unreasoned retirement to ONE problem — the specific, actionable one — rather
        # than also reporting the same key as undocumented.
        attempted = False
        for rel, text in texts.items():
            marked, reason = retired_reason(text, quirk.key)
            if marked:
                attempted = True
                if reason:
                    documented = True
                    continue
                problems.append(
                    f"{rel}: {quirk.key} is retired with NO reason — a retirement "
                    "without a written reason is an absence wearing a label"
                )
                continue
            if key_token(quirk.key).search(text) or table_header(quirk.key).search(text):
                documented = True
        if not documented and not attempted:
            problems.append(
                f"NO DISPOSITION: {quirk.table}.{quirk.key} ({quirk.klass}.{quirk.prop}) "
                f"is documented nowhere in {' or '.join(SURFACES)}; document it there, or "
                f"retire it with `# retired: {quirk.key} — <reason>`"
            )
    return problems


# ── selftest fixtures ─────────────────────────────────────────────────────────────────

COMPLIANT_SOURCE = '''package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class QuirksConfig(
    val store: Boolean = false,
    /** A KDoc between parameters, which a naive paren walk would choke on. */
    @SerialName("cache_key") val cacheKey: String = "first-message-hash",
    @SerialName("tool_surface") val toolSurface: ToolSurfaceConfig? = null,
)

@Serializable
public data class ToolSurfaceConfig(
    val enabled: Boolean = true,
    @SerialName("min_deferred") val minDeferred: Int = 8,
)
'''

COMPLIANT_DOC = '''[providers.codex.quirks]
store = false
cache_key = "first-message-hash"
# The sub-table form is how TOML documents a nested table.
[providers.codex.quirks.tool_surface]
enabled = true
# min_deferred = 8
'''

RETIRED_OK_DOC = '''[providers.codex.quirks]
store = false
cache_key = "first-message-hash"
[providers.codex.quirks.tool_surface]
enabled = true
# retired: min_deferred — folded into the surface defaults; the key is still parsed
'''

# The same tree with the reason deleted. The marker is present, the disposition is not.
RETIRED_NOREASON_DOC = RETIRED_OK_DOC.replace(
    "# retired: min_deferred — folded into the surface defaults; the key is still parsed",
    "# retired: min_deferred —",
)

RUNTIME_MAP_DOC = '''[providers.codex.quirks]
store = false
cache_key = "first-message-hash"
[providers.codex.quirks.tool_surface]
enabled = true
'''

RUNTIME_MAP = '''private fun shape(q: QuirksConfig) = buildJsonObject {
    put("min_deferred", q.toolSurface?.minDeferred)
}
'''

EMPTY_SOURCE = '''package splice.core.topology

public data class QuirksConfig()
'''

# A @SerialName outside any constructor: the comment stripper keeps it, the parser
# cannot attribute it, so the two counts disagree and the run must refuse.
DRIFT_SOURCE = COMPLIANT_SOURCE + '''
@SerialName("orphan")
public val orphan: String = "x"
'''

FAKE_KEY_PARAM = '    @SerialName("fake_new_knob") val fakeNewKnob: Boolean? = null,\n)'


def write_tree(root: pathlib.Path, source: str, doc: str, emitter: str = "") -> None:
    (root / pathlib.Path(SOURCE_REL).parent).mkdir(parents=True, exist_ok=True)
    (root / SOURCE_REL).write_text(source, encoding="utf-8")
    (root / pathlib.Path(SURFACES[0]).parent).mkdir(parents=True, exist_ok=True)
    (root / SURFACES[0]).write_text(doc, encoding="utf-8")
    (root / pathlib.Path(SURFACES[1]).parent).mkdir(parents=True, exist_ok=True)
    (root / SURFACES[1]).write_text(emitter, encoding="utf-8")


def selftest() -> int:
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)

        write_tree(root, COMPLIANT_SOURCE, COMPLIANT_DOC, emitter=RUNTIME_MAP)
        if audit(root):
            failures.append(
                "compliant tree must be GREEN (key token, table header, commented key): "
                + "; ".join(audit(root))
            )
        live = dispositions(root)
        if len(live) != 5:
            failures.append(
                f"report must account for all 5 fixture keys, got {len(live)}: {sorted(live)}"
            )

        # The mutation the row requires: a fake key appended to a temp copy of the source.
        mutated = COMPLIANT_SOURCE.replace(
            '    @SerialName("tool_surface") val toolSurface: ToolSurfaceConfig? = null,\n)',
            '    @SerialName("tool_surface") val toolSurface: ToolSurfaceConfig? = null,\n'
            + FAKE_KEY_PARAM,
        )
        if mutated == COMPLIANT_SOURCE:
            failures.append("the mutation did not apply — the fake key never reached the temp copy")
        else:
            write_tree(root, mutated, COMPLIANT_DOC)
            hits = audit(root)
            if not any("fake_new_knob" in hit for hit in hits):
                failures.append(f"synthetic fake key must be RED BY NAME, got: {hits}")

        write_tree(root, COMPLIANT_SOURCE, RETIRED_OK_DOC)
        hits = audit(root)
        if hits:
            failures.append(f"a reasoned retirement is a disposition, so the tree is GREEN, got: {hits}")

        write_tree(root, COMPLIANT_SOURCE, RETIRED_NOREASON_DOC)
        hits = audit(root)
        if not any("min_deferred" in hit and "NO reason" in hit for hit in hits):
            failures.append(f"a retirement with an empty reason must be RED by name, got: {hits}")
        if sum(1 for hit in hits if "min_deferred" in hit) != 1:
            failures.append(
                f"an unreasoned retirement is ONE problem, not a duplicate pair, got: {hits}"
            )

        write_tree(root, COMPLIANT_SOURCE, RUNTIME_MAP_DOC)
        hits = audit(root)
        if not any("NO DISPOSITION" in hit and "min_deferred" in hit for hit in hits):
            failures.append(
                f"a key with no disposition at all must be RED by name, got: {hits}"
            )

        write_tree(root, COMPLIANT_SOURCE, RUNTIME_MAP)
        hits = audit(root)
        if not any("NO DISPOSITION" in hit and "min_deferred" in hit for hit in hits):
            failures.append(
                f"a key named only in a runtime map is not documented, got: {hits}"
            )

        write_tree(root, EMPTY_SOURCE, COMPLIANT_DOC)
        hits = audit(root)
        if not any("refusing to pass vacuously" in hit for hit in hits):
            failures.append(f"a parse with no keys must be RED, got: {hits}")

        write_tree(root, DRIFT_SOURCE, COMPLIANT_DOC)
        hits = audit(root)
        if not any("disagree" in hit for hit in hits):
            failures.append(f"a @SerialName the parser cannot attribute must be RED, got: {hits}")

    if failures:
        print("quirks-keys-documented SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        "quirks-keys-documented SELFTEST OK — a documented key, a table header and a "
        "reasoned retirement are green; a synthetic key added to a temp source copy, an "
        "unreasoned retirement, a key named only in a runtime map, a parse yielding no "
        "keys, and a @SerialName count that disagrees with the file are all red by name"
    )
    return 0


def report(root: pathlib.Path) -> None:
    source_path = root / SOURCE_REL
    keys, problems = parse_source(source_path.read_text(encoding="utf-8"), SOURCE_REL)
    live = dispositions(root)
    print(f"quirks-keys-documented: {len(keys)} keys parsed from {SOURCE_REL}")
    for problem in problems:
        print(f"  UNTRUSTED: {problem}")
    for quirk in keys:
        where = live.get(quirk.key, "NO DISPOSITION")
        print(f"  {quirk.table}.{quirk.key:24s} {quirk.klass}.{quirk.prop:22s} {where}")


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    root = ROOT
    for arg in sys.argv[1:]:
        if arg not in {"check", "report", "--selftest"} and not arg.startswith("-"):
            root = pathlib.Path(arg)
            break
    if not root.exists():
        print("quirks-keys-documented: tree missing", file=sys.stderr)
        return 1
    root = root.resolve()
    if "report" in sys.argv:
        report(root)
        return 0
    problems = audit(root)
    if problems:
        print("quirks-keys-documented RED:")
        for problem in problems:
            print("  " + problem)
        return 1
    print(
        "quirks-keys-documented GREEN: every quirk key in QuirksConfig.kt has a "
        "disposition in config/splice.example.toml or the profile emitter"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
