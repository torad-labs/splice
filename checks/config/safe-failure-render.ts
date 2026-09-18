#!/usr/bin/env bun
/**
 * DR-140 — the DR-65 wall: no raw throwable text in a credential/state path.
 *
 * DR-65's law is "all failure rendering in credential/state paths goes through
 * SafeFailureText.render". Nothing enforced it, so DR-73 swept the sinks BY HAND and its
 * denominator was FILES rather than SINKS: UsageRingFile's read half was sealed and its
 * write half kept `failure.message` for another eight days (DR-139). A hand sweep closes
 * the instance; only a checker closes the class.
 *
 * DENOMINATOR (the part that matters, per the completeness law): scope is derived from the
 * SOURCE, never from a list of known-interesting files, and never from "files that already
 * call SafeFailureText" — that last one is the tautology this wall exists to avoid, since a
 * NEW credential path that never learned the law would not be in its own denominator. Scope is
 * CAUSAL: DR-65's hazard is an exception quoting the bytes of the file that produced it, which
 * is only reachable if the code TOUCHED FILES, so a source is in scope when it does filesystem
 * I/O (SCOPE_IO) or names credential/state vocabulary (SCOPE_VOCAB, which covers a file that
 * delegates its I/O to a collaborator). No site count is quoted here on purpose: a number in a
 * header rots the first time the tree moves, and `report` prints the live roll on demand.
 *
 * DISPOSITION: every site in scope is COMPLIANT (routed through SafeFailureText.render) or
 * EXEMPT (carries a dated marker naming why that throwable cannot quote state bytes).
 * Absence is not a disposition — an undispositioned site fails BY NAME. A blank, placeholder
 * or too-short reason is an absence wearing a label and fails the same way.
 *
 * COVERAGE — what this wall claims, and what it does NOT. Stated because a wall that overstates its
 * reach is worse than a narrow one: the overstatement is what stops anyone looking again.
 *
 *   CLAIMED, anywhere in a scope file: an interpolation of an UNAMBIGUOUS throwable name —
 *   `${x.message}`, `$failure`, `$cause`, `${someException}` — and any `val` bound from
 *   `exceptionOrNull()`, at every COLUMN that binding is in scope for. Scope is the language's: an
 *   inner declaration of the same name hides it for exactly its own block, whether that declaration
 *   is a `val`, a `var`, a `for` parameter or an EXPLICIT lambda parameter at its arrow — typed or
 *   untyped, including a destructured list and a trailing comma — and a `catch` parameter is itself
 *   a throwable.
 *
 *   CLAIMED, anywhere in a scope file (DR-187): the NON-interpolated spellings of the same two
 *   renders — `failure.message`, `cause?.message`, `someException.toString()`, and the implicit
 *   `"x " + failure` / `append(failure)` — on a receiver the named tier already trusts. Until DR-187
 *   every matcher here keyed on a `$`, so `put("read_error", failure.message)` matched nothing, which
 *   is the DR-65 sink shape verbatim. The receiver's NAME is the type evidence, which is why these are
 *   not in [THROWABLE_TEXT_MEMBER]'s unconditional list: `.message` on an arbitrary receiver says
 *   nothing, `failure.message` says exactly what `$failure` says. The short-name spellings are claimed
 *   on the same failure-frame condition as the interpolated ones, below.
 *
 *   CLAIMED, conditionally: an interpolation of a SHORT name (`it`, `e`, `t`, `ex`, `err`) inside the
 *   body of a lambda opened by `onFailure` / `exceptionOrNull` / `recover` / `recoverCatching` /
 *   `catch (`, at the column where `it` is still bound to that lambda — not inside a nested lambda,
 *   which rebinds it, though nested control blocks keep it. This tier is FRAME-scoped, not
 *   declaration-scoped: inside a failure lambda a short name is attributed for the whole frame, so a
 *   local of that spelling holding a String is reported there.
 *
 *   NOT CLAIMED. `getOrElse` is overloaded and says nothing about the receiver, so a short name in
 *   its lambda is not attributed. Neither half of a POSITIONAL `Result.fold({…},{…})` is attributed,
 *   because nothing lexical distinguishes onSuccess from onFailure and `Iterable.fold` shares the
 *   spelling; the named form is attributed through `onFailure`. A short name reaching a scope file as
 *   a plain function PARAMETER is not attributed. A `var` is not attributed, because it can be
 *   reassigned to a String and this scanner has no flow typing to see it. Neither is a local bound
 *   from `.cause`, which is no more typed than getOrElse. Type information would settle all of these;
 *   this scanner has none, and guessing is what produced the inversions DR-157 and DR-160 record.
 *
 *   NOT CLAIMED, and this one is a KNOWN residual rather than an oversight: an IMPLICIT `it` is not a
 *   declaration on the binding plane, and a nested arrowless lambda is read as rebinding the short
 *   name on the other plane. `forEach { … }` does rebind it; `run { … }` and `apply { … }` do not, and
 *   the two are spelled identically, so no lexical rule separates them. The consequence, pinned by an
 *   arm so a future change cannot silently alter it: a raw `$it` inside `run { … }` inside a failure
 *   lambda is NOT reported. Declaring an implicit `it` everywhere would green that one and open a
 *   false positive on every `forEach` in a failure lambda — a strictly worse trade, since this plane's
 *   errors would then hide renders instead of over-reporting them. Use a named throwable, or an
 *   explicit parameter, and the wall sees it.
 *
 *   NOT CLAIMED, and deliberately: shadowing does NOT apply to the unambiguous tier. A local literally
 *   named `failure`, `cause` or `...Exception` holding a String is still reported, even where it hides
 *   a same-named outer throwable. Suppressing the named tier on a declaration would trade this wall's
 *   one unconditional guarantee for a contrived shape, so the cost is paid the other way — name a
 *   String `failure` in a credential path and you owe it an exemption.
 *
 *   The honest summary: use a NAMED throwable and the wall sees it wherever it is. The short-name
 *   tier is a convenience over the idioms this tree actually uses, never a guarantee.
 *
 * Usage:
 *     bun checks/config/safe-failure-render.ts check <repo-root>
 *     bun checks/config/safe-failure-render.ts report <repo-root>
 *
 * THERE IS NO BARE MODE: a wrong argument count or an unknown verb prints this usage line and exits 2,
 * which is the shape V4-142 requires and which this file already had.
 */
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

// SCOPE IS CAUSAL, not a vocabulary guess. DR-65's hazard is an exception whose text quotes
// "the bytes of the file that produced it" — which is only reachable if the code TOUCHES FILES.
// So a file is in scope when it does filesystem I/O, or when it names credential/state
// vocabulary (which covers a file that delegates its I/O to a collaborator).
//
// The first draft of this wall scoped on the vocabulary list ALONE, and two independent reviews
// mutation-proved the hole within the hour: CodexAuthFile.kt and KimiOAuth.kt name no marker at
// all, so a raw render planted in either did not move the site count. A denominator assembled
// from a list of names I thought of is the same hand-argued denominator that let DR-73's sweep
// miss UsageRingFile — the exact failure this wall exists to end, recurring inside the checker
// written to end it. Causality is checkable; a vocabulary list is a memory test.
const SCOPE_VOCAB = [
  "SafeFailureText", "KeyStore", "KeyStorePath", "StatePaths", "SecureFile",
  "TopologyLoader", "CredentialJson", "Credentials", "MgmtKey", "LoginOutcomeFile",
  "JsonlSink",
];
const SCOPE_IO = [/java\.nio\.file/, /\bFiles\./, /\bPath\b/, /FileChannel/, /writeAtomic/, /readString/];

// Identifiers that name a throwable. Kotlin has no type info here, so the matcher keys on the
// naming the codebase actually uses at catch/onFailure/getOrElse sites.
//
// Two tiers, because `it` is Kotlin's UNIVERSAL lambda parameter. Treating it as a throwable
// everywhere flagged `"Bearer $it"`, `"$it = stored"` and eight more where `it` is a String —
// and a wall that cries wolf gets its exemptions rubber-stamped, which is how a wall dies. So
// the ambiguous short names count only inside a failure-handling lambda; the unambiguous ones
// count anywhere.
const THROWABLE_NAMED = "(?:cause|failure|throwable|\\w+(?:Failure|Error|Exception|Cause))";
const THROWABLE_SHORT = "(?:it|e|t|ex|err)";
//
// DR-157: `\.fold\(` USED to be in this list and had to come out. Result.fold takes TWO lambdas,
// and the list is only consulted for "was a failure combinator seen before this brace" — so the
// FIRST lambda matched, which is onSuccess. codex-splice's fixture proved the exact inversion: the
// success lambda's `$it` was flagged and the failure lambda's `$it` was missed, i.e. a false
// positive and a false negative from one entry. The named form `onFailure = { … }` still matches on
// `onFailure`, which is how the tree's only real fold site is classified; the POSITIONAL form is
// undecidable by short name and is attributed in NEITHER half (see the coverage note).
// DR-160: `getOrElse` came OUT on codex-splice's evidence that it does not imply a Throwable
// receiver — `list.getOrElse(0) { "missing $it" }` binds an Int and `map.getOrElse(k) { … }` binds
// nothing, so both flagged plain Strings. What remains are combinators whose lambda parameter is a
// Throwable by the type's signature.
//
// `exceptionOrNull` STAYED, though codex also reported it, because the evidence pointed at a
// different cause. Its false positive was a bare `outcome.exceptionOrNull()` STATEMENT poisoning a
// later `if (x) {` inside a sibling lambda — the segment between two braces held the combinator, so
// a CONTROL block was opened as a failure lambda. That is fixed at the brace decision below (a
// control-flow head is never a lambda), not by deleting the combinator. Deleting it would have cost
// real coverage: `exceptionOrNull()?.let { … }` genuinely binds the throwable to `it` and is live in
// KimiRefreshedTokens.
// A NAMED throwable is still matched anywhere by [RENDERED], so this narrows only the short-name
// claim — see the module docstring's coverage note.
const FAILURE_CONTEXT = /onFailure|exceptionOrNull|recoverCatching|recover\b|\bcatch\s*\(/;

// DR-160: a DECLARATION is not a call. `fun onFailure(e: Event) { … }` matched the combinator name
// and made the whole method body a failure span, so `$e` — an Event — was flagged. Declarations are
// stripped from a segment before the combinator search.
//
// Round 2: `\bfun\s+\w+\s*\(` only recognised a bare name, so `fun Result<Event>.onFailure(…)`
// — a receiver with a generic — still collided. It now runs from `fun` to that declaration's opening
// paren, which covers receivers, generics and qualified names alike.
const FUN_DECL = /\bfun\b[^(\n]*\(/g;

// DR-159: a `{` that opens a CONTROL BLOCK versus one that opens a LAMBDA. Kotlin's `it` is bound
// per lambda, so a nested lambda inside a failure lambda REBINDS it — `onFailure { names.forEach {
// log("$it") } }` renders a String, not the throwable. A nested control block does NOT rebind, so
// `onFailure { if (x) { log("$it") } }` really is the throwable and must stay caught. Distinguishing
// them is what keeps this from being a choice between a false positive and a false negative.
// A segment ending in `->` is a `when` branch arrow (`is Foo -> {`), which is a block; the lambda
// parameter arrow appears AFTER its own brace and so never ends a segment.
const CONTROL_HEAD = /(?:\belse|\btry|\bfinally|\bdo|\binit|\bwhen|->)\s*$|\b(?:if|while|for|when|catch)\s*\(.*\)\s*$/;

/** Python's `str.splitlines()`: no trailing empty element, and separators beyond `\n`. */
const LINE_BOUNDARY = /\r\n|[\n\r\v\f\x1c-\x1e\x85\u2028\u2029]/;
function pySplitlines(text: string): string[] {
  if (text === "") return [];
  const parts = text.split(LINE_BOUNDARY);
  if (LINE_BOUNDARY.test(text.slice(-2)) || /\r\n$/.test(text)) parts.pop();
  return parts;
}

type Frame = { kind: string; n: number };

// DR-154 / DR-156 / DR-158 / DR-160 — ONE lexer with a STATE STACK, because every hole in this
// scanner traced back to reading structure off text that still contained non-syntax, and each
// targeted patch only moved the hole. codex-splice mutation-proved each class from the source:
//   * a URL's `//` inside a string ate a one-line failure lambda's closing brace (FALSE POSITIVE);
//   * `}` inside a block comment and inside a char literal popped the brace depth early, closing a
//     failure span so a genuine render below it was missed (FALSE NEGATIVE — a green lie);
//   * Kotlin block comments NEST, and a boolean in/out flag exits at the INNER `*/`;
//   * the render matcher read the RAW line, so trailing-comment prose counted as interpolation;
//   * DR-160: a string TEMPLATE's `${ … }` is CODE, and blanking it hid a whole failure lambda
//     (`"outer ${runCatching { x }.getOrElse { "failed $it" }} tail"`) — including its braces, its
//     combinators, and the nested string inside it;
//   * DR-160: `\$it` is an ESCAPED dollar, a literal, not an interpolation.
//
// A flat flag cannot express any of that. Kotlin nests string-in-template-in-string arbitrarily, so
// the lexer carries a STACK and the two views differ in exactly one respect: `code` sees structure
// and never string CONTENT; `text` sees content and never COMMENTS. Interpolation code appears in
// BOTH, because it is simultaneously real code and inside a string.
//
// `code`  — strings, char literals and comments blanked; template `${…}` KEPT. Drives brace depth
//           and decides which combinator governs a brace.
// `text`  — comments blanked, string/char content KEPT, escaped `\$` neutralised. Drives
//           interpolation matching, which by definition happens inside a string.
//
// Every branch emits exactly as many characters as it consumes, so a column index means the same
// thing in `code`, `text` and the raw line. renders_throwable relies on that alignment.
function lex(lines: string[]): [string[], string[]] {
  const codeOut: string[] = [];
  const textOut: string[] = [];
  const stack: Frame[] = []; // innermost last: block | str | raw | char | interp
  for (const raw of lines) {
    const code: string[] = [];
    const text: string[] = [];
    let i = 0;
    const n = raw.length;
    while (i < n) {
      const top = stack.length > 0 ? stack[stack.length - 1].kind : "code";

      if (top === "block") {
        if (raw.startsWith("/*", i)) {          // Kotlin block comments NEST
          stack[stack.length - 1] = { kind: "block", n: stack[stack.length - 1].n + 1 };
          code.push("  "); text.push("  "); i += 2;
        } else if (raw.startsWith("*/", i)) {
          const depth = stack[stack.length - 1].n - 1;
          if (depth) stack[stack.length - 1] = { kind: "block", n: depth };
          else stack.pop();
          code.push("  "); text.push("  "); i += 2;
        } else {
          code.push(" "); text.push(" "); i += 1;
        }
      } else if (top === "str" || top === "raw" || top === "char") {
        // The closer as a STRING, never the frame's numeric field: `String(34)` is "34", not a
        // quote, and that bug silently unclosed every ordinary string in the tree.
        const closer = top === "str" ? '"' : top === "raw" ? '"""' : "'";
        if (top !== "raw" && raw[i] === "\\" && i + 1 < n) {
          // An escape. `\$` in particular is a LITERAL dollar and must not read as an
          // interpolation in the text view — the whole point of DR-160's second boundary.
          code.push("  ");
          text.push(raw[i + 1] === "$" ? "  " : raw.slice(i, i + 2));
          i += 2;
        } else if (raw.startsWith("${", i)) {
          stack.push({ kind: "interp", n: 0 });
          code.push("${"); text.push("${"); i += 2;
        } else if (raw.startsWith(closer, i)) {
          stack.pop();
          code.push(" ".repeat(closer.length));
          text.push(raw.slice(i, i + closer.length));
          i += closer.length;
        } else {
          code.push(" "); text.push(raw[i]); i += 1;
        }
      } else { // "code" (top level) or "interp" (inside a template) — both are real code
        if (raw.startsWith("//", i)) {
          code.push(" ".repeat(n - i)); text.push(" ".repeat(n - i));
          break;
        } else if (raw.startsWith("/*", i)) {
          stack.push({ kind: "block", n: 1 });
          code.push("  "); text.push("  "); i += 2;
        } else if (raw.startsWith('"""', i)) {
          stack.push({ kind: "raw", n: 0 });
          code.push("   "); text.push(raw.slice(i, i + 3)); i += 3;
        } else if (raw[i] === '"') {
          stack.push({ kind: "str", n: 34 }); // the char code of `"`
          code.push(" "); text.push(raw[i]); i += 1;
        } else if (raw[i] === "'") {
          stack.push({ kind: "char", n: 0 });
          code.push(" "); text.push(raw[i]); i += 1;
        } else if (top === "interp" && raw[i] === "{") {
          stack[stack.length - 1] = { kind: "interp", n: stack[stack.length - 1].n + 1 };
          code.push("{"); text.push("{"); i += 1;
        } else if (top === "interp" && raw[i] === "}") {
          if (stack[stack.length - 1].n) {
            stack[stack.length - 1] = { kind: "interp", n: stack[stack.length - 1].n - 1 };
          } else {
            stack.pop(); // the template hole closes; we are back inside the string
          }
          code.push("}"); text.push("}"); i += 1;
        } else {
          code.push(raw[i]); text.push(raw[i]); i += 1;
        }
      }
    }
    codeOut.push(code.join(""));
    textOut.push(text.join(""));
  }
  return [codeOut, textOut];
}

// A throwable rendered INTO TEXT. Both interpolation forms, because the BARE one is strictly
// WORSE: `$failure` calls toString(), which is the class name PLUS the same message — including
// kotlinx's "JSON input: ..." excerpt. The first draft matched only `${x.message}` and a review
// mutation-proved it blind to `$it` while nine live credential sinks used exactly that form, so
// the wall could not fail for the stronger version of the very thing it forbade.
const RENDERED = new RegExp(
  `\\$\\{[^}]*\\.message[^}]*\\}` +
    `|\\$\\{\\s*${THROWABLE_NAMED}\\s*\\}|\\$${THROWABLE_NAMED}\\b`,
);
const RENDERED_SHORT = new RegExp(
  `\\$\\{\\s*${THROWABLE_SHORT}\\s*\\}|\\$${THROWABLE_SHORT}\\b`,
);

// DR-170: a throwable rendered by CALLING one of its text-producing members. Every matcher above
// keys on a throwable NAME — bare `$failure`, braced `${failure}`, or the one property spelling
// `${x.message}` — so an interpolation whose content is an EXPRESSION was invisible to all of them:
// `${e.stackTraceToString()}` contains no bare `$e` and is not `${e}`, and "stackTraceToString"
// does not contain ".message". DaemonBoundary's boot handler used exactly that form and the wall
// read the whole file as clean, in a file that is in scope and already routes its OTHER failure
// correctly. A wall blind to the STRONGEST render — a stack trace carries the message AND the
// frames — while catching the weaker ones is the same shape as the bare-`$failure` hole this
// checker's own selftest header records from its first draft.
//
// Unconditional, with no failure-frame or binding test, and that is deliberate rather than lazy:
// these three members exist ONLY on Throwable, so unlike `.message` (which any response or result
// type may carry) the receiver's type is not in doubt. Matched anywhere in a scope file, inside a
// string or not, because `print(e.stackTraceToString())` renders the same bytes to the same sink as
// the interpolated form and there is no reading under which one is safe and the other is not.
const THROWABLE_TEXT_MEMBER = /\.(?:stackTraceToString\s*\(|printStackTrace\s*\(|localizedMessage\b)/;

// DR-187: DR-170's own ruling — "it reaches the same sink with the same bytes, so there is no
// reading under which the interpolated form is forbidden and the direct call is fine" — applied to
// the two members DR-170 could not make unconditional. Every matcher above this line keys on a `$`:
// [RENDERED] wants `${…message…}` or `$failure`, [RENDERED_SHORT] wants `$it`, [INTERPOLATED_NAME]
// wants `$name`. So `put("read_error", failure.message)` held no `$` and was matched by NOTHING —
// and that is the DR-65 sink shape verbatim, since the compliant sites in GrokAuthDescribe,
// CodexAuthDescribe and KimiRefreshedTokens all spell it `put("read_error", render(failure))`. The
// form the wall could not see was the one its own subject matter is written in.
//
// The RECEIVER carries the type evidence, which is why this is not the unconditional matcher DR-170
// declined to write: `.message` on an arbitrary receiver says nothing (any response or result type
// carries one), but `failure.message` says exactly what `$failure` says, under the same naming the
// named tier already trusts everywhere. Consistent with that tier's standing rule — name a String
// `failure` in a credential path and you owe it an exemption.
//
// `String.plus` and `Appendable.append` call toString() too, so the implicit spellings are here as
// well. They match NO live site today; they are included because leaving a known render form out of
// the wall being widened for exactly that reason is the bug, not the economy.
const _TEXT_MEMBER = "(?:message\\b|toString\\s*\\()";
const _DOT = "\\s*\\??\\s*\\.";                    // `failure.cause?.message` reaches text through a safe call
const _IMPLICIT = '(?:"\\s*\\+\\s*|\\+\\s*"|\\bappend\\s*\\(\\s*)';
// DR-187 gap (2026-09-01): the matcher was single-hop, so `failure.cause?.message` — the very form the
// _DOT comment names — was invisible (0 sites, the disappearing-denominator failure this wall exists
// to stop). Zero or more `receiver?.` hops may now precede the throwable-named segment.
const _HOPS = "(?:\\w+\\s*\\??\\s*\\.\\s*)*";
const RENDERED_DIRECT = new RegExp(
  `(?<![$\\w.])${_HOPS}${THROWABLE_NAMED}${_DOT}${_TEXT_MEMBER}` +
    `|${_IMPLICIT}${THROWABLE_NAMED}\\b(?![\\w.(])`,
);
const RENDERED_DIRECT_SHORT = new RegExp(
  `(?<![$\\w.])${_HOPS}${THROWABLE_SHORT}${_DOT}${_TEXT_MEMBER}` +
    `|${_IMPLICIT}${THROWABLE_SHORT}\\b(?![\\w.(])`,
);

// DR-160 round 2: a segment must end at a STATEMENT boundary, not only at a brace. `outcome
// .exceptionOrNull()` on one line followed by `values.forEach {` on the next let the combinator
// reach forward into an unrelated lambda — the control-head cut fixed the `if` case and left this
// one. A newline ends the statement unless the expression is still open: the next line continues a
// call chain (`.onFailure {` on its own line is the tree's own idiom), or this one ends on an
// operator.
//
// ROUND 3 — these are tested against the TEXT view, and that is the whole fix for grok-splice's
// third root. The code view BLANKS string content, so `val e = "event"` becomes `val e =` and
// rstrip-ends on `=`: the statement was read as still open, swallowed the next line, and the brace
// stash then spliced that line's exceptionOrNull() onto the string's right-hand side, rebinding the
// name at the OUTER depth. A continuation test run against a view that deleted the evidence it
// needs cannot be right; the text view keeps the closing quote, so the statement ends where it
// visibly ends. (`val e = 1` never reproduced it — an int leaves a digit behind, a string leaves
// nothing. That pair is the discriminator, and it is arm 36 below.)
const CONTINUES = /[.,=+\-*/%&|?:<>(\[{]$|->$|\b(?:and|or)$/;
const CONTINUED_BY = /^\s*[.)\]}]|^\s*\?\./;

/** Per line: is the statement still open across the newline that follows it? */
function continuations(textLines: string[]): boolean[] {
  const out: boolean[] = [];
  textLines.forEach((txt, i) => {
    const nxt = i + 1 < textLines.length ? textLines[i + 1] : "";
    out.push(CONTINUES.test(txt.replace(/\s+$/, "")) || CONTINUED_BY.test(nxt));
  });
  return out;
}

/** Which kind of frame a `{` opens: "failure", undefined (a transparent control block), or "shadow".
 *
 *  FAILURE is tested FIRST and that order is load-bearing. `catch (e: IOException) {` matches both
 *  the combinator list and the control-head list, and when the control test won, `catch` could not
 *  open a failure frame at all — while the published coverage statement named it. A claim the code
 *  contradicts is worse than a narrower claim. */
function frameKind(segment: string): string | undefined {
  if (FAILURE_CONTEXT.test(segment.replace(FUN_DECL, " "))) return "failure";
  const rs = segment.replace(/\s+$/, "");
  if (CONTROL_HEAD.test(rs || " ")) return undefined;
  return "shadow";
}

/** Per line and COLUMN: may a SHORT name (`it`, `e`, …) be read as the throwable here?
 *
 *  Only short names consult this — an unambiguous `$failure` is a render wherever it appears — so
 *  the question is precisely "is `it` bound to the throwable at this column".
 *
 *  Structural, not a fixed lookback. The first version asked whether a failure combinator appeared
 *  within 3 lines above, and codex-splice mutation-proved the hole: a real `.onFailure { … }` whose
 *  nested cleanup pushes the render five lines below the opener stayed GREEN. Widening the constant
 *  only moves the hole into the next nested block.
 *
 *  DR-157 made the combinator/brace pairing POSITIONAL: a combinator can only govern a brace that
 *  comes AFTER it, so each `{` is judged on the code between it and the previous boundary.
 *
 *  DR-159 added shadowing, and DR-160 round 2 replaced that single shadow depth with a FRAME STACK.
 *  One depth could not express a genuine failure lambda nested INSIDE a shadowing one —
 *  `onFailure { names.forEach { runCatching { … }.onFailure { log("$it") } } }` — where the
 *  innermost frame rebinds `it` back to a throwable. That was a false negative, and a stack is what
 *  the language's own scoping rule looks like: the innermost binder wins. */
function failureSpans(lines: string[]): number[][] {
  const [codeLines, textLines] = lex(lines);
  const cont = continuations(textLines);
  const attributable: number[][] = [];
  let depth = 0;
  const frames: [number, boolean][] = []; // (depth, isFailure) — only LAMBDAS push
  let segment = "";
  codeLines.forEach((code, i) => {
    const flags: number[] = new Array(code.length).fill(0);
    for (let col = 0; col < code.length; col += 1) {
      const ch = code[col];
      if (ch === "{") {
        depth += 1;
        const kind = frameKind(segment);
        if (kind) frames.push([depth, kind === "failure"]);
        segment = "";
      } else if (ch === "}") {
        while (frames.length > 0 && frames[frames.length - 1][0] === depth) frames.pop();
        depth = Math.max(0, depth - 1);
        segment = "";
      } else if (ch === ";") {
        segment = "";
      } else {
        segment += ch;
      }
      flags[col] = frames.length > 0 && frames[frames.length - 1][1] ? 1 : 0;
    }
    segment = cont[i] ? segment + "\n" : "";
    attributable.push(flags);
  });
  return attributable;
}

// DR-160: a throwable BOUND TO A LOCAL, which has no lambda and therefore no failure frame at all.
// codex-splice's fixture — `val e = outcome.exceptionOrNull()` then `log("$e")` — was missed: `e` is
// too short for [THROWABLE_NAMED] and there is no brace to attribute it to. The binding itself is
// the evidence.
//
// Round 2 narrowed it: `.cause` and `var` came out (neither is typed, and without flow typing
// neither claim can be honoured), the head and the SOURCE became separate patterns so compliance is
// tested against the binding's own WHOLE right-hand side, every binding in a statement is recorded
// rather than the first, and matching moved from the line to the statement.
//
// Round 3 rebuilt the SCOPE model, because round 2 computed structure per column and then REPORTED
// it per line — the same inversion the spans plane had before round 2, and it cut both ways at once.
// grok-splice and codex-splice each reddened one side:
//   * FALSE NEGATIVE — `if (x) { val e = outcome.exceptionOrNull(); log("$e") }`. The binding and its
//     use are on one line, and the closing brace pruned the name before the line-end snapshot, so
//     the render was invisible. Nothing was wrong with the walk; the REPORT threw the answer away.
//   * FALSE POSITIVE — an outer throwable `e` with an inner `val e = "event"` beside its use. Only
//     THROWABLE sources were recorded, so a plain declaration could not hide anything, and the outer
//     binding stayed visible under a name that no longer referred to it.
// Both say the same thing: a name's meaning is a property of a POSITION, not of a line. So bindings
// are now reported per column like spans, and EVERY declaration is recorded — throwable or not —
// with lookup taking the innermost. A shadow is just a binding that is not a throwable, which is
// also how the language sees it.
const BINDING_HEAD = /\b(?:val|var)\s+(\w+)\s*(?::[^=]+)?=\s*(?![=])/g;
const THROWABLE_SOURCE = /\bexceptionOrNull\s*\(/;

// Declarations that are not `val x = …` and so are invisible to [BINDING_HEAD], each of which
// grok-splice caught failing to shadow an outer throwable of the same name.
const FOR_PARAM = /\bfor\s*\(\s*(?:val\s+|var\s+)?(\w+)(?:\s*:[^)]*?)?\s+in\b/g;
const CATCH_PARAM = /\bcatch\s*\(\s*(\w+)\s*:/g;
// DR-160 round 4. Round 3's parameter pattern accepted BARE NAMES only, so every typed spelling of
// the same declaration silently declared nothing and an outer throwable stayed visible under a name
// that no longer referred to it — the false positive round 3 fixed for `{ e -> … }`, still open for
// `{ e: Event -> … }`. codex-splice found it; grok-splice enumerated what leaked: `e: Event`,
// `(e: Event)`, `e: Event, i: Int`, `(e: Event, n: Int)`, `e: List<Event>`, a trailing comma, and a
// destructure carrying its type on the outside.
//
// The list is PARSED rather than matched, because a type can contain both commas and brackets
// (`Map<String, Event>`) and no flat pattern splits that correctly. Depth is counted over `()` and
// `<>`, a top-level `:` ends the name half, and a parenthesised group is a destructure whose
// components are each declared. Anything left that is not an identifier REJECTS THE WHOLE LIST, which
// is the fail-closed direction — an undeclared name is reported, never hidden — and it is also what
// keeps `is Foo ->` out without a second keyword list.
const IDENTIFIER = /^\w+$/;
// `else ->` is a when-branch arrow, not a parameter list, and it is the one branch head that looks
// exactly like a single bare name. `is Foo ->` is rejected by [IDENTIFIER] before this list is read.
const NOT_A_PARAM = new Set(["else", "is", "in", "when", "true", "false", "null", "this"]);

// ...but a keyword list is not enough, and this one is mine rather than a reviewer's: `when (k) { e
// -> … }` compares the subject to the VALUE e, it does not declare one. Read as a lambda parameter
// it shadowed the real throwable and the render went silent — a false negative introduced by the
// fix for the false positives above. The two arrows are only separable by the block they sit in, so
// the block remembers whether a `when` head opened it.
const WHEN_BLOCK = /\bwhen\s*(?:\([^)]*\))?\s*$/;

/** Per line, per COLUMN: the names that mean a throwable AT that column.
 *
 *  Scope is the language's, not the line's: declarations push, a closing brace pops everything it
 *  encloses, and a lookup takes the INNERMOST declaration of a name — so an inner `val e = "event"`
 *  hides an outer throwable `e` for exactly its own block and no longer.
 *
 *  A `{` INTERRUPTS the statement it appears in rather than ending it: `val e = runCatching { … }
 *  .exceptionOrNull()` is one binding, and resetting at the brace lost its head. The interrupted
 *  statement is stashed with the depth it belongs to and resumes when its brace closes, carrying
 *  the block body with it so the compliance test can still see a sanitizer call inside the lambda. */
function throwableBindings(lines: string[]): Set<string>[][] {
  const [codeLines, textLines] = lex(lines);
  const cont = continuations(textLines);
  type Binding = [string, number, boolean];
  let live: Binding[] = [];
  const out: Set<string>[][] = [];
  const stash: [string, number, number, string, boolean][] = [];
  let depth = 0;
  let segment = "";
  let segDepth = 0;
  let visible: Set<string> = new Set();
  codeLines.forEach((code, i) => {
    const perCol: Set<string>[] = [];
    let before = live.length;
    for (const ch of code) {
      if (live.length !== before) {
        visible = visibleOf(live);
        before = live.length;
      }
      perCol.push(visible);
      if (ch === "{") {
        // for/catch parameters belong to the block the brace opens, not to the head.
        declareParams(segment, depth + 1, live);
        register(segment, segDepth, live);
        stash.push([segment, segDepth, depth, "", WHEN_BLOCK.test(segment.replace(/\s+$/, ""))]);
        depth += 1;
        segment = "";
        segDepth = depth;
      } else if (ch === "}") {
        // Register the block's own trailing statement BEFORE the depth drops, so it is
        // recorded at the inner depth and the pop below takes it back out again.
        register(segment, segDepth, live);
        depth = Math.max(0, depth - 1);
        live = live.filter((b) => b[1] <= depth);
        if (stash.length > 0 && stash[stash.length - 1][2] === depth) {
          const [outer, outerDepth, , body] = stash.pop()!;
          segment = `${outer} { ${body.replace(INNER_DECL, "   ")} } `;
          segDepth = outerDepth;
        } else {
          segment = "";
          segDepth = depth;
        }
      } else if (ch === ";") {
        register(segment, segDepth, live);
        segment = "";
        segDepth = depth;
      } else if (ch === ">" && segment.endsWith("-")) {
        // A lambda's parameter list: `{ e -> …` declares e for this block. A when BRANCH
        // arrow declares nothing, so the enclosing block decides which arrow this is.
        if (!(stash.length > 0 && stash[stash.length - 1][4])) {
          declareLambda(segment.slice(0, -1), depth, live);
        }
        segment = "";
        segDepth = depth;
      } else {
        segment += ch;
        for (const frame of stash) frame[3] += ch;
      }
    }
    if (cont[i]) {
      segment += "\n";
    } else {
      register(segment, segDepth, live);
      segment = "";
      segDepth = depth;
    }
    if (live.length !== before) visible = visibleOf(live);
    out.push(perCol);
  });
  return out;
}

/** The names that mean a throwable, taking the INNERMOST declaration of each. */
function visibleOf(live: [string, number, boolean][]): Set<string> {
  const innermost = new Map<string, boolean>();
  for (const [name, , throwable] of live) innermost.set(name, throwable);
  return new Set([...innermost.entries()].filter(([, t]) => t).map(([n]) => n));
}

// On restoring an interrupted statement the lambda body comes back with it, and a declaration made
// INSIDE that body has already been recorded at its own inner depth. The keywords are blanked so the
// body can still be read for compliance evidence without re-declaring anything at the outer depth.
const INNER_DECL = /\b(?:val|var)\b/g;

/** Record every local declared by one completed statement, at [depth].
 *
 *  A declaration whose right-hand side is an unsanitized `exceptionOrNull()` means a throwable;
 *  every other declaration is a SHADOW, recorded so it can hide an outer name of its own spelling. */
function register(statement: string, depth: number, live: [string, number, boolean][]): void {
  const heads = [...statement.matchAll(BINDING_HEAD)];
  heads.forEach((head, n) => {
    const stop = n + 1 < heads.length ? (heads[n + 1].index as number) : statement.length;
    const rhs = statement.slice((head.index as number) + head[0].length, stop);
    // A binding whose OWN right-hand side routes through the sanitizer holds a rendered STRING,
    // not a throwable — `val reason = attempt.exceptionOrNull()?.let { render(it) }` in
    // UninstallCommand.kt is the live shape, and it is why the window must be the whole RHS
    // rather than the head: the sanitizer call sits past the combinator, inside the lambda.
    // `var` can be reassigned to a String with no flow typing to catch it, so it never claims.
    const throwable =
      head[0].replace(/^\s+/, "").startsWith("val") &&
      THROWABLE_SOURCE.test(rhs) &&
      !COMPLIANT.test(rhs);
    live.push([head[1], depth, throwable]);
  });
}

/** `for (e in xs)` and `catch (e: IOException)` declare e for the block their head precedes. */
function declareParams(segment: string, depth: number, live: [string, number, boolean][]): void {
  for (const found of segment.matchAll(FOR_PARAM)) live.push([found[1], depth, false]);
  for (const found of segment.matchAll(CATCH_PARAM)) {
    // A catch parameter IS a throwable, and saying so here is what lets it shadow correctly
    // rather than merely being covered by the short-name tier.
    live.push([found[1], depth, true]);
  }
}

/** [text] split on [sep] at bracket depth zero, counting `()` and `<>` alike.
 *
 *  A type's own commas and colons belong to the type: `Map<String, Event>` is ONE parameter and
 *  `(e, n): Pair<Event, Int>` is one destructure, not two of anything. */
function splitTop(text: string, sep: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let item = "";
  for (const ch of text) {
    if (ch === "(" || ch === "<") depth += 1;
    else if (ch === ")" || ch === ">") depth = Math.max(0, depth - 1);
    if (ch === sep && depth === 0) {
      parts.push(item);
      item = "";
    } else {
      item += ch;
    }
  }
  parts.push(item);
  return parts;
}

/** The names an explicit parameter list declares, or null when [text] is not one. */
function paramNames(text: string): string[] | null {
  const names: string[] = [];
  for (const raw of splitTop(text, ",")) {
    const item = splitTop(raw, ":")[0].trim();
    if (!item) continue; // a trailing comma declares nothing, and neither does an empty list
    if (item.startsWith("(") && item.endsWith(")")) {
      const inner = paramNames(item.slice(1, -1));
      if (inner === null) return null;
      names.push(...inner);
    } else if (IDENTIFIER.test(item)) {
      names.push(item);
    } else {
      return null;
    }
  }
  return names;
}

/** `{ e -> … }`, `{ a, b -> … }`, `{ e: Event -> … }` declare their parameters for the block.
 *
 *  An EXPLICIT parameter list only — the arrow is what makes it decidable. An arrowless lambda's
 *  implicit `it` never reaches here and is deliberately not declared: `forEach { … }` rebinds it
 *  while `run { … }` and `apply { … }` do not, the two are spelled identically, and guessing either
 *  way trades one wrong answer for another. That non-claim is in the module's coverage note. */
function declareLambda(segment: string, depth: number, live: [string, number, boolean][]): void {
  for (const name of paramNames(segment) ?? []) {
    if (!NOT_A_PARAM.has(name)) live.push([name, depth, false]);
  }
}

interface Site {
  path: string;
  line: number;
  text: string;
  verdict: string;
  detail: string | null;
  markers: string[];
}

/** Does line [idx] interpolate a throwable into text?
 *
 *  DR-158: reads the COMMENT-BLANKED view, not the raw line. A trailing `// … $it …` explaining
 *  the law is prose and cannot interpolate anything at runtime, but the raw line made it
 *  indistinguishable from a real render — codex-splice proved it with
 *  `val ignored = 1 // raw $it would leak` inside an onFailure. String content is deliberately
 *  PRESERVED in this view, because a real interpolation lives inside a string by definition. */
function rendersThrowable(
  lines: string[],
  idx: number,
  spans?: number[][],
  textLines?: string[],
  bindings?: Set<string>[][],
): boolean {
  if (textLines === undefined) {
    textLines = lex(lines)[1];
    bindings = throwableBindings(lines);
  }
  const line = textLines[idx];
  // DR-170: an EXPRESSION render — `${e.stackTraceToString()}`, or a bare printStackTrace call —
  // is decided by the member alone, with no name or frame test. See [THROWABLE_TEXT_MEMBER] for
  // why the receiver's type is not in doubt there while it is for `.message`.
  // DR-187: the same line also carries the NON-interpolated forms. Read from this view, not the
  // code one, because the code view blanks a string's quotes and the implicit spelling
  // (`"x " + failure`) is only recognisable with them; an interpolated hit here is the same SITE
  // [RENDERED] already reports, so the overlap costs nothing.
  if (THROWABLE_TEXT_MEMBER.test(line) || RENDERED.test(line) || RENDERED_DIRECT.test(line)) return true;
  if (bindings === undefined) bindings = throwableBindings(lines);
  // DR-160 round 3: at the MATCH's own column, for the same reason the short-name tier already
  // was — a name can be declared, shadowed, or closed out of scope partway along one line.
  const perCol = bindings[idx] ?? [];
  for (const hit of line.matchAll(INTERPOLATED_NAME)) {
    const col = hit.index as number;
    const name = hit[1] ?? hit[2];
    if (col < perCol.length && perCol[col].has(name)) return true;
  }
  // DR-187 adds the non-interpolated short form alongside the interpolated one. Both are
  // frame-scoped for the same reason and each is judged at its OWN column, so a line carrying one
  // of each cannot have the second silently decided by the first one's position.
  const hits: RegExpExecArray[] = [];
  for (const re of [RENDERED_SHORT, RENDERED_DIRECT_SHORT]) {
    const m = re.exec(line);
    if (m) hits.push(m);
  }
  if (hits.length === 0) return false;
  if (spans === undefined) spans = failureSpans(lines);
  // DR-159: at the MATCH's own column, because a nested lambda can rebind `it` partway along the
  // very same line.
  const flags = spans[idx] ?? [];
  return hits.some((hit) => hit.index < flags.length && flags[hit.index] === 1);
}

// A site that already obeys the law. Enumerated DELIBERATELY, even though it can never
// violate: scoring only the raw form would let the denominator SHRINK by one every time a
// site was fixed, so a tree could reach "0 undispositioned" by having no sites left to
// count — the same disappearing-denominator move this wall exists to stop. Routed sites
// stay in the roll, so `compliant + exempt + bad` is the whole population every run.
const COMPLIANT = /SafeFailureText\.render\(/;

// Any interpolated identifier, so the binding tier can be asked "does THIS name mean a throwable at
// THIS column" in one pass rather than once per known name.
const INTERPOLATED_NAME = /\$\{\s*(\w+)\s*\}|\$(\w+)\b/g;

// The disposition marker. Dated so a review can age it, and reasoned so it can be judged.
const EXEMPT = /SAFE-RENDER-EXEMPT\[(\d{4}-\d{2}-\d{2})\]:[ \t]*(.*)/;

// How far above a site the marker may sit — a rendered string often spans a wrapped
// multi-line log call, so the comment explaining it is not always on the matched line.
const EXEMPT_LOOKBACK = 8;

const MIN_REASON_CHARS = 30;
const PLACEHOLDER = /^(todo|tbd|fixme|n\/?a|none|safe|ok|fine|why|reason|\.+|-+|\?+)\b/i;

const SOURCES = "gateway/*/src/main/kotlin/**/*.kt";

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Why this file is in scope — credential vocabulary and/or file I/O — or [] if it is not. */
function inScope(text: string): string[] {
  const why = SCOPE_VOCAB.filter((m) => new RegExp(`\\b${escapeRe(m)}\\b`).test(text));
  if (SCOPE_IO.some((p) => p.test(text))) why.push("file-io");
  return why;
}

function pyRepr(s: string): string {
  return `'${s.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

/** COMPLIANT / EXEMPT / the failure reason for the site at 0-based [idx].
 *
 *  A raw render is judged raw even on a line that ALSO calls the sanitizer: one sanitized
 *  half never launders the other, so a mixed line still needs its own exemption. */
function disposition(
  lines: string[],
  idx: number,
  spans?: number[][],
  textLines?: string[],
  bindings?: Set<string>[][],
): { verdict: string; detail: string | null } {
  if (!rendersThrowable(lines, idx, spans, textLines, bindings)) return { verdict: "compliant", detail: null };
  for (let back = idx; back > Math.max(-1, idx - EXEMPT_LOOKBACK - 1); back -= 1) {
    const found = EXEMPT.exec(lines[back]);
    if (!found) continue;
    const reason = found[2].trim().replace(/\*\/$/, "").trim();
    if (!reason || PLACEHOLDER.test(reason)) {
      return { verdict: "bad", detail: `exemption reason is a placeholder, not a disposition: ${pyRepr(reason)}` };
    }
    if (reason.length < MIN_REASON_CHARS) {
      return {
        verdict: "bad",
        detail: `exemption reason is ${reason.length} chars, under the ${MIN_REASON_CHARS}-char floor: ${pyRepr(reason)}`,
      };
    }
    return { verdict: "exempt", detail: found[1] };
  }
  return { verdict: "bad", detail: "renders a throwable raw with no SafeFailureText.render and no exemption" };
}

/** Every rendered-throwable site under a credential/state file, with its disposition. */
function sites(root: string): Site[] {
  const out: Site[] = [];
  const files = [...new Bun.Glob(SOURCES).scanSync({ cwd: root, followSymlinks: true })].sort();
  for (const rel of files) {
    const path = join(root, rel);
    const text = readFileSync(path, "utf8");
    const markers = inScope(text);
    if (markers.length === 0) continue;
    const lines = pySplitlines(text);
    const spans = failureSpans(lines);
    // DR-158: the comment-blanked view decides BOTH matchers. Comment prose about the law —
    // this file's own header quotes `$failure` as the thing it forbids — is blank here, so it
    // drops out structurally instead of via a "does the line START with //" test that a
    // TRAILING comment walked straight past.
    const textLines = lex(lines)[1];
    const bindings = throwableBindings(lines);
    lines.forEach((line, idx) => {
      const rendered = rendersThrowable(lines, idx, spans, textLines, bindings);
      if (!rendered && !COMPLIANT.test(textLines[idx])) return;
      const { verdict, detail } = disposition(lines, idx, spans, textLines, bindings);
      out.push({ path, line: idx + 1, text: line.trim(), verdict, detail, markers });
    });
  }
  return out;
}

function cmdCheck(root: string): number {
  const found = sites(root);
  const bad = found.filter((s) => s.verdict === "bad");
  for (const s of bad) {
    process.stderr.write(`${s.path}:${s.line}: DR-65 violation — ${s.detail}\n`);
    process.stderr.write(`    ${s.text.slice(0, 110)}\n`);
    process.stderr.write(`    in scope via: ${s.markers.join(", ")}\n`);
  }
  const counts: Record<string, number> = { compliant: 0, exempt: 0, bad: 0 };
  for (const site of found) counts[site.verdict] += 1;
  process.stdout.write(
    `safe-failure-render: ${found.length} sites in credential/state sources — ${counts.compliant} routed, ` +
      `${counts.exempt} exempt, ${counts.bad} undispositioned\n`,
  );
  if (bad.length > 0) {
    process.stderr.write(
      "every rendered throwable in a credential/state source must go through " +
        "SafeFailureText.render, or carry a SAFE-RENDER-EXEMPT[YYYY-MM-DD]: <reason> " +
        "comment naming why that throwable cannot quote state bytes\n",
    );
  }
  return bad.length > 0 ? 1 : 0;
}

/** The disposition roll — the reviewable denominator, not just its failures. */
function cmdReport(root: string): number {
  for (const s of sites(root)) {
    process.stdout.write(`${s.verdict.toUpperCase().padEnd(9)} ${s.path}:${s.line}  ${(s.detail ?? "").slice(0, 40)}\n`);
    process.stdout.write(`          ${s.text.slice(0, 100)}\n`);
  }
  return 0;
}

function main(argv: string[]): number {
  if (argv.length !== 3 || (argv[1] !== "check" && argv[1] !== "report")) {
    process.stderr.write("usage: safe-failure-render.ts {check|report} <repo-root>\n");
    return 2;
  }
  // THE ROOT IS PASSED AS GIVEN, never resolved: python globs Path(argv[2]) and prints the paths
  // that yields, so `check .` reports `gateway/app/...` and an absolute root reports absolute paths.
  // Resolving here made every path absolute, which the tree differential could not see (no violation
  // lines to print on a clean tree) and the arms could not see either (they pass an absolute root).
  // Only `report`, which prints every site, exposed it.
  return argv[1] === "check" ? cmdCheck(argv[2]) : cmdReport(argv[2]);
}

process.exit(main(process.argv.slice(1)));
