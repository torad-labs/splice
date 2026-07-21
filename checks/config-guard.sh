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
while IFS= read -r f; do
  grep -qE '^[[:space:]]*id:' "$f" || continue
  sev=$(grep -E '^[[:space:]]*severity:' "$f" | head -1 | awk '{print $2}')
  [ "$sev" = "error" ] || err "$f severity is '${sev:-unset}', must be 'error'"
done < <(find .rules -path .rules/rule-tests -prune -o -type f -name '*.yml' -print)

if [ "$fail" -eq 0 ]; then echo "config-guard: PASS"; else echo "config-guard: FAIL"; fi
exit "$fail"
