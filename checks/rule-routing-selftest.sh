#!/usr/bin/env bash
# checks/rule-routing-selftest.sh — mutation-proves checks/rule-routing.sh (DR-132).
#
# Same defence-in-depth idiom as the catalog, secret-scan, concentration and config-guard
# selftests: the leg guards the tree, this canary guards the LEG. rule-routing.sh shipped without
# one, and that is exactly how its forward direction ran fail-OPEN — a dormant directory holding a
# flow-style rule (`{id: x, language: kotlin, rule: {pattern: p()}}`) reported PASS, because the
# "is this a rule file?" test was `grep -E '^[[:space:]]*id:'` and flow style has no line-leading
# `id:`. That is the 2026-07-16 .rules/kotlin scar reproduced inside the script written to prevent
# it, and nothing re-ran the hand transcripts that would have caught it.
#
# Runs the real script against a mirrored tree so fixtures never touch the repo's own .rules.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ rule-routing-selftest: $1"; fail=1; }
note() { printf '  %s\n' "$1"; }

# ── mirror: everything rule-routing.sh reads ─────────────────────────────────────────────────
mkdir -p "$tmp/checks/config"
cp "$ROOT/checks/rule-routing.sh" "$tmp/checks/"
cp "$ROOT/checks/config/ast-grep-rule-docs.py" "$tmp/checks/config/"
cp "$ROOT/sgconfig.yml" "$tmp/"
cp -r "$ROOT/.rules" "$tmp/.rules"

rc=0
routing() { bash "$tmp/checks/rule-routing.sh" >"$tmp/out" 2>&1; rc=$?; }

must_fail() { # must_fail <label> <substring>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The wall is fail-open."
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -3 "$tmp/out" | tr '\n' ' ')"
  else
    note "✓ $1 (exit $rc)"
  fi
}

# ── control: the mirrored, unmutated tree must be green ──────────────────────────────────────
routing
if [ "$rc" -ne 0 ]; then
  err "CONTROL: the unmutated mirror must be GREEN (exit $rc): $(tail -4 "$tmp/out" | tr '\n' ' ')"
  echo "  ✗ rule-routing-selftest: control failed — the fixtures below are UNPROVEN"
  exit 1
fi
note "✓ control: mirrored tree green"

DORMANT="$tmp/.rules/zz-selftest-dormant"

# ── 1. DR-132: an unreferenced directory holding a FLOW-STYLE rule ───────────────────────────
mkdir -p "$DORMANT"
printf '{id: zz-dormant-flow, language: kotlin, severity: error, rule: {pattern: selftestBad()}}\n' \
  > "$DORMANT/r.yml"
routing
must_fail "1. dormant dir holding a flow-style rule" "but nothing references it"
rm -rf "$DORMANT"

# ── 2. the block-style equivalent — the shape that already worked, pinned so it keeps working ─
mkdir -p "$DORMANT"
cat > "$DORMANT/r.yml" <<'YML'
id: zz-dormant-block
language: kotlin
severity: error
rule:
  pattern: selftestBad()
YML
routing
must_fail "2. dormant dir holding a block-style rule" "but nothing references it"
rm -rf "$DORMANT"

# ── 3. inverse direction: a routed ruleDir that holds no rules ────────────────────────────────
mkdir -p "$tmp/.rules/zz-empty"
printf 'ruleDirs:\n  - .rules/rules\n  - .rules/kotlin-splice\n  - .rules/zz-empty\ntestConfigs:\n  - testDir: .rules/rule-tests\n' \
  > "$tmp/sgconfig.yml"
routing
must_fail "3. routed ruleDir holding 0 rule files" "holds 0 ast-grep rule files"
rmdir "$tmp/.rules/zz-empty"

# ── 4. inverse direction: a routed ruleDir that does not exist ────────────────────────────────
routing
must_fail "4. routed ruleDir that does not exist" "which does not exist"
cp "$ROOT/sgconfig.yml" "$tmp/"

# ── 5. the enumerator itself must be REQUIRED, not optional ──────────────────────────────────
# A broken module returns 0 rule files for every directory, and 0 means "nothing to check here" —
# silently turning this whole leg into a no-op. That is the bug class the leg exists to catch, one
# level up, so an unrunnable enumerator has to be a hard failure and not a quiet fallback.
mv "$tmp/checks/config/ast-grep-rule-docs.py" "$tmp/checks/config/ast-grep-rule-docs.py.bak"
routing
must_fail "5. enumerator missing" "is not runnable"
mv "$tmp/checks/config/ast-grep-rule-docs.py.bak" "$tmp/checks/config/ast-grep-rule-docs.py"

# ── 6. CONTROL: a dormant dir carrying a dated allowlist entry stays green ────────────────────
# Guards against "fix" by rejecting every unreferenced directory: .rules/kotlin is deliberately
# unrouted and must keep passing on its dated exemption.
routing
if [ "$rc" -ne 0 ]; then
  err "6. CONTROL the allowlisted .rules/kotlin pack must stay green, exited $rc: $(tail -3 "$tmp/out" | tr '\n' ' ')"
else
  note "✓ 6. control: the dated UNROUTED_ALLOWLIST exemption still passes"
fi

if [ "$fail" -eq 0 ]; then
  echo "rule-routing-selftest OK — dormant packs fail in both YAML styles, both inverse shapes fail, a missing enumerator fails"
  exit 0
fi
echo "rule-routing-selftest FAIL"
exit 1
