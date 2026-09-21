#!/usr/bin/env bash
# checks/autocloseable-closed-selftest.sh — mutation-proves checks/autocloseable-closed.ts.
#
# V4-95. The checker guards the tree; this canary guards the CHECKER. Same defence-in-depth idiom as
# tools/gate/test/routing.test.ts and checks/concentration-selftest.sh, and written for the same
# reason those exist: the leg that ran fail-OPEN for a month reported PASS the whole time, and
# nothing re-ran the hand transcripts that would have caught it.
#
# Two things are proven here that `--selftest` cannot prove about itself:
#   - the checker's verdict against the REAL tree is the one the ledger records (not "some
#     failure"), including the exact type named;
#   - `--selftest` CAN FAIL. A canary that only ever passes is the green tick this campaign has
#     already caught twice. Fixture 6 breaks audit() so it returns no problems, and demands that
#     --selftest go red for it.
#
# Runs the real checker against mirrored trees, so no fixture ever touches the repo's own sources.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ autocloseable-closed-selftest: $1"; fail=1; }
note() { printf '  %s\n' "$1"; }

CHECKER="$tmp/checks/autocloseable-closed.ts"
APP="$tmp/app/src/main/kotlin/splice/app"
SPI="$tmp/upstream/src/main/kotlin/splice/upstream"

mkdir -p "$tmp/checks" "$APP" "$SPI"
cp "$ROOT/checks/autocloseable-closed.ts" "$CHECKER"

rc=0
run() { bun "$CHECKER" >"$tmp/out" 2>&1; rc=$?; }

must_red() { # must_red <label> <substring>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The wall is fail-open. Output: $(head -2 "$tmp/out" | tr '\n' ' ')"
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -3 "$tmp/out" | tr '\n' ' ')"
  else
    note "✓ $1 (exit $rc, named '$2')"
  fi
}

must_green() { # must_green <label>
  if [ "$rc" -ne 0 ]; then
    err "$1 — MUST exit 0, exited $rc: $(head -3 "$tmp/out" | tr '\n' ' ')"
  else
    note "✓ $1 (exit 0)"
  fi
}

# ── 0. the checker's own --selftest must pass ─────────────────────────────────────────────────
if bun "$CHECKER" --selftest >"$tmp/out" 2>&1; then
  note "✓ 0. the checker's --selftest passes"
else
  err "0. the checker's --selftest FAILS: $(tail -5 "$tmp/out" | tr '\n' ' ')"
fi

# ── 1. CONTROL against the REAL tree: red, and red for exactly the recorded reason ────────────
# The real tree is RED by design at V4-95 (the sibling fix row burns it down), so the control here
# is not "green" but "the verdict the ledger records". If this ever changes, the ledger's red
# inventory is stale and that is a finding in itself.
( cd "$ROOT" && bun checks/autocloseable-closed.ts >"$tmp/real" 2>&1 )
real_rc=$?
if [ "$real_rc" -eq 0 ]; then
  note "! 1. the REAL tree is now GREEN — the V4-95 red inventory (JvmCodeModeRuntime) has been fixed."
  note "     Update the ledger note; this canary's control expectation is stale, not wrong."
elif grep -q 'JvmCodeModeRuntime' "$tmp/real"; then
  note "✓ 1. control: the real tree is RED naming JvmCodeModeRuntime, as the V4-95 ledger records"
else
  err "1. the real tree is RED for an UNRECORDED reason: $(head -3 "$tmp/real" | tr '\n' ' ')"
fi

# ── the mirrored fixtures ─────────────────────────────────────────────────────────────────────
write_contract() {
  cat > "$SPI/Contract.kt" <<'KOT'
package splice.upstream

public interface CodeModeRuntime : AutoCloseable {
    public fun start(): Unit
}
KOT
}

# ── 2. a closeable that IS closed from main: green ────────────────────────────────────────────
write_contract
cat > "$APP/App.kt" <<'KOT'
package splice.app

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    override fun close() = Unit
}

internal class Runner {
    fun go(path: Path) {
        val lock = DaemonLock(path)
        lock.close()
    }
}
KOT
run
must_green "2. a closeable closed from main"

# ── 3. the SAME tree with the close removed: red BY NAME ──────────────────────────────────────
# The pair 2/3 is the point: 2's green is a measurement of the evidence, not of an empty scan.
cat > "$APP/App.kt" <<'KOT'
package splice.app

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    override fun close() = Unit
}

internal class Runner {
    fun go(path: Path) {
        val lock = DaemonLock(path)
        lock.toString()
    }
}
KOT
run
must_red "3. the same closeable with its close() removed" "DaemonLock"

# ── 4. the scar shape: a TWO-HOP closeable closed only from src/test ──────────────────────────
mkdir -p "$tmp/app/src/test/kotlin"
cat > "$APP/App.kt" <<'KOT'
package splice.app

public class JvmCodeModeRuntime : CodeModeRuntime {
    override fun start() = Unit
    override fun close() = Unit
}

internal class Arm {
    fun build() = Wiring(runtime = JvmCodeModeRuntime())
}
KOT
cat > "$tmp/app/src/test/kotlin/RuntimeTest.kt" <<'KOT'
package splice.app

class CodeModeRuntimeTest {
    fun reclaims() = JvmCodeModeRuntime().use { it.start() }
}
KOT
run
must_red "4. a two-hop closeable closed only in src/test" "JvmCodeModeRuntime"
if ! grep -q 'closed only here' "$tmp/out"; then
  err "4b. the RED must name WHERE the test-only closers are — that is what makes it actionable"
else
  note "✓ 4b. the RED names the test-only closers"
fi

# ── 5. fail CLOSED when there is nothing to read ──────────────────────────────────────────────
# A checker that reads zero sources and exits 0 turns the whole leg into a no-op. That is the bug
# class this file exists to catch, one level up, so an empty tree must be a hard failure.
mv "$APP/App.kt" "$tmp/App.kt.bak"
rm -f "$tmp/app/src/test/kotlin/RuntimeTest.kt"
mv "$SPI/Contract.kt" "$tmp/Contract.kt.bak"
run
must_red "5. no Kotlin main sources at all" "refusing to pass vacuously"
mv "$tmp/App.kt.bak" "$APP/App.kt"
mv "$tmp/Contract.kt.bak" "$SPI/Contract.kt"

# ── 6. --selftest MUST BE ABLE TO FAIL ────────────────────────────────────────────────────────
# Mutate audit() to report nothing. Every one of --selftest's red fixtures then has no finding to
# assert on, so a canary that still passes here is a canary that asserts nothing.
sed 's|^function audit(root: string): string\[\] {$|function audit(root: string): string[] {\n  return []; // selftest mutation|' \
  "$CHECKER" > "$tmp/checks/mutant.ts"
if ! grep -q 'selftest mutation' "$tmp/checks/mutant.ts"; then
  err "6. the mutation did not apply — audit()'s signature moved, so this fixture proves nothing"
elif bun "$tmp/checks/mutant.ts" --selftest >"$tmp/out" 2>&1; then
  err "6. a checker whose audit() reports NOTHING still passed --selftest. The canary asserts nothing."
else
  note "✓ 6. --selftest fails on a checker that cannot find anything (exit $?)"
fi

# ── 7. the ALLOWLIST cannot be widened silently ───────────────────────────────────────────────
# An allowlist entry with no dated reason must fail, even though it names a real type. Proven here
# against the REAL allowlist surface rather than inside the checker's own fixtures.
cat > "$APP/App.kt" <<'KOT'
package splice.app

public class JvmCodeModeRuntime : CodeModeRuntime {
    override fun start() = Unit
    override fun close() = Unit
}

internal class Arm {
    fun build() = Wiring(runtime = JvmCodeModeRuntime())
}
KOT
sed 's|^let ALLOWLIST: Record<string, string> = {};$|let ALLOWLIST: Record<string, string> = { JvmCodeModeRuntime: "trust me" };|' \
  "$CHECKER" > "$tmp/checks/undated.ts"
if ! grep -q 'trust me' "$tmp/checks/undated.ts"; then
  err "7. the mutation did not apply — the ALLOWLIST declaration moved"
else
  bun "$tmp/checks/undated.ts" >"$tmp/out" 2>&1; rc=$?
  must_red "7. an UNDATED allowlist entry" "not 'YYYY-MM-DD"
fi
sed 's|^let ALLOWLIST: Record<string, string> = {};$|let ALLOWLIST: Record<string, string> = { JvmCodeModeRuntime: "2026-09-17: canary fixture." };|' \
  "$CHECKER" > "$tmp/checks/dated.ts"
bun "$tmp/checks/dated.ts" >"$tmp/out" 2>&1; rc=$?
must_green "7b. a DATED allowlist entry is a disposition"

if [ "$fail" -eq 0 ]; then
  echo "autocloseable-closed-selftest OK — real-tree verdict matches the ledger, closed/unclosed pair proven both ways, two-hop test-only closer named, empty tree fails closed, --selftest is red-provable, allowlist needs a date"
  exit 0
fi
echo "autocloseable-closed-selftest FAIL"
exit 1
