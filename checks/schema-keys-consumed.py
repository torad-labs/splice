#!/usr/bin/env python3
"""V4-91 — every config key an operator can write must be ACTED ON somewhere, by name.

WHY THIS EXISTS. A key that parses is not a key that works. `[daemon] state_dir` has been in
the schema since the port (gateway/core/src/main/kotlin/splice/core/topology/Topology.kt:75,
`@SerialName("state_dir") val stateDir: String?`), it deserializes cleanly, it is echoed back
by `doctor --json`, and NOTHING reads it: the state directory comes from
CLAUDEX_STATE_DIR or the default in
gateway/core/src/main/kotlin/splice/core/config/StatePaths.kt:15-16, never from the TOML. An
operator who sets it gets silence — no error, no effect, and a doctor report that shows the
value they asked for. The knob layer has the same shape one level over:
`Knob.DEBUG` ("debug") is parsed, coerced, given a typed accessor
(SpliceConfig.kt:44 `public val debug: Boolean get() = bool(Knob.DEBUG)`) and read by no
production file at all.

Nothing was measuring it. checks/config/quirks-keys-documented.py proves a quirk key is
DOCUMENTED, which is a different question — a key can be perfectly documented and still do
nothing, and in fact a documented dead key is worse than an undocumented one, because the
documentation is a promise. This wall asks the other half: is it WIRED.

THE DENOMINATOR COMES FROM THE SOURCE (§24), on both planes:
  · SCHEMA — the primary constructor of each of the five operator-facing config types, parsed
    on disk: Topology, DaemonConfig, HeadConfig, ProviderConfig, QuirksConfig. Each parameter
    yields its TOML key (@SerialName when present, else the property name). 62 keys today. The
    classes are LOCATED by searching the production tree for their declaration rather than by a
    recorded path, so moving one between files does not drop it; a class the search cannot find
    is a hard error, not a silently shorter list.
  · KNOBS — every enum constant of `Knob`, with its key string. 33 today.
A key added tomorrow is in scope with no edit here. Four guards refuse a vacuous pass: a
missing class, a parse yielding no keys on either plane, a CLASS whose attributed @SerialName
count disagrees with its own constructor body (a parameter the comma split dropped), and a Knob
enum yielding no constants, each FAIL rather than pass.

WHAT COUNTS AS ACTED ON.

  A SCHEMA KEY is consumed when its property is READ — not merely declared, and not merely
  written back:
    · IN ITS OWN FILE, by something other than its declaration. This is deliberate and it is
      the difference between a working wall and a wall that reds the correct design. The schema
      file is where this tree PROJECTS its TOML types into its domain types:
      ProviderConfig.extraWindows / windowRules / defaultContextWindow are read at
      Topology.kt:182-187 and handed to ModelCatalog; HeadConfig.contextWindow becomes the
      `window` that folds into the same catalog; QuirksConfig.compactEffort is read by a
      `require` in its own init that REJECTS the retired key. All four are acted on, all four
      are read only inside the declaring file, and a rule that demanded a foreign reader would
      name every one of them and be ignored within a week. `state_dir` has no read at all, in
      its own file or anywhere else, which is the honest distinction.
    · OR IN ANOTHER production main file. Bare `.prop` is enough when no other class in the
      tree declares a property of that name; when one does, the read must be
      RECEIVER-QUALIFIED. That qualification is the wall. `stateDir` is declared twice — by
      DaemonConfig and by StatePaths — and a name-only rule finds `statePaths.stateDir` in
      seven files and reports the dead key as green. The receiver spellings are derived from
      the schema itself (the decapitalised class name, the class name, and the names of schema
      properties whose declared type mentions the class, singularised for a Map/List), never
      hand-listed.

  A KNOB KEY is consumed when `Knob.<CONST>` is read outside its declaration AND outside the
  ACCESSOR FACADE, or — one hop — when the facade accessor that reads it is itself read
  elsewhere. The hop is what makes this plane non-vacuous: every one of the 33 knobs is read by
  SpliceConfig.kt, so counting the facade as a consumer would make the whole plane green with
  no wiring anywhere. The hop is derived, not listed: the facade is split into its own
  declarations and each is asked which `Knob` constants its body names.

TWO SURFACES ARE NOT CONSUMPTION, each with a dated reason, each checked for staleness
(NON_CONSUMPTION below). Both are precedented: checks/config/quirks-keys-documented.py
excludes the same doctor file for the same reason, in the same words.
  · THE ECHO SURFACE — a doctor/report file that puts the value back out under its own key
    name. `put("state_dir", t.daemon.stateDir)` is the value being SHOWN, not used; counting it
    is how the dead key reads as live. If this file counted, this wall would find nothing.
  · THE ACCESSOR FACADE — SpliceConfig, the typed view over the knob map. A read there is a
    projection, so it is followed one hop rather than trusted.

THE ALLOWLIST is for a key that is deliberately parsed and deliberately not acted on — a
retired key kept so an old config still loads, a key whose only job is to be rejected. Each
entry is `(key, "YYYY-MM-DD: why")`: undated fails, blank-reasoned fails (a placeholder is an
absence wearing a label), and an entry naming a key that IS consumed fails as stale. Empty
today, and that is the honest state: the two live findings are the fix row's work, not
exemptions.

NOT CAUGHT, stated here rather than discovered later.
  · A KEY READ THROUGH A RENAME. `val d = topology.daemon` then `d.stateDir` is a read this
    wall cannot attribute, because `d` is not a derived receiver spelling. It reads as
    unconsumed — a false RED, which is the safe direction: the fix row looks, and finds the
    read. The unsafe direction (a false green) is what the receiver qualification and the echo
    surface exist to close.
  · A KEY WHOSE ONLY READER IS A TEST. Test sources are not scanned at all here: a key wired
    only into a test is not wired. That is the same judgement checks/public-surface.py makes
    about test-only callers, and for the same reason.
  · SEMANTIC DEADNESS ONE LEVEL DOWN. A key read into a variable that is then never used, or
    threaded into a field nothing consults, passes. Only the first hop is checked on the schema
    plane and two on the knob plane; a full reachability answer needs the compiler
    (:fir-checks), not a regex.

SELFTEST. `--selftest` builds temp trees and proves BOTH directions plus the boring cases:
GREEN on a key read in another file, on one read only by its own file's projection, on one
rejected by a `require` in its own init, on a knob read through its accessor, and on an
allowlisted key with a dated reason; RED BY NAME on a synthetic key appended to a temp copy of
the schema, on a key whose only reader is the echo surface (state_dir's exact shape), on a knob
whose accessor nobody calls (debug's exact shape), on an allowlist entry with a blank reason,
on a stale allowlist entry, on a missing schema class, and on a parse that yields no keys.
"""
from __future__ import annotations

import argparse
import io
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

SRC_GLOB = "gateway/*/src/main"

# The five operator-facing config types. Named, not path-pinned: each is LOCATED in the tree, so
# a move is a move and a disappearance is a hard error.
SCHEMA_CLASSES = ("Topology", "DaemonConfig", "HeadConfig", "ProviderConfig", "QuirksConfig")
KNOB_CLASS = "Knob"

# Not consumption. (relative path, dated reason). A stale entry — the file is gone — is a hard
# error: a dead exclusion is an un-graded surface one rename later.
NON_CONSUMPTION: tuple[tuple[str, str], ...] = (
    (
        "gateway/app/src/main/kotlin/splice/app/cli/DoctorReportShape.kt",
        "2026-09-17: THE ECHO SURFACE. It puts every topology key back out under its own key name "
        "(`put(\"state_dir\", t.daemon.stateDir)`) — the value is being SHOWN, not used. Counting it "
        "would make this wall green over exactly the population it exists to name: state_dir's only "
        "read in the whole tree is this file's line 36. Same file, same reason, same words as "
        "checks/config/quirks-keys-documented.py's WHAT IS NOT A DISPOSITION SURFACE.",
    ),
    (
        "gateway/core/src/main/kotlin/splice/core/config/SpliceConfig.kt",
        "2026-09-17: THE ACCESSOR FACADE over the knob map. Every one of the 33 knobs is read here, "
        "so treating it as a consumer would make the knob plane pass with no wiring anywhere. A read "
        "here is a PROJECTION, and it is followed one hop instead: the accessor that names the knob "
        "must itself be read outside this file.",
    ),
)

# Keys deliberately parsed and deliberately not acted on. (key, "YYYY-MM-DD: why").
ALLOWLIST: tuple[tuple[str, str], ...] = ()
DATED_REASON = re.compile(r"^\d{4}-\d{2}-\d{2}: \S")

CLASS_DECL_TEMPLATE = (
    r"^(?:public |internal |private )?(?:data |value |sealed )*class[ \t]+{name}\b[^\n(]*\("
)
ENUM_DECL_TEMPLATE = r"^(?:public |internal )?enum class[ \t]+{name}\b[^\n(]*\("
SERIAL_NAME = re.compile(r'@SerialName\(\s*"([^"]+)"\s*\)')
PARAM = re.compile(r"\b(?:val|var)\s+(\w+)\s*:\s*([^=]+?)(?:\s*=\s*(?:.|\n)*)?$", re.DOTALL)
PROPERTY_DECL = re.compile(r"\b(?:val|var)\s+(\w+)\s*:")
ENUM_CONSTANT = re.compile(r"^    ([A-Z][A-Z0-9_]*)\s*\(", re.MULTILINE)
# The key string is the enum constant's first argument, on its line or the next.
ENUM_KEY = re.compile(r'^\s*"([A-Za-z0-9_.]+)"', re.MULTILINE)
FACADE_MEMBER = re.compile(r"^    (?:public |private |internal )?(?:val|fun)\s+(\w+)", re.MULTILINE)


# ── the parser (behaviour lifted from checks/config/quirks-keys-documented.py) ─────────

def extract_constructor(source: str, start: int) -> str | None:
    """The primary-constructor text inside the parens at [start], comment- and string-aware.

    KDoc is interleaved between parameters throughout these files (DaemonConfig has six such
    blocks), so a naive paren walk stops at the first `)` inside a default value."""
    i, depth, body_start = start, 0, None
    in_string, quote, escape = False, "", False
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
            in_string, quote = True, ch
            i += 1
            continue
        if ch == "/" and source[i + 1 : i + 2] == "/":
            nl = source.find("\n", i)
            i = len(source) if nl < 0 else nl
            continue
        if ch == "/" and source[i + 1 : i + 2] == "*":
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
    """Split a constructor body on top-level commas, dropping comments.

    Angle brackets are tracked SEPARATELY from brackets and only open when immediately preceded
    by an identifier: counting `<`/`>` as depth makes `->` and a `>` comparison decrement it, and
    every parameter after a lambda default falls out of the list (measured on
    checks/constructor-width.py, where ast-grep's own parameter count found eight such
    undercounts)."""
    parts: list[str] = []
    buf: list[str] = []
    depth = angle = 0
    in_string, quote, escape = False, "", False
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
            in_string, quote = True, ch
            buf.append(ch)
            i += 1
            continue
        if ch == "/" and body[i + 1 : i + 2] == "/":
            nl = body.find("\n", i)
            i = len(body) if nl < 0 else nl
            continue
        if ch == "/" and body[i + 1 : i + 2] == "*":
            end = body.find("*/", i + 2)
            i = len(body) if end < 0 else end + 2
            continue
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
        elif ch == "<" and i > 0 and (body[i - 1].isalnum() or body[i - 1] in "_>") and body[i + 1 : i + 2] != "=":
            angle += 1
        elif ch == ">" and angle > 0 and body[i - 1 : i] != "-" and body[i + 1 : i + 2] != "=":
            angle -= 1
        elif ch == "," and depth == 0 and angle == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if buf:
        parts.append("".join(buf))
    return [part for part in (raw.strip() for raw in parts) if part]


def strip_comments(source: str) -> str:
    """// and /* */ out, string-aware. Only the @SerialName count guard reads this."""
    out: list[str] = []
    i = 0
    in_string, quote, escape = False, "", False
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
            in_string, quote = True, ch
            out.append(ch)
            i += 1
            continue
        if ch == "/" and source[i + 1 : i + 2] == "/":
            nl = source.find("\n", i)
            i = len(source) if nl < 0 else nl
            continue
        if ch == "/" and source[i + 1 : i + 2] == "*":
            end = source.find("*/", i + 2)
            i = len(source) if end < 0 else end + 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


# ── the denominator ───────────────────────────────────────────────────────────────────

class Key:
    def __init__(self, plane: str, owner: str, prop: str, key: str, declared_in: str, line: int) -> None:
        self.plane = plane          # "schema" | "knob"
        self.owner = owner          # class or enum name
        self.prop = prop            # property name / enum constant
        self.key = key              # the TOML / knob key an operator writes
        self.declared_in = declared_in
        self.line = line

    def locus(self) -> str:
        return f"{self.declared_in}:{self.line}"

    def __repr__(self) -> str:  # pragma: no cover — diagnostics only
        return f"<{self.owner}.{self.prop} key={self.key}>"


def sources(root: pathlib.Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for directory in sorted(root.glob(SRC_GLOB)):
        for path in sorted(directory.rglob("*.kt")):
            out[str(path.relative_to(root))] = path.read_text(encoding="utf-8", errors="replace")
    return out


def locate(files: dict[str, str], pattern: str) -> tuple[str, re.Match[str]] | None:
    compiled = re.compile(pattern, re.MULTILINE)
    for rel in sorted(files):
        match = compiled.search(files[rel])
        if match is not None:
            return rel, match
    return None


def schema_keys(files: dict[str, str]) -> tuple[list[Key], dict[str, list[tuple[str, str, str]]], list[str]]:
    """(keys, class -> [(prop, key, declared type)], problems)."""
    keys: list[Key] = []
    shapes: dict[str, list[tuple[str, str, str]]] = {}
    problems: list[str] = []
    for name in SCHEMA_CLASSES:
        found = locate(files, CLASS_DECL_TEMPLATE.format(name=name))
        if found is None:
            problems.append(
                f"{name}: no `class {name}(` found under {SRC_GLOB} — it is part of the denominator, "
                f"so its absence cannot pass. If it was renamed, rename it in SCHEMA_CLASSES too."
            )
            continue
        rel, match = found
        body = extract_constructor(files[rel], match.end() - 1)
        if body is None:
            problems.append(f"{rel}: {name}'s primary constructor could not be walked")
            continue
        shape: list[tuple[str, str, str]] = []
        attributed = 0
        for raw in split_params(body):
            param = PARAM.search(raw)
            if param is None:
                continue
            prop = param.group(1)
            serial = SERIAL_NAME.search(raw)
            key = serial.group(1) if serial else prop
            if serial:
                attributed += 1
            line = files[rel][: match.start()].count("\n") + 1
            keys.append(Key("schema", name, prop, key, rel, line))
            shape.append((prop, key, param.group(2).strip()))
        shapes[name] = shape
        # PARSER-DRIFT GUARD, per CLASS rather than per file (the idiom of
        # quirks-keys-documented.py, corrected for a file that holds more than one type). The
        # @SerialName annotations this run ATTRIBUTED to parameters must equal the annotations
        # inside the constructor BODY: a parameter the comma split loses takes its key with it,
        # and a shorter key list is the one failure mode that would otherwise read as green.
        # Counted per class because Topology.kt and QuirksConfig.kt each declare several types
        # (ModelEntry, ExtraWindow, WindowRule, ClaudeWrapperConfig, ToolSurfaceConfig), whose
        # annotations are not in these five constructors at all — a whole-file count reads 46
        # against 41 on this tree and would red every run for the wrong reason.
        in_body = len(SERIAL_NAME.findall(strip_comments(body)))
        if attributed != in_body:
            problems.append(
                f"{rel}: {name} — attributed {attributed} @SerialName key(s) but its constructor "
                f"holds {in_body}; the parser dropped a parameter, so no key list from this run can "
                f"be trusted"
            )
    return keys, shapes, problems


def knob_keys(files: dict[str, str]) -> tuple[list[Key], str, list[str]]:
    """(keys, the file declaring Knob, problems)."""
    found = locate(files, ENUM_DECL_TEMPLATE.format(name=KNOB_CLASS))
    if found is None:
        return [], "", [
            f"no `enum class {KNOB_CLASS}(` found under {SRC_GLOB} — the knob plane's denominator is "
            "absent, which cannot pass"
        ]
    rel, match = found
    text = files[rel]
    body = text[match.end() :]
    keys: list[Key] = []
    constants = list(ENUM_CONSTANT.finditer(body))
    for index, constant in enumerate(constants):
        end = constants[index + 1].start() if index + 1 < len(constants) else len(body)
        key_match = ENUM_KEY.search(body[constant.end() : end])
        if key_match is None:
            # A constant whose key string cannot be read is a parse failure, not a pass.
            return keys, rel, [
                f"{rel}: {constant.group(1)} declares no readable key string — the knob denominator "
                "cannot be trusted"
            ]
        line = text[: match.end() + constant.start()].count("\n") + 1
        keys.append(Key("knob", KNOB_CLASS, constant.group(1), key_match.group(1), rel, line))
    if not keys:
        return [], rel, [f"{rel}: parsed 0 {KNOB_CLASS} constants — refusing to pass vacuously"]
    return keys, rel, []


# ── consumption ───────────────────────────────────────────────────────────────────────

def receivers_for(name: str, shapes: dict[str, list[tuple[str, str, str]]]) -> set[str]:
    """Every spelling a receiver of [name] can have, DERIVED from the schema shapes.

    The decapitalised class name, the class name itself, and the name of every schema property
    whose declared type mentions the class — plus the singular of a plural one, because
    `providers: Map<String, ProviderConfig>` is read as `provider.baseUrl` at the element."""
    out = {name, name[0].lower() + name[1:]}
    for properties in shapes.values():
        for prop, _key, declared in properties:
            if re.search(r"\b" + re.escape(name) + r"\b", declared):
                out.add(prop)
                if prop.endswith("s"):
                    out.add(prop[:-1])
    return out


def consumers(files: dict[str, str], exclude: set[str]) -> dict[str, str]:
    return {rel: text for rel, text in files.items() if rel not in exclude}


def unconsumed(root: pathlib.Path) -> tuple[list[tuple[Key, str]], int, list[str]]:
    """(unconsumed keys with the reason each is red, keys examined, problems)."""
    files = sources(root)
    problems: list[str] = []
    excluded = {rel for rel, _ in NON_CONSUMPTION}
    for rel, reason in NON_CONSUMPTION:
        if not DATED_REASON.match(reason.strip()):
            problems.append(
                f"NON_CONSUMPTION entry for {rel} has no dated reason — every exclusion starts "
                "'YYYY-MM-DD: <why>'. An exclusion nobody can evaluate is indistinguishable from "
                "one nobody should have granted."
            )
        if rel not in files:
            problems.append(
                f"NON_CONSUMPTION names {rel}, which is not a production main file any more — delete "
                "the entry. A stale exclusion is an un-graded surface one rename later."
            )
    for key, reason in ALLOWLIST:
        if not DATED_REASON.match(reason.strip()):
            problems.append(
                f"ALLOWLIST entry {key!r} has no dated reason — a blank or undated reason is an "
                "absence wearing a label, not a disposition."
            )

    schema, shapes, schema_problems = schema_keys(files)
    knobs, knob_file, knob_problems = knob_keys(files)
    problems.extend(schema_problems)
    problems.extend(knob_problems)
    if not schema and not knobs:
        problems.append(
            f"parsed 0 config keys under {SRC_GLOB} — refusing to pass vacuously, because a green "
            "over an empty denominator is what this wall exists to prevent"
        )
        return [], 0, problems

    # Which files declare a property of each name: the AMBIGUITY index, from the source. A name
    # only one class owns can be matched bare; a name two classes own must be receiver-qualified,
    # which is the whole reason `stateDir` does not read as consumed through `statePaths.stateDir`.
    declared_by: dict[str, set[str]] = {}
    for rel, text in files.items():
        for prop in PROPERTY_DECL.findall(text):
            declared_by.setdefault(prop, set()).add(rel)

    facade = next(
        (rel for rel, _ in NON_CONSUMPTION if rel.endswith("SpliceConfig.kt") and rel in files), ""
    )
    facade_accessors = knob_accessors(files.get(facade, ""))

    allowlisted = {key for key, _ in ALLOWLIST}
    red: list[tuple[Key, str]] = []
    consumed_keys: set[str] = set()
    for key in schema:
        why = schema_unconsumed(key, shapes, files, declared_by, excluded)
        if why is None:
            consumed_keys.add(f"{key.owner}.{key.prop}")
        elif key.key not in allowlisted:
            red.append((key, why))
    for key in knobs:
        why = knob_unconsumed(key, files, knob_file, facade, facade_accessors, excluded)
        if why is None:
            consumed_keys.add(f"{key.owner}.{key.prop}")
        elif key.key not in allowlisted:
            red.append((key, why))

    for key, _reason in ALLOWLIST:
        live = [k for k in schema + knobs if k.key == key]
        if not live:
            problems.append(
                f"ALLOWLIST names {key!r}, which is not a config key any more — drop the entry; it "
                "currently exempts nothing."
            )
        elif any(f"{k.owner}.{k.prop}" in consumed_keys for k in live):
            problems.append(
                f"ALLOWLIST names {key!r}, which IS acted on now — drop the entry, so the list keeps "
                "meaning 'deliberately inert'."
            )
    return red, len(schema) + len(knobs), problems


def schema_unconsumed(
    key: Key,
    shapes: dict[str, list[tuple[str, str, str]]],
    files: dict[str, str],
    declared_by: dict[str, set[str]],
    excluded: set[str],
) -> str | None:
    """None when the key is acted on; otherwise the sentence saying how it is dead."""
    own = files[key.declared_in]
    # (A) IN ITS OWN FILE, by something other than its own declaration. The declaration text is
    # removed first, and a bare `prop =` (a named-argument WRITE) is not a read: putting the value
    # back into a constructor call is not acting on it.
    stripped = remove_declaration(own, key.prop)
    read = re.compile(r"(?<![\w$])" + re.escape(key.prop) + r"\b(?!\s*=(?!=))")
    if read.search(stripped):
        return None
    # (B) IN ANOTHER production main file, receiver-qualified when the name is ambiguous.
    ambiguous = bool(declared_by.get(key.prop, set()) - {key.declared_in})
    if ambiguous:
        spellings = sorted(receivers_for(key.owner, shapes), key=len, reverse=True)
        pattern = re.compile(
            r"(?:" + "|".join(re.escape(s) for s in spellings) + r")\s*\??\s*\.\s*" + re.escape(key.prop) + r"\b"
        )
    else:
        pattern = re.compile(r"(?<![\w$])" + re.escape(key.prop) + r"\b")
    for rel, text in files.items():
        if rel == key.declared_in or rel in excluded:
            continue
        if pattern.search(text):
            return None
    echo = [rel for rel in sorted(excluded) if rel in files and pattern.search(files[rel])]
    tail = (
        f" Its only read in the tree is the echo surface ({', '.join(echo)}), which shows the value "
        f"rather than using it."
        if echo
        else " Nothing reads it anywhere, in its own file or outside it."
    )
    return (
        f"{key.owner}.{key.prop} (TOML key `{key.key}`) is PARSED AND NEVER ACTED ON."
        + tail
        + " Thread it into the behaviour it promises, or retire it with a dated ALLOWLIST entry in "
        "checks/schema-keys-consumed.py saying why it is deliberately inert."
    )


def remove_declaration(text: str, prop: str) -> str:
    """The file with [prop]'s own `val prop:` / `var prop:` declaration lines blanked.

    Line-based on purpose: the declaration is what must not count as its own read, and blanking
    the line keeps every other occurrence at its original offset."""
    decl = re.compile(r"\b(?:val|var)\s+" + re.escape(prop) + r"\s*:")
    return "\n".join("" if decl.search(line) else line for line in text.splitlines())


def knob_accessors(facade: str) -> dict[str, set[str]]:
    """Knob constant -> the facade accessor names whose bodies read it.

    Split per DECLARATION rather than by a multi-line regex, so a knob is attributed to the
    accessor that actually names it (a sloppier scan pairs `FOLD_MARKER_TEXT` with
    `foldMaxContinue` and the hop then follows the wrong name)."""
    marks = [(m.start(), m.group(1)) for m in FACADE_MEMBER.finditer(facade)]
    out: dict[str, set[str]] = {}
    for index, (start, name) in enumerate(marks):
        end = marks[index + 1][0] if index + 1 < len(marks) else len(facade)
        for constant in re.findall(r"Knob\.([A-Z][A-Z0-9_]*)", facade[start:end]):
            out.setdefault(constant, set()).add(name)
    return out


def knob_unconsumed(
    key: Key,
    files: dict[str, str],
    knob_file: str,
    facade: str,
    accessors: dict[str, set[str]],
    excluded: set[str],
) -> str | None:
    direct = re.compile(r"Knob\." + re.escape(key.prop) + r"\b")
    for rel, text in files.items():
        if rel in (knob_file, facade):
            continue
        if rel in excluded:
            continue
        if direct.search(text):
            return None
    names = accessors.get(key.prop, set())
    for name in sorted(names):
        hop = re.compile(r"\." + re.escape(name) + r"\b")
        for rel, text in files.items():
            if rel in (knob_file, facade) or rel in excluded:
                continue
            if hop.search(text):
                return None
    if not names:
        return (
            f"Knob.{key.prop} (key `{key.key}`) is PARSED AND NEVER ACTED ON: no production file reads "
            f"it and the accessor facade does not expose it either. Wire it, or retire it with a dated "
            f"ALLOWLIST entry."
        )
    return (
        f"Knob.{key.prop} (key `{key.key}`) is PARSED AND NEVER ACTED ON: its only reader is the "
        f"accessor facade ({', '.join(sorted(names))}), and nothing reads that accessor either. An "
        f"operator who sets `{key.key}` gets silence. Wire it, or retire it with a dated ALLOWLIST "
        f"entry saying why it is deliberately inert."
    )


# ── modes ─────────────────────────────────────────────────────────────────────────────

def audit(root: pathlib.Path) -> int:
    red, examined, problems = unconsumed(root)
    print(f"schema-keys-consumed: {examined} config key(s) examined ({SRC_GLOB})")
    if not problems and not red:
        print(
            "GREEN: every key on Topology / DaemonConfig / HeadConfig / ProviderConfig / "
            "QuirksConfig and every Knob key is read by something that acts on it"
        )
        return 0
    print(f"\nFAIL: schema-keys-consumed — {len(red) + len(problems)} problem(s):", file=sys.stderr)
    for problem in problems:
        print("  x " + problem, file=sys.stderr)
    for key, reason in red:
        print(f"  x {key.locus()}: {reason}", file=sys.stderr)
    return 1


def report(root: pathlib.Path) -> int:
    files = sources(root)
    schema, _shapes, schema_problems = schema_keys(files)
    knobs, _knob_file, knob_problems = knob_keys(files)
    red, examined, problems = unconsumed(root)
    dead = {f"{key.owner}.{key.prop}" for key, _ in red}
    for problem in schema_problems + knob_problems + problems:
        print("  UNTRUSTED: " + problem)
    print(f"schema-keys-consumed: {examined} key(s), {len(red)} parsed-and-never-acted-on")
    for key in schema + knobs:
        state = "DEAD" if f"{key.owner}.{key.prop}" in dead else "acted on"
        print(f"  {key.plane:6s} {key.owner}.{key.prop:24s} key {key.key:26s} {state:9s} {key.locus()}")
    return 1 if (red or problems) else 0


# ── selftest ──────────────────────────────────────────────────────────────────────────

MODULE = "gateway/zz-selftest/src/main/kotlin/splice/selftest"

SCHEMA_FIXTURE = '''package splice.selftest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class Topology(
    val daemon: DaemonConfig = DaemonConfig(),
    val providers: Map<String, ProviderConfig> = emptyMap(),
    val heads: Map<String, HeadConfig> = emptyMap(),
)

@Serializable
public data class DaemonConfig(
    @SerialName("control_port") val controlPort: Int? = null,
    @SerialName("state_dir") val stateDir: String? = null,
    @SerialName("echoed_only") val echoedOnly: String? = null,
)

@Serializable
public data class HeadConfig(
    val port: Int,
    /** A KDoc between parameters, with a comma and a ) in it. */
    @SerialName("context_window") val contextWindow: Long? = null,
)

@Serializable
public data class ProviderConfig(
    @SerialName("base_url") val baseUrl: String,
    val quirks: QuirksConfig = QuirksConfig(),
    @SerialName("extra_windows") val extraWindows: List<String> = emptyList(),
) {
    public fun toCatalog(): Catalog = Catalog(extraWindows = extraWindows)
}

@Serializable
public data class QuirksConfig(
    val store: Boolean = false,
    @SerialName("compact_effort") val compactEffort: String? = null,
) {
    init {
        require(compactEffort == null) { "compact_effort is retired" }
    }
}
'''

CATALOG_FIXTURE = '''package splice.selftest

public class Catalog(val extraWindows: List<String> = emptyList()) {
    public fun widest(): String? = extraWindows.maxOrNull()
}
'''

# The consumer: reads controlPort, port and contextWindow for real. Nothing here reads stateDir
# or echoedOnly.
CONSUMER_FIXTURE = '''package splice.selftest

internal class Wiring(private val topology: Topology) {
    fun bind(): Int = topology.daemon.controlPort ?: 0
    fun providerKeys(): Set<String> = topology.providers.keys
    fun headKeys(): Set<String> = topology.heads.keys
    fun window(head: HeadConfig): Long = head.contextWindow ?: 0
    fun port(head: HeadConfig): Int = head.port
    fun base(provider: ProviderConfig): String = provider.baseUrl
    fun store(quirks: QuirksConfig): Boolean = quirks.store
}
'''

# The echo surface: puts state_dir and echoed_only back out under their own key names. Also
# declares its own `stateDir`, which is what makes the receiver qualification load-bearing.
ECHO_FIXTURE = '''package splice.selftest

internal class DoctorReportShape(private val paths: StatePaths) {
    fun shape(t: Topology): Map<String, Any?> = mapOf(
        "state_dir" to t.daemon.stateDir,
        "echoed_only" to t.daemon.echoedOnly,
        "local_state_dir" to paths.stateDir,
    )
}

internal class StatePaths {
    val stateDir: String = "/var/lib/splice"
}
'''

KNOB_FIXTURE = '''package splice.selftest

public enum class Knob(
    public val key: String,
    public val default: Any?,
) {
    PORT("port", 3099L),
    WIRED_DIRECT("wiredDirect", "x"),
    WIRED_VIA_ACCESSOR("wiredViaAccessor", true),
    DEBUG("debug", false),
}
'''

FACADE_FIXTURE = '''package splice.selftest

public class SpliceConfig internal constructor(private val m: Map<String, Any?>) {
    public val port: Int get() = (m[Knob.PORT.key] as? Int) ?: 0
    public val wiredViaAccessor: Boolean get() = m[Knob.WIRED_VIA_ACCESSOR.key] == true
    public val debug: Boolean get() = m[Knob.DEBUG.key] == true
}
'''

KNOB_CONSUMER_FIXTURE = '''package splice.selftest

internal class KnobWiring(private val cfg: SpliceConfig) {
    fun direct(): String = Knob.WIRED_DIRECT.key
    fun viaAccessor(): Boolean = cfg.wiredViaAccessor
    fun bind(): Int = cfg.port
}
'''

FIXTURE_NON_CONSUMPTION = (
    (f"{MODULE}/DoctorReportShape.kt", "2026-09-17: the echo surface fixture"),
    (f"{MODULE}/SpliceConfig.kt", "2026-09-17: the accessor facade fixture"),
)


def fixture(root: pathlib.Path, **overrides: str) -> None:
    written = {
        "Schema.kt": SCHEMA_FIXTURE,
        "Catalog.kt": CATALOG_FIXTURE,
        "Wiring.kt": CONSUMER_FIXTURE,
        "DoctorReportShape.kt": ECHO_FIXTURE,
        "Knob.kt": KNOB_FIXTURE,
        "SpliceConfig.kt": FACADE_FIXTURE,
        "KnobWiring.kt": KNOB_CONSUMER_FIXTURE,
    }
    written.update(overrides)
    for name, text in written.items():
        if not text:
            continue
        path = root / MODULE / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


def selftest() -> int:
    global NON_CONSUMPTION, ALLOWLIST
    failures: list[str] = []
    real_non_consumption, real_allowlist = NON_CONSUMPTION, ALLOWLIST
    NON_CONSUMPTION = FIXTURE_NON_CONSUMPTION

    def run(root: pathlib.Path) -> tuple[int, str, list[tuple[Key, str]]]:
        captured = io.StringIO()
        stdout, stderr = sys.stdout, sys.stderr
        sys.stdout = sys.stderr = captured
        try:
            code = audit(root)
            red, _examined, _problems = unconsumed(root)
        finally:
            sys.stdout, sys.stderr = stdout, stderr
        return code, captured.getvalue(), red

    def arm(label: str, build, expect: str | None) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            build(root)
            code, text, _red = run(root)
            if expect is None:
                if code != 0:
                    failures.append(f"{label} — must be GREEN, got exit {code}: {text.strip()[:400]}")
            elif code == 0:
                failures.append(f"{label} — MUST be RED, exited 0")
            elif expect not in text:
                failures.append(f"{label} — red for the wrong reason (want {expect!r}): {text.strip()[:400]}")

    try:
        # 1. THE LIVE FINDINGS' SHAPES, in one fixture: state_dir (read only by the echo surface,
        #    with a same-named property on another class) and debug (read only by an accessor
        #    nobody calls) must BOTH be red by name, and nothing else may be.
        def base(root: pathlib.Path) -> None:
            fixture(root)
        arm("1a. a key read only by the ECHO surface is dead (state_dir's exact shape)", base, "state_dir")
        arm("1b. and the reason names the echo surface rather than claiming nothing reads it", base, "echo surface")
        arm("1c. a knob read only by an accessor nobody calls is dead (debug's exact shape)", base, "`debug`")
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture(root)
            _code, _text, red = run(root)
            dead = sorted(key.key for key, _ in red)
            if dead != ["debug", "echoed_only", "state_dir"]:
                failures.append(
                    f"1d. the fixture's dead set must be exactly state_dir, echoed_only and debug "
                    f"(every other key is wired, including the two read only inside their own file): got {dead}"
                )

        # 2. the KEYS THAT MUST NOT BE RED, each a real pattern from this tree:
        #    contextWindow — ambiguous name, read receiver-qualified in another file;
        #    extraWindows  — read ONLY inside its own declaring file, projected into a domain type;
        #    compactEffort — read ONLY by a `require` in its own init, which is acting on it;
        #    port/store/baseUrl/controlPort — ordinary cross-file reads;
        #    wiredDirect   — a knob read directly;
        #    wiredViaAccessor / port — a knob read through its accessor (the one hop).
        #    Arm 1d above pins all of them at once, so this arm asserts the GREEN half explicitly.
        def wired_only(root: pathlib.Path) -> None:
            fixture(
                root,
                **{
                    "Schema.kt": SCHEMA_FIXTURE.replace(
                        '    @SerialName("state_dir") val stateDir: String? = null,\n', ""
                    ).replace('    @SerialName("echoed_only") val echoedOnly: String? = null,\n', ""),
                    "DoctorReportShape.kt": ECHO_FIXTURE.replace(
                        '        "state_dir" to t.daemon.stateDir,\n', ""
                    ).replace('        "echoed_only" to t.daemon.echoedOnly,\n', ""),
                    "Knob.kt": KNOB_FIXTURE.replace('    DEBUG("debug", false),\n', ""),
                    "SpliceConfig.kt": FACADE_FIXTURE.replace(
                        "    public val debug: Boolean get() = m[Knob.DEBUG.key] == true\n", ""
                    ),
                }
            )
        arm("2. the fully-wired twin is GREEN (own-file projection, own-init require, one-hop accessor)", wired_only, None)

        # 3. THE MUTATION THIS ROW REQUIRES: a synthetic key appended to a temp copy of the schema.
        def synthetic(root: pathlib.Path) -> None:
            fixture(
                root,
                **{
                    "Schema.kt": SCHEMA_FIXTURE.replace(
                        "    val store: Boolean = false,",
                        '    val store: Boolean = false,\n    @SerialName("fake_new_key") val fakeNewKey: Boolean? = null,',
                    )
                },
            )
        arm("3. a synthetic key nothing reads is RED BY NAME", synthetic, "fake_new_key")

        # 4. the allowlist: a dated reason is a disposition, a blank one is not, a stale one fails.
        def allowlisted(root: pathlib.Path) -> None:
            global ALLOWLIST
            ALLOWLIST = (
                ("state_dir", "2026-09-17: fixture — deliberately inert"),
                ("echoed_only", "2026-09-17: fixture — deliberately inert"),
                ("debug", "2026-09-17: fixture — deliberately inert"),
            )
            fixture(root)
        arm("4a. a dated, reasoned ALLOWLIST entry is a disposition", allowlisted, None)
        ALLOWLIST = ()

        def blank_reason(root: pathlib.Path) -> None:
            global ALLOWLIST
            ALLOWLIST = (
                ("state_dir", "  "),
                ("echoed_only", "2026-09-17: fixture"),
                ("debug", "2026-09-17: fixture"),
            )
            fixture(root)
        arm("4b. an ALLOWLIST entry with a blank reason is a hard error", blank_reason, "absence wearing a label")
        ALLOWLIST = ()

        def stale_entry(root: pathlib.Path) -> None:
            global ALLOWLIST
            ALLOWLIST = (
                ("state_dir", "2026-09-17: fixture"),
                ("echoed_only", "2026-09-17: fixture"),
                ("debug", "2026-09-17: fixture"),
                ("port", "2026-09-17: fixture — but port IS wired"),
            )
            fixture(root)
        arm("4c. an ALLOWLIST entry naming a key that IS acted on fails as stale", stale_entry, "IS acted on")
        ALLOWLIST = ()

        # 5. a stale NON_CONSUMPTION entry is a hard error — a dead exclusion is an un-graded surface.
        def stale_exclusion(root: pathlib.Path) -> None:
            global NON_CONSUMPTION
            NON_CONSUMPTION = FIXTURE_NON_CONSUMPTION + (
                (f"{MODULE}/Gone.kt", "2026-09-17: fixture — names nothing"),
            )
            fixture(root)
        arm("5. a NON_CONSUMPTION entry naming a file that is gone is a hard error", stale_exclusion, "stale exclusion")
        NON_CONSUMPTION = FIXTURE_NON_CONSUMPTION

        # 6. a missing schema class is a hard error, not a shorter list.
        def missing_class(root: pathlib.Path) -> None:
            fixture(root, **{"Schema.kt": SCHEMA_FIXTURE.replace("class QuirksConfig(", "class QuirksRenamed(")})
        arm("6. a schema class the tree no longer declares is a hard error", missing_class, "part of the denominator")

        # 7. THE BORING CASES (§24), which are the ones that get waved through.
        def empty(root: pathlib.Path) -> None:
            (root / MODULE).mkdir(parents=True, exist_ok=True)
        arm("7a. a tree with no config keys at all must REFUSE, not pass vacuously", empty, "vacuously")

        def one_key(root: pathlib.Path) -> None:
            fixture(
                root,
                **{
                    "Schema.kt": (
                        "package splice.selftest\n\n"
                        "public data class Topology(val daemon: DaemonConfig = DaemonConfig())\n"
                        "public data class DaemonConfig(val only: Int = 0)\n"
                        "public data class HeadConfig(val port: Int)\n"
                        "public data class ProviderConfig(val baseUrl: String)\n"
                        "public data class QuirksConfig(val store: Boolean = false)\n"
                    ),
                    "Catalog.kt": "",
                    "Wiring.kt": (
                        "package splice.selftest\n\n"
                        "internal class Wiring(private val t: Topology) {\n"
                        "    fun a(): Int = t.daemon.only\n"
                        "    fun b(h: HeadConfig): Int = h.port\n"
                        "    fun c(p: ProviderConfig): String = p.baseUrl\n"
                        "    fun d(q: QuirksConfig): Boolean = q.store\n"
                        "}\n"
                    ),
                    "DoctorReportShape.kt": "package splice.selftest\n\ninternal class DoctorReportShape\n",
                    "Knob.kt": (
                        "package splice.selftest\n\n"
                        "public enum class Knob(public val key: String) {\n"
                        '    ONLY("only"),\n'
                        "}\n"
                    ),
                    "SpliceConfig.kt": (
                        "package splice.selftest\n\n"
                        "public class SpliceConfig {\n"
                        "    public val only: String get() = Knob.ONLY.key\n"
                        "}\n"
                    ),
                    "KnobWiring.kt": (
                        "package splice.selftest\n\n"
                        "internal class KnobWiring(private val c: SpliceConfig) { fun a() = c.only }\n"
                    ),
                },
            )
        arm("7b. the one-key-per-plane tree grades green WITH its count", one_key, None)
    finally:
        NON_CONSUMPTION, ALLOWLIST = real_non_consumption, real_allowlist

    if failures:
        print("schema-keys-consumed SELFTEST FAIL:")
        for failure in failures:
            print("  x " + failure)
        return 1
    print(
        "schema-keys-consumed SELFTEST OK — the fully-wired twin is green (a key projected inside "
        "its own file, a key rejected by its own init, an ambiguous name read receiver-qualified, a "
        "knob read directly and a knob read through one accessor hop), and a dated reasoned "
        "allowlist entry is a disposition; a key read only by the echo surface, a knob read only by "
        "an uncalled accessor, a synthetic key, a blank-reasoned allowlist entry, a stale allowlist "
        "entry, a stale non-consumption entry, a renamed schema class and an empty denominator are "
        "all red by name"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--report", action="store_true", help="every key with its disposition")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    root = pathlib.Path(args.root).resolve() if args.root else ROOT
    if not root.exists():
        print(f"schema-keys-consumed: {root} does not exist", file=sys.stderr)
        return 2
    return report(root) if args.report else audit(root)


if __name__ == "__main__":
    sys.exit(main())
