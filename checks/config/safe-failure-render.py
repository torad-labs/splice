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

COVERAGE — what this wall claims, and what it does NOT. Stated because a wall that overstates its
reach is worse than a narrow one: the overstatement is what stops anyone looking again.

  CLAIMED, anywhere in a scope file: an interpolation of an UNAMBIGUOUS throwable name —
  `${x.message}`, `$failure`, `$cause`, `${someException}` — and any `val` bound from
  `exceptionOrNull()`, at every COLUMN that binding is in scope for. Scope is the language's: an
  inner declaration of the same name hides it for exactly its own block, whether that declaration
  is a `val`, a `var`, a `for` parameter or a lambda parameter, and a `catch` parameter is itself
  a throwable.

  CLAIMED, conditionally: an interpolation of a SHORT name (`it`, `e`, `t`, `ex`, `err`) inside the
  body of a lambda opened by `onFailure` / `exceptionOrNull` / `recover` / `recoverCatching` /
  `catch (`, at the column where `it` is still bound to that lambda — not inside a nested lambda,
  which rebinds it, though nested control blocks keep it. This tier is FRAME-scoped, not
  declaration-scoped: inside a failure lambda a short name is attributed for the whole frame, so a
  local of that spelling holding a String is reported there.

  NOT CLAIMED. `getOrElse` is overloaded and says nothing about the receiver, so a short name in
  its lambda is not attributed. Neither half of a POSITIONAL `Result.fold({…},{…})` is attributed,
  because nothing lexical distinguishes onSuccess from onFailure and `Iterable.fold` shares the
  spelling; the named form is attributed through `onFailure`. A short name reaching a scope file as
  a plain function PARAMETER is not attributed. A `var` is not attributed, because it can be
  reassigned to a String and this scanner has no flow typing to see it. Neither is a local bound
  from `.cause`, which is no more typed than getOrElse. Type information would settle all of these;
  this scanner has none, and guessing is what produced the inversions DR-157 and DR-160 record.

  NOT CLAIMED, and deliberately: shadowing does NOT apply to the unambiguous tier. A local literally
  named `failure`, `cause` or `...Exception` holding a String is still reported, even where it hides
  a same-named outer throwable. Suppressing the named tier on a declaration would trade this wall's
  one unconditional guarantee for a contrived shape, so the cost is paid the other way — name a
  String `failure` in a credential path and you owe it an exemption.

  The honest summary: use a NAMED throwable and the wall sees it wherever it is. The short-name
  tier is a convenience over the idioms this tree actually uses, never a guarantee.
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
# undecidable by short name and is attributed in NEITHER half (see the coverage note).
# DR-160: `getOrElse` came OUT on codex-splice's evidence that it does not imply a Throwable
# receiver — `list.getOrElse(0) { "missing $it" }` binds an Int and `map.getOrElse(k) { … }` binds
# nothing, so both flagged plain Strings. What remains are combinators whose lambda parameter is a
# Throwable by the type's signature.
#
# `exceptionOrNull` STAYED, though codex also reported it, because the evidence pointed at a
# different cause. Its false positive was a bare `outcome.exceptionOrNull()` STATEMENT poisoning a
# later `if (x) {` inside a sibling lambda — the segment between two braces held the combinator, so
# a CONTROL block was opened as a failure lambda. That is fixed at the brace decision below (a
# control-flow head is never a lambda), not by deleting the combinator. Deleting it would have cost
# real coverage: `exceptionOrNull()?.let { … }` genuinely binds the throwable to `it` and is live in
# KimiRefreshedTokens.
# A NAMED throwable is still matched anywhere by [RENDERED], so this narrows only the short-name
# claim — see the module docstring's coverage note.
FAILURE_CONTEXT = re.compile(
    r"onFailure|exceptionOrNull|recoverCatching|recover\b|\bcatch\s*\("
)

# DR-160: a DECLARATION is not a call. `fun onFailure(e: Event) { … }` matched the combinator name
# and made the whole method body a failure span, so `$e` — an Event — was flagged. Declarations are
# stripped from a segment before the combinator search.
#
# Round 2: `\bfun\s+\w+\s*\(` only recognised a bare name, so `fun Result<Event>.onFailure(…)`
# — a receiver with a generic — still collided. It now runs from `fun` to that declaration's opening
# paren, which covers receivers, generics and qualified names alike.
FUN_DECL = re.compile(r"\bfun\b[^(\n]*\(")

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

# DR-154 / DR-156 / DR-158 / DR-160 — ONE lexer with a STATE STACK, because every hole in this
# scanner traced back to reading structure off text that still contained non-syntax, and each
# targeted patch only moved the hole. codex-splice mutation-proved each class from the source:
#   * a URL's `//` inside a string ate a one-line failure lambda's closing brace (FALSE POSITIVE);
#   * `}` inside a block comment and inside a char literal popped the brace depth early, closing a
#     failure span so a genuine render below it was missed (FALSE NEGATIVE — a green lie);
#   * Kotlin block comments NEST, and a boolean in/out flag exits at the INNER `*/`;
#   * the render matcher read the RAW line, so trailing-comment prose counted as interpolation;
#   * DR-160: a string TEMPLATE's `${ … }` is CODE, and blanking it hid a whole failure lambda
#     (`"outer ${runCatching { x }.getOrElse { "failed $it" }} tail"`) — including its braces, its
#     combinators, and the nested string inside it;
#   * DR-160: `\$it` is an ESCAPED dollar, a literal, not an interpolation.
#
# A flat flag cannot express any of that. Kotlin nests string-in-template-in-string arbitrarily, so
# the lexer carries a STACK and the two views differ in exactly one respect: `code` sees structure
# and never string CONTENT; `text` sees content and never COMMENTS. Interpolation code appears in
# BOTH, because it is simultaneously real code and inside a string.
def lex(lines):
    r"""(code, text) per line, column-aligned with the raw line.

    `code`  — strings, char literals and comments blanked; template `${…}` KEPT. Drives brace depth
              and decides which combinator governs a brace.
    `text`  — comments blanked, string/char content KEPT, escaped `\$` neutralised. Drives
              interpolation matching, which by definition happens inside a string.

    Every branch emits exactly as many characters as it consumes, so a column index means the same
    thing in `code`, `text` and the raw line. renders_throwable relies on that alignment.
    """
    code_out, text_out = [], []
    stack = []  # innermost last: ("block", depth) | ("str", quote) | ("raw",) | ("char",) | ("interp", depth)
    for raw in lines:
        code, text, i, n = [], [], 0, len(raw)
        while i < n:
            top = stack[-1][0] if stack else "code"

            if top == "block":
                if raw.startswith("/*", i):          # Kotlin block comments NEST
                    stack[-1] = ("block", stack[-1][1] + 1)
                    code.append("  "); text.append("  "); i += 2
                elif raw.startswith("*/", i):
                    depth = stack[-1][1] - 1
                    if depth:
                        stack[-1] = ("block", depth)
                    else:
                        stack.pop()
                    code.append("  "); text.append("  "); i += 2
                else:
                    code.append(" "); text.append(" "); i += 1

            elif top in ("str", "raw", "char"):
                closer = stack[-1][1] if top == "str" else ("\"\"\"" if top == "raw" else "'")
                if top != "raw" and raw[i] == "\\" and i + 1 < n:
                    # An escape. `\$` in particular is a LITERAL dollar and must not read as an
                    # interpolation in the text view — the whole point of DR-160's second boundary.
                    code.append("  ")
                    text.append("  " if raw[i + 1] == "$" else raw[i:i + 2])
                    i += 2
                elif raw.startswith("${", i):
                    stack.append(("interp", 0))
                    code.append("${"); text.append("${"); i += 2
                elif raw.startswith(closer, i):
                    stack.pop()
                    code.append(" " * len(closer)); text.append(raw[i:i + len(closer)])
                    i += len(closer)
                else:
                    code.append(" "); text.append(raw[i]); i += 1

            else:  # "code" (top level) or "interp" (inside a template) — both are real code
                if raw.startswith("//", i):
                    code.append(" " * (n - i)); text.append(" " * (n - i))
                    break
                elif raw.startswith("/*", i):
                    stack.append(("block", 1))
                    code.append("  "); text.append("  "); i += 2
                elif raw.startswith('\"\"\"', i):
                    stack.append(("raw",))
                    code.append("   "); text.append(raw[i:i + 3]); i += 3
                elif raw[i] == '"':
                    stack.append(("str", '"'))
                    code.append(" "); text.append(raw[i]); i += 1
                elif raw[i] == "'":
                    stack.append(("char",))
                    code.append(" "); text.append(raw[i]); i += 1
                elif top == "interp" and raw[i] == "{":
                    stack[-1] = ("interp", stack[-1][1] + 1)
                    code.append("{"); text.append("{"); i += 1
                elif top == "interp" and raw[i] == "}":
                    if stack[-1][1]:
                        stack[-1] = ("interp", stack[-1][1] - 1)
                    else:
                        stack.pop()  # the template hole closes; we are back inside the string
                    code.append("}"); text.append("}"); i += 1
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


# DR-160 round 2: a segment must end at a STATEMENT boundary, not only at a brace. `outcome
# .exceptionOrNull()` on one line followed by `values.forEach {` on the next let the combinator
# reach forward into an unrelated lambda — the control-head cut fixed the `if` case and left this
# one. A newline ends the statement unless the expression is still open: the next line continues a
# call chain (`.onFailure {` on its own line is the tree's own idiom), or this one ends on an
# operator.
#
# ROUND 3 — these are tested against the TEXT view, and that is the whole fix for grok-splice's
# third root. The code view BLANKS string content, so `val e = "event"` becomes `val e =` and
# rstrip-ends on `=`: the statement was read as still open, swallowed the next line, and the brace
# stash then spliced that line's exceptionOrNull() onto the string's right-hand side, rebinding the
# name at the OUTER depth. A continuation test run against a view that deleted the evidence it
# needs cannot be right; the text view keeps the closing quote, so the statement ends where it
# visibly ends. (`val e = 1` never reproduced it — an int leaves a digit behind, a string leaves
# nothing. That pair is the discriminator, and it is arm 36 below.)
CONTINUES = re.compile(r"[.,=+\-*/%&|?:<>(\[{]$|->$|\b(?:and|or)$")
CONTINUED_BY = re.compile(r"^\s*[.)\]}]|^\s*\?\.")


def continuations(text_lines):
    """Per line: is the statement still open across the newline that follows it?"""
    out = []
    for i, txt in enumerate(text_lines):
        nxt = text_lines[i + 1] if i + 1 < len(text_lines) else ""
        out.append(bool(CONTINUES.search(txt.rstrip()) or CONTINUED_BY.match(nxt)))
    return out


def _frame_kind(segment):
    """Which kind of frame a `{` opens: "failure", None (a transparent control block), or "shadow".

    FAILURE is tested FIRST and that order is load-bearing. `catch (e: IOException) {` matches both
    the combinator list and the control-head list, and when the control test won, `catch` could not
    open a failure frame at all — while the published coverage statement named it. A claim the code
    contradicts is worse than a narrower claim.
    """
    if FAILURE_CONTEXT.search(FUN_DECL.sub(" ", segment)):
        return "failure"
    if CONTROL_HEAD.search(segment.rstrip() or " "):
        return None
    return "shadow"


def failure_spans(lines):
    """Per line and COLUMN: may a SHORT name (`it`, `e`, …) be read as the throwable here?

    Only short names consult this — an unambiguous `$failure` is a render wherever it appears — so
    the question is precisely "is `it` bound to the throwable at this column".

    Structural, not a fixed lookback. The first version asked whether a failure combinator appeared
    within 3 lines above, and codex-splice mutation-proved the hole: a real `.onFailure { … }` whose
    nested cleanup pushes the render five lines below the opener stayed GREEN. Widening the constant
    only moves the hole into the next nested block.

    DR-157 made the combinator/brace pairing POSITIONAL: a combinator can only govern a brace that
    comes AFTER it, so each `{` is judged on the code between it and the previous boundary.

    DR-159 added shadowing, and DR-160 round 2 replaced that single shadow depth with a FRAME STACK.
    One depth could not express a genuine failure lambda nested INSIDE a shadowing one —
    `onFailure { names.forEach { runCatching { … }.onFailure { log("$it") } } }` — where the
    innermost frame rebinds `it` back to a throwable. That was a false negative, and a stack is what
    the language's own scoping rule looks like: the innermost binder wins.
    """
    code_lines, text_lines = lex(lines)
    cont = continuations(text_lines)
    attributable = []
    depth, frames = 0, []  # frames: (depth, is_failure) — only LAMBDAS push; control blocks are transparent
    segment = ""
    for i, code in enumerate(code_lines):
        flags = bytearray(len(code))
        for col, ch in enumerate(code):
            if ch == "{":
                depth += 1
                kind = _frame_kind(segment)
                if kind:
                    frames.append((depth, kind == "failure"))
                segment = ""
            elif ch == "}":
                while frames and frames[-1][0] == depth:
                    frames.pop()
                depth = max(0, depth - 1)
                segment = ""
            elif ch == ";":
                segment = ""
            else:
                segment += ch
            flags[col] = 1 if (frames and frames[-1][1]) else 0
        segment = segment + "\n" if cont[i] else ""
        attributable.append(flags)
    return attributable


# DR-160: a throwable BOUND TO A LOCAL, which has no lambda and therefore no failure frame at all.
# codex-splice's fixture — `val e = outcome.exceptionOrNull()` then `log("$e")` — was missed: `e` is
# too short for [THROWABLE_NAMED] and there is no brace to attribute it to. The binding itself is
# the evidence.
#
# Round 2 narrowed it: `.cause` and `var` came out (neither is typed, and without flow typing
# neither claim can be honoured), the head and the SOURCE became separate patterns so compliance is
# tested against the binding's own WHOLE right-hand side, every binding in a statement is recorded
# rather than the first, and matching moved from the line to the statement.
#
# Round 3 rebuilt the SCOPE model, because round 2 computed structure per column and then REPORTED
# it per line — the same inversion the spans plane had before round 2, and it cut both ways at once.
# grok-splice and codex-splice each reddened one side:
#   * FALSE NEGATIVE — `if (x) { val e = outcome.exceptionOrNull(); log("$e") }`. The binding and its
#     use are on one line, and the closing brace pruned the name before the line-end snapshot, so
#     the render was invisible. Nothing was wrong with the walk; the REPORT threw the answer away.
#   * FALSE POSITIVE — an outer throwable `e` with an inner `val e = "event"` beside its use. Only
#     THROWABLE sources were recorded, so a plain declaration could not hide anything, and the outer
#     binding stayed visible under a name that no longer referred to it.
# Both say the same thing: a name's meaning is a property of a POSITION, not of a line. So bindings
# are now reported per column like spans, and EVERY declaration is recorded — throwable or not —
# with lookup taking the innermost. A shadow is just a binding that is not a throwable, which is
# also how the language sees it.
BINDING_HEAD = re.compile(r"\b(?:val|var)\s+(\w+)\s*(?::[^=]+)?=\s*(?![=])")
THROWABLE_SOURCE = re.compile(r"\bexceptionOrNull\s*\(")

# Declarations that are not `val x = …` and so are invisible to [BINDING_HEAD], each of which
# grok-splice caught failing to shadow an outer throwable of the same name.
FOR_PARAM = re.compile(r"\bfor\s*\(\s*(?:val\s+|var\s+)?(\w+)(?:\s*:[^)]*?)?\s+in\b")
CATCH_PARAM = re.compile(r"\bcatch\s*\(\s*(\w+)\s*:")
LAMBDA_PARAMS = re.compile(r"^\s*\(?\s*(\w+(?:\s*,\s*\w+)*)\s*\)?\s*$")
# `else ->` and `is Foo ->` are when-branch arrows, not parameter lists. Only `else` can look like a
# single bare name, and a one-word branch head is the only thing [LAMBDA_PARAMS] would accept.
NOT_A_PARAM = frozenset({"else", "is", "in", "when", "true", "false", "null", "this"})

# ...but a keyword list is not enough, and this one is mine rather than a reviewer's: `when (k) { e
# -> … }` compares the subject to the VALUE e, it does not declare one. Read as a lambda parameter
# it shadowed the real throwable and the render went silent — a false negative introduced by the
# fix for the false positives above. The two arrows are only separable by the block they sit in, so
# the block remembers whether a `when` head opened it.
WHEN_BLOCK = re.compile(r"\bwhen\s*(?:\([^)]*\))?\s*$")


def throwable_bindings(lines):
    """Per line, per COLUMN: the names that mean a throwable AT that column.

    Scope is the language's, not the line's: declarations push, a closing brace pops everything it
    encloses, and a lookup takes the INNERMOST declaration of a name — so an inner `val e = "event"`
    hides an outer throwable `e` for exactly its own block and no longer.

    A `{` INTERRUPTS the statement it appears in rather than ending it: `val e = runCatching { … }
    .exceptionOrNull()` is one binding, and resetting at the brace lost its head. The interrupted
    statement is stashed with the depth it belongs to and resumes when its brace closes, carrying
    the block body with it so the compliance test can still see a sanitizer call inside the lambda.
    """
    code_lines, text_lines = lex(lines)
    cont = continuations(text_lines)
    live, out, stash = [], [], []
    depth, segment, seg_depth = 0, "", 0
    visible = frozenset()
    for i, code in enumerate(code_lines):
        per_col, before = [], len(live)
        for ch in code:
            if len(live) != before:
                visible, before = _visible(live), len(live)
            per_col.append(visible)
            if ch == "{":
                # for/catch parameters belong to the block the brace opens, not to the head.
                _declare_params(segment, depth + 1, live)
                _register(segment, seg_depth, live)
                stash.append([segment, seg_depth, depth, "", bool(WHEN_BLOCK.search(segment.rstrip()))])
                depth += 1
                segment, seg_depth = "", depth
            elif ch == "}":
                # Register the block's own trailing statement BEFORE the depth drops, so it is
                # recorded at the inner depth and the pop below takes it back out again.
                _register(segment, seg_depth, live)
                depth = max(0, depth - 1)
                live = [b for b in live if b[1] <= depth]
                if stash and stash[-1][2] == depth:
                    outer, outer_depth, _, body, _when = stash.pop()
                    segment, seg_depth = outer + " { " + INNER_DECL.sub("   ", body) + " } ", outer_depth
                else:
                    segment, seg_depth = "", depth
            elif ch == ";":
                _register(segment, seg_depth, live)
                segment, seg_depth = "", depth
            elif ch == ">" and segment.endswith("-"):
                # A lambda's parameter list: `{ e -> …` declares e for this block. A when BRANCH
                # arrow declares nothing, so the enclosing block decides which arrow this is.
                if not (stash and stash[-1][4]):
                    _declare_lambda(segment[:-1], depth, live)
                segment, seg_depth = "", depth
            else:
                segment += ch
                for frame in stash:
                    frame[3] += ch
        if cont[i]:
            segment += "\n"
        else:
            _register(segment, seg_depth, live)
            segment, seg_depth = "", depth
        if len(live) != before:
            visible = _visible(live)
        out.append(per_col)
    return out


def _visible(live):
    """The names that mean a throwable, taking the INNERMOST declaration of each."""
    innermost = {}
    for name, _, throwable in live:
        innermost[name] = throwable
    return frozenset(name for name, throwable in innermost.items() if throwable)


# On restoring an interrupted statement the lambda body comes back with it, and a declaration made
# INSIDE that body has already been recorded at its own inner depth. The keywords are blanked so the
# body can still be read for compliance evidence without re-declaring anything at the outer depth.
INNER_DECL = re.compile(r"\b(?:val|var)\b")


def _register(statement, depth, live):
    """Record every local declared by one completed statement, at [depth].

    A declaration whose right-hand side is an unsanitized `exceptionOrNull()` means a throwable;
    every other declaration is a SHADOW, recorded so it can hide an outer name of its own spelling.
    """
    heads = list(BINDING_HEAD.finditer(statement))
    for n, head in enumerate(heads):
        stop = heads[n + 1].start() if n + 1 < len(heads) else len(statement)
        rhs = statement[head.end():stop]
        # A binding whose OWN right-hand side routes through the sanitizer holds a rendered STRING,
        # not a throwable — `val reason = attempt.exceptionOrNull()?.let { render(it) }` in
        # UninstallCommand.kt is the live shape, and it is why the window must be the whole RHS
        # rather than the head: the sanitizer call sits past the combinator, inside the lambda.
        # `var` can be reassigned to a String with no flow typing to catch it, so it never claims.
        throwable = (
            head.group(0).lstrip().startswith("val")
            and bool(THROWABLE_SOURCE.search(rhs))
            and not COMPLIANT.search(rhs)
        )
        live.append((head.group(1), depth, throwable))


def _declare_params(segment, depth, live):
    """`for (e in xs)` and `catch (e: IOException)` declare e for the block their head precedes."""
    for found in FOR_PARAM.finditer(segment):
        live.append((found.group(1), depth, False))
    for found in CATCH_PARAM.finditer(segment):
        # A catch parameter IS a throwable, and saying so here is what lets it shadow correctly
        # rather than merely being covered by the short-name tier.
        live.append((found.group(1), depth, True))


def _declare_lambda(segment, depth, live):
    """`{ e -> … }` and `{ a, b -> … }` declare their parameters for the enclosing block."""
    found = LAMBDA_PARAMS.match(segment)
    if not found:
        return
    for name in (n.strip() for n in found.group(1).split(",")):
        if name and name not in NOT_A_PARAM:
            live.append((name, depth, False))


def renders_throwable(lines, idx, spans=None, text_lines=None, bindings=None):
    """Does line [idx] interpolate a throwable into text?

    DR-158: reads the COMMENT-BLANKED view, not the raw line. A trailing `// … $it …` explaining
    the law is prose and cannot interpolate anything at runtime, but the raw line made it
    indistinguishable from a real render — codex-splice proved it with
    `val ignored = 1 // raw $it would leak` inside an onFailure. String content is deliberately
    PRESERVED in this view, because a real interpolation lives inside a string by definition.
    """
    if text_lines is None:
        text_lines = lex(lines)[1]
        bindings = throwable_bindings(lines)
    line = text_lines[idx]
    if RENDERED.search(line):
        return True
    if bindings is None:
        bindings = throwable_bindings(lines)
    # DR-160 round 3: at the MATCH's own column, for the same reason the short-name tier already
    # was — a name can be declared, shadowed, or closed out of scope partway along one line.
    per_col = bindings[idx]
    for hit in INTERPOLATED_NAME.finditer(line):
        col = hit.start()
        if col < len(per_col) and (hit.group(1) or hit.group(2)) in per_col[col]:
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


# DR-160: positional_folds() is GONE. It existed because `.fold(` was in FAILURE_CONTEXT and
# matched onSuccess, so an unnamed fold had to be failed by name rather than guessed. With short
# names no longer attributed through fold at all, the polarity question disappears — a NAMED
# throwable in either lambda is caught by [RENDERED] wherever it sits, and `it` is attributed in
# neither. The rule was also a live FALSE POSITIVE on `items.fold(0) { acc, x -> … }`, which is
# Iterable.fold and has no failure half to name.


# A site that already obeys the law. Enumerated DELIBERATELY, even though it can never
# violate: scoring only the raw form would let the denominator SHRINK by one every time a
# site was fixed, so a tree could reach "0 undispositioned" by having no sites left to
# count — the same disappearing-denominator move this wall exists to stop. Routed sites
# stay in the roll, so `compliant + exempt + bad` is the whole population every run.
COMPLIANT = re.compile(r"SafeFailureText\.render\(")

# Any interpolated identifier, so the binding tier can be asked "does THIS name mean a throwable at
# THIS column" in one pass rather than once per known name.
INTERPOLATED_NAME = re.compile(r"\$\{\s*(\w+)\s*\}|\$(\w+)\b")

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


def disposition(lines, idx, spans=None, text_lines=None, bindings=None):
    """COMPLIANT / EXEMPT / the failure reason for the site at 0-based [idx].

    A raw render is judged raw even on a line that ALSO calls the sanitizer: one sanitized
    half never launders the other, so a mixed line still needs its own exemption.
    """
    if not renders_throwable(lines, idx, spans, text_lines, bindings):
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
        bindings = throwable_bindings(lines)
        for idx, line in enumerate(lines):
            rendered = renders_throwable(lines, idx, spans, text_lines, bindings)
            if not rendered and not COMPLIANT.search(text_lines[idx]):
                continue
            verdict, detail = disposition(lines, idx, spans, text_lines, bindings)
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
