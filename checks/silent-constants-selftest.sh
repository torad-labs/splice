#!/usr/bin/env bash
# checks/silent-constants-selftest.sh — red-green proof for the silent-constants ratchet, AGAINST
# THE REAL TREE.
#
# WHY THIS EXISTS on top of `silent-constants.ts --selftest`. That selftest proves the logic
# against hand-written fixtures; that answers "does the ratchet work", not "does it work on THIS
# source". A ratchet's whole value is the claim "the recorded census is what the tree measures, and
# a regression moves it", and the way that claim dies is silently: a parser that stops matching the
# real declaration shapes measures zero, agrees with a baseline of zero, and reports OK forever.
# So every fixture here mirrors the actual gateway modules and mutates a COPY (§24: prove the gate
# can fail, including the boring case).
#
# THE CONTROL COMES FIRST and is not decoration. Each fixture claims "this mutation turns green
# into red", which is worth nothing unless the unmutated mirror is green. If the control fails,
# this script says so and reports every fixture below it as unproven rather than passing.
#
# FIXTURES (each must exit non-zero, and each asserts the REASON as well as the exit code):
#   0  control: the real tree + the real baseline                  -> exit 0, counts printed
#   1  a synthetic silent const in a NEW module file               -> GROWTH, by const name
#   2  a synthetic silent const appended to a REAL file            -> GROWTH, by file and name
#   3  the same const given an adjacent `// why:` reason           -> exit 0 (the compliant twin)
#   4  a baseline entry raised above the measurement               -> STALE
#   5  a baseline entry naming a file that does not exist          -> STALE
#   6  the baseline file removed                                   -> untrustworthy, exit 2
#   7  the documented gate line, run verbatim on fixture 1         -> non-zero (the line gates)
#
# EVERYTHING RUNS OUT OF TREE. The harness is a mktemp -d holding a COPY of the checker (ROOT is
# derived from its own __file__, so a copy under $tmp/checks measures $tmp), a COPY of the
# baseline, one SYMLINK per gateway module, and — for the modules it mutates — a real copy of just
# that module's src/main. Nothing is written into gateway/ and the working tree is not touched.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ silent-constants-selftest: $1"; fail=1; }

CHECKER="$tmp/checks/silent-constants.ts"
BASELINE="$tmp/checks/config/silent-constants-baseline.json"
# the module whose src/main is materialised as real files so a fixture can mutate one (7 .kt
# files; every other module stays a symlink so the census is the real one)
MUTABLE_FILE="providers/muse/src/main/kotlin/splice/provider/muse/MuseKeyMint.kt"
MUTABLE_DIR="${MUTABLE_FILE%%/src/*}" # the module home the arms mutate — its directory, wherever the module lives

# ── harness ───────────────────────────────────────────────────────────────────────────────────
build_harness() {
  rm -rf "$tmp/gateway" "$tmp/client" "$tmp/checks"
  mkdir -p "$tmp/checks/config" "$tmp/gateway"
  cp "$ROOT/checks/silent-constants.ts" "$CHECKER"
  cp "$ROOT/checks/config/silent-constants-baseline.json" "$BASELINE"
  # THE LINK SET COMES FROM settings.gradle.kts, never from one directory (restructure PR 3 moves the
  # modules out of gateway/ one commit at a time): a harness that measures a tree with a module
  # missing hands its control a red that reads exactly like a real regression — or, worse, a green
  # over a smaller tree. Every module home the build declares is linked, except the one module the
  # arms mutate, which is COPIED so the tree is never written; none is spelled here.
  module_dirs="$(grep -oE 'projectDir = file\("[^"]+"\)' "$ROOT/settings.gradle.kts" | sed -E 's/.*file\("([^"]+)"\)/\1/' | sort -u)"
  [ -n "$module_dirs" ] || { echo "  ✗ silent-constants-selftest: settings.gradle.kts states no projectDir — nothing to link"; exit 1; }
  for dir in $module_dirs; do
    [ -d "$ROOT/$dir/src/main" ] || continue
    mkdir -p "$tmp/$(dirname "$dir")"
    if [ "$dir" = "$MUTABLE_DIR" ]; then
      mkdir -p "$tmp/$dir/src"
      cp -r "$ROOT/$dir/src/main" "$tmp/$dir/src/main"
    else
      [ -e "$tmp/$dir" ] || ln -s "$ROOT/$dir" "$tmp/$dir"
    fi
  done
  [ -e "$tmp/core/src/main" ] || { echo "  ✗ silent-constants-selftest: :core is not linked — the harness lost the first module that moved out of gateway/"; exit 1; }
  [ -e "$tmp/client/src/main" ] || { echo "  ✗ silent-constants-selftest: :client is not linked — the harness lost a module home"; exit 1; }
  [ -f "$tmp/$MUTABLE_FILE" ] || { echo "  ✗ silent-constants-selftest: $MUTABLE_FILE did not materialise"; exit 1; }
}

ratchet() { bun "$CHECKER" --ratchet --root "$tmp" 2>&1; }

expect_red() { # label, needle...
  local label="$1"; shift
  local out code
  out="$(ratchet)"; code=$?
  if [ "$code" -eq 0 ]; then
    err "$label: expected non-zero, got 0"
    return
  fi
  local needle
  for needle in "$@"; do
    case "$out" in
      *"$needle"*) ;;
      *) err "$label: red for the wrong reason — no '$needle' in output" ;;
    esac
  done
  echo "  ✓ $label (exit $code)"
}

expect_green() { # label, needle...
  local label="$1"; shift
  local out code
  out="$(ratchet)"; code=$?
  if [ "$code" -ne 0 ]; then
    err "$label: expected 0, got $code"
    echo "$out" | sed 's/^/      /'
    return
  fi
  local needle
  for needle in "$@"; do
    case "$out" in
      *"$needle"*) ;;
      *) err "$label: green but did not report '$needle'" ;;
    esac
  done
  echo "  ✓ $label"
}

# ── 0. control ────────────────────────────────────────────────────────────────────────────────
build_harness
control_out="$(ratchet)"; control_code=$?
if [ "$control_code" -ne 0 ]; then
  echo "  ✗ silent-constants-selftest: CONTROL FAILED — the real tree does not match its own"
  echo "    baseline, so every fixture below is UNPROVEN. Re-record with"
  echo "    'bun checks/silent-constants.ts --record > checks/config/silent-constants-baseline.json'"
  echo "$control_out" | sed 's/^/      /'
  exit 1
fi
echo "  ✓ control: the real tree matches its recorded baseline"
echo "$control_out" | grep -E 'numeric const val|silent \(no reason\)|files carrying' | sed 's/^/      /'

# ── 1. GROWTH: a synthetic silent const in a new module file ──────────────────────────────────
mkdir -p "$tmp/gateway/zz-selftest/src/main/kotlin/splice/selftest"
cat > "$tmp/gateway/zz-selftest/src/main/kotlin/splice/selftest/Synthetic.kt" <<'KT'
package splice.selftest

private const val SELFTEST_UNEXPLAINED_MS = 31_337L
KT
expect_red "1. a silent const in a NEW file is GROWTH" "GROWTH" "SELFTEST_UNEXPLAINED_MS"

# ── 2. GROWTH: appended to a REAL file already carrying explained constants ───────────────────
build_harness
printf '\nprivate const val SELFTEST_APPENDED_CAP = 4_096\n' >> "$tmp/$MUTABLE_FILE"
expect_red "2. a silent const appended to a real file is GROWTH by file and name" \
  "GROWTH" "$MUTABLE_FILE" "SELFTEST_APPENDED_CAP"

# ── 3. the compliant twin: the SAME const with an adjacent reason ──────────────────────────────
build_harness
printf '\n// why: 4 KiB is the mint payload ceiling this selftest asserts against\nprivate const val SELFTEST_APPENDED_CAP = 4_096\n' >> "$tmp/$MUTABLE_FILE"
expect_green "3. the same const with an adjacent '// why:' reason is GREEN" "ratchet holds"

# ── 4. STALE: a baseline entry raised above the measurement ───────────────────────────────────
build_harness
bun -e '
const [path, rel] = process.argv.slice(1);
const doc = JSON.parse(await Bun.file(path).text());
doc.files[rel] = (doc.files[rel] ?? 0) + 5;
doc.total += 5;
await Bun.write(path, JSON.stringify(doc, null, 2));
' "$BASELINE" "$MUTABLE_FILE"
expect_red "4. a baseline entry held above the measurement is STALE" "STALE" "$MUTABLE_FILE"

# ── 5. STALE: a baseline entry naming a file that does not exist ──────────────────────────────
build_harness
bun -e '
const path = process.argv[1];
const doc = JSON.parse(await Bun.file(path).text());
doc.files["core/src/main/kotlin/splice/core/ZzVanished.kt"] = 0;
await Bun.write(path, JSON.stringify(doc, null, 2));
' "$BASELINE"
expect_red "5. a baseline naming a vanished file is STALE" "STALE" "ZzVanished.kt" "no longer exists"

# ── 6. the baseline removed: the instrument is untrustworthy, not green ───────────────────────
build_harness
rm -f "$BASELINE"
out="$(ratchet)"; code=$?
if [ "$code" -ne 2 ]; then
  err "6. a missing baseline must exit 2 (untrustworthy), got $code"
else
  case "$out" in
    *"has no recorded census"*) echo "  ✓ 6. a missing baseline is untrustworthy (exit 2)" ;;
    *) err "6. red for the wrong reason — no 'has no recorded census' in output" ;;
  esac
fi

# ── 7. the documented gate line is the line that gates ────────────────────────────────────────
# The ledger note tells the orchestrator to wire exactly:
#   run "silent constants"  bun checks/silent-constants.ts --ratchet
# Proven here verbatim (with --root, which is the only difference between this harness and the
# repo root) against fixture 1, so the note cannot document a command that does not gate.
build_harness
mkdir -p "$tmp/gateway/zz-selftest/src/main/kotlin/splice/selftest"
cat > "$tmp/gateway/zz-selftest/src/main/kotlin/splice/selftest/Synthetic.kt" <<'KT'
package splice.selftest

private const val SELFTEST_UNEXPLAINED_MS = 31_337L
KT
if bun "$CHECKER" --ratchet --root "$tmp" >/dev/null 2>&1; then
  err "7. the documented gate line exited 0 on a tree with a synthetic regression"
else
  echo "  ✓ 7. the documented gate line fails on a regression"
fi

# ── --top is an instrument, not a gate: it must report without failing ────────────────────────
build_harness
if ! bun "$CHECKER" --top 15 --root "$tmp" | grep -q 'carry no adjacent reason'; then
  err "--top 15 did not report the census"
else
  echo "  ✓ --top 15 reports the worst files without gating"
fi

if [ "$fail" -ne 0 ]; then
  echo "silent-constants-selftest FAILED"
  exit 1
fi
echo "silent-constants-selftest OK — the real tree matches its baseline; a new silent const, one"
echo "appended to a real file, a baseline held above the measurement and a baseline naming a"
echo "vanished file all go red BY NAME; the compliant twin and --top stay green; a missing"
echo "baseline is untrustworthy rather than passing."
