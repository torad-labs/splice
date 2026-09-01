#!/usr/bin/env python3
"""DR-140 — the DR-65 wall: no raw throwable text in a credential/state path.

DR-65's law is "all failure rendering in credential/state paths goes through
SafeFailureText.render". Nothing enforced it, so DR-73 swept the sinks BY HAND and its
denominator was FILES rather than SINKS: UsageRingFile's read half was sealed and its
write half kept `failure.message` for another eight days (DR-139). A hand sweep closes
the instance; only a checker closes the class.

DENOMINATOR (the part that matters, per the completeness law): scope is derived from the
SOURCE, never from a list of known-interesting files, and never from "files that already
call SafeFailureText" — that last one is the tautology this wall exists to avoid, since a
NEW credential path that never learned the law would not be in its own denominator. Scope is
CAUSAL: DR-65's hazard is an exception quoting the bytes of the file that produced it, which
is only reachable if the code TOUCHED FILES, so a source is in scope when it does filesystem
I/O (SCOPE_IO) or names credential/state vocabulary (SCOPE_VOCAB, which covers a file that
delegates its I/O to a collaborator). No site count is quoted here on purpose: a number in a
header rots the first time the tree moves, and `report` prints the live roll on demand.

DISPOSITION: every site in scope is COMPLIANT (routed through SafeFailureText.render) or
EXEMPT (carries a dated marker naming why that throwable cannot quote state bytes).
Absence is not a disposition — an undispositioned site fails BY NAME. A blank, placeholder
or too-short reason is an absence wearing a label and fails the same way.
"""
import pathlib
import re
import sys

# SCOPE IS CAUSAL, not a vocabulary guess. DR-65's hazard is an exception whose text quotes
# "the bytes of the file that produced it" — which is only reachable if the code TOUCHES FILES.
# So a file is in scope when it does filesystem I/O, or when it names credential/state
# vocabulary (which covers a file that delegates its I/O to a collaborator).
#
# The first draft of this wall scoped on the vocabulary list ALONE, and two independent reviews
# mutation-proved the hole within the hour: CodexAuthFile.kt and KimiOAuth.kt name no marker at
# all, so a raw render planted in either did not move the site count. A denominator assembled
# from a list of names I thought of is the same hand-argued denominator that let DR-73's sweep
# miss UsageRingFile — the exact failure this wall exists to end, recurring inside the checker
# written to end it. Causality is checkable; a vocabulary list is a memory test.
SCOPE_VOCAB = (
    "SafeFailureText", "KeyStore", "KeyStorePath", "StatePaths", "SecureFile",
    "TopologyLoader", "CredentialJson", "Credentials", "MgmtKey", "LoginOutcomeFile",
    "JsonlSink",
)
SCOPE_IO = (r"java\.nio\.file", r"\bFiles\.", r"\bPath\b", r"FileChannel", r"writeAtomic",
            r"readString")

# Identifiers that name a throwable. Kotlin has no type info here, so the matcher keys on the
# naming the codebase actually uses at catch/onFailure/getOrElse sites.
#
# Two tiers, because `it` is Kotlin's UNIVERSAL lambda parameter. Treating it as a throwable
# everywhere flagged `"Bearer $it"`, `"$it = stored"` and eight more where `it` is a String —
# and a wall that cries wolf gets its exemptions rubber-stamped, which is how a wall dies. So
# the ambiguous short names count only inside a failure-handling lambda; the unambiguous ones
# count anywhere.
THROWABLE_NAMED = r"(?:cause|failure|throwable|\w+(?:Failure|Error|Exception|Cause))"
THROWABLE_SHORT = r"(?:it|e|t|ex|err)"
FAILURE_CONTEXT = re.compile(
    r"onFailure|getOrElse|exceptionOrNull|recoverCatching|recover\b|\bcatch\s*\(|\.fold\("
)

# DR-154: a real lexical masker, because two rounds of ordering patches each left a hole that
# codex-splice mutation-proved from the scanner's own source:
#   * comment-before-string produced a FALSE POSITIVE — a URL inside a one-line failure lambda
#     ("https://…") had its `//` treated as a comment, the closing brace vanished, the span never
#     closed, and every later bare `$it` in the file was flagged. A blocking-gate false positive is
#     the failure direction that gets a wall switched off rather than fixed.
#   * counting braces inside `/* … */` and inside a char literal `'}'` produced FALSE NEGATIVES —
#     the depth popped early, the failure lambda looked closed, and a genuine raw `$it` render
#     inside it was missed entirely.
# Both classes come from the same root: brace depth was read off text that still contained
# non-syntax. So mask first, in ONE stateful pass over the file, and count only what is left.
def mask_source(lines):
    """Every string, char literal and comment replaced by spaces, so only real syntax remains.

    Multi-line aware: block comments and raw strings carry state across lines, which per-line
    regex stripping structurally cannot do.
    """
    masked, state = [], None  # state: None | "block" | "raw"
    for raw in lines:
        out, i, n = [], 0, len(raw)
        while i < n:
            if state == "block":
                if raw.startswith("*/", i):
                    state, i = None, i + 2
                    out.append("  ")
                    continue
                out.append(" ")
                i += 1
            elif state == "raw":
                if raw.startswith('"""', i):
                    state, i = None, i + 3
                    out.append("   ")
                    continue
                out.append(" ")
                i += 1
            elif raw.startswith("//", i):
                out.append(" " * (n - i))
                break
            elif raw.startswith("/*", i):
                state, i = "block", i + 2
                out.append("  ")
            elif raw.startswith('"""', i):
                state, i = "raw", i + 3
                out.append("   ")
            elif raw[i] in "\"'":
                quote, i = raw[i], i + 1
                out.append(" ")
                while i < n:
                    if raw[i] == "\\":
                        out.append("  ")
                        i += 2
                        continue
                    out.append(" ")
                    closed = raw[i] == quote
                    i += 1
                    if closed:
                        break
            else:
                out.append(raw[i])
                i += 1
        masked.append("".join(out))
    return masked

# A throwable rendered INTO TEXT. Both interpolation forms, because the BARE one is strictly
# WORSE: `$failure` calls toString(), which is the class name PLUS the same message — including
# kotlinx's "JSON input: ..." excerpt. The first draft matched only `${x.message}` and a review
# mutation-proved it blind to `$it` while nine live credential sinks used exactly that form, so
# the wall could not fail for the stronger version of the very thing it forbade.
RENDERED = re.compile(
    r"\$\{[^}]*\.message[^}]*\}"                              # ${ ....message.... }
    r"|\$\{\s*%s\s*\}|\$%s\b" % (THROWABLE_NAMED, THROWABLE_NAMED)   # ${failure} / $failure
)
RENDERED_SHORT = re.compile(
    r"\$\{\s*%s\s*\}|\$%s\b" % (THROWABLE_SHORT, THROWABLE_SHORT)    # ${it} / $it
)


def failure_spans(lines):
    """Per line: is it inside the BODY of a failure-handling lambda?

    Structural, not a fixed lookback. The first version asked whether a failure combinator
    appeared within 3 lines above, and codex-splice mutation-proved the hole: a real
    `.onFailure { ... }` in SecureFile.kt whose nested cleanup pushes the `$it` render five lines
    below the opener stayed GREEN. Widening the constant only moves the hole deeper into the next
    nested block, so scope is tracked by BRACE DEPTH: the lambda's body is in context until its own
    closing brace, at any nesting depth and any length.
    """
    inside = [False] * len(lines)
    depth, stack, pending = 0, [], False
    for i, code in enumerate(mask_source(lines)):
        pending = pending or bool(FAILURE_CONTEXT.search(code))
        line_inside = bool(stack)
        for ch in code:
            if ch == "{":
                depth += 1
                if pending:
                    stack.append(depth)
                    pending = False
                    line_inside = True
            elif ch == "}":
                if stack and stack[-1] == depth:
                    stack.pop()
                depth = max(0, depth - 1)
        inside[i] = line_inside or bool(stack)
    return inside


def renders_throwable(lines, idx, spans=None):
    """Does line [idx] interpolate a throwable into text?"""
    if RENDERED.search(lines[idx]):
        return True
    if not RENDERED_SHORT.search(lines[idx]):
        return False
    if spans is None:
        spans = failure_spans(lines)
    return spans[idx]

# A site that already obeys the law. Enumerated DELIBERATELY, even though it can never
# violate: scoring only the raw form would let the denominator SHRINK by one every time a
# site was fixed, so a tree could reach "0 undispositioned" by having no sites left to
# count — the same disappearing-denominator move this wall exists to stop. Routed sites
# stay in the roll, so `compliant + exempt + bad` is the whole population every run.
COMPLIANT = re.compile(r"SafeFailureText\.render\(")

# The disposition marker. Dated so a review can age it, and reasoned so it can be judged.
EXEMPT = re.compile(r"SAFE-RENDER-EXEMPT\[(\d{4}-\d{2}-\d{2})\]:[ \t]*(.*)")

# How far above a site the marker may sit — a rendered string often spans a wrapped
# multi-line log call, so the comment explaining it is not always on the matched line.
EXEMPT_LOOKBACK = 8

MIN_REASON_CHARS = 30
PLACEHOLDER = re.compile(
    r"^(todo|tbd|fixme|n/?a|none|safe|ok|fine|why|reason|\.+|-+|\?+)\b", re.IGNORECASE
)

SOURCES = "gateway/*/src/main/kotlin/**/*.kt"


def in_scope(text):
    """Why this file is in scope — credential vocabulary and/or file I/O — or [] if it is not."""
    why = [m for m in SCOPE_VOCAB if re.search(r"\b%s\b" % m, text)]
    if any(re.search(p, text) for p in SCOPE_IO):
        why.append("file-io")
    return why


def disposition(lines, idx, spans=None):
    """COMPLIANT / EXEMPT / the failure reason for the site at 0-based [idx].

    A raw render is judged raw even on a line that ALSO calls the sanitizer: one sanitized
    half never launders the other, so a mixed line still needs its own exemption.
    """
    if not renders_throwable(lines, idx, spans):
        return "compliant", None
    for back in range(idx, max(-1, idx - EXEMPT_LOOKBACK - 1), -1):
        found = EXEMPT.search(lines[back])
        if not found:
            continue
        reason = found.group(2).strip().rstrip("*/").strip()
        if not reason or PLACEHOLDER.match(reason):
            return "bad", "exemption reason is a placeholder, not a disposition: %r" % reason
        if len(reason) < MIN_REASON_CHARS:
            return "bad", "exemption reason is %d chars, under the %d-char floor: %r" % (
                len(reason), MIN_REASON_CHARS, reason
            )
        return "exempt", found.group(1)
    return "bad", "renders a throwable raw with no SafeFailureText.render and no exemption"


def sites(root):
    """Every rendered-throwable site under a credential/state file, with its disposition."""
    out = []
    for path in sorted(pathlib.Path(root).glob(SOURCES)):
        text = path.read_text(encoding="utf-8", errors="replace")
        markers = in_scope(text)
        if not markers:
            continue
        lines = text.splitlines()
        spans = failure_spans(lines)
        for idx, line in enumerate(lines):
            # A comment cannot render anything at runtime. Skipped so prose ABOUT the law (this
            # file's own header quotes `$failure` as the thing it forbids) is not a violation.
            if line.lstrip().startswith(("//", "*", "/*")):
                continue
            if not renders_throwable(lines, idx, spans) and not COMPLIANT.search(line):
                continue
            verdict, detail = disposition(lines, idx, spans)
            out.append((str(path), idx + 1, line.strip(), verdict, detail, markers))
    return out


def cmd_check(root):
    found = sites(root)
    bad = [s for s in found if s[3] == "bad"]
    for path, line, text, _, detail, markers in bad:
        print("%s:%d: DR-65 violation — %s" % (path, line, detail), file=sys.stderr)
        print("    %s" % text[:110], file=sys.stderr)
        print("    in scope via: %s" % ", ".join(markers), file=sys.stderr)
    counts = {"compliant": 0, "exempt": 0, "bad": 0}
    for site in found:
        counts[site[3]] += 1
    print("safe-failure-render: %d sites in credential/state sources — %d routed, %d exempt, %d undispositioned"
          % (len(found), counts["compliant"], counts["exempt"], counts["bad"]))
    if bad:
        print("every rendered throwable in a credential/state source must go through "
              "SafeFailureText.render, or carry a SAFE-RENDER-EXEMPT[YYYY-MM-DD]: <reason> "
              "comment naming why that throwable cannot quote state bytes", file=sys.stderr)
    return 1 if bad else 0


def cmd_report(root):
    """The disposition roll — the reviewable denominator, not just its failures."""
    for path, line, text, verdict, detail, _ in sites(root):
        print("%-9s %s:%d  %s" % (verdict.upper(), path, line, (detail or "")[:40]))
        print("          %s" % text[:100])
    return 0


def main(argv):
    if len(argv) != 3 or argv[1] not in ("check", "report"):
        print("usage: safe-failure-render.py {check|report} <repo-root>", file=sys.stderr)
        return 2
    return cmd_check(argv[2]) if argv[1] == "check" else cmd_report(argv[2])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
