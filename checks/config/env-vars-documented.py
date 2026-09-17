#!/usr/bin/env python3
"""V4-87 — every environment variable splice READS has a disposition in the documentation.

WHY THIS EXISTS. An env var is the highest-precedence configuration layer splice has: it
beats state config.json, it beats the TOML, it beats the knob default. It is also the only
layer with no schema, no `splice doctor` line and no file an operator can diff — so an
undocumented one is a setting that silently wins and that nobody can discover. The
2026-09-17 architecture audit (C rows 1, 5, 12) counted the gap by hand; a hand count
closes the instance, this closes the class. A var read tomorrow is in scope with no edit
to this file.

Twin of checks/config/knob-keys-documented.py (the TOML half of the same row) and of
checks/config/quirks-keys-documented.py (V4-44), whose guards, disposition vocabulary and
selftest idiom this mirrors. Separate files rather than one parameterised one because the
denominators are parsed out of structurally different source, and sharing would mean
editing a wall outside this row's fence.

THE SEAM. kt-no-system-getenv forces every environment read in the gateway through ONE
port: `splice.core.util.EnvReader`, a `(String) -> String?` fun interface threaded by
constructor injection (production wires `System::getenv`; a test pins a hermetic map).
Main sources name it in exactly two spellings, `env` and `envReader`, measured across
gateway/*/src/main. That port is what makes this wall possible at all: without it the
denominator would be "every string anywhere", which is not a denominator.

DENOMINATOR, from the SOURCE, never a hand list. Three resolution kinds, all read off disk:
  literal — a string literal at a seam call site: `env("PATH")`, `envReader("SPLICE_CONFIG")`,
            either with an explicit `.invoke(...)`;
  const   — a file-local `const val NAME = "LITERAL"` passed to the seam, resolved to its
            literal (SetupDetection's OPENROUTER_KEY is the live case);
  knob    — every name in a `Knob` entry's `envNames` list in Knob.kt. These are read
            through the same seam, at ConfigService's
            `knob.envNames.firstNotNullOfOrNull { name -> envReader(name) }`, so they are
            reads exactly as much as a literal is, and they are the largest family by far.

FOUR GUARDS refuse a vacuous pass:
  - a direct `System.getenv("X")` CALL anywhere in main sources is red by file:line. Not
    because the ast-grep rule already bans it in non-config code — it does, and exempts
    core/config — but because a direct call is a read this scan CANNOT see, so its
    existence means the denominator is incomplete and no green from that run is true.
    Every site on the tree today is a `System::getenv` REFERENCE handed to an EnvReader,
    which is injection, not a read;
  - zero seam call sites found is a failure: it means the scan lost the seam, not that the
    gateway stopped reading the environment;
  - zero names in Knob.kt's envNames is a failure, for the same reason;
  - zero names overall is a failure rather than a pass.

COMPUTED ARGUMENTS — what this wall does NOT cover, named rather than implied. Four seam
sites pass a name the parser cannot resolve, and every run prints them by file:line under
COMPUTED. They are excluded WITH A REASON, which is the disposition: the name they read is
not splice's to document.
  - ApiKeyAuthProvider / AddChecks / LoginIo read `envVar`, which is whatever the OPERATOR
    wrote in `auth = { kind = "api-key", env = "..." }`. The name is operator-authored, so
    there is no splice-owned name to document;
  - SetupHeads reads `profiles.apiKeyEnv(headKey)` = `HEADKEY.uppercase() + "_API_KEY"`, a
    family whose members depend on the head keys in a config this checker has never seen.
  - ConfigService's `envReader(name)` over `knob.envNames` is NOT in this class: the names
    are enumerable from Knob.kt and are in the denominator as the `knob` kind above.
A site moving from computed to literal lands in the denominator automatically. A NEW
computed site appears in the COMPUTED list, where a reviewer sees it; it does not fail,
because a computed argument yields no name and a wall cannot demand the documentation of a
string that does not exist.

DISPOSITION. Every name must be accounted for, in one of two forms:
  documented — the name appears inside the ENVIRONMENT VARIABLES header block of
               config/splice.example.toml. A BLOCK, not merely the file, because the row
               asks the doc to state the precedence chain once and describe each var
               against it — and because a bare file-wide token search would count a var
               mentioned in passing inside an unrelated provider comment. The block is the
               sentinel line `# ENVIRONMENT VARIABLES` plus the contiguous run of comment
               and blank lines after it, and it must itself contain the precedence chain
               `env > TOML > default`. A missing block is red; a block without the chain is
               red;
  retired    — a machine-readable marker, `# retired: <NAME> — <reason>`, anywhere in the
               surface, whose reason must be non-empty: a retirement with no written reason
               is an absence wearing a label and fails BY NAME like any other absence.
Absence is not a disposition. Matching is CASE-SENSITIVE: `no_proxy` and `NO_PROXY` are two
variables and the gateway reads both.

NOT CAUGHT, and why.
  A var splice WRITES rather than reads. LaunchService plants ~8 `CLAUDE_CODE_*` values into
  the client's environment (buildEnv). Those are outbound, and this row's denominator is the
  READ seam, so they are out of scope here by definition — worth its own wall, not worth
  quietly widening this one's denominator to a mix of two directions.
  A var read outside the JVM — install.sh, the shim, packaging. Different source language
  and a different seam; naming them here would be a hand list.
  A seam-shaped identifier that is not the seam. The scan keys off the two spellings the
  port is threaded under, `env` and `envReader`, measured to be the only two in main
  sources. A local `env` of some other type, called with a literal, would be counted. What
  is already handled: the same text inside a STRING literal (McpSharing.kt:171 holds the
  message "malformed env (expected string values)", which a first draft of this scan
  reported as a computed seam site) and inside a comment.
  Whether the documented prose is CORRECT. Only the name token and the presence of the
  precedence chain are machine-checkable; that a var's description is true is not.

SELFTEST. --selftest builds a temp tree and proves BOTH directions: GREEN on the compliant
form (literal, const, knob alias and a reasoned retirement), GREEN WITH A COUNT on the
boring one-var tree; RED naming a synthetic var injected into a temp copy of the source
(the mutation this row requires), RED on a synthetic knob alias, RED on an unreasoned
retirement, RED when the header block is absent, RED when the block exists but omits the
precedence chain, RED when a var is named only OUTSIDE the block, RED on a direct
System.getenv call, RED on zero seam sites, and RED on zero knob aliases.
"""
from __future__ import annotations

import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]

# The knob half of the denominator. Fixed path on purpose: a checker that silently loses
# its source is a checker that passes.
KNOB_REL = "gateway/core/src/main/kotlin/splice/core/config/Knob.kt"

# Where main sources live. The seam scan walks these.
MAIN_GLOBS = ("gateway/*/src/main/**/*.kt",)

# The one file an operator copies to write a config.
SURFACE = "config/splice.example.toml"

# The header block that documents the environment layer.
# The sentinel tolerates the file's own box-drawing decoration
# (`# ── ENVIRONMENT VARIABLES ──────`) but nothing that carries meaning: only `#`,
# whitespace and rule characters may sit between the comment marker and the phrase, so a
# sentence that merely mentions environment variables cannot pass as the block header.
BLOCK_SENTINEL = re.compile(
    r"^[ \t]*#[ \t#\u2500\u2501\u2550=\u2014\u2013*_.-]*ENVIRONMENT VARIABLES\b", re.IGNORECASE
)
PRECEDENCE_CHAIN = re.compile(r"env[ \t]*>[ \t]*TOML[ \t]*>[ \t]*default", re.IGNORECASE)

# The seam's two spellings in main sources, called or `.invoke`d.
SEAM_CALL = re.compile(r"(?<![\w.])(env|envReader)[ \t]*(?:\.invoke[ \t]*)?\(")
# A direct read the seam scan cannot see. `System::getenv` (a reference handed to an
# EnvReader) is injection and deliberately NOT matched.
DIRECT_GETENV = re.compile(r"(?<![\w:])(?:(?:java\.lang\.)?System\s*\.\s*)?getenv[ \t]*\(")
CONST_DECL = re.compile(r'\bconst\s+val\s+([A-Za-z_]\w*)\s*(?::[^=]+)?=\s*"([^"]*)"')
STRING_ARG = re.compile(r'^\s*"([^"]*)"\s*$')
IDENT_ARG = re.compile(r"^\s*([A-Za-z_]\w*)\s*$")

ENUM_DECL = re.compile(r"\benum\s+class\s+Knob\b")
ENTRY_HEAD = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*\(", re.DOTALL)


class EnvName:
    """One env var name and the first place it is read."""

    def __init__(self, name: str, kind: str, where: str) -> None:
        self.name = name
        self.kind = kind
        self.where = where


def blank_comments(source: str) -> str:
    """Replace // and /* */ comment bodies with spaces, preserving length and newlines.

    Length-preserving on purpose: every finding this checker reports is a file:line, and a
    stripper that deleted bytes would report the wrong line. String literals are left
    intact — they are the denominator.
    """
    out = list(source)
    i = 0
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
            end = source.find("\n", i)
            end = len(source) if end < 0 else end
            for j in range(i, end):
                out[j] = " "
            i = end
            continue
        if ch == "/" and i + 1 < len(source) and source[i + 1] == "*":
            end = source.find("*/", i + 2)
            end = len(source) if end < 0 else end + 2
            for j in range(i, end):
                if out[j] != "\n":
                    out[j] = " "
            i = end
            continue
        i += 1
    return "".join(out)


def string_spans(code: str) -> list[tuple[int, int]]:
    """(start, end) of every string literal body in comment-blanked Kotlin.

    blank_comments deliberately leaves literals intact — they ARE the denominator — which
    means a literal can itself contain something that looks like a seam call. It does:
    McpSharing.kt:171 holds the message "malformed env (expected string values)", and a
    naive regex reported it as a computed seam site. Matches starting inside one of these
    spans are dropped.
    """
    spans: list[tuple[int, int]] = []
    i = 0
    while i < len(code):
        ch = code[i]
        if ch in "\"'":
            quote = ch
            j = i + 1
            escape = False
            while j < len(code):
                if escape:
                    escape = False
                elif code[j] == "\\":
                    escape = True
                elif code[j] == quote:
                    break
                j += 1
            spans.append((i + 1, min(j, len(code))))
            i = j + 1
            continue
        i += 1
    return spans


def inside_string(spans: list[tuple[int, int]], index: int) -> bool:
    return any(start <= index < end for start, end in spans)


def close_paren(text: str, open_index: int) -> int | None:
    """Index of the paren closing the one at open_index, string-aware."""
    depth = 0
    i = open_index
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
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def main_sources(root: pathlib.Path) -> list[pathlib.Path]:
    found: list[pathlib.Path] = []
    for pattern in MAIN_GLOBS:
        found.extend(sorted(root.glob(pattern)))
    return found


def scan_seam(root: pathlib.Path) -> tuple[list[EnvName], list[str], list[str]]:
    """Return (names, computed_sites, problems) from the EnvReader seam in main sources."""
    names: list[EnvName] = []
    computed: list[str] = []
    problems: list[str] = []
    sites = 0
    for path in main_sources(root):
        rel = path.relative_to(root).as_posix()
        raw = path.read_text(encoding="utf-8")
        code = blank_comments(raw)
        spans = string_spans(code)
        constants = {m.group(1): m.group(2) for m in CONST_DECL.finditer(code)}

        for direct in DIRECT_GETENV.finditer(code):
            if inside_string(spans, direct.start()):
                continue
            problems.append(
                f"DIRECT READ OUTSIDE THE SEAM: {rel}:{line_of(code, direct.start())} calls "
                "getenv() directly — this scan cannot see the name it reads, so the "
                "denominator is incomplete and no green from this run is true. Inject an "
                "EnvReader instead (kt-no-system-getenv)"
            )

        for call in SEAM_CALL.finditer(code):
            if inside_string(spans, call.start()):
                continue
            open_index = code.index("(", call.end() - 1)
            end = close_paren(code, open_index)
            if end is None:
                problems.append(
                    f"{rel}:{line_of(code, call.start())}: a seam call's argument list does "
                    "not close — the argument cannot be read"
                )
                continue
            arg = code[open_index + 1 : end]
            # `EnvReader(env)` / `foo(envReader)` style pass-throughs are not reads.
            if IDENT_ARG.match(arg) and IDENT_ARG.match(arg).group(1) in {"env", "envReader"}:
                continue
            sites += 1
            where = f"{rel}:{line_of(code, call.start())}"
            literal = STRING_ARG.match(arg)
            if literal is not None:
                names.append(EnvName(literal.group(1), "literal", where))
                continue
            ident = IDENT_ARG.match(arg)
            if ident is not None and ident.group(1) in constants:
                names.append(EnvName(constants[ident.group(1)], "const", where))
                continue
            computed.append(f"{where}  {call.group(1)}({arg.strip()})")
    if sites == 0:
        problems.append(
            "no EnvReader seam call site found in main sources — the scan has lost the "
            "seam, and a gateway that reads no environment is not the gateway this wall "
            "was written against"
        )
    return names, computed, problems


def split_top_level(body: str, separator: str = ",") -> list[str]:
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


def scan_knob_aliases(root: pathlib.Path) -> tuple[list[EnvName], list[str]]:
    """Every name in a Knob entry's envNames list — read at ConfigService's
    `knob.envNames.firstNotNullOfOrNull { name -> envReader(name) }`."""
    path = root / KNOB_REL
    if not path.exists():
        return [], [
            f"{KNOB_REL}: missing — its envNames lists are the largest part of the "
            "denominator, so its absence cannot pass"
        ]
    raw = path.read_text(encoding="utf-8")
    code = blank_comments(raw)
    decl = ENUM_DECL.search(code)
    if decl is None:
        return [], [
            f"{KNOB_REL}: no `enum class Knob` declaration found — the enum has moved or "
            "been renamed, so this run has no knob denominator and must not pass"
        ]
    open_index = code.index("(", decl.end())
    ctor_end = close_paren(code, open_index)
    if ctor_end is None:
        return [], [f"{KNOB_REL}: the Knob primary constructor could not be parsed"]
    brace = code.index("{", ctor_end)
    body_end = close_paren(code, brace)
    if body_end is None:
        return [], [f"{KNOB_REL}: the Knob enum body could not be parsed"]
    entries_text = split_top_level(code[brace + 1 : body_end], ";")[0]

    names: list[EnvName] = []
    problems: list[str] = []
    for part in split_top_level(entries_text, ","):
        head = ENTRY_HEAD.match(part)
        if head is None:
            continue
        entry = head.group(1)
        args_open = part.index("(", head.end() - 1)
        args_end = close_paren(part, args_open)
        if args_end is None:
            problems.append(f"{KNOB_REL}: {entry} argument list could not be parsed")
            continue
        listed = [
            arg for arg in split_top_level(part[args_open + 1 : args_end], ",")
            if arg.strip().startswith("listOf(")
        ]
        if not listed:
            problems.append(
                f"{KNOB_REL}: {entry} declares no envNames listOf(...) — its env aliases "
                "cannot be read from the source"
            )
            continue
        inner = listed[0].strip()
        inner_end = close_paren(inner, inner.index("("))
        for alias in split_top_level(inner[inner.index("(") + 1 : inner_end], ","):
            literal = STRING_ARG.match(alias)
            if literal is None:
                if alias.strip():
                    problems.append(
                        f"{KNOB_REL}: {entry} lists a non-literal env alias "
                        f"({alias.strip()!r}) — the name cannot be read from the source"
                    )
                continue
            names.append(EnvName(literal.group(1), "knob", f"Knob.{entry}"))
    if not names:
        problems.append(
            f"{KNOB_REL}: parsed 0 env aliases — refusing to pass vacuously, because a "
            "green over an empty denominator is what this wall exists to prevent"
        )
    return names, problems


def denominator(root: pathlib.Path) -> tuple[dict[str, EnvName], list[str], list[str]]:
    """name -> first read site, plus the computed residue and any untrusted-parse problems."""
    seam_names, computed, problems = scan_seam(root)
    knob_names, knob_problems = scan_knob_aliases(root)
    problems.extend(knob_problems)
    names: dict[str, EnvName] = {}
    for env in seam_names + knob_names:
        names.setdefault(env.name, env)
    if not names and not problems:
        problems.append(
            "parsed 0 environment variable names — refusing to pass vacuously over an "
            "empty denominator"
        )
    return names, computed, problems


def header_block(text: str) -> tuple[str | None, int]:
    """The ENVIRONMENT VARIABLES block: the sentinel line plus the contiguous run of
    comment and blank lines after it. Returns (block text, sentinel line number)."""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if BLOCK_SENTINEL.match(line):
            block = [line]
            for following in lines[index + 1 :]:
                stripped = following.strip()
                if stripped == "" or stripped.startswith("#"):
                    block.append(following)
                    continue
                break
            return "\n".join(block), index + 1
    return None, 0


def name_token(name: str) -> re.Pattern[str]:
    """The name as a whole token. CASE-SENSITIVE: `no_proxy` and `NO_PROXY` are two
    variables and the gateway reads both."""
    return re.compile(r"(?<![\w-])" + re.escape(name) + r"(?![\w-])")


def retired_reason(text: str, name: str) -> tuple[bool, str]:
    pattern = re.compile(
        r"^[ \t]*#[ \t]*retired:[ \t]*" + re.escape(name) + r"(?![A-Za-z0-9_-])(.*)$",
        re.MULTILINE,
    )
    match = pattern.search(text)
    if match is None:
        return False, ""
    return True, match.group(1).strip().lstrip("—:-").strip()


def audit(root: pathlib.Path) -> list[str]:
    names, _computed, problems = denominator(root)
    if problems:
        # An untrusted parse is terminal: a disposition report over a denominator that
        # cannot be trusted would be a green wearing the wrong number.
        return problems

    surface = root / SURFACE
    if not surface.exists():
        return [
            f"{SURFACE}: disposition surface missing — a surface that cannot be read "
            "cannot document anything"
        ]
    text = surface.read_text(encoding="utf-8")
    block, line = header_block(text)
    if block is None:
        problems.append(
            f"{SURFACE}: no `# ENVIRONMENT VARIABLES` header block — environment is the "
            "highest-precedence config layer and the file that teaches the config does not "
            f"mention it. Add the block, state the precedence chain (env > TOML > default) "
            f"in it, and describe each of the {len(names)} variables splice reads"
        )
        block = ""
    elif PRECEDENCE_CHAIN.search(block) is None:
        problems.append(
            f"{SURFACE}:{line}: the ENVIRONMENT VARIABLES block does not state the "
            "precedence chain — a var list that does not say env beats TOML beats default "
            "leaves the one fact an operator needs unwritten. Write `env > TOML > default`"
        )

    for name in sorted(names):
        env = names[name]
        marked, reason = retired_reason(text, name)
        if marked:
            if not reason:
                problems.append(
                    f"{SURFACE}: {name} is retired with NO reason — a retirement without a "
                    "written reason is an absence wearing a label"
                )
            continue
        if name_token(name).search(block):
            continue
        outside = "" if name_token(name).search(text) else " (not named anywhere in the file)"
        problems.append(
            f"NO DISPOSITION: {name} ({env.kind}, read at {env.where}) is not documented in "
            f"the ENVIRONMENT VARIABLES block of {SURFACE}{outside}; document it there "
            f"against the precedence chain, or retire it with `# retired: {name} — <reason>`"
        )
    return problems


# ── selftest fixtures ─────────────────────────────────────────────────────────────────

SEAM_SOURCE_REL = "gateway/app/src/main/kotlin/splice/app/cli/Fixture.kt"
COMPUTED_SOURCE_REL = "gateway/app/src/main/kotlin/splice/app/cli/Computed.kt"

SEAM_SOURCE = '''package splice.app.cli

internal object Fixture {
    fun paths(env: EnvReader = EnvReader(System::getenv)): String? {
        // env("COMMENTED_OUT_VAR") — a commented read is not a read.
        val config = env("SPLICE_CONFIG")
        val openRouter = env(OPENROUTER_KEY)
        val explicit = env.invoke("XDG_CONFIG_HOME")
        return config ?: openRouter ?: explicit
    }
}

private const val OPENROUTER_KEY = "OPENROUTER_API_KEY"
'''

COMPUTED_SOURCE = '''package splice.app.cli

internal object Computed {
    fun key(envVar: String, env: EnvReader): String? = env(envVar)

    // The McpSharing.kt:171 shape: a seam-shaped call inside a STRING literal.
    fun complain(): String = "malformed env (expected string values)"
}
'''

KNOB_SOURCE = '''package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
) {
    PORT("port", KnobKind.NUMBER, listOf("CODEX_PROXY_PORT")),
    // A prose comment with (parens) and "quotes" a naive walk would choke on.
    DEBUG("debug", KnobKind.BOOL, listOf("CLAUDEX_DEBUG", "CODEX_PROXY_DEBUG")),
    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT")),
}
'''

COMPLIANT_DOC = '''[daemon]
control_port = 3096

# ── ENVIRONMENT VARIABLES ──────────────────────────────────────────────────────
# Precedence: env > TOML > default. An env var set in the daemon's environment wins
# over anything in this file.
#   SPLICE_CONFIG        absolute path to splice.toml; overrides the XDG lookup
#   XDG_CONFIG_HOME      base for the default config lookup (~/.config when unset)
#   OPENROUTER_API_KEY   OpenRouter bearer, read by `splice setup` detection
#   CODEX_PROXY_PORT     the codex head's listen port (knob `port`)
#   CLAUDEX_DEBUG        verbose daemon logging (knob `debug`)
# retired: CODEX_PROXY_DEBUG — superseded by CLAUDEX_DEBUG in v0.3.0; still read as an alias
# retired: GROK_PROXY_PORT — the grok head takes its port from [heads.*.port]

[providers.openrouter]
dialect = "openai-chat"
'''

NO_BLOCK_DOC = COMPLIANT_DOC.replace(
    "# ── ENVIRONMENT VARIABLES ──────────────────────────────────────────────────────\n", ""
)

NO_CHAIN_DOC = COMPLIANT_DOC.replace(
    "# Precedence: env > TOML > default. An env var set in the daemon's environment wins\n"
    "# over anything in this file.\n",
    "# Set these in the daemon's environment.\n",
)

# SPLICE_CONFIG named only OUTSIDE the block — past a live TOML line, which is what ends
# the block: the drift a file-wide token search misses.
OUTSIDE_BLOCK_DOC = (
    COMPLIANT_DOC.replace(
        "#   SPLICE_CONFIG        absolute path to splice.toml; overrides the XDG lookup\n", ""
    )
    + "# SPLICE_CONFIG is mentioned down here, in an unrelated provider comment.\n"
)

RETIRED_NOREASON_DOC = COMPLIANT_DOC.replace(
    "# retired: GROK_PROXY_PORT — the grok head takes its port from [heads.*.port]",
    "# retired: GROK_PROXY_PORT —",
)

# The boring case: one var, one knob alias, documented.
BORING_SEAM = '''package splice.app.cli

internal object Fixture {
    fun path(env: EnvReader): String? = env("SPLICE_CONFIG")
}
'''

BORING_KNOB = '''package splice.core.config

public enum class Knob(
    public val key: String,
    public val envNames: List<String>,
) {
    PORT("port", listOf("CODEX_PROXY_PORT")),
}
'''

BORING_DOC = '''# ENVIRONMENT VARIABLES
# Precedence: env > TOML > default.
#   SPLICE_CONFIG      absolute path to splice.toml
#   CODEX_PROXY_PORT   the codex head's listen port
'''

DIRECT_GETENV_SOURCE = '''package splice.app.cli

internal object Sneaky {
    fun home(): String? = System.getenv("HOME")
}
'''

NO_SEAM_SOURCE = '''package splice.app.cli

internal object Inert {
    fun nothing(): String = "no environment here"
}
'''

EMPTY_KNOB_SOURCE = '''package splice.core.config

public enum class Knob(
    public val key: String,
    public val envNames: List<String>,
) {
}
'''


def write_tree(
    root: pathlib.Path,
    doc: str,
    seam: str = SEAM_SOURCE,
    knob: str = KNOB_SOURCE,
    computed: str | None = COMPUTED_SOURCE,
    extra: tuple[str, str] | None = None,
) -> None:
    for rel, content in [(SEAM_SOURCE_REL, seam), (KNOB_REL, knob)]:
        (root / pathlib.Path(rel).parent).mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(content, encoding="utf-8")
    path = root / COMPUTED_SOURCE_REL
    if computed is None:
        path.unlink(missing_ok=True)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(computed, encoding="utf-8")
    if extra is not None:
        extra_path = root / extra[0]
        extra_path.parent.mkdir(parents=True, exist_ok=True)
        extra_path.write_text(extra[1], encoding="utf-8")
    (root / pathlib.Path(SURFACE).parent).mkdir(parents=True, exist_ok=True)
    (root / SURFACE).write_text(doc, encoding="utf-8")


def selftest() -> int:
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)

        write_tree(root, COMPLIANT_DOC)
        hits = audit(root)
        if hits:
            failures.append(
                "compliant tree must be GREEN (literal, const, .invoke, knob alias, two "
                "reasoned retirements): " + "; ".join(hits)
            )
        names, computed, problems = denominator(root)
        if problems:
            failures.append(f"the compliant denominator must be trusted, got: {problems}")
        if sorted(names) != [
            "CLAUDEX_DEBUG",
            "CODEX_PROXY_DEBUG",
            "CODEX_PROXY_PORT",
            "GROK_PROXY_PORT",
            "OPENROUTER_API_KEY",
            "SPLICE_CONFIG",
            "XDG_CONFIG_HOME",
        ]:
            failures.append(f"denominator wrong: {sorted(names)}")
        if "COMMENTED_OUT_VAR" in names:
            failures.append("a read inside a comment must not enter the denominator")
        if len(computed) != 1 or "env(envVar)" not in computed[0]:
            failures.append(
                "the computed residue must be exactly the one real computed site — a "
                "seam-shaped call inside a string literal is not a seam site — got: "
                f"{computed}"
            )

        # The BORING case: one literal, one knob alias, and the count must come out at two.
        write_tree(root, BORING_DOC, seam=BORING_SEAM, knob=BORING_KNOB, computed=None)
        hits = audit(root)
        if hits:
            failures.append(f"the one-var tree must be GREEN, got: {hits}")
        names, computed, problems = denominator(root)
        if problems or sorted(names) != ["CODEX_PROXY_PORT", "SPLICE_CONFIG"] or computed:
            failures.append(
                f"the boring tree must parse to exactly 2 names and no computed residue, "
                f"got {sorted(names)} computed={computed} problems={problems}"
            )

        # The mutation this row requires: a synthetic literal read injected into a temp copy.
        mutated = SEAM_SOURCE.replace(
            'val config = env("SPLICE_CONFIG")',
            'val config = env("SPLICE_FAKE_NEW_VAR") ?: env("SPLICE_CONFIG")',
        )
        if mutated == SEAM_SOURCE:
            failures.append("the seam mutation did not apply")
        else:
            write_tree(root, COMPLIANT_DOC, seam=mutated)
            hits = audit(root)
            if not any("SPLICE_FAKE_NEW_VAR" in hit for hit in hits):
                failures.append(f"a synthetic literal read must be RED BY NAME, got: {hits}")

        mutated_knob = KNOB_SOURCE.replace(
            'GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT")),',
            'GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT", "SPLICE_FAKE_ALIAS")),',
        )
        if mutated_knob == KNOB_SOURCE:
            failures.append("the knob mutation did not apply")
        else:
            write_tree(root, COMPLIANT_DOC, knob=mutated_knob)
            hits = audit(root)
            if not any("SPLICE_FAKE_ALIAS" in hit for hit in hits):
                failures.append(f"a synthetic knob alias must be RED BY NAME, got: {hits}")

        write_tree(root, RETIRED_NOREASON_DOC)
        hits = audit(root)
        if not any("GROK_PROXY_PORT" in hit and "NO reason" in hit for hit in hits):
            failures.append(f"a retirement with an empty reason must be RED by name, got: {hits}")
        if sum(1 for hit in hits if "GROK_PROXY_PORT" in hit) != 1:
            failures.append(
                f"an unreasoned retirement is ONE problem, not a duplicate pair, got: {hits}"
            )

        write_tree(root, NO_BLOCK_DOC)
        hits = audit(root)
        if not any("no `# ENVIRONMENT VARIABLES` header block" in hit for hit in hits):
            failures.append(f"a missing header block must be RED, got: {hits}")

        write_tree(root, NO_CHAIN_DOC)
        hits = audit(root)
        if not any("precedence chain" in hit for hit in hits):
            failures.append(f"a block without the precedence chain must be RED, got: {hits}")

        write_tree(root, OUTSIDE_BLOCK_DOC)
        hits = audit(root)
        if not any("NO DISPOSITION: SPLICE_CONFIG" in hit for hit in hits):
            failures.append(
                f"a var named only OUTSIDE the block must be RED by name, got: {hits}"
            )
        if any("not named anywhere in the file" in hit and "SPLICE_CONFIG" in hit for hit in hits):
            failures.append(
                "a var named outside the block must NOT be reported as absent from the file"
            )

        write_tree(
            root,
            COMPLIANT_DOC,
            extra=("gateway/app/src/main/kotlin/splice/app/cli/Sneaky.kt", DIRECT_GETENV_SOURCE),
        )
        hits = audit(root)
        if not any("DIRECT READ OUTSIDE THE SEAM" in hit for hit in hits):
            failures.append(f"a direct System.getenv call must be RED, got: {hits}")
        (root / "gateway/app/src/main/kotlin/splice/app/cli/Sneaky.kt").unlink()

        write_tree(root, COMPLIANT_DOC, seam=NO_SEAM_SOURCE, computed=None)
        hits = audit(root)
        if not any("has lost the seam" in hit for hit in hits):
            failures.append(f"zero seam call sites must be RED, got: {hits}")

        write_tree(root, COMPLIANT_DOC, knob=EMPTY_KNOB_SOURCE)
        hits = audit(root)
        if not any("refusing to pass vacuously" in hit for hit in hits):
            failures.append(f"zero knob aliases must be RED, got: {hits}")

        write_tree(root, COMPLIANT_DOC)
        (root / SURFACE).unlink()
        hits = audit(root)
        if not any("disposition surface missing" in hit for hit in hits):
            failures.append(f"a missing surface must be RED, got: {hits}")

    if failures:
        print("env-vars-documented SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        "env-vars-documented SELFTEST OK — a literal read, a const read, an explicit "
        ".invoke, a knob alias and two reasoned retirements are green, and the one-var tree "
        "is green with a count of 2; a synthetic literal read, a synthetic knob alias, an "
        "unreasoned retirement, a missing header block, a block without the precedence "
        "chain, a var named only outside the block, a direct System.getenv call, zero seam "
        "sites, zero knob aliases and a missing surface are all red by name"
    )
    return 0


def report(root: pathlib.Path) -> None:
    names, computed, problems = denominator(root)
    surface = root / SURFACE
    text = surface.read_text(encoding="utf-8") if surface.exists() else ""
    block, line = header_block(text)
    print(
        f"env-vars-documented: {len(names)} env var names read through the EnvReader seam "
        f"({KNOB_REL} envNames + main-source literals)"
    )
    for problem in problems:
        print(f"  UNTRUSTED: {problem}")
    print(
        f"  header block: {'present at ' + SURFACE + ':' + str(line) if block else 'ABSENT'}"
        + (
            ""
            if not block
            else f", precedence chain {'stated' if PRECEDENCE_CHAIN.search(block) else 'MISSING'}"
        )
    )
    documented = 0
    for name in sorted(names):
        env = names[name]
        marked, reason = retired_reason(text, name)
        if marked and reason:
            where = "retired"
        elif block and name_token(name).search(block):
            where = "block"
        else:
            where = "NO DISPOSITION"
        if where != "NO DISPOSITION":
            documented += 1
        print(f"  {name:32s} {env.kind:8s} {env.where:60s} {where}")
    print(f"  COMPUTED seam arguments ({len(computed)}) — excluded, see this file's header:")
    for site in computed:
        print(f"    {site}")
    missing = sorted(name for name in names if name not in ())
    undocumented = [
        name
        for name in sorted(names)
        if not (retired_reason(text, name)[1] or (block and name_token(name).search(block)))
    ]
    print(
        f"  documented {documented}/{len(names)}; undocumented {len(undocumented)}: "
        + ", ".join(undocumented)
    )
    del missing


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    root = ROOT
    for arg in sys.argv[1:]:
        if arg not in {"check", "report", "--selftest"} and not arg.startswith("-"):
            root = pathlib.Path(arg)
            break
    if not root.exists():
        print("env-vars-documented: tree missing", file=sys.stderr)
        return 1
    root = root.resolve()
    if "report" in sys.argv:
        report(root)
        return 0
    problems = audit(root)
    if problems:
        print("env-vars-documented RED:")
        for problem in problems:
            print("  " + problem)
        return 1
    print(
        "env-vars-documented GREEN: every env var read through the EnvReader seam has a "
        f"disposition in the ENVIRONMENT VARIABLES block of {SURFACE}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
