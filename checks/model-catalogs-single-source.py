#!/usr/bin/env python3
"""V4-98 — the three hand-authored model rosters agree with config/splice.example.toml.

WHY THIS EXISTS. splice declares its model rows THREE times, by hand, in three
languages:

  config/splice.example.toml                                     the reference an
      operator copies — ids, labels, context windows, per-provider commentary. This
      file is the SOURCE: it is the one an operator reads, and the one whose windows
      carry the measured justification (see the k3[1m] note about 1e6 vs 1048576).
  gateway/app/src/main/kotlin/splice/app/cli/AddProfileCatalog.kt  the rows
      `splice add <vendor>` renders into the operator's file.
  gateway/app/src/main/kotlin/splice/app/TopologyLoader.kt         DEFAULT_TOML, the
      starter materialized on first run when no config exists.

Nothing paired them. A context window corrected in the example stayed wrong in the two
emitters, and a model id added to an emitter never had to exist in the reference at all
— which is exactly today's state: `splice add openrouter` and the first-run starter both
declare eight OpenRouter ids the example never mentions. A window that disagrees is not
cosmetic: TopologyLoader plants the pinned row's window as CLAUDE_CODE_MAX_CONTEXT_TOKENS
and ModelCatalog.usageScale compacts every other row against its declared number, so a
roster that drifted by 4.6% compacts 4.6% early or late with nothing logging it.

This is the §24 shape: three hand-authored lists, two of which agreed with each other and
disagreed with the reference in silence, and no denominator enumerated from outside.

THE LAW. Every model row a DERIVED roster declares must exist in the example's roster for
the SAME provider, with a byte-identical context window. The example may declare more —
it is the full reference and the emitters are curated starters — so a superset in the
example is not drift. A derived id the example does not carry, or a window that differs,
is RED BY NAME.

THE JOIN KEY IS base_url, NEVER the table name. The provider keys already disagree on
purpose: the catalog calls xAI `grok` and Anthropic `claude` (they name the WRAPPER an
operator types), while the example calls them `xai` and `anthropic` (they name the
VENDOR). A hand-written alias map between them would be a fourth hand-authored list, and
this checker exists because hand-authored lists agree with each other. base_url is the
provider's actual identity, it is declared in all three files, and it is what the daemon
dials. Two example providers sharing one base_url make the join ambiguous and are RED.

DENOMINATORS, from the SOURCES, never a hand list.
  The example's providers and their model rows are parsed out of the TOML on disk.
  The catalog's rows are parsed out of AddProfileCatalog.kt's AddProfile/AddModel calls,
    with the WINDOW_* constants resolved from their own `private const val` declarations
    in that same file.
  DEFAULT_TOML is extracted from TopologyLoader.kt by its marker and parsed as TOML.
  A vendor added to any of the three is in scope with no edit to this file.

FOUR GUARDS REFUSE A VACUOUS PASS, because a green over an empty denominator is the whole
failure this wall exists to prevent:
  - the example must yield at least one provider carrying at least one model row;
  - each derived source must yield at least one roster carrying at least one model row;
  - the model rows parsed out of each TOML must equal the count of `[[providers.*.models]]`
    headers in its comment-stripped text, and the AddModel rows parsed out of the catalog
    must equal the count of `AddModel(` calls in its comment-stripped text — a parser that
    has drifted off its source produces a list no run can be trusted with;
  - the number of (id, window) comparisons actually performed must be non-zero.

DISPOSITION. Every derived roster is accounted for in exactly one of three forms:
  agrees   — joined to an example provider by base_url, every id present, every window equal;
  no-roster— the roster declares NO base_url and NO models, so there is nothing to compare.
             That is the generic `api-key` row, whose base URL and models the operator
             supplies on the command line. The condition is COMPUTED (models empty AND
             base_url absent), not a named exemption — a row that grew models while keeping
             a null base_url stops qualifying and goes red;
  RED      — anything else, by name.
Absence is not a disposition: a roster that matches no example provider fails rather than
being skipped.

WHAT IS NOT CAUGHT, and why.
  A label that disagrees. The example says "Codex 5.6 Sol" where the catalog says
  "GPT-5.6 Sol", and "Kimi K3 (256k)" where the catalog says "Kimi K3 256k". Those are
  display strings shown in different places (a config comment vs a picker row) and pinning
  them would red the wall for a copy edit. Ids and windows are the wire.
  A model the example declares and an emitter omits. Deliberate: the emitters are curated
  starters — the example lists eight Codex rows and `splice add codex` ships three.
  Slots, rates and quirks. Other walls own those surfaces; this one owns id + window.
  A window that is wrong in the EXAMPLE. Nothing here validates the reference against the
  vendor — that is a live-probe job, not a static one. This wall makes the three agree.

SELFTEST. --selftest builds temp trees and proves both directions: GREEN on a compliant
three-file tree (including the alias join and the example-superset case), GREEN on the
BORING case (one provider, one model), and RED BY NAME on each of: a window mutated in the
catalog, a synthetic id added to the catalog, a window mutated in DEFAULT_TOML, an id added
to DEFAULT_TOML, a roster whose base_url matches no example provider, a roster with models
but a null base_url, a duplicated base_url in the example, an empty example, an empty
derived roster set, a DEFAULT_TOML marker that cannot be found, and a parser/source count
disagreement in both the TOML and the Kotlin parser.
"""
from __future__ import annotations

import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

# The source of truth. A fixed path on purpose: a checker that silently loses its source is
# a checker that passes.
EXAMPLE_REL = "config/splice.example.toml"
CATALOG_REL = "gateway/app/src/main/kotlin/splice/app/cli/AddProfileCatalog.kt"
STARTER_REL = "gateway/app/src/main/kotlin/splice/app/TopologyLoader.kt"

STARTER_MARKER = "DEFAULT_TOML"

TABLE = re.compile(r"^[ \t]*(\[\[?)([^\[\]\n]+)\]\]?[ \t]*$")
KV = re.compile(r"^[ \t]*([A-Za-z0-9_-]+)[ \t]*=[ \t]*(.*)$")
MODELS_HEADER = re.compile(r"^[ \t]*\[\[providers\.[^\[\]\n]+\.models\]\][ \t]*$", re.MULTILINE)
WINDOW_CONST = re.compile(r"\bconst\s+val\s+(WINDOW_\w+)\s*=\s*([0-9_]+)L?\b")
ADD_MODEL_CALL = re.compile(r"\bAddModel\s*\(")
ADD_PROFILE_CALL = re.compile(r"\bAddProfile\s*\(")
NUMBER = re.compile(r"^([0-9_]+)L?$")
STRING_ARG = re.compile(r'^"((?:[^"\\]|\\.)*)"$')


class Roster:
    """One provider's declared model rows, as parsed out of one file."""

    def __init__(self, label: str, provider: str, base_url: str | None) -> None:
        self.label = label
        self.provider = provider
        self.base_url = base_url
        self.models: list[tuple[str, int | None]] = []

    @property
    def where(self) -> str:
        return f"{self.label} [{self.provider}]"


def strip_toml_comments(text: str) -> str:
    """Drop `#` comments, respecting quoted strings. Keeps line structure so a header
    regex over the stripped text still anchors."""
    out: list[str] = []
    for line in text.split("\n"):
        kept: list[str] = []
        in_string = False
        quote = ""
        escape = False
        for ch in line:
            if in_string:
                kept.append(ch)
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
                kept.append(ch)
                continue
            if ch == "#":
                break
            kept.append(ch)
        out.append("".join(kept).rstrip())
    return "\n".join(out)


def strip_kotlin_comments(source: str) -> str:
    """Remove // and /* */ comments, respecting string literals (including \"\"\"raw\"\"\")."""
    out: list[str] = []
    i = 0
    in_string = False
    quote = ""
    escape = False
    while i < len(source):
        ch = source[i]
        if in_string:
            out.append(ch)
            if quote == '"""':
                if source.startswith('"""', i):
                    out.append('""')
                    i += 3
                    in_string = False
                    continue
                i += 1
                continue
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == quote:
                in_string = False
            i += 1
            continue
        if source.startswith('"""', i):
            in_string = True
            quote = '"""'
            out.append('"""')
            i += 3
            continue
        if ch in "\"'":
            in_string = True
            quote = ch
            out.append(ch)
            i += 1
            continue
        if ch == "/" and source.startswith("//", i):
            nl = source.find("\n", i)
            i = len(source) if nl < 0 else nl
            continue
        if ch == "/" and source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = len(source) if end < 0 else end + 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def unquote(raw: str) -> str | None:
    """A TOML/Kotlin scalar's string value, or None when it is not a quoted string."""
    text = raw.strip()
    match = STRING_ARG.match(text)
    if match is None:
        return None
    return match.group(1)


def parse_number(raw: str) -> int | None:
    match = NUMBER.match(raw.strip())
    if match is None:
        return None
    return int(match.group(1).replace("_", ""))


def parse_toml_rosters(text: str, label: str) -> tuple[list[Roster], list[str]]:
    """Providers and their `[[providers.X.models]]` rows out of TOML text.

    `extra_windows` and every non-provider table (heads, daemon, quirks) are not model
    rosters and are deliberately skipped; only `[providers.X]` and the exact
    `[[providers.X.models]]` shape contribute."""
    problems: list[str] = []
    rosters: dict[str, Roster] = {}
    order: list[str] = []
    stripped = strip_toml_comments(text)
    provider: str | None = None
    in_model = False
    model_id: str | None = None
    model_window: int | None = None
    rows_parsed = 0

    def close_model() -> None:
        nonlocal in_model, model_id, model_window, rows_parsed
        if not in_model:
            return
        rows_parsed += 1
        if provider is None:
            problems.append(f"{label}: a [[providers.*.models]] row outside any provider table")
        elif model_id is None:
            problems.append(f"{label} [{provider}]: a model row declares no id")
        else:
            rosters[provider].models.append((model_id, model_window))
        in_model = False
        model_id = None
        model_window = None

    for line in stripped.split("\n"):
        header = TABLE.match(line)
        if header is not None:
            close_model()
            path = header.group(2).strip()
            parts = path.split(".")
            if parts[0] != "providers" or len(parts) < 2:
                provider = None
                continue
            name = parts[1]
            if len(parts) == 2:
                provider = name
                if name not in rosters:
                    rosters[name] = Roster(label, name, None)
                    order.append(name)
                continue
            if parts[-1] == "models" and len(parts) == 3 and header.group(1) == "[[":
                provider = name
                if name not in rosters:
                    rosters[name] = Roster(label, name, None)
                    order.append(name)
                in_model = True
                continue
            # quirks, tool_surface, extra_windows, overrides: the provider stays current so a
            # later base_url cannot be mis-attributed, but these tables carry no model rows.
            provider = name if name in rosters else provider
            continue
        pair = KV.match(line)
        if pair is None or provider is None:
            continue
        key, raw = pair.group(1), pair.group(2)
        if in_model:
            if key == "id":
                model_id = unquote(raw)
            elif key == "context_window":
                model_window = parse_number(raw)
            continue
        if key == "base_url":
            rosters[provider].base_url = unquote(raw)
    close_model()

    raw_rows = len(MODELS_HEADER.findall(stripped))
    if raw_rows != rows_parsed:
        problems.append(
            f"{label}: parsed {rows_parsed} model rows but the text holds {raw_rows} "
            "[[providers.*.models]] headers — the parser and the source disagree, so no "
            "roster from this run can be trusted"
        )
    return [rosters[name] for name in order], problems


def paren_body(source: str, open_index: int) -> str | None:
    """The text inside the parens whose opener is at open_index. Comment- and string-aware."""
    i = open_index
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
        if source.startswith("//", i):
            nl = source.find("\n", i)
            i = len(source) if nl < 0 else nl
            continue
        if source.startswith("/*", i):
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


def split_args(body: str) -> list[str]:
    """Split a call's argument list on top-level commas, dropping comments."""
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
        if body.startswith("//", i):
            nl = body.find("\n", i)
            i = len(body) if nl < 0 else nl
            continue
        if body.startswith("/*", i):
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
        if ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if "".join(buf).strip():
        parts.append("".join(buf))
    return parts


def named_arg(args: list[str], name: str) -> str | None:
    """The raw text of `name = ...` in an argument list, or None."""
    pattern = re.compile(r"^\s*" + re.escape(name) + r"\s*=\s*(.*)$", re.DOTALL)
    for arg in args:
        match = pattern.match(arg)
        if match is not None:
            return match.group(1).strip()
    return None


def parse_kotlin_catalog(source: str, label: str) -> tuple[list[Roster], list[str]]:
    """AddProfile rows out of AddProfileCatalog.kt, with WINDOW_* constants resolved."""
    problems: list[str] = []
    rosters: list[Roster] = []
    windows = {name: int(value.replace("_", "")) for name, value in WINDOW_CONST.findall(source)}
    rows_parsed = 0

    for match in ADD_PROFILE_CALL.finditer(source):
        body = paren_body(source, match.end() - 1)
        if body is None:
            problems.append(f"{label}: an AddProfile( call could not be parsed")
            continue
        args = split_args(body)
        raw_name = named_arg(args, "name")
        name = unquote(raw_name) if raw_name else None
        if name is None:
            problems.append(f"{label}: an AddProfile row declares no literal name")
            continue
        raw_base = named_arg(args, "baseUrl")
        base_url = None if raw_base in (None, "null") else unquote(raw_base or "")
        if raw_base not in (None, "null") and base_url is None:
            problems.append(
                f"{label} [{name}]: baseUrl is neither a string literal nor null "
                f"({raw_base!r}) — it cannot be joined to the example"
            )
        roster = Roster(label, name, base_url)
        raw_models = named_arg(args, "models") or ""
        for model_match in ADD_MODEL_CALL.finditer(raw_models):
            model_body = paren_body(raw_models, model_match.end() - 1)
            rows_parsed += 1
            if model_body is None:
                problems.append(f"{label} [{name}]: an AddModel( call could not be parsed")
                continue
            model_args = split_args(model_body)
            positional = [arg for arg in model_args if "=" not in arg.split('"')[0]]
            model_id = unquote(positional[0]) if positional else None
            if model_id is None:
                problems.append(f"{label} [{name}]: an AddModel row declares no literal id")
                continue
            raw_window = positional[2].strip() if len(positional) > 2 else None
            window: int | None = None
            if raw_window is None:
                problems.append(f"{label} [{name}]: {model_id} declares no context window")
            elif raw_window in windows:
                window = windows[raw_window]
            else:
                window = parse_number(raw_window)
                if window is None:
                    problems.append(
                        f"{label} [{name}]: {model_id}'s window {raw_window!r} resolves to no "
                        "constant in this file and is not a literal — an unresolved window "
                        "cannot be compared, so this run is not trusted"
                    )
            roster.models.append((model_id, window))
        rosters.append(roster)

    raw_rows = len(ADD_MODEL_CALL.findall(strip_kotlin_comments(source)))
    if raw_rows != rows_parsed:
        problems.append(
            f"{label}: parsed {rows_parsed} AddModel rows but the file holds {raw_rows} "
            "AddModel( calls — the parser and the source disagree, so no roster from this "
            "run can be trusted"
        )
    if not windows:
        problems.append(f"{label}: no WINDOW_* constant declarations found — windows cannot be resolved")
    return rosters, problems


def extract_starter_toml(source: str, label: str) -> tuple[str | None, list[str]]:
    """DEFAULT_TOML's raw-string body out of TopologyLoader.kt, by its marker."""
    anchor = source.find(STARTER_MARKER)
    if anchor < 0:
        return None, [f"{label}: {STARTER_MARKER} not found — the starter roster's source is absent"]
    open_quote = source.find('"""', anchor)
    if open_quote < 0:
        return None, [f"{label}: {STARTER_MARKER} is not followed by a raw string literal"]
    close_quote = source.find('"""', open_quote + 3)
    if close_quote < 0:
        return None, [f"{label}: {STARTER_MARKER}'s raw string is unterminated"]
    return source[open_quote + 3 : close_quote], []


def index_by_base_url(rosters: list[Roster], label: str) -> tuple[dict[str, Roster], list[str]]:
    problems: list[str] = []
    index: dict[str, Roster] = {}
    for roster in rosters:
        if roster.base_url is None:
            continue
        if roster.base_url in index:
            problems.append(
                f"{label}: base_url {roster.base_url} is declared by BOTH "
                f"[providers.{index[roster.base_url].provider}] and [providers.{roster.provider}] "
                "— the join key is ambiguous, so no comparison against this file is trustworthy"
            )
            continue
        index[roster.base_url] = roster
    return index, problems


def load_sources(root: pathlib.Path) -> tuple[list[Roster], list[list[Roster]], list[str]]:
    """(example rosters, [catalog rosters, starter rosters], problems)."""
    problems: list[str] = []
    example: list[Roster] = []
    derived: list[list[Roster]] = []

    example_path = root / EXAMPLE_REL
    if not example_path.exists():
        problems.append(f"{EXAMPLE_REL}: missing — it IS the source, so its absence cannot pass")
    else:
        example, hits = parse_toml_rosters(example_path.read_text(encoding="utf-8"), EXAMPLE_REL)
        problems.extend(hits)

    catalog_path = root / CATALOG_REL
    if not catalog_path.exists():
        problems.append(f"{CATALOG_REL}: missing — a roster that cannot be read cannot be proven to agree")
        derived.append([])
    else:
        rosters, hits = parse_kotlin_catalog(catalog_path.read_text(encoding="utf-8"), CATALOG_REL)
        problems.extend(hits)
        derived.append(rosters)

    starter_path = root / STARTER_REL
    if not starter_path.exists():
        problems.append(f"{STARTER_REL}: missing — a roster that cannot be read cannot be proven to agree")
        derived.append([])
    else:
        text, hits = extract_starter_toml(starter_path.read_text(encoding="utf-8"), STARTER_REL)
        problems.extend(hits)
        if text is None:
            derived.append([])
        else:
            label = f"{STARTER_REL}:{STARTER_MARKER}"
            rosters, hits = parse_toml_rosters(text, label)
            problems.extend(hits)
            derived.append(rosters)
    return example, derived, problems


def audit(root: pathlib.Path) -> list[str]:
    example, derived, problems = load_sources(root)

    if not any(roster.models for roster in example):
        problems.append(
            f"{EXAMPLE_REL}: parsed no provider carrying a model row — refusing to compare "
            "against an empty source, because a green over an empty denominator is what this "
            "wall exists to prevent"
        )
        return problems

    index, hits = index_by_base_url(example, EXAMPLE_REL)
    problems.extend(hits)

    comparisons = 0
    for rosters in derived:
        if not any(roster.models for roster in rosters):
            label = rosters[0].label if rosters else "a derived roster source"
            problems.append(
                f"{label}: parsed no roster carrying a model row — refusing to pass vacuously"
            )
            continue
        for roster in rosters:
            if roster.base_url is None:
                if not roster.models:
                    # no-roster: nothing to compare. COMPUTED, not a named exemption.
                    continue
                problems.append(
                    f"{roster.where}: declares {len(roster.models)} model row(s) but no base_url "
                    "— it cannot be joined to the example, and an unjoinable roster is an "
                    "absence, not a disposition"
                )
                continue
            target = index.get(roster.base_url)
            if target is None:
                problems.append(
                    f"{roster.where}: base_url {roster.base_url} matches no provider table in "
                    f"{EXAMPLE_REL} — add the provider there, or point this roster at a declared one"
                )
                continue
            expected = {model_id: window for model_id, window in target.models}
            for model_id, window in roster.models:
                comparisons += 1
                if model_id not in expected:
                    problems.append(
                        f"{roster.where}: {model_id} (context_window {window}) is absent from "
                        f"{EXAMPLE_REL} [providers.{target.provider}] — the example is the source, "
                        "so declare the row there or drop it here"
                    )
                    continue
                if expected[model_id] != window:
                    problems.append(
                        f"{roster.where}: {model_id} declares context_window {window} but "
                        f"{EXAMPLE_REL} [providers.{target.provider}] declares "
                        f"{expected[model_id]} — the example is the source"
                    )
    if comparisons == 0 and not problems:
        problems.append(
            "compared 0 model rows across the derived rosters — refusing to pass vacuously"
        )
    return problems


# ── selftest fixtures ─────────────────────────────────────────────────────────────────

EXAMPLE_OK = '''[daemon]
control_port = 3096

[providers.xai]
dialect = "openai-responses"
base_url = "https://api.x.ai/v1"
[[providers.xai.models]]
id = "grok-4.6"
label = "Grok 4.6"
context_window = 500000        # a trailing comment the stripper must drop
[[providers.xai.models]]
id = "grok-4.3"
label = "Grok 4.3"
context_window = 1000000

[providers.kimi]
base_url = "https://api.kimi.com/coding"
[[providers.kimi.extra_windows]]
id = "k3"
context_window = 1000000
[[providers.kimi.models]]
id = "k3[1m]"
label = "Kimi K3 (1M)"
context_window = 1000000

[heads.grok]
provider = "xai"
context_window = 500000
'''

CATALOG_OK = '''package splice.app.cli

private const val WINDOW_500K = 500_000L
private const val WINDOW_1M = 1_000_000L

internal class AddProfileCatalog {
    val rows: List<AddProfile> = listOf(
        AddProfile(
            // The catalog calls xAI `grok`; the example calls it `xai`. base_url joins them.
            name = "grok",
            baseUrl = "https://api.x.ai/v1",
            models = listOf(
                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),
            ),
        ),
        AddProfile(
            name = "kimi",
            baseUrl = "https://api.kimi.com/coding",
            models = listOf(
                AddModel("k3[1m]", "Kimi K3 (1M)", WINDOW_1M, slots = listOf("opus")),
            ),
        ),
        AddProfile(
            name = "api-key",
            baseUrl = null,
            models = emptyList(),
        ),
    )
}
'''

STARTER_OK = '''package splice.app

public object TopologyLoader {
    private const val DEFAULT_TOML = """
[providers.xai]
base_url = "https://api.x.ai/v1"
[[providers.xai.models]]
id = "grok-4.6"
label = "Grok 4.6"
context_window = 500000
"""
}
'''

EXAMPLE_BORING = '''[providers.solo]
base_url = "https://example.invalid/v1"
[[providers.solo.models]]
id = "only-one"
label = "Only One"
context_window = 128000
'''

CATALOG_BORING = '''package splice.app.cli

private const val WINDOW_128K = 128_000L

internal class AddProfileCatalog {
    val rows: List<AddProfile> = listOf(
        AddProfile(name = "solo", baseUrl = "https://example.invalid/v1", models = listOf(
            AddModel("only-one", "Only One", WINDOW_128K),
        )),
    )
}
'''

STARTER_BORING = '''package splice.app

public object TopologyLoader {
    private const val DEFAULT_TOML = """
[providers.solo]
base_url = "https://example.invalid/v1"
[[providers.solo.models]]
id = "only-one"
context_window = 128000
"""
}
'''


def write_tree(
    root: pathlib.Path, example: str, catalog: str, starter: str
) -> None:
    for rel, text in ((EXAMPLE_REL, example), (CATALOG_REL, catalog), (STARTER_REL, starter)):
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


def selftest() -> int:  # noqa: C901 — one fixture per arm; splitting hides the arm list
    failures: list[str] = []

    def expect_green(root: pathlib.Path, what: str) -> None:
        hits = audit(root)
        if hits:
            failures.append(f"{what} must be GREEN, got: {hits}")

    def expect_red(root: pathlib.Path, what: str, *needles: str) -> None:
        hits = audit(root)
        if not hits:
            failures.append(f"{what} must be RED, got a clean pass")
            return
        blob = " | ".join(hits)
        for needle in needles:
            if needle not in blob:
                failures.append(f"{what} must name {needle!r}, got: {blob}")

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)

        # CONTROL. Every fixture below claims "this mutation turns green into red", which is
        # worth nothing unless the unmutated tree is green.
        write_tree(root, EXAMPLE_OK, CATALOG_OK, STARTER_OK)
        expect_green(root, "the compliant tree (alias join, example superset, null-baseUrl row)")
        if failures:
            print("model-catalogs-single-source SELFTEST FAIL (control):")
            for failure in failures:
                print("  " + failure)
            return 1

        # BORING: one provider, one model, nothing else. The case that gets waved through.
        write_tree(root, EXAMPLE_BORING, CATALOG_BORING, STARTER_BORING)
        expect_green(root, "the boring tree (one provider, one model)")

        # A window mutated in the catalog.
        write_tree(root, EXAMPLE_OK, CATALOG_OK.replace("WINDOW_500K = 500_000L", "WINDOW_500K = 400_000L"), STARTER_OK)
        expect_red(root, "a catalog window that disagrees", "grok-4.6", "400000", "500000")

        # A synthetic id added to the catalog — the mutation this row requires.
        mutated = CATALOG_OK.replace(
            '                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),',
            '                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),\n'
            '                AddModel("grok-fake-9", "Grok Fake 9", WINDOW_1M),',
        )
        if mutated == CATALOG_OK:
            failures.append("the catalog id mutation did not apply")
        write_tree(root, EXAMPLE_OK, mutated, STARTER_OK)
        expect_red(root, "a synthetic catalog id", "grok-fake-9", "absent from")

        # A window mutated in DEFAULT_TOML.
        write_tree(root, EXAMPLE_OK, CATALOG_OK, STARTER_OK.replace("context_window = 500000", "context_window = 262144"))
        expect_red(root, "a starter window that disagrees", "DEFAULT_TOML", "grok-4.6", "262144")

        # A synthetic id added to DEFAULT_TOML.
        write_tree(
            root,
            EXAMPLE_OK,
            CATALOG_OK,
            STARTER_OK.replace(
                'context_window = 500000\n"""',
                'context_window = 500000\n[[providers.xai.models]]\nid = "grok-fake-9"\ncontext_window = 500000\n"""',
            ),
        )
        expect_red(root, "a synthetic starter id", "grok-fake-9", "absent from")

        # A roster whose base_url matches no example provider.
        write_tree(root, EXAMPLE_OK, CATALOG_OK.replace("https://api.x.ai/v1", "https://api.xai.example/v1"), STARTER_OK)
        expect_red(root, "an unjoinable base_url", "matches no provider table")

        # A roster with models but a null base_url: the no-roster disposition stops applying.
        write_tree(
            root,
            EXAMPLE_OK,
            CATALOG_OK.replace(
                '            name = "api-key",\n            baseUrl = null,\n            models = emptyList(),',
                '            name = "api-key",\n            baseUrl = null,\n            models = listOf(\n'
                '                AddModel("mystery", "Mystery", WINDOW_1M),\n            ),',
            ),
            STARTER_OK,
        )
        expect_red(root, "a null-baseUrl roster that grew models", "no base_url")

        # A duplicated base_url in the example: the join key is ambiguous.
        write_tree(
            root,
            EXAMPLE_OK + '\n[providers.xai-clone]\nbase_url = "https://api.x.ai/v1"\n',
            CATALOG_OK,
            STARTER_OK,
        )
        expect_red(root, "a duplicated example base_url", "ambiguous")

        # An empty example.
        write_tree(root, "[daemon]\ncontrol_port = 3096\n", CATALOG_OK, STARTER_OK)
        expect_red(root, "an empty example", "refusing to compare against an empty source")

        # An empty derived roster set.
        write_tree(root, EXAMPLE_OK, "package splice.app.cli\n", STARTER_OK)
        expect_red(root, "a catalog with no rosters", "refusing to pass vacuously")

        # A DEFAULT_TOML marker that cannot be found.
        write_tree(root, EXAMPLE_OK, CATALOG_OK, "package splice.app\npublic object TopologyLoader\n")
        expect_red(root, "a missing starter marker", "DEFAULT_TOML not found")

        # Parser/source disagreement, TOML side: a models header the walker cannot attribute.
        write_tree(
            root,
            EXAMPLE_OK,
            CATALOG_OK,
            STARTER_OK.replace(
                '[providers.xai]\nbase_url',
                '[[providers.xai.models]]\nid = "orphan"\ncontext_window = 1\n[providers.xai]\nbase_url',
            ),
        )
        hits = audit(root)
        if not any("orphan" in hit or "disagree" in hit for hit in hits):
            failures.append(f"a model row the walker cannot attribute must be RED, got: {hits}")

        # Parser/source disagreement, Kotlin side: an AddModel( call outside any AddProfile.
        write_tree(
            root,
            EXAMPLE_OK,
            CATALOG_OK + '\nprivate val orphan = AddModel("orphan", "Orphan", WINDOW_1M)\n',
            STARTER_OK,
        )
        expect_red(root, "an AddModel outside any AddProfile", "the parser and the source disagree")

    if failures:
        print("model-catalogs-single-source SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        "model-catalogs-single-source SELFTEST OK — a compliant tree (alias join by base_url, "
        "example superset, computed no-roster row) and the BORING one-provider/one-model tree are "
        "green; a mutated window and a synthetic id in EITHER emitter, an unjoinable base_url, a "
        "null-baseUrl roster that grew models, a duplicated example base_url, an empty example, an "
        "empty emitter, a missing DEFAULT_TOML marker, and a parser/source count disagreement on "
        "both the TOML and the Kotlin side are all red by name"
    )
    return 0


def report(root: pathlib.Path) -> None:
    example, derived, problems = load_sources(root)
    index, _ = index_by_base_url(example, EXAMPLE_REL)
    print(f"model-catalogs-single-source: {EXAMPLE_REL} is the SOURCE")
    for problem in problems:
        print(f"  UNTRUSTED: {problem}")
    for roster in example:
        print(f"  source   [{roster.provider:12s}] {len(roster.models):2d} rows  {roster.base_url}")
    for rosters in derived:
        if rosters:
            print(f"  {rosters[0].label}")
        for roster in rosters:
            target = index.get(roster.base_url or "")
            if roster.base_url is None and not roster.models:
                state = "no-roster (no base_url, no models)"
            elif target is None:
                state = "NO DISPOSITION (base_url matches no example provider)"
            else:
                expected = {mid: win for mid, win in target.models}
                bad = [
                    mid
                    for mid, win in roster.models
                    if mid not in expected or expected[mid] != win
                ]
                state = f"agrees with [{target.provider}]" if not bad else f"DRIFT: {', '.join(bad)}"
            print(f"    [{roster.provider:12s}] {len(roster.models):2d} rows  {state}")


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    root = ROOT
    for arg in sys.argv[1:]:
        if arg not in {"check", "report", "--selftest"} and not arg.startswith("-"):
            root = pathlib.Path(arg)
            break
    if not root.exists():
        print("model-catalogs-single-source: tree missing", file=sys.stderr)
        return 1
    root = root.resolve()
    if "report" in sys.argv:
        report(root)
        return 0
    problems = audit(root)
    if problems:
        print("model-catalogs-single-source RED:")
        for problem in problems:
            print("  " + problem)
        return 1
    print(
        "model-catalogs-single-source GREEN: every model row AddProfileCatalog.kt and "
        f"TopologyLoader.DEFAULT_TOML declare exists in {EXAMPLE_REL} with the same context window"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
