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
# DR-131/DR-132: the shared rule-document enumerator the severity wall now parses with. Omitting it
# would make config-guard.sh fail CLOSED on every fixture below — including the control — and a wall
# that rejects everything because its checker is missing proves nothing about the checker.
cp "$ROOT/checks/config/ast-grep-rule-docs.py" "$tmp/checks/config/"
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

# ── DR-131/DR-132 ────────────────────────────────────────────────────────────────────────────
# Arms 1-2 above were the whole mutation set, and BOTH plant a top-level, block-style `severity:`
# line — so they only ever exercised shapes the old line grep could already see. Four shapes it
# could not see rode straight through while ast-grep loaded the rule and ran it NON-BLOCKING
# (measured, ast-grep 0.45.0: `ast-grep scan` exits 0 on a match for each of these; the control
# below exits 1). The boring case is the one that matters — an unlisted doc with no severity at
# all — and it is arm 4.

# ── 3. DR-131: severity nested under `metadata:` satisfies a line count, not a parser ─────────
cat > "$FIXTURE" <<'YML'
id: dr131-nested
language: kotlin
metadata:
  severity: error
rule:
  pattern: selftestBadOne()
YML
guard
must_fail "3. severity nested under metadata:" "runs non-blocking"
rm -f "$FIXTURE"

# ── 4. DR-131: `severity: error` inside a block scalar — 15 .rules files use block scalars ────
cat > "$FIXTURE" <<'YML'
id: dr131-scalar
language: kotlin
note: |
  severity: error
rule:
  pattern: selftestBadOne()
YML
guard
must_fail "4. severity: error only inside a note: scalar" "runs non-blocking"
rm -f "$FIXTURE"

# ── 5. DR-131: the DR-115 multi-doc case re-opened by a decoy in doc one ──────────────────────
cat > "$FIXTURE" <<'YML'
id: dr131-decoy-one
language: kotlin
severity: error
note: |
  severity: error
rule:
  pattern: neverMatchesAnything()
---
id: dr131-decoy-two
language: kotlin
rule:
  pattern: selftestBadTwo()
YML
guard
must_fail "5. multi-doc, doc two severity-less behind a decoy" "runs non-blocking"
rm -f "$FIXTURE"

# ── 6. DR-132: flow style has no line-leading `id:`, so the old wall skipped the file whole ───
cat > "$FIXTURE" <<'YML'
{id: dr132-flow, language: kotlin, rule: {pattern: selftestBadOne()}}
YML
guard
must_fail "6. flow-style rule with no severity" "runs non-blocking"
rm -f "$FIXTURE"

# ── 7. CONTROL: a flow-style rule that IS a blocking error must PASS ──────────────────────────
# Without this, arm 6 is satisfied by a wall that rejects every flow-style file on sight — which
# would be a new false-positive class, not a fix. The exemption must key on severity, not shape.
cat > "$FIXTURE" <<'YML'
{id: dr132-flow-ok, language: kotlin, severity: error, rule: {pattern: selftestBadOne()}}
YML
guard
if [ "$rc" -ne 0 ]; then
  err "7. CONTROL flow-style rule WITH severity: error — must PASS, exited $rc: $(tail -3 "$tmp/out" | tr '\n' ' ')"
else
  note "✓ 7. control: a flow-style rule with severity: error passes (the wall keys on severity, not shape)"
fi
rm -f "$FIXTURE"

# ── 8. CONTROL: a rule-test FIXTURE still needs no severity, in flow style too ────────────────
cat > "$FIXTURE" <<'YML'
{id: dr132-fixture, valid: ["fun ok() {}"], invalid: ["fun bad() {}"]}
YML
guard
if [ "$rc" -ne 0 ]; then
  err "8. CONTROL flow-style rule-test fixture — must PASS (no rule: block), exited $rc: $(tail -3 "$tmp/out" | tr '\n' ' ')"
else
  note "✓ 8. control: a valid/invalid fixture with no rule: block stays exempt in flow style"
fi
rm -f "$FIXTURE"

# ── DR-133: the concentration-leg routing guard's own reachability model ─────────────────────
# config-guard.sh's fifth section runs concentration-leg-routed.py, which asserts in its docstring
# that only a TOP-LEVEL leg counts. Its DR-114 nesting model knew if/while/until/for/case and
# nothing else, so a leg bash never executes was accepted as a routing. Mutations go on the
# MIRRORED gate.sh, never the repo's own.
GATE="$tmp/checks/gate.sh"
LEG='run "concentration"  npm run --silent gate:concentration'
gate_without_leg() { grep -vF "$LEG" "$ROOT/checks/gate.sh" > "$GATE"; }

# ── 9. DR-133: the leg moved into a function body nobody calls ────────────────────────────────
gate_without_leg
printf 'disabled_legs() {\n%s\n}\n' "$LEG" >> "$GATE"
guard
must_fail "9. concentration leg buried in an uncalled function" "nested scope"
cp "$ROOT/checks/gate.sh" "$GATE"

# ── 10. DR-133: the identical leg text as heredoc DATA ───────────────────────────────────────
gate_without_leg
printf "cat <<'EOF' >/dev/null\n%s\nEOF\n" "$LEG" >> "$GATE"
guard
must_fail "10. concentration leg present only as heredoc data" "does not run"
cp "$ROOT/checks/gate.sh" "$GATE"

# ── 11. DR-114 regression pin: the wrapped-leg case that model was built for ──────────────────
gate_without_leg
printf 'if false; then\n%s\nfi\n' "$LEG" >> "$GATE"
guard
must_fail "11. concentration leg wrapped in if false" "nested scope"
cp "$ROOT/checks/gate.sh" "$GATE"

# ── 12. CONTROL: the real gate.sh defines functions with braces and must stay routed ──────────
# Brace counting is what closes arm 9, and gate.sh's own `run()`/`java_major()`/`path_java_*()`
# definitions are full of braces — if the model miscounted them the real leg would read as nested.
guard
if [ "$rc" -ne 0 ]; then
  err "12. CONTROL the real gate.sh must stay routed, exited $rc: $(tail -3 "$tmp/out" | tr '\n' ' ')"
else
  note "✓ 12. control: the real gate.sh's own function braces do not bury its top-level legs"
fi

if [ "$fail" -eq 0 ]; then
  echo "config-guard-selftest OK — 6 severity dodges and 3 unreachable-leg shapes fail the wall; real shapes still pass"
  exit 0
fi
echo "config-guard-selftest FAIL"
exit 1
