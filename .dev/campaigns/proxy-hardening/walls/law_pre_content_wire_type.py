#!/usr/bin/env python3
"""WALL for the PRE-CONTENT-WIRE-TYPE law (V4-78 / V4-79 / V4-81 / V4-82) — the pre-content rule is
applied ONCE, at the emitter seam, and nothing outside that seam may put a client-terminal error
type into an in-band error envelope.

THE LAW  Claude Code 2.1.257 retries an IN-BAND SSE error event only when its body carries
      overloaded_error (a real 429/529 is retried by STATUS, and neither is ours to send once the
      200 is committed at TurnStreamer.stream). So a pre-content `api_error` or `rate_limit_error`
      ENDS the session where a retry would have healed it. The rule lives as a VALUE —
      `PreContentWireType.of(type, contentReachedClient, permanent)` — and since V4-81 it is
      APPLIED IN EXACTLY ONE PLACE: `SseEmitter.emitError`, the only code that can write an error
      frame and the only code that owns the question the rule turns on (has content reached this
      client yet?).

WHY THE SEAM IS THE INVARIANT (V4-82 part 2)  V4-79 asked every ENDING to call the rule, and the
      wall's first form graded the first argument of every `emitError(` call. That form is now both
      wrong and weaker. Wrong: after V4-81 the endings pass their REAL type (TurnConnEnd,
      TurnEnding, TurnKnownEnd, TurnPipeline) and the emitter routes it — demanding a routing at
      those sites would demand code that must not exist. Weaker: four hand copies of one rule is
      the defect V4-81 removed, and a per-site allowlist cannot see it. So the wall now checks the
      SEAM and the ABSENCE of copies, which is one invariant instead of N and is what makes a new
      ending unable to skip the rule — there is no other way to write an error frame.

WHAT IT CHECKS
      (a) SEAM        `wire/SseEmitter.kt` declares `object PreContentWireType` with a `fun of(`,
                      and EVERY `fun emitError(` in that file applies it — exactly once, as a WHOLE
                      expression (see below), with the routed value the one that reaches the wire.
      (b) NO COPIES   no OTHER main source under `gateway/*/src/main/kotlin` calls
                      `PreContentWireType.of(`. This is the check that would have caught V4-79's
                      four hand copies, and it is why the rule cannot be re-derived slightly
                      differently at the next ending.
      (c) GOVERNED    the governed set is read out of the rule object's own body (the types it
                      COMPARES AGAINST), never re-authored here, and RATCHETED: it must still
                      contain RATE_LIMIT and API_ERROR, so narrowing the rule to make this wall go
                      green is itself red.
      (d) ENVELOPES   every other in-band error-envelope construction in main sources — an
                      `errorEnvelope(`, or an `ErrorType`/failure `.wireName` handed to a JSON
                      writer — is red BY NAME unless it is the seam or the ONE written exemption.

WHOLE EXPRESSIONS, NOT SUBSTRINGS (V4-82 part 1, /code-review 2026-09-17 finding 6). The grader
      asked `"PreContentWireType.of(" in expr`, so `outcome.type ?: ErrorType.OVERLOADED`,
      `if (c) PreContentWireType.of(t, c) else ErrorType.API_ERROR` and
      `PreContentWireType.of(t, contentReachedClient = true)` all passed GREEN while putting a
      governed type on a pre-content wire. A routing is now graded ENTIRE: the of(…) call must BE
      the expression (its balanced close-paren is the expression's last character), and its content
      flag must not be the literal `true` — pinned true, the rule returns the type UNCHANGED, so
      the routing is spelled and does nothing. The flag's NAME and arity come from the rule's own
      `fun of(` signature, so a rename or a new parameter widens the wall in the same commit.

THE ONE EXEMPTION, WRITTEN DOWN  `wire/CollectingTerminal.kt` builds error envelopes and is NOT
      routed, deliberately: it is the COLLECT path (`stream:false`). There the failure's real HTTP
      STATUS carries the retry semantics — a buffered 429 stays 429 with its rate-limit headers and
      a buffered api_error stays 502 — and the client retries by status, not by body. Remapping
      there would answer 529 to every buffered failure, which V4-81 finding 3 measured as the
      regression the seam move had to avoid. The exemption is PINNED to that path AND to the file
      still being that terminal: a file at that path which no longer declares `CollectingTerminal`
      is red, so the exemption cannot outlive the reason for it.

FAIL-CLOSED  Red — never a vacuous pass — when: the seam file is missing; it declares no
      `object PreContentWireType` with a `fun of(`; the derived governed set lost either founding
      type; the rule's signature is unreadable; the seam declares no `emitError`; an `emitError`
      does not apply the rule, applies it more than once, applies it inside a mixed expression,
      pins the content flag to `true`, or writes something OTHER than the routed value to the wire;
      the seam writes no wire type at all; no source roots resolve; or the whole scan finds ZERO
      error-envelope constructions (a denominator of zero passes for any rule).

      Comments and string literals are BLANKED (offsets and newlines preserved, so file:line stays
      exact) before any scan: a violation quoted in a KDoc is not a call, a raw string carrying the
      compliant spelling is not a routing, and a `// PreContentWireType.of(...)` left beside a hand
      copy must not excuse it.

EXIT 0 = the rule is applied at the seam and nowhere else, and no unexempted envelope builds an
      in-band error type.  EXIT 1 = at least one of those is false, named by file:line.
      --selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
SOURCE_ROOT_GLOB = "gateway/*/src/main/kotlin"
SEAM_FILE = "gateway/gateway/src/main/kotlin/splice/gateway/wire/SseEmitter.kt"
SEAM_FUN = "emitError"
# The one exemption, and the mark that keeps it honest — see THE ONE EXEMPTION above.
COLLECT_FILE = "gateway/gateway/src/main/kotlin/splice/gateway/wire/CollectingTerminal.kt"
COLLECT_MARK = "CollectingTerminal"
COLLECT_REASON = ("the COLLECT path (stream:false), where the failure's real HTTP status carries "
                  "the retry semantics — a buffered 429 stays 429 and a buffered api_error stays "
                  "502 — so the in-band remap must NOT reach it (V4-81 finding 3)")

# V4-102 added two more, and the SHAPE is deliberately the same triple: file, the mark that keeps
# the exemption honest, and the dated reason. The law governs IN-BAND SSE ERROR EVENTS on a stream
# the client is already reading; a body that never becomes one is outside its subject, not exempt
# from a rule it was never in scope for. Both entries below answer with a real HTTP STATUS, which
# is what Claude Code keys its retry on — so the in-band relabel is not merely unnecessary, there
# is no in-band event for it to apply to.
PRE_TURN_FILE = "gateway/gateway/src/main/kotlin/splice/gateway/head/AdmissionResponses.kt"
PRE_TURN_MARK = "AdmissionResponses"
PRE_TURN_REASON = ("2026-09-17: the PRE-TURN admission plane. Every verdict here is decided before "
                   "a turn exists — 400/401/408/413/429/529, each with its real status and, for the "
                   "429, a Retry-After — so there is no emitter to route through and no in-band "
                   "event to relabel. The client retries on the STATUS, not on the body's type")
POOLED_FILE = "gateway/provider-spi/src/main/kotlin/splice/spi/RateLimitCooldown.kt"
POOLED_MARK = "RateLimitCooldown"
POOLED_REASON = ("2026-09-17: the POOLED-REFUSAL fail-fast body, synthesized by the cooldown itself "
                 "and carried as the 429 the transport sends. Like the pre-turn plane it reaches the "
                 "client as a status, and provider-spi cannot import :gateway, so it could not route "
                 "through the seam even if the relabel applied")

# The written exemptions, in the order they were granted. Each is pinned to its file AND to a mark
# inside it, so an exemption cannot outlive the reason it was written for.
EXEMPTIONS = (
    (COLLECT_FILE, COLLECT_MARK, COLLECT_REASON),
    (PRE_TURN_FILE, PRE_TURN_MARK, PRE_TURN_REASON),
    (POOLED_FILE, POOLED_MARK, POOLED_REASON),
)

ROUTED = "PreContentWireType.of("
RULE_OBJECT = "object PreContentWireType"
# The two types the law was founded on (V4-71 rate-limit, V4-78 api_error). The live governed set
# is DERIVED from the rule object; these are the floor it may never fall below.
FOUNDING = ("RATE_LIMIT", "API_ERROR")
# A `.wireName` reaching one of these is a value going INTO a client-visible envelope, as opposed to
# the many `.wireName` reads that are telemetry tags and log lines (those take no envelope writer).
ENVELOPE_WRITERS = ("put", "putJsonObject", "add", "JsonPrimitive", "errorEnvelope", "of")
# V4-102 added "of": the envelope builder is now a STATIC FACTORY — `ErrorEnvelope.of(x.wireName, …)`
# — and `_enclosing_call` sees only the innermost name, so without it the wall goes blind at the one
# place the routed type is handed over, and refuses vacuously. "of" is broad, and the narrowing that
# keeps it safe is the one this function already applies: it only ever fires on a line carrying a
# literal `.wireName`, so a bare `of(` with no error type in it stays invisible.
_GOVERNED_RE = re.compile(r"==\s*ErrorType\.([A-Z][A-Z_0-9]*)\b")
_OF_SIGNATURE_RE = re.compile(r"\bfun\s+of\s*\(")
_ROUTED_HEAD_RE = re.compile(r"^PreContentWireType\s*\.\s*of\s*\(")
_NAMED_ARG_RE = re.compile(r"^([A-Za-z_]\w*)\s*=(?!=)\s*(.*)$")
# V4-102: the envelope builder moved to core as a STATIC FACTORY — `ErrorEnvelope.of(...)` — so the
# recognizer must know that spelling or it goes blind exactly where the type is handed over. The
# optional `(?:\.of)?` matches both forms and loosens nothing: the receiver must still be named
# `…errorEnvelope…`, which is why a bare `of(` or any other static call stays invisible here.
# This is the coordination the envelope rule's header described as checked; it was checked against
# the inline builder, so it went stale the moment that builder moved rather than being wrong.
_ENVELOPE_FUN_RE = re.compile(r"\b(\w*[eE]rrorEnvelope)(?:\s*\.\s*of)?\s*\(")
_WIRENAME_RE = re.compile(r"\.wireName\b")
_BIND_RE = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\b[^=\n]*?=\s*")


def code_view(text: str) -> str:
    """Blank comments and string/char literals WITHOUT moving offsets or newlines.

    One lexical pass, so a `//` inside a string cannot open a comment and a `"` inside a comment
    cannot open a string — the two ways a two-regex stripper gets this backwards. Offsets survive
    so every finding can still name file:LINE, which is this wall's whole contract."""
    out = list(text)
    n = len(text)
    i = 0

    def blank(start: int, end: int) -> None:
        for at in range(start, min(end, n)):
            if out[at] not in "\r\n":
                out[at] = " "

    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            blank(i, end)
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            blank(i, end)
            i = end
        elif text.startswith('"""', i):
            close = text.find('"""', i + 3)
            end = n if close < 0 else close + 3
            blank(i, end)
            i = end
        elif text[i] in ('"', "'"):
            quote = text[i]
            end = i + 1
            while end < n:
                if text[end] == "\\":
                    end += 2
                elif text[end] == quote:
                    end += 1
                    break
                elif text[end] == "\n":
                    break
                else:
                    end += 1
            blank(i, end)
            i = end
        else:
            i += 1
    return "".join(out)


def _line(code: str, at: int) -> int:
    return code.count("\n", 0, at) + 1


def _balanced_end(code: str, open_at: int) -> int:
    depth = 0
    for at in range(open_at, len(code)):
        if code[at] in "([{":
            depth += 1
        elif code[at] in ")]}":
            depth -= 1
            if depth == 0:
                return at
    return len(code)


def _rhs_of(code: str, assign_end: int) -> str:
    """The initializer expression at [assign_end]: to the first newline at paren depth 0, so a
    multi-line `PreContentWireType.of(\\n  a,\\n  b,\\n)` is read whole."""
    depth = 0
    for at in range(assign_end, len(code)):
        ch = code[at]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "\n" and depth <= 0:
            return code[assign_end:at]
    return code[assign_end:]


def _norm(expr: str) -> str:
    """One-line form of an expression: whitespace (and the blanked comments inside it) collapsed."""
    return " ".join(expr.split())


def _unwrap(expr: str) -> str:
    """Drop parens that wrap the WHOLE expression, so `(of(t, c))` grades as `of(t, c)`."""
    while expr.startswith("(") and _balanced_end(expr, 0) == len(expr) - 1:
        expr = expr[1:-1].strip()
    return expr


def _split_args(inner: str) -> list[str]:
    """Top-level commas only, and a trailing comma is not an argument (Kotlin's live spelling)."""
    parts: list[str] = []
    depth = 0
    start = 0
    for at, ch in enumerate(inner):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(inner[start:at])
            start = at + 1
    parts.append(inner[start:])
    return [part.strip() for part in parts if part.strip()]


def _enclosing_call(code: str, at: int) -> str:
    """Name of the innermost call whose '(' encloses [at] — '' when none. This is what separates a
    wire write (`put(TYPE, x.wireName)`) from a telemetry read (`recordStreamError(.., x.wireName)`
    and the log lines, which vanish with their strings anyway)."""
    stack: list[int] = []
    for i in range(at):
        if code[i] == "(":
            stack.append(i)
        elif code[i] == ")" and stack:
            stack.pop()
    if not stack:
        return ""
    head = code[: stack[-1]].rstrip()
    name = re.search(r"([A-Za-z_]\w*)$", head)
    return name.group(1) if name else ""


def _receiver_before(code: str, dot_at: int) -> str:
    """The receiver expression immediately left of the '.' at [dot_at] — `outcome.type` out of
    `outcome.type.wireName`, and the whole of `PreContentWireType.of(t, c)` out of its inline
    `.wireName`, so 'is the value on the wire the ROUTED one?' is answerable either way."""
    end = dot_at
    while end > 0 and code[end - 1] in " \t\n":
        end -= 1
    i = end
    while i > 0:
        ch = code[i - 1]
        if ch == ")":
            depth = 0
            while i > 0:
                if code[i - 1] == ")":
                    depth += 1
                elif code[i - 1] == "(":
                    depth -= 1
                    if depth == 0:
                        i -= 1
                        break
                i -= 1
            continue
        if ch.isalnum() or ch in "_.":
            i -= 1
            continue
        break
    return _norm(code[i:end])


def wire_writes(code: str) -> list[tuple[int, str, str]]:
    """(offset, receiver, writer) for every `.wireName` this file hands to a JSON writer."""
    out: list[tuple[int, str, str]] = []
    for m in _WIRENAME_RE.finditer(code):
        writer = _enclosing_call(code, m.start())
        if writer in ENVELOPE_WRITERS:
            out.append((m.start(), _receiver_before(code, m.start()), writer))
    return out


def envelope_sites(code: str) -> list[tuple[int, str]]:
    """(offset, what) for every in-band error-envelope construction: a `.wireName` going to a JSON
    writer, or an `errorEnvelope(` (a call OR its declaration — either way this file builds one)."""
    sites = [(at, f"`{recv}.wireName` into {writer}(…)") for at, recv, writer in wire_writes(code)]
    sites += [(m.start(), f"a `{m.group(1)}(` error-envelope builder") for m in
              _ENVELOPE_FUN_RE.finditer(code)]
    seen: dict[int, str] = {}
    for at, what in sites:
        seen.setdefault(_line(code, at), what)
    return sorted((at, what) for at, what in seen.items())


def governed_types(seam_code: str) -> set[str]:
    """The ErrorType values `object PreContentWireType` actually remaps — read from its own body so
    the wall widens with the rule instead of carrying a second, drifting copy of the list. Only the
    types the rule COMPARES AGAINST: reading every ErrorType token swept up the OVERLOADED it
    RETURNS and read the one safe type as banned (caught by this wall's own selftest)."""
    at = seam_code.find(RULE_OBJECT)
    if at < 0:
        return set()
    brace = seam_code.find("{", at)
    if brace < 0:
        return set()
    body = seam_code[brace:_balanced_end(seam_code, brace)]
    return set(_GOVERNED_RE.findall(body))


def rule_params(seam_code: str) -> list[str]:
    """The parameter NAMES of the rule's own `fun of(`, so the content flag is identified by the
    rule's signature rather than by a second copy of the name living in this wall."""
    at = seam_code.find(RULE_OBJECT)
    if at < 0:
        return []
    brace = seam_code.find("{", at)
    if brace < 0:
        return []
    body = seam_code[brace:_balanced_end(seam_code, brace)]
    sig = _OF_SIGNATURE_RE.search(body)
    if not sig:
        return []
    inner = body[sig.end():_balanced_end(body, sig.end() - 1)]
    names = []
    for param in _split_args(inner):
        name = re.match(r"(?:\w+\s+)*([A-Za-z_]\w*)\s*:", param)
        if not name:
            return []
        names.append(name.group(1))
    return names


def routing_verdict(expr: str, params: list[str]) -> str:
    """routed | not-whole | flag-true | of-unreadable — the WHOLE-expression grade of [expr]."""
    expr = _unwrap(_norm(expr))
    head = _ROUTED_HEAD_RE.match(expr)
    if not head or _balanced_end(expr, head.end() - 1) != len(expr) - 1:
        return "not-whole"  # the call is only PART of the expression, or is not the expression
    args = _split_args(expr[head.end():-1])
    flag_name = params[1]
    named: dict[str, str] = {}
    positional: list[str] = []
    for arg in args:
        hit = _NAMED_ARG_RE.match(arg)
        if hit:
            named[hit.group(1)] = hit.group(2).strip()
        else:
            positional.append(arg)
    if len(args) > len(params) or len(args) < 2:
        return "of-unreadable"
    if flag_name in named:
        flag = named[flag_name]
    elif len(positional) >= 2:
        flag = positional[1]
    else:
        return "of-unreadable"
    return "flag-true" if flag == "true" else "routed"


def _fun_bodies(code: str, name: str) -> list[tuple[int, str]] | None:
    """(offset, balanced body) per `fun <name>(` declaration. None when one has no brace body — an
    expression-bodied emitter is a shape this wall has never read, so it refuses to judge it."""
    out: list[tuple[int, str]] = []
    for m in re.finditer(r"\bfun\s+" + re.escape(name) + r"\s*\(", code):
        close = _balanced_end(code, m.end() - 1)
        brace = code.find("{", close)
        if brace < 0 or "=" in code[close + 1:brace] or "fun " in code[close + 1:brace]:
            return None
        out.append((brace, code[brace:_balanced_end(code, brace) + 1]))
    return out


def seam_problems(seam_code: str, params: list[str]) -> list[str]:
    """(a): every emitError in the seam applies the rule, whole, and ships the routed value."""
    bodies = _fun_bodies(seam_code, SEAM_FUN)
    if bodies is None:
        return [f"{SEAM_FILE} declares a `fun {SEAM_FUN}(` with no brace body — a shape this wall "
                "cannot read; refusing to pass vacuously"]
    if not bodies:
        return [f"{SEAM_FILE} declares no `fun {SEAM_FUN}(` — the seam the whole law now rests on "
                "is gone; refusing to pass vacuously"]
    problems: list[str] = []
    for brace, body in bodies:
        line = _line(seam_code, brace)
        calls = [m.start() for m in re.finditer(re.escape(ROUTED), body)]
        if not calls:
            problems.append(f"{SEAM_FILE}:{line} — {SEAM_FUN} writes an error frame WITHOUT "
                            f"{ROUTED}…): the one place the pre-content rule is applied no longer "
                            "applies it, so every pre-content failure reaches the client terminal")
            continue
        if len(calls) > 1:
            problems.append(f"{SEAM_FILE}:{line} — {SEAM_FUN} applies {ROUTED}…) {len(calls)} "
                            "times; one seam means one application, and the wall cannot tell which "
                            "result ships")
            continue
        at = calls[0]
        call = body[at:_balanced_end(body, at + len(ROUTED) - 1) + 1]
        bound: str | None = None
        verdict = "not-whole"
        for m in _BIND_RE.finditer(body):
            rhs = _rhs_of(body, m.end())
            if ROUTED in rhs:
                bound, verdict = m.group(1), routing_verdict(rhs, params)
                break
        if bound is None:
            # Not bound to a local: legitimate only when it goes STRAIGHT to the wire inline.
            verdict = routing_verdict(call, params)
            bound = _norm(call)
        if verdict != "routed":
            problems.append(f"{SEAM_FILE}:{line} — {_why(verdict, call, params)}")
            continue
        writes = wire_writes(body)
        if not writes:
            problems.append(f"{SEAM_FILE}:{line} — {SEAM_FUN} computes the routed type but hands "
                            "no `.wireName` to any envelope writer, so the wall cannot see the "
                            "routed value reach the wire; refusing to pass vacuously")
            continue
        for off, recv, writer in writes:
            if recv != bound:
                problems.append(
                    f"{SEAM_FILE}:{_line(seam_code, brace + off)} — {SEAM_FUN} routes the type "
                    f"into `{bound}` and then writes `{recv}.wireName` to the wire via "
                    f"{writer}(…): the routing is computed and DISCARDED, which is exactly what "
                    "it looks like when the rule is applied and forgotten")
    return problems


def _why(verdict: str, call: str, params: list[str]) -> str:
    snippet = _norm(call)[:60]
    if verdict == "flag-true":
        return (f"{SEAM_FUN} applies the rule as `{snippet}` — with {params[1]} pinned to the "
                "literal true the rule returns the type UNCHANGED, so the routing is spelled and "
                "does nothing. Pass the emitter's real content flag")
    if verdict == "of-unreadable":
        return (f"{SEAM_FUN} applies the rule as `{snippet}`, whose arguments do not read against "
                f"the rule's own signature ({', '.join(params)}), so the content flag cannot be "
                "checked; refusing to judge it routed")
    return (f"{SEAM_FUN} applies the rule inside the expression `{snippet}` rather than AS the "
            "expression: a conditional or elvis that merely CONTAINS the routing can still put the "
            "real type on the wire. Route the whole expression")


def detect(sources: dict[str, str] | None, seam_text: str | None) -> list[str]:
    """Pure detection — no I/O, so the selftest feeds synthetic trees."""
    if seam_text is None:
        return [f"{SEAM_FILE} is missing — the seam this law now rests on does not exist; refusing "
                "to pass vacuously"]
    seam_code = code_view(seam_text)
    if RULE_OBJECT not in seam_code or "fun of(" not in seam_code:
        return [f"{SEAM_FILE} no longer declares `{RULE_OBJECT}` with a `fun of(` — the rule the "
                "seam applies is gone or has moved; refusing to pass vacuously"]
    governed = governed_types(seam_code)
    missing = [t for t in FOUNDING if t not in governed]
    if missing:
        return [f"PreContentWireType no longer remaps {', '.join(missing)} — the rule was "
                "NARROWED, which silently narrows every wall derived from it. A wall may only "
                "tighten."]
    params = rule_params(seam_code)
    if len(params) < 2:
        return [f"{SEAM_FILE}'s `fun of(` signature does not read as (type, content flag, …) — the "
                "wall derives the content flag from it and will not guess; refusing to pass "
                "vacuously"]
    if not sources:
        return [f"no Kotlin sources found under {SOURCE_ROOT_GLOB} — refusing to pass vacuously"]

    problems = seam_problems(seam_code, params)
    envelopes = 0
    for path in sorted(sources):
        code = code_view(sources[path])
        if path != SEAM_FILE:
            for m in re.finditer(re.escape(ROUTED), code):
                problems.append(
                    f"{path}:{_line(code, m.start())} — a SECOND site applies {ROUTED}…). The rule "
                    "is applied once, at the emitter seam; a hand copy here is the V4-79 defect "
                    "(four copies of one rule) that made a new ending able to skip it")
        exempt = path == SEAM_FILE
        for ex_file, ex_mark, ex_reason in EXEMPTIONS:
            if path != ex_file:
                continue
            if ex_mark not in code:
                problems.append(f"{path} holds a written envelope exemption but no longer "
                                f"declares `{ex_mark}` — the exemption was written for "
                                f"{ex_reason}; it may not outlive that reason")
            else:
                exempt = True
        sites = envelope_sites(code)
        envelopes += len(sites)
        if exempt:
            continue
        for at, what in sites:
            problems.append(
                f"{path}:{at} — {what} builds an in-band error envelope OUTSIDE the emitter seam, "
                f"so the pre-content rule cannot reach it. Either route it through {SEAM_FILE}'s "
                f"{SEAM_FUN} or earn an exemption written into this wall the way the collect path "
                "has one")
    if envelopes == 0:
        return [f"scanned {len(sources)} source file(s) and found ZERO error-envelope "
                "constructions — a denominator of zero passes for any rule; refusing to pass "
                "vacuously"]
    if problems:
        governed_list = ", ".join(sorted(governed))
        problems.append(f"(census: {envelopes} error-envelope construction(s) in {len(sources)} "
                        f"file(s); governed types derived from the rule: {governed_list}; rule "
                        f"signature: of({', '.join(params)}))")
    return problems


def _load() -> tuple[dict[str, str] | None, str | None]:
    sources: dict[str, str] = {}
    for root in sorted(ROOT.glob(SOURCE_ROOT_GLOB)):
        for kt in sorted(root.rglob("*.kt")):
            sources[str(kt.relative_to(ROOT))] = kt.read_text(encoding="utf-8")
    seam = ROOT / SEAM_FILE
    return (sources or None), (seam.read_text(encoding="utf-8") if seam.exists() else None)


# ── synthetic fixtures ───────────────────────────────────────────────────────

_RULE = (
    "internal object PreContentWireType {\n"
    "    fun of(type: ErrorType, contentReachedClient: Boolean, permanent: Boolean = false)"
    ": ErrorType {\n"
    "        if (contentReachedClient) return type\n"
    "        return when {\n"
    "            type == ErrorType.RATE_LIMIT -> ErrorType.OVERLOADED\n"
    "            type == ErrorType.API_ERROR && !permanent -> ErrorType.OVERLOADED\n"
    "            else -> type\n"
    "        }\n    }\n}\n"
)
_RULE_NARROWED = (
    "internal object PreContentWireType {\n"
    "    fun of(type: ErrorType, contentReachedClient: Boolean, permanent: Boolean = false)"
    ": ErrorType =\n"
    "        if (type == ErrorType.RATE_LIMIT && !contentReachedClient) ErrorType.OVERLOADED "
    "else type\n}\n"
)


def _seam(routing: str, wire: str = "wireType", rule: str = _RULE) -> str:
    """An SseEmitter whose emitError applies [routing] and writes [wire].wireName to the frame."""
    return ('class SseEmitter : TurnTerminal {\n'
            '    override suspend fun emitError(type: ErrorType, message: String, '
            'permanent: Boolean) {\n'
            f'        {routing}\n'
            '        frames.frame(\n'
            '            "error",\n'
            '            buildJsonObject {\n'
            f'                put(TYPE, {wire}.wireName)\n'
            '                put(MESSAGE, message)\n'
            '            },\n'
            '        )\n'
            '    }\n}\n') + rule


_SEAM_OK = _seam("val wireType = PreContentWireType.of(type, contentReached(), permanent)")
_SEAM_INLINE = ('class SseEmitter : TurnTerminal {\n'
                '    override suspend fun emitError(type: ErrorType, message: String, '
                'permanent: Boolean) {\n'
                '        frames.frame(\n'
                '            "error",\n'
                '            buildJsonObject {\n'
                '                put(TYPE, PreContentWireType.of(type, contentReached(), '
                'permanent).wireName)\n'
                '            },\n'
                '        )\n'
                '    }\n}\n') + _RULE
_SEAM_NO_ROUTE = _seam("val wireType = type")
_SEAM_TWICE = _seam("val wireType = if (permanent) PreContentWireType.of(type, contentReached()) "
                    "else PreContentWireType.of(type, true)")
_SEAM_MIXED = _seam("val wireType = if (permanent) type else PreContentWireType.of(type, "
                    "contentReached())")
_SEAM_ELVIS = _seam("val wireType = PreContentWireType.of(type, contentReached()) ?: type")
_SEAM_FLAG_TRUE = _seam("val wireType = PreContentWireType.of(type, contentReachedClient = true)")
_SEAM_DISCARDS = _seam("val wireType = PreContentWireType.of(type, contentReached(), permanent)",
                       wire="type")
_SEAM_NO_WIRE = ('class SseEmitter : TurnTerminal {\n'
                 '    override suspend fun emitError(type: ErrorType, message: String, '
                 'permanent: Boolean) {\n'
                 '        val wireType = PreContentWireType.of(type, contentReached(), permanent)\n'
                 '        frames.frame("error", buildJsonObject { put(MESSAGE, message) })\n'
                 '    }\n}\n') + _RULE
_SEAM_NO_RULE = _seam("val wireType = PreContentWireType.of(type, contentReached(), permanent)",
                      rule="internal object Something { fun other() {} }\n")
_SEAM_NARROWED = _seam("val wireType = PreContentWireType.of(type, contentReached(), permanent)",
                       rule=_RULE_NARROWED)

# The collect path: the ONE exemption, and a copy of it that has stopped being the collect path.
_COLLECT = ('internal class CollectingTerminal : TurnTerminal {\n'
            '    override suspend fun emitError(type: ErrorType, message: String, '
            'permanent: Boolean) {\n'
            '        body = errorEnvelope(type.wireName, message)\n'
            '    }\n'
            '    private fun errorEnvelope(type: String, message: String): JsonObject =\n'
            '        buildJsonObject { put("type", type) }\n}\n')
_COLLECT_STALE = _COLLECT.replace("CollectingTerminal", "SomethingElse")
# The two shapes the denominator exists for: a hand copy of the rule, and an envelope built outside
# the seam (V4-79's endings, or a new one) — plus the decoys that must NOT count as either.
_HAND_COPY = ('internal class TurnConnEnd {\n  suspend fun end() {\n'
              '    drive.emitter.emitError(PreContentWireType.of(ErrorType.API_ERROR, c), "boom")\n'
              '  }\n}\n')
_ENVELOPE_OUTSIDE = ('internal class NewEnding {\n  fun frame(failure: ClassifiedFailure) =\n'
                     '    buildJsonObject { put("type", failure.type.wireName) }\n}\n')
_ENVELOPE_BUILDER = ('internal class NewEnding {\n'
                     '  private fun errorEnvelope(type: String) = buildJsonObject '
                     '{ put("type", type) }\n}\n')
_TELEMETRY_READ = ('internal class TurnLine {\n  fun tag(outcome: TurnOutcome) =\n'
                   '    telemetry.recordStreamError(meta, elapsedMs, outcome.type.wireName)\n}\n')
_COMMENT_DECOY = ('internal class TurnEnding {\n  suspend fun end() {\n'
                  '    // the wire type is routed by PreContentWireType.of(type, reached) at the '
                  'seam\n'
                  '    drive.emitter.emitError(ErrorType.API_ERROR, "boom")\n  }\n}\n')
_STRING_DECOY = ('internal class Doc {\n  val doc = """PreContentWireType.of(t, c) and '
                 'put("type", failure.type.wireName)"""\n}\n')
_CLEAN_ENDING = ('internal class TurnEnding {\n  suspend fun end() {\n'
                 '    drive.emitter.emitError(ErrorType.API_ERROR, "boom", permanent = true)\n'
                 '  }\n}\n')


def _tree(seam: str = _SEAM_OK, **extra: str) -> dict[str, str]:
    """A synthetic main-source tree: the seam, the exempt collect path, and whatever else."""
    sources = {SEAM_FILE: seam, COLLECT_FILE: _COLLECT}
    for name, text in extra.items():
        sources[f"gateway/gateway/src/main/kotlin/splice/gateway/head/{name}.kt"] = text
    return sources


def selftest() -> int:
    fails: list[str] = []

    def case(name: str, srcs, seam, *, want_red: bool, must_name: str | None = None):
        got = detect(srcs, seam)
        if want_red and not got:
            fails.append(f"{name}: must be RED")
            return
        if not want_red and got:
            fails.append(f"{name}: must be GREEN, got {got}")
            return
        if must_name and not any(must_name in g for g in got):
            fails.append(f"{name}: must name {must_name}, got {got}")

    # (a) THE SEAM
    case("seam: routes through a local and ships it", _tree(), _SEAM_OK, want_red=False)
    case("seam: routes INLINE straight to the wire", _tree(_SEAM_INLINE), _SEAM_INLINE,
         want_red=False)
    case("seam: emitError no longer applies the rule", _tree(_SEAM_NO_ROUTE), _SEAM_NO_ROUTE,
         want_red=True, must_name="WITHOUT PreContentWireType.of(")
    case("seam: the rule applied twice in one body", _tree(_SEAM_TWICE), _SEAM_TWICE,
         want_red=True, must_name="2 times")
    case("seam: the routing CONTAINED in a conditional (V4-82 part 1, at the seam)",
         _tree(_SEAM_MIXED), _SEAM_MIXED, want_red=True, must_name="rather than AS the expression")
    case("seam: the routing elvis'd behind the raw type", _tree(_SEAM_ELVIS), _SEAM_ELVIS,
         want_red=True, must_name="rather than AS the expression")
    case("seam: the content flag pinned to the literal true", _tree(_SEAM_FLAG_TRUE),
         _SEAM_FLAG_TRUE, want_red=True, must_name="returns the type UNCHANGED")
    case("seam: routes, then writes the RAW type to the wire", _tree(_SEAM_DISCARDS),
         _SEAM_DISCARDS, want_red=True, must_name="computed and DISCARDED")
    case("seam: routes but writes no wire type at all", _tree(_SEAM_NO_WIRE), _SEAM_NO_WIRE,
         want_red=True, must_name="hands no `.wireName`")
    # (b) NO COPIES
    case("copies: a second file applies the rule (the V4-79 hand-copy defect)",
         _tree(TurnConnEnd=_HAND_COPY), _SEAM_OK, want_red=True, must_name="TurnConnEnd.kt:3")
    case("copies: an ending that passes its REAL type is CORRECT after V4-81",
         _tree(TurnEnding=_CLEAN_ENDING), _SEAM_OK, want_red=False)
    # (c) THE GOVERNED SET
    case("ratchet: the rule NARROWED to stop governing API_ERROR", _tree(_SEAM_NARROWED),
         _SEAM_NARROWED, want_red=True, must_name="NARROWED")
    # (d) ENVELOPES OUTSIDE THE SEAM
    case("envelopes: a new ending builds its own error envelope",
         _tree(NewEnding=_ENVELOPE_OUTSIDE), _SEAM_OK, want_red=True, must_name="NewEnding.kt:3")
    case("envelopes: a new errorEnvelope( builder outside the seam",
         _tree(NewEnding=_ENVELOPE_BUILDER), _SEAM_OK, want_red=True, must_name="NewEnding.kt:2")
    # V4-102's two new exemptions. Each gets a GREEN case AND a RED twin whose only difference is
    # the missing mark, because the pairing is what proves the exemption fires for the enumerated
    # reason rather than for whatever happens to be in the file. The red twin is also the check that
    # the stale-exemption rule survived turning the single exemption into a tuple.
    _envelope_body = 'fun body() = buildJsonObject { put("type", "error") }'
    case("envelopes: the pre-turn admission plane keeps its WRITTEN exemption",
         {SEAM_FILE: _SEAM_OK, COLLECT_FILE: _COLLECT,
          PRE_TURN_FILE: f'object {PRE_TURN_MARK} {{ {_envelope_body} }}'},
         _SEAM_OK, want_red=False)
    case("envelopes: the pre-turn exemption without its mark is RED",
         {SEAM_FILE: _SEAM_OK, COLLECT_FILE: _COLLECT,
          PRE_TURN_FILE: f'object SomethingElse {{ {_envelope_body} }}'},
         _SEAM_OK, want_red=True, must_name=PRE_TURN_MARK)
    case("envelopes: the pooled refusal keeps its WRITTEN exemption",
         {SEAM_FILE: _SEAM_OK, COLLECT_FILE: _COLLECT,
          POOLED_FILE: f'class {POOLED_MARK} {{ {_envelope_body} }}'},
         _SEAM_OK, want_red=False)
    case("envelopes: the pooled exemption without its mark is RED",
         {SEAM_FILE: _SEAM_OK, COLLECT_FILE: _COLLECT,
          POOLED_FILE: f'class SomethingElse {{ {_envelope_body} }}'},
         _SEAM_OK, want_red=True, must_name=POOLED_MARK)

    case("envelopes: the collect path keeps its WRITTEN exemption", _tree(), _SEAM_OK,
         want_red=False)
    case("envelopes: the exemption's file is no longer the collect terminal",
         {SEAM_FILE: _SEAM_OK, COLLECT_FILE: _COLLECT_STALE}, _SEAM_OK, want_red=True,
         must_name="may not outlive that reason")
    case("envelopes: a telemetry `.wireName` is not an envelope", _tree(TurnLine=_TELEMETRY_READ),
         _SEAM_OK, want_red=False)
    # decoys
    case("decoy: a COMMENT claiming the routing beside a raw-type emit",
         _tree(TurnEnding=_COMMENT_DECOY), _SEAM_OK, want_red=False)
    case("decoy: a raw STRING carrying both the routing and an envelope",
         _tree(Doc=_STRING_DECOY), _SEAM_OK, want_red=False)
    # FAIL-CLOSED
    case("vacuous: the seam file is gone", _tree(), None, want_red=True, must_name="is missing")
    case("vacuous: the rule object no longer declared", _tree(_SEAM_NO_RULE), _SEAM_NO_RULE,
         want_red=True, must_name="no longer declares")
    case("vacuous: no sources at all", {}, _SEAM_OK, want_red=True)
    case("vacuous: zero error-envelope constructions anywhere",
         {SEAM_FILE: _SEAM_NO_WIRE}, _SEAM_NO_WIRE, want_red=True)

    if governed_types(code_view(_SEAM_OK)) < {"RATE_LIMIT", "API_ERROR"}:
        fails.append("the governed set must be READ from the rule object's body, not assumed")
    if rule_params(code_view(_SEAM_OK))[:2] != ["type", "contentReachedClient"]:
        fails.append("the content flag must be DERIVED from the rule's own `fun of(` signature")
    if fails:
        print("PRE-CONTENT-WIRE-TYPE SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("PRE-CONTENT-WIRE-TYPE SELFTEST OK — green on the live seam (routed through a local and "
          "inline), on an ending that passes its real type, on the collect path's written "
          "exemption, on a telemetry .wireName and on comment/raw-string decoys; red by file:line "
          "on a seam that stops routing, routes twice, routes inside a conditional or an elvis, "
          "pins the content flag to true, discards the routed value, or writes no wire type; red "
          "on a SECOND site applying the rule (the V4-79 hand-copy defect) and on any error "
          "envelope built outside the seam; red rather than vacuous on a stale exemption, a "
          "NARROWED rule, a vanished rule object, a missing seam, no sources, and zero envelopes")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    sources, seam = _load()
    problems = detect(sources, seam)
    if problems:
        print("PRE-CONTENT-WIRE-TYPE WALL RED — the pre-content rule is not the seam's sole, whole "
              "application, or an in-band error envelope is built outside it:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print(f"PRE-CONTENT-WIRE-TYPE WALL GREEN: {SEAM_FILE}'s {SEAM_FUN} applies {ROUTED}…) as the "
          f"whole wire type and ships it; no other main source among {len(sources or {})} applies "
          "the rule or builds an in-band error envelope outside the one written exemption.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
