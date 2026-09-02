#!/usr/bin/env bash
# checks/config-guard.sh — rules that guard the RULES (#924 Phase 0.5, eli C2). Once inline
# @Suppress is walled, the generator's next drift move is to weaken the CONFIG instead of the code:
# add a detekt baseline, raise maxIssues, drop warningsAsErrors, or downgrade a wall's severity.
# This makes that regression fail CI — the config surface is now a checked boundary, not a soft one.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
fail=0
err() { echo "  ✗ $1"; fail=1; }

DETEKT=gateway/detekt.yml

# 1. No detekt baseline — a baseline.xml silently whitelists every finding present when it is created.
if grep -qE '^[[:space:]]*baseline[[:space:]]*:' "$DETEKT"; then
  err "detekt.yml declares a baseline — remove it (it suppresses existing findings)"
fi
if find gateway -name 'detekt-baseline.xml' -o -name 'baseline.xml' 2>/dev/null | grep -q .; then
  err "a detekt baseline.xml exists — delete it (findings must be fixed, not whitelisted)"
fi

# 2. Zero-tolerance posture intact.
grep -qE 'maxIssues:[[:space:]]*0' "$DETEKT" || err "detekt.yml build.maxIssues must be 0"
grep -qE 'warningsAsErrors:[[:space:]]*true' "$DETEKT" || err "detekt.yml config.warningsAsErrors must be true"

# 3. Every ast-grep rule definition stays a blocking error (no silent downgrade to
# warning/hint). Rule-test fixtures also have an id but are not rule definitions; the exemption is
# structural (`valid:`/`invalid:` cases and no `rule:`), never a hardcoded path, so a real rule
# dropped into any directory named rule-tests is still checked.
#
# DR-131/DR-132: this used to be a line grep — `id:` lines counted against `severity: error` lines.
# That denominator was indentation-blind and shape-blind, and four shapes rode through it while
# ast-grep loaded the rule and ran it NON-BLOCKING: severity nested under `metadata:`, severity
# sitting inside a `note:` block scalar, a multi-doc file whose second doc has no severity at all,
# and flow style (`{id: x, rule: {...}}`), which has no line-leading `id:` at all and so skipped
# this check entirely. Structure is the denominator now: checks/config/ast-grep-rule-docs.py parses
# the YAML and enumerates documents the way ast-grep does. rule-routing.sh calls the same module,
# so the two legs still cannot disagree about what a rule is — the difference is that they now
# agree with ast-grep rather than only with each other.
python3 checks/config/ast-grep-rule-docs.py severity . || fail=1

# 4. Dependabot Kotlin ignore block stays scoped to the compiler/toolchain (#18/#37),
# not the independently-versioned kotlinx libraries (kover, coroutines, serialization).
python3 checks/config/dependabot-kotlin-scope.py || fail=1

# 5. The concentration leg is routed AND still ratchets. package.json is a config surface like any
# other here, and `"gate:concentration": "python3 checks/concentration.py --top 5"` is a one-line
# edit that exits 0 forever while checks/gate.sh keeps printing a green concentration leg. Same
# completeness shape checks/rule-routing.sh applies to ast-grep rule directories, one surface up.
python3 checks/config/concentration-leg-routed.py || fail=1

if [ "$fail" -eq 0 ]; then echo "config-guard: PASS"; else echo "config-guard: FAIL"; fi
exit "$fail"
