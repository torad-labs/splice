#!/usr/bin/env bash
# checks/config-guard.sh — rules that guard the RULES (#924 Phase 0.5, eli C2). Once inline
# @Suppress is walled, the generator's next drift move is to weaken the CONFIG instead of the code:
# add a detekt baseline, raise maxIssues, drop warningsAsErrors, or downgrade a wall's severity.
# This makes that regression fail CI — the config surface is now a checked boundary, not a soft one.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
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
# warning/hint). Rule-test fixtures also have an id but are not rule definitions.
#
# A fixture is identified STRUCTURALLY (`valid:`/`invalid:` cases, and no `rule:` block), not by
# living at one hardcoded path. It used to be `-path .rules/rule-tests -prune`, which broke the
# moment a second test directory appeared — .rules/kotlin/ast-grep/rule-tests, added 2026-08-17
# (HD-21) so the dormant pack can red/green pin a matcher before it graduates. Deriving the
# exemption from the file's SHAPE also closes the hole the old prune opened: a real rule definition
# dropped into any directory named rule-tests skipped this check entirely, and now does not,
# because it carries a `rule:` block. Fixture-shaped, exempt; rule-shaped, checked — wherever it sits.
while IFS= read -r f; do
  grep -qE '^[[:space:]]*id:' "$f" || continue
  if grep -qE '^[[:space:]]*(valid|invalid):' "$f" && ! grep -qE '^[[:space:]]*rule:' "$f"; then
    continue # a rule-test fixture, not a rule definition
  fi
  sev=$(grep -E '^[[:space:]]*severity:' "$f" | head -1 | awk '{print $2}')
  [ "$sev" = "error" ] || err "$f severity is '${sev:-unset}', must be 'error'"
done < <(find .rules -type f -name '*.yml' -print)

# 4. Dependabot Kotlin ignore block stays scoped to the compiler/toolchain (#18/#37),
# not the independently-versioned kotlinx libraries (kover, coroutines, serialization).
python3 checks/config/dependabot-kotlin-scope.py || fail=1

if [ "$fail" -eq 0 ]; then echo "config-guard: PASS"; else echo "config-guard: FAIL"; fi
exit "$fail"
