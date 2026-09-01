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
#
# DR-157: `\.fold\(` USED to be in this list and had to come out. Result.fold takes TWO lambdas,
# and the list is only consulted for "was a failure combinator seen before this brace" — so the
# FIRST lambda matched, which is onSuccess. codex-splice's fixture proved the exact inversion: the
# success lambda's `$it` was flagged and the failure lambda's `$it` was missed, i.e. a false
# positive and a false negative from one entry. The named form `onFailure = { … }` still matches on
# `onFailure`, which is how the tree's only real fold site is classified; the POSITIONAL form is
# undecidable by short name and is failed by name instead (see [POSITIONAL_FOLD]).
FAILURE_CONTEXT = re.compile(
    r"onFailure|getOrElse|exceptionOrNull|recoverCatching|recover\b|\bcatch\s*\("
)

# DR-159: a `{` that opens a CONTROL BLOCK versus one that opens a LAMBDA. Kotlin's `it` is bound
# per lambda, so a nested lambda inside a failure lambda REBINDS it — `onFailure { names.forEach {
# log("$it") } }` renders a String, not the throwable. A nested control block does NOT rebind, so
# `onFailure { if (x) { log("$it") } }` really is the throwable and must stay caught. Distinguishing
# them is what keeps this from being a choice between a false positive and a false negative.
# A segment ending in `->` is a `when` branch arrow (`is Foo -> {`), which is a block; the lambda
# parameter arrow appears AFTER its own brace and so never ends a segment.
CONTROL_HEAD = re.compile(
    r"(?:\belse|\btry|\bfinally|\bdo|\binit|\bwhen|->)\s*$"
    r"|\b(?:if|while|for|when|catch)\s*\(.*\)\s*$"
)

# DR-157, fail-closed: a `.fold(` whose failure half is NOT named cannot be attributed by this
# scanner — `fold({ a }, { b })` gives it no way to tell onSuccess from onFailure. Guessing is what
# produced the inversion above, and silently declining to look is a false green. So an unnamed fold
# in a scope file is reported BY NAME and the author must use the named form (which the tree
# already does) or exempt it. An unclassifiable site becomes a build failure, never an absence.
POSITIONAL_FOLD = re.compile(r"\.fold\s*\(")
NAMED_FOLD_HALVES = re.compile(r"onFailure\s*=")

# DR-154 / DR-156 / DR-158 — ONE lexer, because every hole in this scanner so far came from
# reading structure off text that still contained non-syntax, and each targeted patch just moved
# the hole. codex-splice mutation-proved four classes from the scanner's own source:
#   * a URL's `//` inside a string ate a one-line failure lambda's closing brace (FALSE POSITIVE —
#     the direction that gets a wall switched off rather than fixed);
#   * `}` inside a block comment and inside a char literal popped the brace depth early, closing the
#     failure span so a genuine raw render below it was missed (FALSE NEGATIVE — a green lie);
#   * Kotlin block comments NEST, and a boolean in/out flag exits at the INNER `*/`, so
#     `/* outer /* inner */ still outer } */` leaked its brace. RETRACTION: an earlier version of
#     this comment claimed four such comments exist in this tree. They do not. That count came from
#     a crude regex of mine that matched `sessions/*` inside a LINE comment; codex-splice's
#     ast-grep over real Kotlin multiline_comment nodes finds ZERO, and a second probe of my own
#     produced a third, equally wrong number. The arm guards the GRAMMAR, which Kotlin defines and
#     any future comment may use — not an observed instance;
#   * the render matcher read the RAW line, so prose in a trailing `// … $it …` comment was
#     reported as runtime interpolation.
#
# Hence TWO views from one pass. They differ in exactly one respect, and that difference is the
# point: brace structure must not see string CONTENT, while interpolation matching must see nothing
# BUT string content.
def lex(lines):
    """(code, text) per line.

    `code` blanks strings, char literals and comments — structure only, for brace depth and for
    deciding which combinator governs a brace.
    `text` blanks comments but PRESERVES string and char content — for matching an interpolation
    that is, by definition, inside a string.

    Multi-line aware: block-comment DEPTH and raw-string state carry across lines, which per-line
    regex stripping structurally cannot do.
    """
    code_out, text_out = [], []
    block = 0        # nesting depth of /* */ — Kotlin nests these
    in_raw = False
    for raw in lines:
        code, text, i, n = [], [], 0, len(raw)
        while i < n:
            if block:
                # Check the OPENER first: `/*/` must not read as an open and a close.
                if raw.startswith("/*", i):
                    block += 1
                    code.append("  "); text.append("  "); i += 2
                elif raw.startswith("*/", i):
                    block -= 1
                    code.append("  "); text.append("  "); i += 2
                else:
                    code.append(" "); text.append(" "); i += 1
            elif in_raw:
                if raw.startswith('\"\"\"', i):
                    in_raw = False
                    code.append("   "); text.append(raw[i:i + 3]); i += 3
                else:
                    code.append(" "); text.append(raw[i]); i += 1
            elif raw.startswith("//", i):
                code.append(" " * (n - i)); text.append(" " * (n - i))
                break
            elif raw.startswith("/*", i):
                block = 1
                code.append("  "); text.append("  "); i += 2
            elif raw.startswith('\"\"\"', i):
                in_raw = True
                code.append("   "); text.append(raw[i:i + 3]); i += 3
            elif raw[i] in "\"'":
                quote = raw[i]
                code.append(" "); text.append(raw[i]); i += 1
                while i < n:
                    if raw[i] == "\\":
                        code.append("  "); text.append(raw[i:i + 2]); i += 2
                        continue
                    code.append(" "); text.append(raw[i])
                    closed = raw[i] == quote
                    i += 1
                    if closed:
                        break
            else:
                code.append(raw[i]); text.append(raw[i]); i += 1
        code_out.append("".join(code))
        text_out.append("".join(text))
    return code_out, text_out


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
    """Per line and COLUMN: may a SHORT name (`it`, `e`, …) be read as the throwable here?

    Only short names consult this — an unambiguous `$failure` is a render wherever it appears — so
    the question is precisely "is `it` bound to the throwable at this column".

    Structural, not a fixed lookback. The first version asked whether a failure combinator appeared
    within 3 lines above, and codex-splice mutation-proved the hole: a real `.onFailure { … }` in
    SecureFile.kt whose nested cleanup pushes the `$it` render five lines below the opener stayed
    GREEN. Widening the constant only moves the hole into the next nested block, so scope is tracked
    by BRACE DEPTH: the body is in context until its own closing brace, at any depth and any length.

    DR-157 makes the combinator/brace pairing POSITIONAL. It used to be a per-LINE flag — "this line
    mentions a failure combinator" — which then attached to the next brace opened anywhere,
    including one already opened EARLIER on the same line. codex-splice's fixture:
    `runCatching { names.forEach { log("$it = stored") } }.exceptionOrNull()` was flagged, because
    the trailing `exceptionOrNull` reached back and claimed the leading `runCatching {`. A
    combinator can only govern a brace that comes AFTER it, so each `{` is judged on the code
    between it and the previous brace token — the call that actually opened it.

    DR-159 adds SHADOWING, and forces column resolution. A nested LAMBDA rebinds `it`, so its body
    is not attributable; a nested CONTROL BLOCK does not, so its body still is. Both can share a
    line with the failure lambda that encloses them — codex-splice's `m.getOrElse(k) { names.forEach
    { log("$it") } }` is one line holding both — so a per-line answer cannot be right. The two lex
    views are column-aligned with the raw line by construction, which is what makes this checkable.
    """
    code_lines, _ = lex(lines)
    attributable = []
    depth, stack, shadow = 0, [], None
    segment = ""  # code seen since the previous brace token: the call that opens the next `{`
    for code in code_lines:
        flags = bytearray(len(code))
        for col, ch in enumerate(code):
            if ch == "{":
                depth += 1
                if FAILURE_CONTEXT.search(segment):
                    stack.append(depth)
                elif stack and shadow is None and not CONTROL_HEAD.search(segment.rstrip() or " "):
                    # A lambda (or a local fun body) opening inside a failure lambda. Either way
                    # `it` is no longer the throwable in there.
                    shadow = depth
                segment = ""
            elif ch == "}":
                if shadow == depth:
                    shadow = None
                if stack and stack[-1] == depth:
                    stack.pop()
                depth = max(0, depth - 1)
                segment = ""
            else:
                segment += ch
            flags[col] = 1 if (stack and shadow is None) else 0
        segment += "\n"
        attributable.append(flags)
    return attributable


def renders_throwable(lines, idx, spans=None, text_lines=None):
    """Does line [idx] interpolate a throwable into text?

    DR-158: reads the COMMENT-BLANKED view, not the raw line. A trailing `// … $it …` explaining
    the law is prose and cannot interpolate anything at runtime, but the raw line made it
    indistinguishable from a real render — codex-splice proved it with
    `val ignored = 1 // raw $it would leak` inside an onFailure. String content is deliberately
    PRESERVED in this view, because a real interpolation lives inside a string by definition.
    """
    if text_lines is None:
        text_lines = lex(lines)[1]
    line = text_lines[idx]
    if RENDERED.search(line):
        return True
    hit = RENDERED_SHORT.search(line)
    if not hit:
        return False
    if spans is None:
        spans = failure_spans(lines)
    # DR-159: at the MATCH's own column, because a nested lambda can rebind `it` partway along the
    # very same line.
    flags = spans[idx]
    col = hit.start()
    return bool(col < len(flags) and flags[col])


def positional_folds(lines):
    """Line numbers of `.fold(` calls whose failure half is not named — DR-157, fail-closed.

    `fold({ a }, { b })` gives the scanner no way to tell onSuccess from onFailure, and guessing is
    exactly what inverted the two. Rather than guess or silently skip, the call is reported by name
    so the author uses the named form the tree already uses, or exempts it.
    """
    code_lines, _ = lex(lines)
    out = []
    for idx, code in enumerate(code_lines):
        if not POSITIONAL_FOLD.search(code):
            continue
        # The named half may sit on a following line — `.fold(` then `onFailure = { … }`.
        window = "\n".join(code_lines[idx:idx + EXEMPT_LOOKBACK])
        if not NAMED_FOLD_HALVES.search(window):
            out.append(idx)
    return out


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


def disposition(lines, idx, spans=None, text_lines=None):
    """COMPLIANT / EXEMPT / the failure reason for the site at 0-based [idx].

    A raw render is judged raw even on a line that ALSO calls the sanitizer: one sanitized
    half never launders the other, so a mixed line still needs its own exemption.
    """
    if not renders_throwable(lines, idx, spans, text_lines):
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
        # DR-158: the comment-blanked view decides BOTH matchers. Comment prose about the law —
        # this file's own header quotes `$failure` as the thing it forbids — is blank here, so it
        # drops out structurally instead of via a "does the line START with //" test that a
        # TRAILING comment walked straight past.
        text_lines = lex(lines)[1]
        for idx, line in enumerate(lines):
            rendered = renders_throwable(lines, idx, spans, text_lines)
            if not rendered and not COMPLIANT.search(text_lines[idx]):
                continue
            verdict, detail = disposition(lines, idx, spans, text_lines)
            out.append((str(path), idx + 1, line.strip(), verdict, detail, markers))
        # DR-157: an unnamed fold is undecidable, so it is a site with its own failure reason
        # rather than a silent skip. It still passes through the SAME exemption machinery.
        for idx in positional_folds(lines):
            verdict, detail = "bad", (
                "positional .fold( — name the halves (onSuccess =/onFailure =) so the failure "
                "lambda can be attributed; the unnamed form is undecidable by this scanner"
            )
            for back in range(idx, max(-1, idx - EXEMPT_LOOKBACK - 1), -1):
                found = EXEMPT.search(lines[back])
                if found and len(found.group(2).strip()) >= MIN_REASON_CHARS:
                    verdict, detail = "exempt", found.group(1)
                    break
            out.append((str(path), idx + 1, lines[idx].strip(), verdict, detail, markers))
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
