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

# 13 — CONTROL: prose ABOUT the law is a comment and cannot render anything at runtime.
arm "comment mentioning \$failure is not flagged" 0 A.kt 'package p
import java.nio.file.Files
// a bare `$failure` here would quote file bytes, which is why ${e.message} is banned
fun a(e: Throwable) = Files.exists(p).also { log("x (${SafeFailureText.render(e)})") }'

# 14 — the real gate must be green on the real tree, or the leg is reporting on nothing.
( cd "$ROOT" && python3 checks/config/safe-failure-render.py check . >/dev/null 2>&1 ) \
  || err "the real repository does not pass its own wall"

[ "$fail" = 0 ] && echo "  ✓ safe-failure-render selftest: 14 arms"
exit "$fail"
