#!/usr/bin/env python3
"""WALL for the PRE-CONTENT-WIRE-TYPE law (V4-78 / V4-79) — no direct error emitter may put a
client-terminal error type on the wire without routing it through PreContentWireType.of.

THE LAW  Claude Code 2.1.257 retries an IN-BAND SSE error event only when its body carries
      overloaded_error (a real 429/529 is retried by STATUS, and neither is ours to send once the
      200 is committed at TurnStreamer.stream). So a pre-content `api_error` or `rate_limit_error`
      ENDS the session where a retry would have healed it. The rule lives as a VALUE —
      `PreContentWireType.of(type, contentReachedClient)` in
      gateway/gateway/src/main/kotlin/splice/gateway/head/TurnKnownEnd.kt — precisely so it cannot
      be re-derived slightly differently at each ending. This wall is what makes a NEW ending
      unable to skip it.

GAP (RED at authoring, 2026-09-17, proven against the tree at 74f54734 with V4-78's rule object
      overlaid so the compliant shape exists to be skipped):
        gateway/gateway/src/main/kotlin/splice/gateway/head/TurnEnding.kt:44   literal API_ERROR
        gateway/gateway/src/main/kotlin/splice/gateway/head/TurnConnEnd.kt:37  literal API_ERROR
        gateway/gateway/src/main/kotlin/splice/gateway/pipeline/TurnPipeline.kt:63  `outcome.type`
      The third is why this wall is an ALLOWLIST and not a banned-literal scan. `emitter.emitError(
      outcome.type, spoken)` contains no literal at all, and a rule that only greps for
      `emitError(ErrorType.API_ERROR` would have declared that line clean while it was the widest
      hole of the three — every classified upstream failure the pipeline finishes flows through it.
      A denylist can only ever name the spellings someone already thought of.

WHAT IT CHECKS  Every `.emitError(` CALL in every gateway main source (roots enumerated from the
      filesystem — `gateway/*/src/main/kotlin`, never a hand-kept list, so a new module cannot be
      absent from the denominator). A call's FIRST ARGUMENT is compliant only when it is:
        · routed        — the expression contains `PreContentWireType.of(`, or is a local
                          `val`/`var` that scope-dominates the call and is assigned from it
                          (both live shapes: TurnEnding/TurnConnEnd/TurnPipeline inline,
                          TurnKnownEnd through `val wireType`); or
        · provably safe — a literal `ErrorType.X` whose X the rule does NOT govern.
      ANYTHING ELSE reds by file:line: a governed literal, and any COMPUTED type (`outcome.type`,
      a bare parameter, a `when` subject) that can BE a governed type at runtime and is not routed.

THE GOVERNED SET IS DERIVED, NEVER RE-AUTHORED  It is read out of `object PreContentWireType`'s own
      body — the types it COMPARES AGAINST (`type == ErrorType.RATE_LIMIT || type ==
      ErrorType.API_ERROR`), not every token in it — so widening the rule
      widens the wall in the same commit. A RATCHET guards the other direction: the derived set
      must still contain RATE_LIMIT and API_ERROR — narrowing the rule to make this wall go green
      is itself red.

FAIL-CLOSED  Red — never a vacuous pass — when: TurnKnownEnd.kt is missing or no longer declares
      `object PreContentWireType` with a `fun of(`; the derived governed set lost either founding
      type; no source roots resolve; or the scan finds ZERO `.emitError(` call sites (a denominator
      of zero passes for any rule).

      Comments and string literals are BLANKED (offsets and newlines preserved, so file:line stays
      exact) before any scan: a violation quoted in a KDoc is not a call, a raw string carrying the
      compliant spelling is not a routing, and a `// PreContentWireType.of(...)` left above a live
      unrouted emit must not excuse it.

EXIT 0 = every wire type is routed or provably safe.  EXIT 1 = at least one is not, named by
      file:line.  --selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
SOURCE_ROOT_GLOB = "gateway/*/src/main/kotlin"
RULE_FILE = "gateway/gateway/src/main/kotlin/splice/gateway/head/TurnKnownEnd.kt"

ROUTED = "PreContentWireType.of("
# The two types the law was founded on (V4-71 rate-limit, V4-78 api_error). The live governed set
# is DERIVED from the rule object; these are the floor it may never fall below.
FOUNDING = ("RATE_LIMIT", "API_ERROR")
_CALL_RE = re.compile(r"\bemitError\s*\(")
_TYPE_RE = re.compile(r"\bErrorType\.([A-Z][A-Z_0-9]*)\b")
# Only the types the rule COMPARES AGAINST are governed. Reading every ErrorType token in the
# body instead swept up ErrorType.OVERLOADED — the value the rule RETURNS — and made the one
# safe type the wall exists to allow read as banned (caught by this wall's own selftest).
_GOVERNED_RE = re.compile(r"==\s*ErrorType\.([A-Z][A-Z_0-9]*)\b")
_IDENT_RE = re.compile(r"^[A-Za-z_]\w*$")


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


def _scopes_at(code: str, positions: list[int]) -> dict[int, tuple[int, ...]]:
    """Brace ancestry of each position, one lexical pass (nf_03's idiom). Used for dominance: a
    local whose braces are a PREFIX of the call's braces is in scope at the call."""
    targets = sorted(set(positions))
    result: dict[int, tuple[int, ...]] = {}
    stack: list[int] = []
    t = 0
    for at, ch in enumerate(code):
        while t < len(targets) and targets[t] == at:
            result[targets[t]] = tuple(stack)
            t += 1
        if ch == "{":
            stack.append(at)
        elif ch == "}" and stack:
            stack.pop()
    while t < len(targets):
        result[targets[t]] = tuple(stack)
        t += 1
    return result


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


def _first_arg(code: str, open_paren: int) -> str | None:
    """Text of the first argument of the call whose '(' is at [open_paren]."""
    depth = 0
    start = open_paren + 1
    for at in range(open_paren, len(code)):
        ch = code[at]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return code[start:at]
        elif ch == "," and depth == 1:
            return code[start:at]
    return None


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


def governed_types(rule_code: str) -> set[str]:
    """The ErrorType values `object PreContentWireType` actually remaps — read from its own body so
    the wall widens with the rule instead of carrying a second, drifting copy of the list."""
    at = rule_code.find("object PreContentWireType")
    if at < 0:
        return set()
    brace = rule_code.find("{", at)
    if brace < 0:
        return set()
    body = rule_code[brace:_balanced_end(rule_code, brace)]
    return set(_GOVERNED_RE.findall(body))


def _verdict(expr: str, governed: set[str]) -> str:
    """routed | safe | governed:<TYPE> | computed"""
    if ROUTED in expr:
        return "routed"
    named = _TYPE_RE.findall(expr)
    if not named:
        return "computed"
    hit = [n for n in named if n in governed]
    return f"governed:{hit[0]}" if hit else "safe"


def _resolve(code: str, expr: str, call_at: int, governed: set[str]) -> str:
    """One level of local indirection: a bare identifier is re-read as the nearest preceding
    `val`/`var` binding of that name whose brace scope DOMINATES the call."""
    name = expr.strip()
    if not _IDENT_RE.match(name):
        return "computed"
    binds = list(re.finditer(r"\b(?:val|var)\s+" + re.escape(name) + r"\b[^=\n]*?=\s*", code))
    call_scope = _scopes_at(code, [call_at])[call_at]
    best: str | None = None
    for b in binds:
        if b.start() >= call_at:
            continue
        scope = _scopes_at(code, [b.start()])[b.start()]
        if len(scope) <= len(call_scope) and call_scope[: len(scope)] == scope:
            best = _rhs_of(code, b.end())
    return _verdict(best, governed) if best is not None else "computed"


def scan_file(path: str, text: str, governed: set[str]) -> tuple[list[str], int]:
    """(violations, call_sites_seen) for one source file."""
    code = code_view(text)
    violations: list[str] = []
    calls = 0
    listed = "/".join(sorted(governed))
    for m in _CALL_RE.finditer(code):
        if code[: m.start()].rstrip().endswith("fun"):
            continue  # a declaration/override, not a call
        calls += 1
        line = code.count("\n", 0, m.start()) + 1
        arg = _first_arg(code, m.end() - 1)
        if arg is None:
            violations.append(f"{path}:{line} — unparseable emitError argument list; refusing to "
                              "judge it clean")
            continue
        verdict = _verdict(arg, governed)
        if verdict == "computed":
            verdict = _resolve(code, arg, m.start(), governed)
        if verdict in ("routed", "safe"):
            continue
        if verdict.startswith("governed:"):
            violations.append(
                f"{path}:{line} — emitError sends the literal ErrorType.{verdict.split(':', 1)[1]} "
                f"to the client without {ROUTED}…): pre-content this is TERMINAL for Claude Code "
                "in band, so the turn ends where a retry would have healed it")
        else:
            violations.append(
                f"{path}:{line} — emitError's wire type is the computed expression "
                f"`{' '.join(arg.split())[:60]}`, which can be {listed} at runtime, and it does "
                f"not go through {ROUTED}…): route it or emit a literal type the rule leaves alone")
    return violations, calls


def detect(sources: dict[str, str] | None, rule_text: str | None) -> list[str]:
    """Pure detection — no I/O, so the selftest feeds synthetic trees."""
    if rule_text is None:
        return [f"{RULE_FILE} is missing — the routing rule this law points every ending at does "
                "not exist; refusing to pass vacuously"]
    rule_code = code_view(rule_text)
    if "object PreContentWireType" not in rule_code or "fun of(" not in rule_code:
        return [f"{RULE_FILE} no longer declares `object PreContentWireType` with a `fun of(` — "
                "the shape this wall calls compliant changed; refusing to pass vacuously"]
    governed = governed_types(rule_code)
    missing = [t for t in FOUNDING if t not in governed]
    if missing:
        return [f"PreContentWireType no longer remaps {', '.join(missing)} — the rule was NARROWED, "
                "which silently narrows every wall derived from it. A wall may only tighten."]
    if not sources:
        return [f"no Kotlin sources found under {SOURCE_ROOT_GLOB} — refusing to pass vacuously"]

    problems: list[str] = []
    calls = 0
    for path in sorted(sources):
        found, seen = scan_file(path, sources[path], governed)
        problems.extend(found)
        calls += seen
    if calls == 0:
        return [f"scanned {len(sources)} source file(s) and found ZERO emitError call sites — a "
                "denominator of zero passes for any rule; refusing to pass vacuously"]
    if problems:
        problems.append(f"(census: {calls} emitError call site(s) in {len(sources)} file(s); "
                        f"governed types derived from the rule: {', '.join(sorted(governed))})")
    return problems


def _load() -> tuple[dict[str, str] | None, str | None]:
    sources: dict[str, str] = {}
    for root in sorted(ROOT.glob(SOURCE_ROOT_GLOB)):
        for kt in sorted(root.rglob("*.kt")):
            sources[str(kt.relative_to(ROOT))] = kt.read_text(encoding="utf-8")
    rule = ROOT / RULE_FILE
    return (sources or None), (rule.read_text(encoding="utf-8") if rule.exists() else None)


# ── synthetic fixtures ───────────────────────────────────────────────────────

_RULE = (
    "internal object PreContentWireType {\n"
    "    fun of(type: ErrorType, contentReachedClient: Boolean): ErrorType {\n"
    "        val retryable = type == ErrorType.RATE_LIMIT || type == ErrorType.API_ERROR\n"
    "        return if (retryable && !contentReachedClient) ErrorType.OVERLOADED else type\n"
    "    }\n}\n"
)
_RULE_NARROWED = (
    "internal object PreContentWireType {\n"
    "    fun of(type: ErrorType, contentReachedClient: Boolean): ErrorType =\n"
    "        if (type == ErrorType.RATE_LIMIT && !contentReachedClient) ErrorType.OVERLOADED "
    "else type\n}\n"
)
_OPEN = ('class E {\n  suspend fun end() {\n'
         '    drive.emitter.emitError(ErrorType.API_ERROR, "boom")\n  }\n}\n')
_OPEN_RL = ('class E {\n  suspend fun end() {\n'
            '    drive.emitter.emitError(ErrorType.RATE_LIMIT, "slow")\n  }\n}\n')
_OPEN_COMPUTED = ('class E {\n  suspend fun end() {\n'
                  '    emitter.emitError(outcome.type, spoken)\n  }\n}\n')
_CLOSED = ('class E {\n  suspend fun end() {\n'
           '    drive.emitter.emitError(\n'
           '      PreContentWireType.of(ErrorType.API_ERROR, contentReachedClient = c),\n'
           '      "boom",\n    )\n  }\n}\n')
_CLOSED_VAL = ('class E {\n  suspend fun end() {\n'
               '    val wireType = PreContentWireType.of(failure.type, contentReachedClient)\n'
               '    drive.emitter.emitError(wireType, message)\n  }\n}\n')
_SAFE_LITERALS = ('interface T {\n  suspend fun emitError(type: ErrorType, message: String)\n}\n'
                  'class E {\n  suspend fun end() {\n'
                  '    drive.emitter.emitError(ErrorType.OVERLOADED, "retry")\n'
                  '    drive.emitter.emitError(ErrorType.AUTHENTICATION, "log in")\n  }\n}\n')
_DODGE_VAL = ('class E {\n  suspend fun end() {\n'
              '    val t = ErrorType.API_ERROR\n'
              '    drive.emitter.emitError(t, "boom")\n  }\n}\n')
_COMMENT_QUOTE = ('class E {\n  suspend fun end() {\n'
                  '    // never write emitError(ErrorType.API_ERROR, "boom") here\n'
                  '    drive.emitter.emitError(PreContentWireType.of(t, c), "ok")\n  }\n}\n')
_COMMENT_EXCUSE = ('class E {\n  suspend fun end() {\n'
                   '    // routed by PreContentWireType.of(type, contentReachedClient)\n'
                   '    drive.emitter.emitError(ErrorType.API_ERROR, "boom")\n  }\n}\n')
_STRING_DECOY = ('class E {\n  suspend fun end() {\n'
                 '    val doc = """emitError(ErrorType.API_ERROR, "boom")"""\n'
                 '    drive.emitter.emitError(PreContentWireType.of(t, c), "ok")\n  }\n}\n')
_STRING_EXCUSE = ('class E {\n  suspend fun end() {\n'
                  '    val doc = """PreContentWireType.of(t, c)"""\n'
                  '    drive.emitter.emitError(ErrorType.API_ERROR, "boom")\n  }\n}\n')
_BRANCH_VAL = ('class E {\n  suspend fun end() {\n'
               '    if (x) {\n      val wireType = PreContentWireType.of(t, c)\n    }\n'
               '    drive.emitter.emitError(wireType, message)\n  }\n}\n')
_NO_CALLS = 'class E {\n  fun end() {\n    log("done")\n  }\n}\n'


def selftest() -> int:
    fails: list[str] = []

    def case(name: str, srcs, rule, *, want_red: bool, must_name: str | None = None):
        got = detect(srcs, rule)
        if want_red and not got:
            fails.append(f"{name}: must be RED")
            return
        if not want_red and got:
            fails.append(f"{name}: must be GREEN, got {got}")
            return
        if must_name and not any(must_name in g for g in got):
            fails.append(f"{name}: must name {must_name}, got {got}")

    case("open: literal API_ERROR", {"A.kt": _OPEN}, _RULE, want_red=True, must_name="A.kt:3")
    case("open: literal RATE_LIMIT", {"B.kt": _OPEN_RL}, _RULE, want_red=True, must_name="B.kt:3")
    case("open: COMPUTED type (the TurnPipeline:63 class a denylist misses)",
         {"C.kt": _OPEN_COMPUTED}, _RULE, want_red=True, must_name="C.kt:3")
    case("closed: routed inline", {"A.kt": _CLOSED}, _RULE, want_red=False)
    case("closed: routed through a local val", {"A.kt": _CLOSED_VAL}, _RULE, want_red=False)
    case("closed: literal types the rule leaves alone, plus a declaration",
         {"A.kt": _SAFE_LITERALS}, _RULE, want_red=False)
    case("dodge: governed literal parked in a local val", {"A.kt": _DODGE_VAL}, _RULE,
         want_red=True, must_name="A.kt:4")
    case("dodge: the routing val is inside a branch the call is not in",
         {"A.kt": _BRANCH_VAL}, _RULE, want_red=True, must_name="A.kt:6")
    case("decoy: the violation quoted in a comment", {"A.kt": _COMMENT_QUOTE}, _RULE, want_red=False)
    case("decoy: a COMMENT claiming the routing above a live unrouted emit",
         {"A.kt": _COMMENT_EXCUSE}, _RULE, want_red=True, must_name="A.kt:4")
    case("decoy: the violation inside a raw string", {"A.kt": _STRING_DECOY}, _RULE, want_red=False)
    case("decoy: a raw STRING carrying the routing beside a live unrouted emit",
         {"A.kt": _STRING_EXCUSE}, _RULE, want_red=True, must_name="A.kt:4")
    case("ratchet: the rule NARROWED to stop governing API_ERROR",
         {"A.kt": _OPEN}, _RULE_NARROWED, want_red=True, must_name="NARROWED")
    case("vacuous: zero emitError call sites", {"A.kt": _NO_CALLS}, _RULE, want_red=True,
         must_name="ZERO emitError")
    case("vacuous: no sources at all", {}, _RULE, want_red=True)
    case("vacuous: the rule file is gone", {"A.kt": _CLOSED}, None, want_red=True)
    case("vacuous: PreContentWireType no longer declared", {"A.kt": _CLOSED},
         "object Something { fun other() {} }\n", want_red=True)
    case("mixed: one red among compliant files",
         {"A.kt": _CLOSED, "B.kt": _OPEN, "C.kt": _CLOSED_VAL, "D.kt": _SAFE_LITERALS}, _RULE,
         want_red=True, must_name="B.kt:3")

    if governed_types(code_view(_RULE)) < {"RATE_LIMIT", "API_ERROR"}:
        fails.append("the governed set must be READ from the rule object's body, not assumed")
    if fails:
        print("PRE-CONTENT-WIRE-TYPE SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("PRE-CONTENT-WIRE-TYPE SELFTEST OK — red by file:line on both governed literals, on a "
          "COMPUTED wire type, on a governed literal parked in a local val, on a routing val "
          "nested in a branch the call is not in, and on comment/raw-string decoys claiming the "
          "routing; green on both live routed shapes, on quoted violations and on literal types "
          "the rule leaves alone; red rather than vacuous on a NARROWED rule, zero call sites, no "
          "sources, and a vanished rule object")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    sources, rule = _load()
    problems = detect(sources, rule)
    if problems:
        print("PRE-CONTENT-WIRE-TYPE WALL RED — a client-terminal error type reaches the wire "
              "without the pre-content rule:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print(f"PRE-CONTENT-WIRE-TYPE WALL GREEN: every emitError in {len(sources or {})} gateway main "
          f"source(s) routes its wire type through {ROUTED}…) or names a literal type the rule "
          "leaves alone.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
