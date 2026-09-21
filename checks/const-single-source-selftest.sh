#!/usr/bin/env bash
# checks/const-single-source-selftest.sh — red-green proof for the constant single-source wall,
# AGAINST THE REAL TREE, by PREDICTED DELTA on each plane separately.
#
# WHY THIS EXISTS on top of `const-single-source.ts --selftest`. The checker's own selftest proves
# the detector logic against hand-written fixtures, and it deliberately NEUTRALISES the shipped
# NAMED_SCARS list so a temp tree that does not contain the real tree's duplicates is not reported
# stale eight times over. That leaves two claims only the real source can settle, and they are the
# two that rot silently:
#   - the shipped NAMED_SCARS list still describes real duplicates (its STALE arm), and
#   - checks/config/const-single-source-baseline.json still matches what the tree measures.
# Both are asserted by the control below. A parser that drifts off the real declaration shapes
# would report an empty census, agree with an empty baseline, and pass forever (§24).
#
# THE CONTROL IS RED, ON PURPOSE, AND ITS REDNESS IS EXACT. The wall is graded (see the checker's
# docstring): the STRICT plane — EQUAL-BY-COMMENT, KNOB-SHADOW, NAMED-SCAR — is 13 findings today
# and has no baseline; the RATCHET plane — COPY/COLLISION — is 136 groups, all recorded, so it
# contributes ZERO problems. "Control" therefore means: exactly the strict findings, and not one
# GROWTH or STALE line. Every fixture then asserts a PREDICTED DELTA against those two numbers,
# which is the only way a green can mean "the mutation was seen" rather than "something was red
# anyway".
#
# FIXTURES (each asserts the delta AND the reason, never just an exit code):
#   0  control: the real tree + the real baseline  -> trustworthy; strict 13, growth/stale 0
#   1  a synthetic COPY, not baselined             -> ratchet +1 GROWTH, strict +0
#   2  the compliant twin (an import)              -> ratchet +0, strict +0
#   3  a BASELINED group spread to a new file      -> ratchet +1, the line says SPREAD
#   4  a baseline entry for a group not in the tree-> ratchet +1, the line says STALE
#   5  a NAMED_SCARS name recorded in the baseline -> ratchet +1, "both baselined and strict"
#   6  a synthetic must-stay-equal comment         -> strict +1, EQUAL-BY-COMMENT
#   7  a synthetic Knob-default shadow             -> strict +1, KNOB-SHADOW
#   8  the gateway modules removed                 -> untrustworthy, exit 2, not green
#   9  the documented gate line verbatim           -> passes clean, fails on a planted violation
#  10  `--inventory` lists the held plane          -> the held groups printed, baseline ignored
#  11  a BARE run on a tree with a planted dup     -> NON-ZERO, where --inventory on the same tree
#                                                     is zero: bare is the gate, not the inventory
#
# EVERYTHING RUNS OUT OF TREE: a mktemp -d holding a COPY of the checker and a COPY of the
# baseline, plus one SYMLINK per gateway module. Nothing is written into gateway/ and the working
# tree is not touched.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ const-single-source-selftest: $1"; fail=1; }

CHECKER="$tmp/checks/const-single-source.ts"
BASELINE="$tmp/checks/config/const-single-source-baseline.json"
SYNTH="$tmp/gateway/zz-selftest/src/main/kotlin/splice/selftest"

build_harness() {
  rm -rf "$tmp/gateway" "$tmp/client" "$tmp/checks"
  mkdir -p "$tmp/checks/config" "$tmp/gateway"
  cp "$ROOT/checks/const-single-source.ts" "$CHECKER"
  cp "$ROOT/checks/config/const-single-source-baseline.json" "$BASELINE"
  # THE LINK SET COMES FROM settings.gradle.kts, never from one directory (restructure PR 3 moves the
  # modules out of gateway/ one commit at a time): a harness that measures a tree with a module
  # missing hands its control a red that reads exactly like a real regression — or, worse, a green
  # over a smaller tree. Every module home the build declares is linked; none is spelled here.
  module_dirs="$(grep -oE 'projectDir = file\("[^"]+"\)' "$ROOT/settings.gradle.kts" | sed -E 's/.*file\("([^"]+)"\)/\1/' | sort -u)"
  [ -n "$module_dirs" ] || { echo "  ✗ const-single-source-selftest: settings.gradle.kts states no projectDir — nothing to link"; exit 1; }
  for dir in $module_dirs; do
    [ -d "$ROOT/$dir/src/main" ] || continue
    mkdir -p "$tmp/$(dirname "$dir")"
    [ -e "$tmp/$dir" ] || ln -s "$ROOT/$dir" "$tmp/$dir"
  done
  [ -e "$tmp/core/src/main" ] || { echo "  ✗ const-single-source-selftest: :core is not linked — the harness lost the first module that moved out of gateway/"; exit 1; }
  [ -e "$tmp/client/src/main" ] || { echo "  ✗ const-single-source-selftest: :client is not linked — the harness lost a module home"; exit 1; }
}

run_gate() { bun "$CHECKER" --ratchet --root "$tmp" 2>&1; }
strict_count() { printf '%s\n' "$1" | sed -n 's/.*STRICT findings .* measured *\([0-9]*\) .*/\1/p'; }
moved_count() { printf '%s\n' "$1" | grep -cE '^  ✗ (GROWTH|STALE)'; }

# ── 0. control ────────────────────────────────────────────────────────────────────────────────
build_harness
control_out="$(run_gate)"
control_code=$?
case "$control_out" in
  *"the parser and the source disagree"*|*"denominator is absent"*|*"refusing to pass vacuously"*|*"NAMED-SCAR STALE"*|*"missing required key"*)
    echo "  ✗ const-single-source-selftest: CONTROL UNTRUSTWORTHY — the instrument or the shipped"
    echo "    NAMED_SCARS list has drifted off the source, so every fixture below is UNPROVEN:"
    printf '%s\n' "$control_out" | grep -E 'disagree|absent|vacuously|NAMED-SCAR STALE|missing required key' | sed 's/^/      /'
    exit 1 ;;
esac
STRICT_BASE="$(strict_count "$control_out")"
MOVED_BASE="$(moved_count "$control_out")"
# V4-122 fixed every strict finding the shipped tree carried, so this control no longer expects a
# RED tree — requiring one would make the row's own success fail its own selftest, which is the
# stale-control failure this file already hit twice today (a control that pins a red state stops
# being a control the moment the red is fixed). What the control must still establish is that the
# instrument RAN and produced a READABLE count, and that the ratchet plane matches the tree; the
# strict arm is proven below by fixtures that plant their own violations, so a strict base of 0 does
# not make it vacuous — and the shape guard above still refuses a drifted or unparsable run.
if [ -z "$STRICT_BASE" ]; then
  echo "  ✗ const-single-source-selftest: CONTROL produced no strict count at all (exit $control_code);"
  echo "    every fixture below is UNPROVEN."
  printf '%s\n' "$control_out" | tail -4 | sed 's/^/      /'
  exit 1
fi
if [ "$MOVED_BASE" -ne 0 ]; then
  echo "  ✗ const-single-source-selftest: CONTROL has $MOVED_BASE GROWTH/STALE line(s) — the"
  echo "    baseline does not match the tree, so every ratchet fixture below is UNPROVEN."
  echo "    Re-record with 'bun checks/const-single-source.ts --record >"
  echo "    checks/config/const-single-source-baseline.json'."
  printf '%s\n' "$control_out" | grep -E '^  ✗ (GROWTH|STALE)' | sed 's/^/      /'
  exit 1
fi
echo "  ✓ control: strict $STRICT_BASE (V4-122 fixed the shipped tree's strict findings; the fixtures"
echo "            below plant their own), growth/stale 0 (the baseline matches the tree)"
printf '%s\n' "$control_out" | grep -E 'const declarations|STRICT findings|COPY/COLLISION|NAMED_SCARS' | sed 's/^/      /'

expect_delta() { # label, strict-delta, moved-delta, needle...
  local label="$1" want_strict="$2" want_moved="$3"; shift 3
  local out s m ds dm needle
  out="$(run_gate)"
  s="$(strict_count "$out")"
  m="$(moved_count "$out")"
  if [ -z "$s" ]; then
    err "$label: no strict count in output"
    return
  fi
  ds=$(( s - STRICT_BASE ))
  dm=$(( m - MOVED_BASE ))
  if [ "$ds" -ne "$want_strict" ] || [ "$dm" -ne "$want_moved" ]; then
    err "$label: predicted strict $want_strict / growth-stale $want_moved, measured $ds / $dm"
    return
  fi
  for needle in "$@"; do
    case "$out" in
      *"$needle"*) ;;
      *) err "$label: deltas are right but no '$needle' in output — it moved for the wrong reason" ;;
    esac
  done
  echo "  ✓ $label (strict +$ds, growth/stale +$dm)"
}

# ── 1. a synthetic COPY that is not in the baseline ───────────────────────────────────────────
build_harness
mkdir -p "$SYNTH"
printf 'package splice.selftest\n\ninternal const val SELFTEST_CEILING_MS = 120_000L\n' > "$SYNTH/A.kt"
printf 'package splice.selftest\n\ninternal const val SELFTEST_CEILING_MS = 120_000L\n' > "$SYNTH/B.kt"
expect_delta "1. an unbaselined synthetic COPY" 0 1 "GROWTH (COPY)" "SELFTEST_CEILING_MS" "not in the baseline"

# ── 2. the compliant twin ─────────────────────────────────────────────────────────────────────
# The SAME two files, except the second imports instead of re-declaring. BOTH deltas must be zero:
# a move here would mean the wall fires on the remedy, and no move in fixture 1 would mean it never
# fired at all. Both halves are needed.
build_harness
mkdir -p "$SYNTH"
printf 'package splice.selftest\n\ninternal const val SELFTEST_CEILING_MS = 120_000L\n' > "$SYNTH/A.kt"
printf 'package splice.selftest\n\nimport splice.selftest.SELFTEST_CEILING_MS\n\ninternal fun hold(): Long = SELFTEST_CEILING_MS\n' > "$SYNTH/B.kt"
expect_delta "2. the compliant twin (one declaration + an import) moves nothing" 0 0

# ── 3. a BASELINED group that spreads to a new file ───────────────────────────────────────────
# `COPY TYPE` is a real baseline entry (7 files today). An eighth declaration must be GROWTH even
# though the group itself is recorded — that is what makes the ratchet a one-way valve.
build_harness
grep -q '"COPY TYPE"' "$BASELINE" || err "3. precondition: 'COPY TYPE' is not in the baseline"
mkdir -p "$SYNTH"
printf 'package splice.selftest\n\ninternal const val TYPE = "type"\n' > "$SYNTH/A.kt"
expect_delta "3. a baselined group spread to a new file" 0 1 "GROWTH (COPY)" "SPREAD" "zz-selftest"

# ── 4. a baseline entry describing a group the tree does not have ─────────────────────────────
build_harness
# The harness edits the baseline with bun rather than an inline heredoc for the retired interpreter,
# so this file stops being an invoker at all. `bun -e '<script>' ARG` puts ARG at process.argv[1]
# (argv[0] is bun), which is the whole of the port: the mutation logic is unchanged.
bun -e '
const fs = require("fs");
const p = process.argv[1];
const doc = JSON.parse(fs.readFileSync(p, "utf8"));
doc.groups["COPY ZZ_SELFTEST_VANISHED"] = [
  "core/src/main/kotlin/splice/core/Gone.kt",
  "app/src/main/kotlin/splice/app/AlsoGone.kt",
];
doc.total += 1;
fs.writeFileSync(p, JSON.stringify(doc, null, 2));
' "$BASELINE"
expect_delta "4. a baseline entry for a group the tree no longer has" 0 1 \
  "STALE" "ZZ_SELFTEST_VANISHED" "no longer declares it in 2+ files"

# ── 5. a NAMED_SCARS name recorded in the baseline ────────────────────────────────────────────
# The two planes must not be able to launder each other: recording a strict name in the ratchet
# baseline is itself an error, and the strict finding still fires.
#
# V4-122 MADE THIS ARM SELF-CONTAINED. It used TAG_CHARS, which was a real shipped scar — so it
# depended on the tree still carrying the duplication the row was fixing, and went red the moment
# the row succeeded. It now PLANTS both halves: a duplication in a synthetic module (a real
# directory, never through build_harness's symlinks) and the matching scar entry in the harness's
# own copy of the checker. The claim is unchanged — the ratchet may not launder a strict name — and
# it is now a claim about the instrument, which is what a fixture should assert.
build_harness
mkdir -p "$tmp/gateway/syntheticmod/src/main/kotlin/splice/synthetic"
printf 'package splice.synthetic\n\nprivate const val SCAR_DUP = 5\n' \
  >"$tmp/gateway/syntheticmod/src/main/kotlin/splice/synthetic/A.kt"
printf 'package splice.synthetic\n\nprivate const val SCAR_DUP = 5\n' \
  >"$tmp/gateway/syntheticmod/src/main/kotlin/splice/synthetic/B.kt"
bun -e '
const fs = require("fs");
const [baseline, checker] = process.argv.slice(1);
const doc = JSON.parse(fs.readFileSync(baseline, "utf8"));
doc.groups["COPY SCAR_DUP"] = [
  "gateway/syntheticmod/src/main/kotlin/splice/synthetic/A.kt",
  "gateway/syntheticmod/src/main/kotlin/splice/synthetic/B.kt",
];
doc.total += 1;
fs.writeFileSync(baseline, JSON.stringify(doc, null, 2));
// The scar goes into the HARNESS copy of the checker, which build_harness cp-s (a real file, not
// a symlink) — so this never touches the shipped NAMED_SCARS list. The anchor is the ported
// declaration; if it ever stops matching, throw rather than silently skip the mutation.
const text = fs.readFileSync(checker, "utf8");
const old = "let NAMED_SCARS: Record<string, string> = {};";
if (text.split(old).length - 1 !== 1) throw new Error("the harness copy does not carry the empty shipped list");
// The replacement KEEPS the `let`: the anchor is the whole declaration, so assigning without it
// would leave a bare assignment to an undeclared name and the harness copy would not even load.
fs.writeFileSync(checker, text.replace(old, "let NAMED_SCARS: Record<string, string> = { SCAR_DUP: \"fixture scar: one value, two files\" };"));
' "$BASELINE" "$CHECKER"
expect_delta "5. a NAMED_SCARS name recorded in the baseline" 1 1 \
  "STALE" "SCAR_DUP" "cannot be both baselined and strict"
# ...and the strict finding for it is STILL there, unlaundered.
out="$(run_gate)"
case "$out" in
  *"NAMED-SCAR (COPY): SCAR_DUP"*) echo "  ✓ 5b. the NAMED-SCAR finding survives being baselined" ;;
  *) err "5b. baselining SCAR_DUP suppressed its NAMED-SCAR finding" ;;
esac

# ── 6. a synthetic must-stay-equal comment ────────────────────────────────────────────────────
# Names MAX_RATE_LIMIT_COOLDOWN_MS, which really is declared in :upstream — so this fixture
# also proves the detector resolves its counterpart against the REAL census, not a fixture list.
build_harness
mkdir -p "$SYNTH"
cat > "$SYNTH/A.kt" <<'KT'
package splice.selftest

// Mirrors :upstream's MAX_RATE_LIMIT_COOLDOWN_MS. The two must stay equal — a client told to
// come back before the cooldown ends is told a time that is not true.
internal const val SELFTEST_CLIENT_HOLD_MS = 120_000L
KT
expect_delta "6. a comment asserting an equality with a REAL const" 1 0 \
  "EQUAL-BY-COMMENT" "SELFTEST_CLIENT_HOLD_MS" "MAX_RATE_LIMIT_COOLDOWN_MS"

# ── 7. a synthetic Knob-default shadow ────────────────────────────────────────────────────────
# Knob.MAX_INFLIGHT's default is 12L in the real Knob.kt; a local MAX_INFLIGHT forks it.
build_harness
mkdir -p "$SYNTH"
printf 'package splice.selftest\n\ninternal const val DEFAULT_MAX_INFLIGHT = 12\n' > "$SYNTH/A.kt"
expect_delta "7. a local default shadowing a REAL Knob default" 1 0 "KNOB-SHADOW" "Knob.MAX_INFLIGHT"

# ── 8. no sources: untrustworthy, never green ─────────────────────────────────────────────────
# EVERY module home is emptied — the ones settings.gradle.kts declares, wherever they live: with one
# module still linked this arm would find its main sources and grade a real (if partial) tree
# instead of proving the no-denominator refusal.
build_harness
rm -rf "$tmp/gateway" "$tmp/client"
for dir in $module_dirs; do rm -rf "$tmp/$dir"; done
mkdir -p "$tmp/gateway"
out="$(run_gate)"; code=$?
if [ "$code" -ne 2 ]; then
  err "8. a tree with no main sources must exit 2 (untrustworthy), got $code"
else
  case "$out" in
    *"denominator is absent"*) echo "  ✓ 8. a tree with no main sources is untrustworthy, not green (exit 2)" ;;
    *) err "8. red for the wrong reason — no 'denominator is absent' in output" ;;
  esac
fi

# ── 9. the documented gate line is the line that gates ────────────────────────────────────────
# The ledger note tells the orchestrator to wire exactly:
#   run "const single source"  bun checks/const-single-source.ts --ratchet
# Proven verbatim here (with --root, the harness's only difference from the repo root).
#
# V4-122 CHANGED WHAT THIS ARM PINS. It used to run the documented line against a harness copy of
# the shipped tree and expect non-zero, which only worked while the tree CARRIED strict findings —
# so the row fixing them would have broken the arm that proves the gate gates. That is a control
# pinned to a red state, and it stops being a control the moment the red is fixed. The gate now gets
# a tree that is GREEN to begin with and then a VIOLATION planted in it, so the arm proves the thing
# it names: the documented line fails on a tree that offends, and passes on one that does not.
build_harness
if ! bun "$CHECKER" --ratchet --root "$tmp" >/dev/null 2>&1; then
  err "9. the documented gate line is non-zero on a CLEAN harness tree — the arm below cannot distinguish a violation from a broken gate"
else
  # PLANT INTO A REAL MODULE DIR, NEVER THROUGH build_harness's SYMLINK. That function links
  # $tmp/gateway/<mod> at the REAL module, so writing "$tmp/core/src/..." writes into the
  # repository — this arm did exactly that on its first run and left two files in gateway/core plus
  # their compiled classes, which the next run's CONTROL then reported as real growth. A synthetic
  # module is what the checker's own glob (gateway/*/src/main/**) is happy to scan and what nothing
  # else in this harness aliases.
  mkdir -p "$tmp/gateway/syntheticmod/src/main/kotlin/splice/synthetic"
  printf 'package splice.synthetic\n\nprivate const val SYNTHETIC_DUP = 7\n' \
    >"$tmp/gateway/syntheticmod/src/main/kotlin/splice/synthetic/A.kt"
  printf 'package splice.synthetic\n\nprivate const val SYNTHETIC_DUP = 7\n' \
    >"$tmp/gateway/syntheticmod/src/main/kotlin/splice/synthetic/B.kt"
  if bun "$CHECKER" --ratchet --root "$tmp" >/dev/null 2>&1; then
    err "9. the documented gate line exited 0 on a tree carrying a PLANTED duplication"
  else
    echo "  ✓ 9. the documented gate line passes clean and fails on a planted violation"
  fi
fi
build_harness

# ── 10. `--inventory` prints the held plane and ignores the baseline ──────────────────────────
build_harness
inventory="$(bun "$CHECKER" --inventory --root "$tmp" 2>&1)"
held="$(printf '%s\n' "$inventory" | sed -n 's/^RATCHETED — \([0-9]*\) duplicated name(s).*/\1/p')"
if [ -z "$held" ] || [ "$held" -lt 100 ]; then
  err "10. \`--inventory\` did not list the held plane (got '${held:-nothing}')"
else
  echo "  ✓ 10. \`--inventory\` lists all $held held duplicate(s) without gating them"
fi

# ── 11. a BARE run is the GATE, not the inventory — the defect this port fixed ────────────────
# The .py's bare form ran the inventory, which exits 0 on a tree whose ratchet plane has grown, so
# a mis-invocation read as a pass. The port makes bare fall through to --ratchet. Proven by
# PREDICTED DIVERGENCE: on one tree carrying a planted, unbaselined duplication, bare must be
# NON-ZERO while --inventory on the SAME tree is ZERO. Asserting only "bare is non-zero" would pass
# on a checker that had simply been broken; asserting only "inventory is zero" would pass on the
# old behaviour. The pair is the proof.
build_harness
mkdir -p "$tmp/gateway/syntheticmod/src/main/kotlin/splice/synthetic"
printf 'package splice.synthetic\n\nprivate const val BARE_GATE_DUP = 7\n' \
  >"$tmp/gateway/syntheticmod/src/main/kotlin/splice/synthetic/A.kt"
printf 'package splice.synthetic\n\nprivate const val BARE_GATE_DUP = 7\n' \
  >"$tmp/gateway/syntheticmod/src/main/kotlin/splice/synthetic/B.kt"
bare_code=0; bun "$CHECKER" --root "$tmp" >/dev/null 2>&1 || bare_code=$?
inv_code=0; bun "$CHECKER" --inventory --root "$tmp" >/dev/null 2>&1 || inv_code=$?
if [ "$bare_code" -eq 0 ]; then
  err "11. a BARE run exited 0 on a tree with a planted unbaselined duplication — bare is not gating"
elif [ "$inv_code" -ne 0 ]; then
  err "11. --inventory exited $inv_code on the same tree — the inventory must not gate"
else
  echo "  ✓ 11. a BARE run gates (exit $bare_code) where --inventory does not (exit 0)"
fi
build_harness

# ── the checker's own fixture selftest, run from here so one command proves both planes ───────
if ! bun "$CHECKER" --selftest >/dev/null 2>&1; then
  err "the checker's own --selftest failed"
else
  echo "  ✓ the checker's --selftest (fixture plane) passes"
fi

if [ "$fail" -ne 0 ]; then
  echo "const-single-source-selftest FAILED"
  exit 1
fi
echo "const-single-source-selftest OK — control: $STRICT_BASE strict findings and zero growth/stale,"
echo "so the shipped NAMED_SCARS list and the baseline both still match the source. An unbaselined"
echo "COPY, a baselined group that spread, a baseline entry for a vanished group and a strict name"
echo "recorded in the baseline each add exactly one ratchet problem; a must-stay-equal comment and a"
echo "Knob shadow each add exactly one strict finding; the compliant twin adds neither; baselining a"
echo "NAMED-SCAR cannot suppress it; an absent denominator is untrustworthy rather than passing."
