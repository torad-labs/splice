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

# 13 — CONTROL: prose ABOUT the law is a comment and cannot render anything at runtime.
arm "comment mentioning \$failure is not flagged" 0 A.kt 'package p
import java.nio.file.Files
// a bare `$failure` here would quote file bytes, which is why ${e.message} is banned
fun a(e: Throwable) = Files.exists(p).also { log("x (${SafeFailureText.render(e)})") }'

# 14 — the real gate must be green on the real tree, or the leg is reporting on nothing.
( cd "$ROOT" && python3 checks/config/safe-failure-render.py check . >/dev/null 2>&1 ) \
  || err "the real repository does not pass its own wall"

[ "$fail" = 0 ] && echo "  ✓ safe-failure-render selftest: 40 arms"
exit "$fail"
