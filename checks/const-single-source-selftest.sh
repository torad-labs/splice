#!/usr/bin/env bash
# checks/const-single-source-selftest.sh — red-green proof for the constant single-source wall,
# AGAINST THE REAL TREE, by PREDICTED DELTA on each plane separately.
#
# WHY THIS EXISTS on top of `const-single-source.py --selftest`. The python selftest proves the
# detector logic against hand-written fixtures, and it deliberately NEUTRALISES the shipped
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
#   9  the documented gate line verbatim           -> non-zero on the tree it is wired against
#  10  bare `check` lists the held plane           -> the 136 groups printed, baseline ignored
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

CHECKER="$tmp/checks/const-single-source.py"
BASELINE="$tmp/checks/config/const-single-source-baseline.json"
SYNTH="$tmp/gateway/zz-selftest/src/main/kotlin/splice/selftest"

build_harness() {
  rm -rf "$tmp/gateway" "$tmp/checks"
  mkdir -p "$tmp/checks/config" "$tmp/gateway"
  cp "$ROOT/checks/const-single-source.py" "$CHECKER"
  cp "$ROOT/checks/config/const-single-source-baseline.json" "$BASELINE"
  for main in "$ROOT"/gateway/*/src/main; do
    [ -d "$main" ] || continue
    mod="${main#"$ROOT"/gateway/}"
    mod="${mod%%/*}"
    ln -s "$ROOT/gateway/$mod" "$tmp/gateway/$mod"
  done
  [ -e "$tmp/gateway/core" ] || { echo "  ✗ const-single-source-selftest: no gateway modules under $ROOT"; exit 1; }
}

run_gate() { python3 "$CHECKER" --ratchet --root "$tmp" 2>&1; }
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
if [ -z "$STRICT_BASE" ] || [ "$control_code" -ne 1 ]; then
  echo "  ✗ const-single-source-selftest: CONTROL did not produce a strict count (exit $control_code);"
  echo "    every fixture below is UNPROVEN."
  printf '%s\n' "$control_out" | tail -4 | sed 's/^/      /'
  exit 1
fi
if [ "$MOVED_BASE" -ne 0 ]; then
  echo "  ✗ const-single-source-selftest: CONTROL has $MOVED_BASE GROWTH/STALE line(s) — the"
  echo "    baseline does not match the tree, so every ratchet fixture below is UNPROVEN."
  echo "    Re-record with 'python3 checks/const-single-source.py --record >"
  echo "    checks/config/const-single-source-baseline.json'."
  printf '%s\n' "$control_out" | grep -E '^  ✗ (GROWTH|STALE)' | sed 's/^/      /'
  exit 1
fi
echo "  ✓ control: strict $STRICT_BASE (unbaselined by design), growth/stale 0 (the baseline matches the tree),"
echo "            and the shipped NAMED_SCARS list still names real duplicates"
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
python3 - "$BASELINE" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1]))
doc["groups"]["COPY ZZ_SELFTEST_VANISHED"] = [
    "gateway/core/src/main/kotlin/splice/core/Gone.kt",
    "gateway/app/src/main/kotlin/splice/app/AlsoGone.kt",
]
doc["total"] += 1
json.dump(doc, open(sys.argv[1], "w"), indent=2)
PY
expect_delta "4. a baseline entry for a group the tree no longer has" 0 1 \
  "STALE" "ZZ_SELFTEST_VANISHED" "no longer declares it in 2+ files"

# ── 5. a NAMED_SCARS name recorded in the baseline ────────────────────────────────────────────
# The two planes must not be able to launder each other: recording a strict name in the ratchet
# baseline is itself an error, and the strict finding still fires.
build_harness
python3 - "$BASELINE" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1]))
doc["groups"]["COPY TAG_CHARS"] = [
    "gateway/gateway/src/main/kotlin/splice/gateway/head/LocalResponses.kt",
    "gateway/gateway/src/main/kotlin/splice/gateway/head/TurnPreparation.kt",
]
doc["total"] += 1
json.dump(doc, open(sys.argv[1], "w"), indent=2)
PY
expect_delta "5. a NAMED_SCARS name recorded in the baseline" 0 1 \
  "STALE" "TAG_CHARS" "cannot be both baselined and strict"
# ...and the strict finding for it is STILL there, unlaundered.
out="$(run_gate)"
case "$out" in
  *"NAMED-SCAR (COPY): TAG_CHARS"*) echo "  ✓ 5b. the NAMED-SCAR finding survives being baselined" ;;
  *) err "5b. baselining TAG_CHARS suppressed its NAMED-SCAR finding" ;;
esac

# ── 6. a synthetic must-stay-equal comment ────────────────────────────────────────────────────
# Names MAX_RATE_LIMIT_COOLDOWN_MS, which really is declared in provider-spi — so this fixture
# also proves the detector resolves its counterpart against the REAL census, not a fixture list.
build_harness
mkdir -p "$SYNTH"
cat > "$SYNTH/A.kt" <<'KT'
package splice.selftest

// Mirrors :provider-spi's MAX_RATE_LIMIT_COOLDOWN_MS. The two must stay equal — a client told to
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
build_harness
rm -rf "$tmp/gateway"
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
#   run "const single source"  python3 checks/const-single-source.py --ratchet
# Proven verbatim here (with --root, the harness's only difference from the repo root).
build_harness
if python3 "$CHECKER" --ratchet --root "$tmp" >/dev/null 2>&1; then
  err "9. the documented gate line exited 0 on a tree carrying $STRICT_BASE strict findings"
else
  echo "  ✓ 9. the documented gate line fails on the tree it is wired against"
fi

# ── 10. bare `check` is the inventory: it prints the held plane and ignores the baseline ───────
build_harness
inventory="$(python3 "$CHECKER" --root "$tmp" 2>&1)"
held="$(printf '%s\n' "$inventory" | sed -n 's/^RATCHETED — \([0-9]*\) duplicated name(s).*/\1/p')"
if [ -z "$held" ] || [ "$held" -lt 100 ]; then
  err "10. bare \`check\` did not list the held plane (got '${held:-nothing}')"
else
  echo "  ✓ 10. bare \`check\` lists all $held held duplicate(s) without gating them"
fi

# ── the python fixture selftest, run from here so one command proves both planes ──────────────
if ! python3 "$CHECKER" --selftest >/dev/null 2>&1; then
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
