#!/usr/bin/env bash
# checks/safe-failure-render-selftest.sh — mutation-proves checks/config/safe-failure-render.py
# (DR-140). Same defence-in-depth idiom as the rule-routing, config-guard, catalog and secret-scan
# selftests: the leg guards the tree, this canary guards the LEG.
#
# It exists because the DR-140 wall shipped its FIRST draft with two holes that two independent
# reviews mutation-proved within the hour, both of them the wall's own subject matter:
#   * the matcher saw `${x.message}` but was blind to bare `$failure`, which calls toString() and
#     is a strict SUPERSET (class name PLUS the same message) — nine live credential sinks used
#     exactly that form, so the wall could not fail for the stronger version of what it forbade;
#   * scope came from a hand-written vocabulary list, so CodexAuthFile.kt and KimiOAuth.kt — which
#     name no listed type — were not in the denominator at all, and a violation planted in either
#     did not move the site count.
# Both are pinned below as permanent arms. A wall whose own failure modes are not pinned is a wall
# that gets quietly weakened back to its first draft.
#
# Fixtures are synthetic and hermetic: the checker runs against a fabricated gateway tree in a
# temp dir, never the repo's own sources.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ safe-failure-render-selftest: $1"; fail=1; }

mkdir -p "$tmp/checks/config"
cp "$ROOT/checks/config/safe-failure-render.py" "$tmp/checks/config/" || {
  echo "  ✗ safe-failure-render-selftest: the checker is missing — the gate leg cannot be trusted"
  exit 1
}

SRC="$tmp/gateway/mod/src/main/kotlin/splice/probe"
mkdir -p "$SRC"

# Writes one fixture file and asserts the checker's verdict on the whole tree.
# $1 label · $2 expected rc (0 pass / 1 fail) · $3 file basename · $4 body
arm() {
  local label="$1" want="$2" name="$3" body="$4"
  printf '%s\n' "$body" > "$SRC/$name"
  ( cd "$tmp" && python3 checks/config/safe-failure-render.py check . >/dev/null 2>&1 )
  local rc=$?
  [ "$rc" = "$want" ] || err "$label: expected rc=$want, got rc=$rc"
  rm -f "$SRC/$name"
}

# Same, but also asserts WHICH line the checker blames and a phrase from its reason. Exit code
# alone cannot discriminate a correct verdict from a wrong one that happens to be non-zero — the
# DR-157 fold fixture failed under BOTH the old and new checkers, the old one blaming the SUCCESS
# lambda it had misidentified. An arm that cannot tell those apart is not a proof.
# $1 label · $2 expected line · $3 expected phrase · $4 file basename · $5 body
arm_at() {
  local label="$1" line="$2" phrase="$3" name="$4" body="$5"
  printf '%s\n' "$body" > "$SRC/$name"
  local out
  out=$( cd "$tmp" && python3 checks/config/safe-failure-render.py check . 2>&1 )
  echo "$out" | grep -q "$name:$line:" || err "$label: expected the blame on line $line, got: $(echo "$out" | head -1)"
  echo "$out" | grep -q -- "$phrase" || err "$label: expected reason to mention '$phrase'"
  rm -f "$SRC/$name"
}

# 1 — a routed sink passes.
arm "routed sink passes" 0 A.kt 'package p
import java.nio.file.Files
fun a(e: Throwable) = Files.exists(p).also { log("x (${SafeFailureText.render(e)})") }'

# 2 — a raw `.message` in a file that touches the filesystem fails.
arm "raw .message fails" 1 A.kt 'package p
import java.nio.file.Files
fun a(e: Throwable) = Files.exists(p).also { log("x (${e.message})") }'

# 3 — THE FIRST-DRAFT HOLE: bare `$failure` is worse than `.message` and must fail too.
arm "bare \$failure fails" 1 A.kt 'package p
import java.nio.file.Files
fun a(failure: Throwable) = Files.exists(p).also { log("x ($failure)") }'

# 4 — THE OTHER FIRST-DRAFT HOLE: scope is causal, so a file that names NO credential type but
#     does file I/O is still in the denominator.
arm "file-io-only file is in scope" 1 NoVocab.kt 'package p
fun a(failure: Throwable) = java.nio.file.Files.getLastModifiedTime(x).also { log("$failure") }'

# 5 — a dated, reasoned exemption passes.
arm "valid exemption passes" 0 A.kt 'package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT[2026-08-31]: a bind failure names a port and an address, never file bytes
fun a(e: Throwable) = Files.exists(p).also { log("x (${e.message})") }'

# 6 — THE BORING CASE: a blank reason is an absence wearing a label.
arm "blank reason fails" 1 A.kt 'package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT[2026-08-31]:
fun a(e: Throwable) = Files.exists(p).also { log("x (${e.message})") }'

# 7 — a placeholder reason fails.
arm "placeholder reason fails" 1 A.kt 'package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT[2026-08-31]: TODO decide later
fun a(e: Throwable) = Files.exists(p).also { log("x (${e.message})") }'

# 8 — a too-short reason fails.
arm "short reason fails" 1 A.kt 'package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT[2026-08-31]: fs only
fun a(e: Throwable) = Files.exists(p).also { log("x (${e.message})") }'

# 9 — an undated marker is not a marker.
arm "undated marker fails" 1 A.kt 'package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT: a bind failure names a port and an address, never any file bytes
fun a(e: Throwable) = Files.exists(p).also { log("x (${e.message})") }'

# 10 — CONTROL: a file with neither file I/O nor credential vocabulary is out of scope. Without
#      this arm a checker that flagged EVERYTHING would pass every arm above, and a wall that
#      rejects the whole tree is not a wall that works.
arm "out-of-scope file is not flagged" 0 Pure.kt 'package p
fun a(failure: Throwable) = log("x ($failure)")'

# 11 — CONTROL: `it` is Kotlin'"'"'s universal lambda parameter, so a non-failure `$it` must NOT be
#      flagged. A wall that cries wolf gets its exemptions rubber-stamped.
arm "non-failure \$it is not flagged" 0 A.kt 'package p
import java.nio.file.Files
fun a(bearer: String?) = Files.exists(p).also { bearer?.let { h("Bearer $it") } }'

# 12 — but `$it` inside a failure-handling lambda IS a throwable render.
arm "\$it in onFailure fails" 1 A.kt 'package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure { log("stat failed: $it — skipping") }'

# 12b — codex-splice's mutation, 2026-08-31: the first version asked whether a failure combinator
#       appeared within a FIXED 3-line lookback, and a real `.onFailure { ... }` in SecureFile.kt
#       whose nested cleanup pushes the `$it` render five lines below the opener stayed GREEN.
#       Widening the constant only moves the hole into the next nested block, so context is now
#       tracked by brace depth. This arm reproduces that exact shape: a multi-line nested call
#       between the opener and the render.
arm "\$it deep inside a multi-line onFailure body fails" 1 Deep.kt 'package p
import java.nio.file.Files
fun a() = write().onFailure {
    discard(
        runCatching { Files.deleteIfExists(tmp) },
        "cleanup is best-effort; the write failure rethrows",
    )
    throw java.io.IOException("secure write failed: $it", it)
}'

# 12c — and the span must CLOSE with its lambda: a `$it` after the body ended is not a throwable.
arm "\$it after the lambda closes is not flagged" 0 After.kt 'package p
import java.nio.file.Files
fun a() {
    write().onFailure {
        log("failed")
    }
    names.forEach { log("$it = stored") }
}'

# 12d — DR-154, codex-splice's probe: a URL inside a one-line failure lambda must not eat the
#       closing brace. Comment stripping used to run before string stripping, so the `//` in
#       https:// truncated the line, the span never closed, and the LATER non-failure `$it` was
#       flagged — a blocking-gate false positive.
arm "a URL in a one-line failure lambda does not leak the span" 0 Url.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p).onFailure { log("https://example.test/failure") }
    names.forEach { log("$it = stored") }
}'

# 12e — DR-154 REDO, codex-splice mutation-proved from the scanner source, 2026-08-31: a `}` inside
#       a BLOCK comment popped the real brace depth, the failure span closed early, and the genuine
#       raw `$it` below it was never seen. FALSE NEGATIVE — the direction that makes a green gate a
#       lie. Ordering patches cannot fix this class; the masker lexes comments out entirely.
arm "a brace inside a block comment does not close the span" 1 BlockComment.kt 'package p
import java.nio.file.Files
fun a() = write().onFailure {
    /* nested cleanup closes with } after retries */
    log("failed: $it")
}'

# 12f — codex-splice'"'"'s second false-negative fixture, same root: a `}` inside a CHAR literal.
#       `val close = '"'"'}'"'"'` popped the depth exactly like the comment did.
arm "a brace inside a char literal does not close the span" 1 CharLit.kt 'package p
import java.nio.file.Files
fun a() = write().onFailure {
    val close = '"'"'}'"'"'
    log("failed: $it, terminator $close")
}'

# 12g — the multi-line half the per-line regex could never express: a block comment OPENED on one
#       line and CLOSED on another, with the stray brace in between.
arm "a brace inside a multi-line block comment does not close the span" 1 MultiComment.kt 'package p
import java.nio.file.Files
fun a() = write().onFailure {
    /* the retry ladder
       closes with } here
       and continues */
    log("failed: $it")
}'

# 15 — DR-156, codex-splice: Kotlin block comments NEST. A boolean in/out flag exits at the INNER
#      `*/`, so the rest of the outer comment is read as code and its `}` pops the real depth. The
#      lexer counts DEPTH. RETRACTION: an earlier version claimed four such comments exist in this
#      tree. They do not — that came from a regex of mine matching `sessions/*` inside a LINE
#      comment. This arm guards the grammar Kotlin defines, not an observed instance.
arm "a nested block comment does not close the span" 1 Nested.kt 'package p
import java.nio.file.Files
fun a() = write().onFailure {
    /* outer /* inner */ still outer } */
    log("failed: $it")
}'

# 16 — DR-157, codex-splice: a combinator can only govern a brace that comes AFTER it. The per-line
#      flag let a TRAILING exceptionOrNull reach back and claim a LEADING runCatching brace, so a
#      plain String `$it` was flagged. Blocking-gate false positive.
arm "a trailing exceptionOrNull does not claim an earlier brace" 0 Trailing.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    runCatching { names.forEach { log("$it = stored") } }.exceptionOrNull()
}'

# 16b — but exceptionOrNull that DOES feed a lambda still opens the span; the fix must not blunt it.
arm "exceptionOrNull feeding a let still opens the span" 1 FeedsLet.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    outcome.exceptionOrNull()?.let { log("read_error: $it") }
}'

# 17 — DR-157/DR-160. `.fold(` used to be in FAILURE_CONTEXT and matched Result.fold's FIRST
#      lambda (onSuccess), so the success `$it` was a false positive and the real failure `$it` a
#      false negative — one entry producing both directions. DR-157 answered that by FAILING any
#      unnamed fold; DR-160 removes even that, because codex-splice showed `items.fold(0) { acc, x
#      -> … }` is Iterable.fold and has no failure half to name, so the rule was itself a
#      blocking-gate false positive. With short names no longer attributed through fold at all, the
#      polarity question disappears: a NAMED throwable is caught in either lambda, `it` in neither.
arm "an unnamed positional fold is not itself a violation" 0 PosFold.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    result.fold(
        { names.forEach { log("$it = stored") } },
        { log("failed: $it") },
    )
}'

# 17a — the Iterable.fold codex-splice used to prove the rule over-fired.
arm "Iterable.fold is not a failure combinator" 0 IterFold.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    items.fold(0) { acc, x -> acc + x }
}'

# 17b — CONTROL: the NAMED form is decidable and is exactly what this tree already uses, so it must
#       pass when routed. A fail-closed rule that also fails the compliant form is unusable.
arm "a named fold routed through the sanitizer passes" 0 NamedFold.kt 'package p
import java.nio.file.Files
fun a() = Files.size(p).fold(
    onSuccess = { true },
    onFailure = { log("failed: ${SafeFailureText.render(it)}"); false },
)'

# 18 — DR-158, codex-splice: the render matcher read the RAW line, so prose in a TRAILING comment
#      was indistinguishable from a runtime interpolation. The comment-blanked view fixes it while
#      PRESERVING string content, because a real interpolation lives inside a string by definition.
arm "a throwable named only in a trailing comment is not a render" 0 TrailingProse.kt 'package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    val ignored = 1 // raw $it would leak here
    log("failed: ${SafeFailureText.render(it)}")
}'

# 18b — and the same line shape with a REAL render still fails, so 18 did not simply blind it.
arm "a real render on a line that also carries comment prose still fails" 1 ProseAndReal.kt 'package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    log("failed: $it") // the $it above is the violation, this prose is not
}'

# 19 — DR-159, from codex-splice's getOrElse scope note: Kotlin binds `it` PER LAMBDA, so a nested
#      lambda inside a failure lambda REBINDS it. This body renders a String, not the throwable.
arm "a nested lambda inside a failure lambda rebinds it" 0 Shadow.kt 'package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    names.forEach { log("$it = stored") }
}'

# 19b — the other side, and the reason this needed real discrimination rather than a depth cutoff:
#       a nested CONTROL BLOCK does NOT rebind, so the throwable is still `it` and must stay caught.
#       Narrowing on depth alone would have traded a false positive for a false negative here.
arm "a nested control block does not rebind it" 1 ControlBlock.kt 'package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    if (x) {
        log("failed: $it")
    }
}'

# 19c — codex's exact one-liner: failure lambda AND shadowing lambda on the SAME line, which is why
#       attribution is per COLUMN rather than per line.
arm "shadowing on the same line as the failure lambda" 0 OneLine.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    m.getOrElse(k) { names.forEach { log("$it") } }
}'

# 19d — when-branch arrows open blocks, not lambdas.
arm "a when branch does not rebind it" 1 WhenBranch.kt 'package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    when (x) {
        is Foo -> {
            log("failed: $it")
        }
    }
}'

# 19e — THE BOUND on the narrowing: it applies to SHORT names only. An unambiguous throwable name
#       is a render wherever it appears, including inside a shadowing lambda — otherwise DR-159
#       would have bought a false-positive fix with a false-negative hole.
arm "a named throwable inside a nested lambda still fails" 1 NamedInside.kt 'package p
import java.nio.file.Files
fun a(failure: Throwable) = Files.size(p).onFailure {
    names.forEach { log("$failure") }
}'

# 20 — DR-160/1, codex-splice: getOrElse is OVERLOADED and does not imply a Throwable receiver.
#      `list.getOrElse(0) { … }` binds an Int; `map.getOrElse(k) { … }` binds nothing.
arm "getOrElse does not bind a throwable" 0 GetOrElse.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    list.getOrElse(0) { "missing $it" }
}'

# 21 — DR-160/2: an ESCAPED dollar is a literal, not an interpolation.
arm "an escaped dollar is not an interpolation" 0 Escaped.kt 'package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    log("literal \$it stays literal")
}'

# 22 — DR-160/3: a string TEMPLATE hole is CODE. Blanking it hid an entire failure lambda —
#      braces, combinator and the nested string inside it — so the render went unseen.
arm "a failure lambda inside a template hole is still seen" 1 Template.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val s = "outer ${runCatching { x }.onFailure { "failed $it" }} tail"
}'

# 23 — DR-160/4: a bare exceptionOrNull STATEMENT must not poison a later CONTROL block in a
#      sibling lambda. Fixed at the brace decision — a control-flow head is never a lambda — rather
#      than by deleting the combinator, which arm 16b shows would have cost real coverage.
arm "a bare exceptionOrNull statement does not poison a sibling lambda" 0 Poison.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    names.forEach {
        outcome.exceptionOrNull()
        if (x) { log("$it") }
    }
}'

# 24 — DR-160/5: a throwable bound to a short LOCAL has no lambda to be attributed to, so the
#      binding itself is the evidence.
arm "a throwable bound to a short local is still a render" 1 Bound.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    log("$e")
}'

# 24b — but a binding already routed through the sanitizer holds a STRING. This is the live shape
#       in UninstallCommand.kt, and the binding rule flagged it on its first run.
arm "a sanitized binding is not a throwable" 0 BoundSafe.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val reason = outcome.exceptionOrNull()?.let { SafeFailureText.render(it) } ?: "unknown"
    log("failed ($reason)")
}'

# 25 — DR-160/6: a DECLARATION is not a call. `fun onFailure(e: Event)` made a whole method body a
#      failure span, so an Event named `e` was flagged.
arm "a fun named onFailure is not a failure lambda" 0 FunDecl.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
}
fun onFailure(e: Event) {
    log("$e")
}'

# ---------------------------------------------------------------------------------------------
# DR-160 ROUND 2. codex-splice probed the round-1 recognizer and reddened ten shapes; every one is
# pinned here. Six were FALSE POSITIVES (the wall firing on a String), two FALSE NEGATIVES (the
# wall blind to a real throwable), and two were published-claim violations — the coverage note
# named `catch` and plain parameters while the code did the opposite. The last kind is the worst:
# a claim the code contradicts is what stops anyone looking again.

# 26 — arm 23 fixed a bare exceptionOrNull STATEMENT poisoning a later CONTROL block. The same
#      statement reaching forward into a sibling LAMBDA survived it, because only the brace
#      decision was fixed and not the statement boundary. `$it` here is a String.
arm "a bare exceptionOrNull statement does not poison the next lambda" 0 NextLambda.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    names.forEach {
        outcome.exceptionOrNull()
        values.forEach { log("$it") }
    }
}'

# 27 — the FALSE NEGATIVE that a single shadow depth could not express: a genuine failure lambda
#      nested INSIDE a shadowing one. The innermost binder wins, which is what a frame STACK says
#      and a lone depth cannot.
arm_at "a failure lambda inside a shadowing lambda is still caught" 5 "renders a throwable raw" Restored.kt 'package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    names.forEach {
        runCatching { z() }.onFailure { log("$it") }
    }
}'

# 28 — the binding walk took the depth at the END of the line, so a block that OPENED AND CLOSED on
#      one line registered its local at the outer depth and the binding outlived its scope. The
#      `val e` below is a String and was rendered as a throwable.
arm "a binding dies with the block that closes on its own line" 0 SameLine.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    if (x) { val e = outcome.exceptionOrNull() }
    val e = "event"
    log("$e")
}'

# 28b — THE BOUND on 28: a binding is scoped to its BLOCK, so it survives an unrelated nested block
#       that opens and closes beside it, and stays live inside blocks nested within its own.
#       Narrowing 28 by clearing bindings at every closing brace passes 28 and guts the rule — and
#       the first draft of this arm could not tell: it put the only brace AFTER the use, so the
#       prune it exists to guard was never reached and the over-narrowing mutant ran green.
arm_at "a binding survives a sibling block and stays live in a nested one" 8 "renders a throwable raw" NestedLive.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    if (x) { log("unrelated") }
    if (y) {
        log("$e")
    }
}'

# 29 — the walk used .search, which finds the FIRST binding on a statement and stops. The second
#      one was invisible.
arm_at "every binding in a statement is recorded, not just the first" 6 "renders a throwable raw" TwoBind.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = first.exceptionOrNull(); val err = second.exceptionOrNull()
    log("$err")
}'

# 30 — arm 25 stripped `fun <name>(`, which does not describe a receiver or a generic. This
#      contradicted the published claim that a plain PARAMETER is never attributed.
arm "a receiver-and-generic fun declaration is not a failure lambda" 0 FunRecv.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
}
fun Result<Event>.onFailure(e: Event) {
    log("$e")
}'

# 31 — `.cause` was inferred to bind a throwable. It is no more typed than getOrElse: `incident.cause`
#      may be a String, and an AST census found zero live `.cause` bindings, so the inference bought
#      nothing and cost this false positive.
arm "an untyped .cause binding is not a throwable" 0 Cause.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val err = incident.cause
    log("$err")
}'

# 32 — the published coverage list named `catch (` as attributed while the code could not attribute
#      it AT ALL: `catch (e: IOException) {` matches both the combinator list and the control-head
#      list, and the control test ran first, so the brace opened a transparent block. The order of
#      those two tests is the fix and is load-bearing.
arm_at "catch binds the throwable, as the coverage note claims" 4 "renders a throwable raw" Catch.kt 'package p
import java.nio.file.Files
fun a() {
    try { Files.size(p) } catch (e: java.io.IOException) { log("$e") }
}'

# 33 — arm 24b skipped a binding whose LINE called the sanitizer. An unrelated sanitized call
#      beside a raw binding therefore laundered it. The test is scoped to the binding OWN
#      right-hand side.
arm_at "a sanitized call beside a raw binding does not launder it" 6 "renders a throwable raw" Launder.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull(); log(SafeFailureText.render(other))
    log("$e")
}'

# 34 — a `var` can be reassigned to a String and this scanner has no flow typing, so the claim
#      cannot be honoured and is not made.
arm "a var binding is not claimed" 0 VarBind.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    var e: Any? = outcome.exceptionOrNull()
    e = "plain"
    log("$e")
}'

# 35 — the walk matched per LINE, so a binding wrapped after `val e =` was missed entirely.
arm_at "a binding wrapped onto the next line is seen" 7 "renders a throwable raw" Wrapped.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e =
        outcome.exceptionOrNull()
    log("$e")
}'

# 35b — THE BOUND on 35: a `{` INTERRUPTS a statement rather than ending it. Resetting at the brace
#       would pass 28 and 35 while losing the head of every binding whose source runs through a
#       lambda — which is the tree own runCatching idiom.
arm_at "a binding whose source runs through a lambda keeps its head" 6 "renders a throwable raw" ThroughLambda.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = runCatching { z() }.exceptionOrNull()
    log("$e")
}'

# ---------------------------------------------------------------------------------------------
# DR-160 ROUND 3. Round 2 computed binding structure per COLUMN and then REPORTED it per LINE —
# the same inversion the spans plane had before round 2, and it cut both ways at once. codex-splice
# reddened one side each way; grok-splice's adjacent sweep widened the shadow half and found a
# third root underneath both. Bindings are now reported per column, EVERY declaration is recorded
# (a shadow is just a binding that is not a throwable, which is how the language sees it), and the
# statement-continuation test reads the TEXT view instead of the string-blanked code view.

# 36 — THE THIRD ROOT, and the one neither reviewer had to guess at. lex blanks string CONTENT in
#      the code view, so `val e = "event"` rstrip-ended on `=`, the statement was read as still
#      open, and the brace stash spliced the NEXT line's exceptionOrNull() onto that string's
#      right-hand side — rebinding e at the outer depth. `val e = 1` never reproduced it, because an
#      int leaves a digit behind and a string leaves nothing. That pair is the whole diagnosis.
arm "a string-valued binding does not swallow the next statement" 0 StringCont.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = "event"
    if (x) { outcome.exceptionOrNull() }
    log("$e")
}'

# 36b — CONTROL for 36: the continuation itself must still work, or the fix is just a deletion.
arm_at "a genuinely open statement still continues across the newline" 7 "renders a throwable raw" StillCont.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e =
        outcome.exceptionOrNull()
    log("$e")
}'

# 37 — FALSE NEGATIVE (codex-splice): binding and use on ONE line inside a block that also closes
#      there. The walk was right; the line-end snapshot threw the answer away before it was read.
arm_at "a binding used on the same line it closes is still seen" 5 "renders a throwable raw" SameLineUse.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    if (x) { val e = outcome.exceptionOrNull(); log("$e") }
}'

# 37b — the same shape one level deeper and through a non-control lambda, because arm 37 alone
#       would pass a fix that special-cased `if`.
arm_at "the same-line shape holds nested and inside a lambda" 5 "renders a throwable raw" SameLineNest.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    run { if (y) { val e = outcome.exceptionOrNull(); log("$e") } }
}'

# 38 — FALSE POSITIVE (codex-splice): only THROWABLE sources were recorded, so a plain declaration
#      could not hide anything and an outer binding stayed visible under a name that no longer
#      referred to it. Every declaration is recorded now; a shadow is a binding that is not one.
arm "an inner nonthrowable val hides the outer throwable" 0 ShadowVal.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    if (x) { val e = "event"; log("$e") }
}'

# 38b — THE BOUND on 38: the shadow lasts for its own block and NOT one character longer. A fix
#       that dropped the outer binding instead of hiding it would pass 38 and lose the real render.
arm_at "the outer throwable comes back when the shadow block ends" 7 "renders a throwable raw" ShadowEnds.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    if (x) { val e = "event"; log("$e") }
    log("$e")
}'

# 39 — grok-splice widened the shadow class: a `for` parameter is a declaration too.
arm "a for-loop parameter hides an outer throwable" 0 ShadowFor.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    for (e in xs) { log("$e") }
}'

# 40 — and so is a lambda parameter, which is the same rebinding the SHORT-name tier already
#      respected on its own plane. The two planes disagreeing about what `e` means was the defect.
arm "a lambda parameter hides an outer throwable" 0 ShadowLambda.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEach { e -> log("$e") }
}'

# 40b — THE BOUND on 39/40, and this one is a defect the FIX introduced rather than one a reviewer
#       reported. `when (k) { e -> … }` COMPARES the subject to the value e; it declares nothing.
#       Read as a lambda parameter it shadowed the real throwable and the render went silent — a
#       false negative bought with the two false positives above. A keyword list cannot separate the
#       two arrows (the name here is neither `else` nor `is`), so the block remembers whether a
#       `when` head opened it.
arm_at "a when-branch head is a comparison, not a parameter" 7 "renders a throwable raw" WhenArrow.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    when (k) {
        e -> log("$e")
    }
}'

# 40c — subjectless `when { … }` takes the same path and is spelled differently enough to miss.
arm_at "a subjectless when branch head is not a parameter either" 7 "renders a throwable raw" WhenBare.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    when {
        e -> log("$e")
    }
}'

# 40d — and the bound on THAT: a real lambda nested inside a when branch still shadows. Suppressing
#       parameter declarations for everything under a `when` would pass 40b and 40c and reopen 40.
arm "a lambda inside a when branch still shadows" 0 WhenLambda.kt 'package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    when (k) {
        else -> items.forEach { e -> log("$e") }
    }
}'

# 13 — CONTROL: prose ABOUT the law is a comment and cannot render anything at runtime.
arm "comment mentioning \$failure is not flagged" 0 A.kt 'package p
import java.nio.file.Files
// a bare `$failure` here would quote file bytes, which is why ${e.message} is banned
fun a(e: Throwable) = Files.exists(p).also { log("x (${SafeFailureText.render(e)})") }'

# 14 — the real gate must be green on the real tree, or the leg is reporting on nothing.
( cd "$ROOT" && python3 checks/config/safe-failure-render.py check . >/dev/null 2>&1 ) \
  || err "the real repository does not pass its own wall"

[ "$fail" = 0 ] && echo "  ✓ safe-failure-render selftest: 62 arms"
exit "$fail"
