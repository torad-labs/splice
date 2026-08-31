#!/usr/bin/env bash
# checks/config-guard-selftest.sh — mutation-proves config-guard.sh's severity wall (DR-115).
# The wall guards the RULES; this proves the wall can actually fail. Runs the real script against
# a mirrored tree so fixtures never touch the repo's own .rules.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ config-guard-selftest: $1"; fail=1; }
note() { printf '  %s\n' "$1"; }

# ── mirror: everything config-guard.sh and its two python legs read ──────────────────────────
mkdir -p "$tmp/checks/config" "$tmp/gateway" "$tmp/.github"
cp "$ROOT/checks/config-guard.sh" "$tmp/checks/"
cp "$ROOT/checks/config/dependabot-kotlin-scope.py" "$ROOT/checks/config/concentration-leg-routed.py" "$tmp/checks/config/"
cp "$ROOT/gateway/detekt.yml" "$tmp/gateway/"
cp "$ROOT/.github/dependabot.yml" "$tmp/.github/"
cp "$ROOT/package.json" "$tmp/"
cp "$ROOT/checks/gate.sh" "$tmp/checks/"
cp -r "$ROOT/.rules" "$tmp/.rules"

rc=0
guard() { bash "$tmp/checks/config-guard.sh" >"$tmp/out" 2>&1; rc=$?; }

must_fail() { # must_fail <label> <substring>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The wall is not enforcing."
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -3 "$tmp/out" | tr '\n' ' ')"
  else
    note "✓ $1 (exit $rc)"
  fi
}

# ── control: the mirrored, unmutated tree must be green ──────────────────────────────────────
guard
if [ "$rc" -ne 0 ]; then
  err "CONTROL: the unmutated mirror must be GREEN (exit $rc): $(tail -4 "$tmp/out" | tr '\n' ' ')"
  echo "  ✗ config-guard-selftest: control failed — the fixtures below are UNPROVEN"
  exit 1
fi
note "✓ control: mirrored tree green"

FIXTURE="$tmp/.rules/kotlin-splice/zz-dr115-fixture.yml"

# ── 1. DR-115: a second YAML doc downgraded to warning must fail, not hide behind doc one ────
cat > "$FIXTURE" <<'YML'
id: dr115-doc-one
language: kotlin
severity: error
rule:
  pattern: selftestBadOne()
---
id: dr115-doc-two
language: kotlin
severity: warning
rule:
  pattern: selftestBadTwo()
YML
guard
must_fail "1. second doc at severity warning" "non-error severity"
rm -f "$FIXTURE"

# ── 2. DR-115: a second doc with NO severity runs non-blocking and must equally fail ─────────
cat > "$FIXTURE" <<'YML'
id: dr115-doc-one
language: kotlin
severity: error
rule:
  pattern: selftestBadOne()
---
id: dr115-doc-two
language: kotlin
rule:
  pattern: selftestBadTwo()
YML
guard
must_fail "2. second doc with severity omitted" "runs non-blocking"
rm -f "$FIXTURE"

if [ "$fail" -eq 0 ]; then
  echo "config-guard-selftest OK — multi-document severity downgrades and omissions both fail the wall"
  exit 0
fi
echo "config-guard-selftest FAIL"
exit 1
