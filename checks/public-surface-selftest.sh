#!/usr/bin/env bash
# checks/public-surface-selftest.sh — red-green proof for the V4-92 public-surface ratchet,
# taken against the REAL tree rather than against fixtures alone.
#
# WHY BOTH HALVES EXIST. `public-surface.py --selftest` proves the LOGIC on temp fixtures: a
# consumed declaration, a star import, a sibling test-only caller, a stale baseline entry. What it
# cannot prove is that the checker still finds this tree — that its module parse, its
# nonLibrary derivation and its consumer walk are pointed at the real source sets. A checker whose
# denominator quietly goes to zero prints a clean surface and exits 0, which is the failure
# checks/concentration-selftest.sh was written for after a gate leg spent a month executing `true`.
# So the control here is the real repo, and every mutation below lands on a throwaway copy.
#
# EVERYTHING RUNS OUT OF TREE. The harness is a mktemp -d holding a COPY of the checker, a COPY of
# the baseline, a COPY of settings.gradle.kts (mutable: one arm appends a synthetic module) and one
# SYMLINK per gateway module — so the checker measures the real source (ROOT is derived from its own
# __file__, so a copy under $tmp/checks measures $tmp) while nothing is ever written into gateway/.
#
# THE CONTROL COMES FIRST and is not decoration: each arm claims "this mutation turns green into
# red", which is worth nothing unless the unmutated harness is green.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  x public-surface-selftest: $1"; fail=1; }
note() { printf '  %s\n' "$1"; }

CHECK="$tmp/checks/public-surface.py"
BASELINE="$tmp/checks/config/public-surface-baseline.json"
SETTINGS="$tmp/gateway/settings.gradle.kts"
SYNTH_MODULE="zz-selftest-surface"

mkdir -p "$tmp/checks/config" "$tmp/gateway"
for main in "$ROOT"/gateway/*/src/main; do
  [ -d "$main" ] || continue
  mod="${main#"$ROOT"/gateway/}"
  mod="${mod%%/*}"
  ln -s "$ROOT/gateway/$mod" "$tmp/gateway/$mod"
done
# build-logic holds the nonLibrary set and ALSO has a src/main, so the loop above already linked it.
# `ln -s` into an existing symlink-to-a-directory writes THROUGH it — that is how an earlier
# revision of this harness created gateway/build-logic/build-logic in the working tree, the exact
# mktemp-hygiene failure CLAUDE.md s19 records. Guarded, and the guard is the point.
[ -e "$tmp/gateway/build-logic" ] || ln -s "$ROOT/gateway/build-logic" "$tmp/gateway/build-logic"
[ -e "$tmp/gateway/core" ] || { echo "  x public-surface-selftest: no gateway modules found under $ROOT"; exit 1; }
[ -e "$tmp/gateway/build-logic/src/main/kotlin" ] || { echo "  x public-surface-selftest: the module law is unreachable from the harness"; exit 1; }
# NOTHING MAY LAND IN THE TREE. Recorded before the arms run and re-checked at exit: a harness that
# writes into the tree it measures has stopped being a harness.
tree_state="$(cd "$ROOT/gateway" && ls -1A)"

reset_all() {
  cp "$ROOT/checks/public-surface.py" "$CHECK"
  cp "$ROOT/checks/config/public-surface-baseline.json" "$BASELINE"
  cp "$ROOT/gateway/settings.gradle.kts" "$SETTINGS"
  rm -rf "$tmp/gateway/$SYNTH_MODULE"
}
reset_all

rc=0
check() { python3 "$CHECK" "$@" >"$tmp/out" 2>&1; rc=$?; }

must_fail() { # must_fail <label> <substring the failure must name>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The arm it is supposed to prove is not enforcing."
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -4 "$tmp/out" | tr '\n' ' ')"
  else
    note "ok $1 (exit $rc)"
  fi
}

# -- control ---------------------------------------------------------------------------------
python3 "$ROOT/checks/public-surface.py" --selftest >"$tmp/out" 2>&1 || {
  err "CONTROL: the fixture selftest must be green: $(tail -6 "$tmp/out" | tr '\n' ' ')"
}
check --ratchet
if [ "$rc" -ne 0 ]; then
  err "CONTROL: the unmutated tree must hold its baseline (exit $rc): $(tail -6 "$tmp/out" | tr '\n' ' ')"
fi
# The denominator must be REAL, not an empty walk that passes. Any number here is checked against
# the live tree rather than a constant, so the guard cannot rot as the tree grows.
live="$(grep -oE 'declarations[[:space:]]+measured[[:space:]]+[0-9]+' "$tmp/out" | grep -oE '[0-9]+$')"
if [ -z "${live:-}" ] || [ "$live" -lt 100 ]; then
  err "CONTROL: the harness measured ${live:-no} public declaration(s) — the module walk is not pointed at the real source sets, so every arm below is unproven"
else
  note "ok CONTROL: baseline holds over $live real public top-level declarations"
fi
if [ "$fail" -ne 0 ]; then
  echo "  x public-surface-selftest: control failed — the arms below are UNPROVEN, not passing"
  exit 1
fi

# -- 1. GROWTH: a synthetic unjustified public type in a real-looking module -----------------
# It gets its OWN module rather than being planted in an existing one, because every existing
# module is a symlink into the working tree and a selftest that writes into the tree it measures
# is not a selftest. The module is appended to the COPIED settings.gradle.kts, which is exactly
# how a real new module enters the denominator.
mkdir -p "$tmp/gateway/$SYNTH_MODULE/src/main/kotlin/splice/selftest"
cat > "$tmp/gateway/$SYNTH_MODULE/src/main/kotlin/splice/selftest/SelftestLeak.kt" <<'KT'
package splice.selftest
public class SelftestLeakedType(val v: String)
KT
python3 - "$SETTINGS" "$SYNTH_MODULE" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text()
needle = '    ":fir-checks",\n'
assert needle in text, "settings.gradle.kts no longer includes :fir-checks — the injection point moved"
path.write_text(text.replace(needle, needle + f'    ":{sys.argv[2]}",\n', 1))
PY
check --ratchet
must_fail "1. GROWTH — an unjustified public type no baseline entry records" "GROWTH"
grep -q "SelftestLeakedType" "$tmp/out" ||
  err "1. GROWTH — the failure does not NAME the planted type, so the arm went red for something else"
reset_all

# -- 2. GROWTH: a baseline entry deleted while its offender stands ----------------------------
python3 - "$BASELINE" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
assert data["offenders"], "the baseline is empty — this arm has nothing to delete"
data["offenders"].pop(0)
path.write_text(json.dumps(data, indent=2) + "\n")
PY
check --ratchet
must_fail "2. GROWTH — an offender whose baseline line was deleted" "GROWTH"
reset_all

# -- 3. STALE: a baseline entry naming a declaration that does not exist ----------------------
python3 - "$BASELINE" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["offenders"].append(":core splice.core.selftest.NeverExisted")
path.write_text(json.dumps(data, indent=2) + "\n")
PY
check --ratchet
must_fail "3. STALE — a baseline entry naming a declaration the tree does not have" "STALE"
grep -q "NeverExisted" "$tmp/out" || err "3. STALE — the failure does not NAME the stale entry"
reset_all

# -- 4. an undated baseline is a hard error, not a pass ---------------------------------------
python3 - "$BASELINE" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["recorded"] = ""
path.write_text(json.dumps(data, indent=2) + "\n")
PY
check --ratchet
must_fail "4. an undated baseline is a hard error" "recorded"
reset_all

# -- 5. a missing baseline cannot read as green -----------------------------------------------
rm -f "$BASELINE"
check --ratchet
must_fail "5. a missing baseline refuses rather than passing" "missing"
reset_all

# -- 6. a lost denominator refuses, it does not report a clean surface ------------------------
# The boring case, which is the one that gets waved through (CLAUDE.md §24): if the module law
# stops parsing, every module reads as a consumer and NOTHING is graded. That must be red.
python3 - "$tmp/gateway/settings.gradle.kts" <<'PY'
import pathlib, sys
pathlib.Path(sys.argv[1]).write_text('rootProject.name = "splice-gateway"\ninclude(\n    ":app",\n)\n')
PY
check --ratchet
must_fail "6. a settings file whose every module is nonLibrary must REFUSE" "vacuously"
reset_all

if [ "$tree_state" != "$(cd "$ROOT/gateway" && ls -1A)" ]; then
  err "the harness changed gateway/ — everything here must land in mktemp; diff: $(diff <(printf '%s\n' "$tree_state") <(cd "$ROOT/gateway" && ls -1A) | tr '\n' ' ')"
fi

if [ "$fail" -eq 0 ]; then
  note "public-surface selftest: control green over the real tree, 6 mutation arms red for their stated reasons, gateway/ untouched"
fi
exit "$fail"
