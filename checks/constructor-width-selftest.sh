#!/usr/bin/env bash
# checks/constructor-width-selftest.sh — red-green proof for the V4-93 constructor-width ratchet,
# taken against the REAL tree rather than against fixtures alone.
#
# WHY BOTH HALVES EXIST. `constructor-width.ts --selftest` proves the LOGIC on temp fixtures (the
# limits themselves, a 13-parameter defaulted data class, a widened entry, a padded entry) and it
# grades its own class census against ast-grep's `primary_constructor` nodes. What it cannot prove
# is that the checker is still pointed at THIS tree: a checker whose glob stops matching measures
# zero constructors, finds zero offenders and exits 0. That is the failure
# checks/concentration-selftest.sh exists for — a gate leg that spent a month executing `true`.
#
# THE OTHER HALF IS THE PREMISE. This wall exists because quality/detekt/detekt.yml:38-43 turns
# LongParameterList off for data classes and defaulted parameters. If someone deletes those two
# ignores, detekt starts billing the width and this wall is redundant; if someone deletes the RULE,
# this wall is the only thing left. Either way the premise must not change silently, so it is
# asserted here — the one place that re-reads it on every gate run.
#
# EVERYTHING RUNS OUT OF TREE. mktemp -d holding a COPY of the checker and of the baseline, plus one
# SYMLINK per gateway module — the checker measures the real source (its ROOT comes from its own
# module URL, so a copy under $tmp/checks measures $tmp) while every mutation lands on a throwaway.
#
# THE CONTROL COMES FIRST: each arm claims "this mutation turns green into red", which is worth
# nothing unless the unmutated harness is green.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  x constructor-width-selftest: $1"; fail=1; }
note() { printf '  %s\n' "$1"; }

CHECK="$tmp/checks/constructor-width.ts"
BASELINE="$tmp/checks/config/constructor-width-baseline.json"
SYNTH="$tmp/gateway/zz-selftest-width/src/main/kotlin/splice/selftest"

mkdir -p "$tmp/checks/config" "$tmp/gateway"
for main in "$ROOT"/gateway/*/src/main; do
  [ -d "$main" ] || continue
  mod="${main#"$ROOT"/gateway/}"
  mod="${mod%%/*}"
  ln -s "$ROOT/gateway/$mod" "$tmp/gateway/$mod"
done
# restructure PR 3: :client is the first module to live outside gateway/, so the loop above
# cannot reach it. A harness that measures a tree with one module missing hands its control a
# red that reads exactly like a real regression (or, worse, a green over a smaller tree).
[ -e "$tmp/client" ] || ln -s "$ROOT/client" "$tmp/client"
[ -e "$tmp/gateway/core" ] || { echo "  x constructor-width-selftest: no gateway modules found under $ROOT"; exit 1; }
[ -e "$tmp/client/src/main" ] || { echo "  x constructor-width-selftest: :client is not linked — the harness lost a module home"; exit 1; }

reset_all() {
  cp "$ROOT/checks/constructor-width.ts" "$CHECK"
  cp "$ROOT/checks/config/constructor-width-baseline.json" "$BASELINE"
  rm -rf "$tmp/gateway/zz-selftest-width"
}
reset_all
# NOTHING MAY LAND IN THE TREE (the 2026-09-17 scar: an earlier public-surface harness
# `ln -s`-ed THROUGH an existing symlink and created gateway/build-logic/build-logic in the
# working tree). Recorded before the arms run, re-checked at exit.
tree_state="$(cd "$ROOT/gateway" && ls -1A)"

rc=0
check() { bun "$CHECK" "$@" >"$tmp/out" 2>&1; rc=$?; }

must_fail() { # must_fail <label> <substring the failure must name>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The arm it is supposed to prove is not enforcing."
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -4 "$tmp/out" | tr '\n' ' ')"
  else
    note "ok $1 (exit $rc)"
  fi
}

# -- the premise: detekt still cannot see this ------------------------------------------------
DETEKT="$ROOT/quality/detekt/detekt.yml"
if ! grep -q "LongParameterList:" "$DETEKT"; then
  err "PREMISE: quality/detekt/detekt.yml no longer configures LongParameterList — re-read this wall's header before trusting either instrument"
elif ! grep -q "ignoreDataClasses: true" "$DETEKT" || ! grep -q "ignoreDefaultParameters: true" "$DETEKT"; then
  note "PREMISE CHANGED: detekt's ignoreDataClasses/ignoreDefaultParameters are no longer both true — detekt may now bill some of these widths itself; re-read checks/constructor-width.ts's header"
else
  note "ok PREMISE: detekt ignores data classes AND defaulted parameters, so nothing but this wall bills the width"
fi

# -- control ---------------------------------------------------------------------------------
bun "$ROOT/checks/constructor-width.ts" --selftest >"$tmp/out" 2>&1 || {
  err "CONTROL: the fixture selftest (incl. the ast-grep denominator) must be green: $(tail -6 "$tmp/out" | tr '\n' ' ')"
}
check --ratchet
if [ "$rc" -ne 0 ]; then
  err "CONTROL: the unmutated tree must hold its baseline (exit $rc): $(tail -6 "$tmp/out" | tr '\n' ' ')"
fi
live="$(grep -oE 'primary constructors[[:space:]]+measured[[:space:]]+[0-9]+' "$tmp/out" | grep -oE '[0-9]+$')"
if [ -z "${live:-}" ] || [ "$live" -lt 200 ]; then
  err "CONTROL: the harness measured ${live:-no} primary constructor(s) — the source glob is not pointed at the real tree, so every arm below is unproven"
else
  note "ok CONTROL: baseline holds over $live real primary constructors"
fi
if [ "$fail" -ne 0 ]; then
  echo "  x constructor-width-selftest: control failed — the arms below are UNPROVEN, not passing"
  exit 1
fi

# -- 1. GROWTH in the exact shape detekt ignores ---------------------------------------------
# A data class whose every parameter is defaulted: ignoreDataClasses AND ignoreDefaultParameters
# both apply, so detekt reports nothing and this is the only instrument that can.
mkdir -p "$SYNTH"
# Every fixture mutation below is bun rather than an inline heredoc for the retired interpreter,
# so this file stops being an invoker at all. `bun -e '<script>' ARG` puts ARG at process.argv[1]
# (argv[0] is bun); the mutation logic is otherwise unchanged.
bun -e '
const fs = require("fs");
const params = Array.from({ length: 13 }, (_, i) => `    val p${i}: Int = 0`).join(",\n");
fs.writeFileSync(process.argv[1], "package splice.selftest\n\npublic data class SelftestWideCtor(\n" + params + ",\n)\n");
' "$SYNTH/SelftestWide.kt"
check --ratchet
must_fail "1. GROWTH — a 13-parameter defaulted data class nothing records" "GROWTH"
grep -q "SelftestWideCtor" "$tmp/out" ||
  err "1. GROWTH — the failure does not NAME the planted class, so the arm went red for something else"
reset_all

# -- 2. GROWTH by SUBSYSTEM, with the parameter count well inside ----------------------------
mkdir -p "$SYNTH"
bun -e '
const fs = require("fs");
const imports = Array.from({ length: 7 }, (_, i) => `import splice.selftestsub${i}.Type${i}`).join("\n");
const params = Array.from({ length: 7 }, (_, i) => `    val p${i}: Type${i}`).join(",\n");
fs.writeFileSync(process.argv[1], "package splice.selftest\n\n" + imports + "\n\npublic class SelftestSubsCtor(\n" + params + ",\n)\n");
' "$SYNTH/SelftestSubs.kt"
check --ratchet
must_fail "2. GROWTH — 7 splice subsystems in a 7-parameter constructor" "subsystems (max 6)"
grep -q "SelftestSubsCtor" "$tmp/out" || err "2. the subsystem arm does not NAME the planted class"
reset_all

# -- 3. WIDENED: a recorded offender gains parameters ----------------------------------------
# The arm without which a baseline entry is a licence: HeadDeps could go 25 -> 40 under a green
# gate. The mutation is applied to the CHECKER's view by shrinking the recorded number, which is
# the same arithmetic as the class gaining parameters and does not touch the working tree.
bun -e '
const fs = require("fs");
const path = process.argv[1];
const data = JSON.parse(fs.readFileSync(path, "utf8"));
const key = Object.keys(data.offenders).find((k) => data.offenders[k].params > 13);
data.offenders[key].params -= 1;
fs.writeFileSync(path, JSON.stringify(data, null, 2) + "\n");
' "$BASELINE"
check --ratchet
must_fail "3. WIDENED — a recorded offender measuring wider than its entry" "WIDENED"
reset_all

# -- 4. PADDED: an entry recorded above the measurement --------------------------------------
bun -e '
const fs = require("fs");
const path = process.argv[1];
const data = JSON.parse(fs.readFileSync(path, "utf8"));
const key = Object.keys(data.offenders)[0];
data.offenders[key].params += 7;
fs.writeFileSync(path, JSON.stringify(data, null, 2) + "\n");
' "$BASELINE"
check --ratchet
must_fail "4. PADDED — an entry recorded above the measured width" "PADDED"
reset_all

# -- 5. STALE: an entry naming a constructor that is not wide --------------------------------
bun -e '
const fs = require("fs");
const path = process.argv[1];
const data = JSON.parse(fs.readFileSync(path, "utf8"));
data.offenders["gateway/core/src/main/kotlin/splice/core/Nope.kt WasWideOnce"] = { params: 30, subsystems: 0 };
fs.writeFileSync(path, JSON.stringify(data, null, 2) + "\n");
' "$BASELINE"
check --ratchet
must_fail "5. STALE — an entry naming a constructor the tree does not have" "STALE"
reset_all

# -- 6. an undated baseline, and a missing one, are hard errors ------------------------------
bun -e '
const fs = require("fs");
const path = process.argv[1];
const data = JSON.parse(fs.readFileSync(path, "utf8"));
data.recorded = "";
fs.writeFileSync(path, JSON.stringify(data, null, 2) + "\n");
' "$BASELINE"
check --ratchet
must_fail "6. an undated baseline is a hard error" "recorded"
reset_all

rm -f "$BASELINE"
check --ratchet
must_fail "6b. a missing baseline refuses rather than passing" "missing"
reset_all

# -- 7. the BORING case: a lost denominator must refuse, not report a clean tree -------------
bun -e '
const fs = require("fs");
const path = process.argv[1];
const text = fs.readFileSync(path, "utf8");
const patched = text.replace(/^const SRC_GLOBS = .*$/m, "const SRC_GLOBS = [\"gateway/*/src/nowhere\"];");
if (patched === text) throw new Error("SRC_GLOBS assignment not found — the lost-denominator fixture cannot be built");
fs.writeFileSync(path, patched);
' "$CHECK"
check --ratchet
must_fail "7. a source glob that matches nothing must REFUSE, not pass vacuously" "vacuously"
reset_all

# -- 8A. the CONFIG-RECORD budget: a config record one key over IS red ------------------------
# V4-122 item 8' added the shape, so the shape needs a red-green proof of its own. 33 is one past
# MAX_CONFIG_KEYS; the class satisfies all three clauses (@Serializable, every parameter a
# defaulted val, no subsystem), so it is graded against the CONFIG budget and must still fail it.
mkdir -p "$SYNTH"
bun -e '
const fs = require("fs");
const params = Array.from({ length: 33 }, (_, i) => `    val k${i}: Int = 0`).join(",\n");
fs.writeFileSync(process.argv[1], "package splice.selftest\n\nimport kotlinx.serialization.Serializable\n\n@Serializable\npublic data class SelftestConfigOver(\n" + params + ",\n)\n");
' "$SYNTH/SelftestConfigOver.kt"
check --ratchet
must_fail "8A. a config record one key past MAX_CONFIG_KEYS is red" "config keys"
grep -q "SelftestConfigOver" "$tmp/out" ||
  err "8A. the failure does not NAME the planted class — the arm went red for something else"
reset_all

# -- 8B. ...and the exemption cannot be reached by DELETING AN ANNOTATION ---------------------
# The same 22 defaulted vals WITHOUT @Serializable: still graded at the ordinary MAX_PARAMS, so it
# is red at 22. This is the arm that makes 8A an exemption rather than a hole — without it, a class
# could escape the ordinary budget by satisfying a shape whose parts are all optional in practice.
# 22 is chosen so the two budgets cannot be confused: it is over MAX_PARAMS (12) and well under
# MAX_CONFIG_KEYS (32), so a green here would mean the un-annotated class had been let through.
mkdir -p "$SYNTH"
bun -e '
const fs = require("fs");
const params = Array.from({ length: 22 }, (_, i) => `    val k${i}: Int = 0`).join(",\n");
fs.writeFileSync(process.argv[1], "package splice.selftest\n\npublic data class SelftestNoAnnotation(\n" + params + ",\n)\n");
' "$SYNTH/SelftestNoAnnotation.kt"
check --ratchet
must_fail "8B. the same defaulted vals WITHOUT @Serializable stay red at the ordinary width" "parameters (max 12)"
grep -q "SelftestNoAnnotation" "$tmp/out" ||
  err "8B. the failure does not NAME the planted class — the arm went red for something else"
reset_all

# -- 9. a BARE run is the GATE, not the report — the defect this port fixed --------------------
# The .py's bare form ran the REPORT, which exits 0 on a tree whose ratchet plane has grown, so a
# mis-invocation read as a pass. The port makes bare fall through to --ratchet. Proven by PREDICTED
# DIVERGENCE: on one tree carrying a planted, unrecorded wide constructor, bare must be NON-ZERO
# while --report on the SAME tree is ZERO. Asserting only "bare is non-zero" would pass on a
# checker that had simply been broken; asserting only "report is zero" would pass on the old
# behaviour. The pair is the proof.
mkdir -p "$SYNTH"
bun -e '
const fs = require("fs");
const params = Array.from({ length: 13 }, (_, i) => `    val q${i}: Int = 0`).join(",\n");
fs.writeFileSync(process.argv[1], "package splice.selftest\n\npublic data class SelftestBareGate(\n" + params + ",\n)\n");
' "$SYNTH/SelftestBareGate.kt"
bare_rc=0; bun "$CHECK" >"$tmp/out" 2>&1 || bare_rc=$?
report_rc=0; bun "$CHECK" --report >"$tmp/out2" 2>&1 || report_rc=$?
if [ "$bare_rc" -eq 0 ]; then
  err "9. a BARE run exited 0 on a tree with a planted unrecorded wide constructor — bare is not gating"
elif [ "$report_rc" -ne 0 ]; then
  err "9. --report exited $report_rc on the same tree — the report must not gate"
else
  note "ok 9. a BARE run gates (exit $bare_rc) where --report does not (exit 0)"
fi
reset_all

if [ "$tree_state" != "$(cd "$ROOT/gateway" && ls -1A)" ]; then
  err "the harness changed gateway/ — everything here must land in mktemp"
fi

if [ "$fail" -eq 0 ]; then
  note "constructor-width selftest: premise asserted, control green over the real tree, 11 mutation arms red for their stated reasons, gateway/ untouched"
fi
exit "$fail"
