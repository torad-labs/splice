#!/usr/bin/env bash
# checks/public-surface-selftest.sh — red-green proof for the V4-92 public-surface ratchet,
# taken against the REAL tree rather than against fixtures alone.
#
# WHY BOTH HALVES EXIST. `public-surface.ts --selftest` proves the LOGIC on temp fixtures: a
# consumed declaration, a star import, a sibling test-only caller, a stale baseline entry. What it
# cannot prove is that the checker still finds this tree — that its module parse, its
# nonLibrary derivation and its consumer walk are pointed at the real source sets. A checker whose
# denominator quietly goes to zero prints a clean surface and exits 0, which is the failure
# checks/concentration-selftest.sh was written for after a gate leg spent a month executing `true`.
# So the control here is the real repo, and every mutation below lands on a throwaway copy.
#
# EVERYTHING RUNS OUT OF TREE. The harness is a mktemp -d holding a COPY of the checker, a COPY of
# the baseline, a COPY of settings.gradle.kts (mutable: one arm appends a synthetic module) and one
# SYMLINK per module — so the checker measures the real source (ROOT is derived from its own
# __file__, so a copy under $tmp/checks measures $tmp) while nothing is ever written into the tree.
#
# THE LINK SET COMES FROM settings.gradle.kts, not from `gateway/*` (restructure PR 3, :client).
# The modules no longer all live under one parent: :client is at client/ and the rest are still at
# gateway/<id>. A `for main in $ROOT/gateway/*/src/main` loop would silently omit :client, the
# checker would read an empty tree for it, and the CONTROL would go red for a harness defect that
# reads exactly like a real regression. Gradle states every directory, so the harness reads THE
# SAME LINE the checker does.
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

CHECK="$tmp/checks/public-surface.ts"
BASELINE="$tmp/checks/config/public-surface-baseline.json"
SETTINGS="$tmp/settings.gradle.kts"
SYNTH_MODULE="zz-selftest-surface"

mkdir -p "$tmp/checks/config"
module_dirs="$(grep -oE 'project\("(:[A-Za-z0-9._-]+)"\)\.projectDir = file\("[^"]+"\)' "$ROOT/settings.gradle.kts" \
  | sed -E 's/.*file\("([^"]+)"\).*/\1/' | sort -u)"
[ -n "$module_dirs" ] || { echo "  x public-surface-selftest: settings.gradle.kts states no projectDir — nothing to link"; exit 1; }
for dir in $module_dirs; do
  [ -d "$ROOT/$dir/src/main" ] || continue
  mkdir -p "$tmp/$(dirname "$dir")"
  [ -e "$tmp/$dir" ] || ln -s "$ROOT/$dir" "$tmp/$dir"
done
# build-logic holds the nonLibrary set and lives at the ROOT since PR 2, so the module loop above
# does not link it. `ln -s` into an existing symlink-to-a-directory writes THROUGH it — that is how
# an earlier revision of this harness created gateway/build-logic/build-logic in the working tree,
# the exact mktemp-hygiene failure CLAUDE.md s19 records. Guarded, and the guard is the point.
[ -e "$tmp/build-logic" ] || ln -s "$ROOT/build-logic" "$tmp/build-logic"
[ -e "$tmp/gateway/core" ] || { echo "  x public-surface-selftest: no gateway modules found under $ROOT"; exit 1; }
[ -e "$tmp/client" ] || { echo "  x public-surface-selftest: :client is not linked — the link set lost a module that lives outside gateway/"; exit 1; }
[ -e "$tmp/build-logic/src/main/kotlin" ] || { echo "  x public-surface-selftest: the module law is unreachable from the harness"; exit 1; }
# NOTHING MAY LAND IN THE TREE. Recorded before the arms run and re-checked at exit: a harness that
# writes into the tree it measures has stopped being a harness. Both module homes are watched, for
# the same reason the link set reads settings.gradle.kts: one of them is no longer under gateway/.
tree_state="$(cd "$ROOT/gateway" && ls -1A)"
client_state="$(cd "$ROOT/client" && ls -1A)"
law_state="$(cd "$ROOT/build-logic" && ls -1A)"

reset_all() {
  cp "$ROOT/checks/public-surface.ts" "$CHECK"
  cp "$ROOT/checks/config/public-surface-baseline.json" "$BASELINE"
  cp "$ROOT/settings.gradle.kts" "$SETTINGS"
  rm -rf "$tmp/gateway/$SYNTH_MODULE"
}
reset_all

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

# -- control ---------------------------------------------------------------------------------
bun "$ROOT/checks/public-surface.ts" --selftest >"$tmp/out" 2>&1 || {
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
# A real new module enters the denominator through BOTH lines settings.gradle.kts carries for one:
# the include() and the projectDir. The checker refuses an id with only the first (its --selftest
# arm 13b), so a fixture that appended only the include() would go red for the wrong reason.
bun -e "$(cat <<'JS'
const [path, mod] = process.argv.slice(1);
const text = await Bun.file(path).text();
const needle = '    ":fir-checks",\n';
if (!text.includes(needle)) throw new Error("settings.gradle.kts no longer includes :fir-checks - the injection point moved");
const dirNeedle = 'project(":fir-checks").projectDir = file("gateway/fir-checks")\n';
if (!text.includes(dirNeedle)) throw new Error("settings.gradle.kts no longer states :fir-checks' projectDir - the injection point moved");
await Bun.write(
  path,
  text.replace(needle, needle + `    ":${mod}",\n`)
      .replace(dirNeedle, dirNeedle + `project(":${mod}").projectDir = file("gateway/${mod}")\n`),
);
JS
)" "$SETTINGS" "$SYNTH_MODULE"
check --ratchet
must_fail "1. GROWTH — an unjustified public type no baseline entry records" "GROWTH"
grep -q "SelftestLeakedType" "$tmp/out" ||
  err "1. GROWTH — the failure does not NAME the planted type, so the arm went red for something else"
reset_all

# -- 2. GROWTH: a baseline entry deleted while its offender stands ----------------------------
bun -e "$(cat <<'JS'
const path = process.argv[1];
const data = JSON.parse(await Bun.file(path).text());
if (!data.offenders || data.offenders.length === 0) throw new Error("the baseline is empty - this arm has nothing to delete");
data.offenders.shift();
await Bun.write(path, JSON.stringify(data, null, 2) + "\n");
JS
)" "$BASELINE"
check --ratchet
must_fail "2. GROWTH — an offender whose baseline line was deleted" "GROWTH"
reset_all

# -- 3. STALE: a baseline entry naming a declaration that does not exist ----------------------
bun -e "$(cat <<'JS'
const path = process.argv[1];
const data = JSON.parse(await Bun.file(path).text());
data.offenders.push(":core splice.core.selftest.NeverExisted");
await Bun.write(path, JSON.stringify(data, null, 2) + "\n");
JS
)" "$BASELINE"
check --ratchet
must_fail "3. STALE — a baseline entry naming a declaration the tree does not have" "STALE"
grep -q "NeverExisted" "$tmp/out" || err "3. STALE — the failure does not NAME the stale entry"
reset_all

# -- 4. an undated baseline is a hard error, not a pass ---------------------------------------
bun -e "$(cat <<'JS'
const path = process.argv[1];
const data = JSON.parse(await Bun.file(path).text());
data.recorded = "";
await Bun.write(path, JSON.stringify(data, null, 2) + "\n");
JS
)" "$BASELINE"
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
bun -e "$(cat <<'JS'
const path = process.argv[1];
await Bun.write(path, 'rootProject.name = "splice-gateway"\ninclude(\n    ":app",\n)\n\nproject(":app").projectDir = file("gateway/app")\n');
JS
)" "$SETTINGS"
check --ratchet
must_fail "6. a settings file whose every module is nonLibrary must REFUSE" "vacuously"
reset_all

if [ "$tree_state" != "$(cd "$ROOT/gateway" && ls -1A)" ]; then
  err "the harness changed gateway/ — everything here must land in mktemp; diff: $(diff <(printf '%s\n' "$tree_state") <(cd "$ROOT/gateway" && ls -1A) | tr '\n' ' ')"
fi
if [ "$client_state" != "$(cd "$ROOT/client" && ls -1A)" ]; then
  err "the harness changed client/ — everything here must land in mktemp; diff: $(diff <(printf '%s\n' "$client_state") <(cd "$ROOT/client" && ls -1A) | tr '\n' ' ')"
fi
if [ "$law_state" != "$(cd "$ROOT/build-logic" && ls -1A)" ]; then
  err "the harness changed build-logic/ — everything here must land in mktemp; diff: $(diff <(printf '%s\n' "$law_state") <(cd "$ROOT/build-logic" && ls -1A) | tr '\n' ' ')"
fi

if [ "$fail" -eq 0 ]; then
  note "public-surface selftest: control green over the real tree, 6 mutation arms red for their stated reasons, gateway/, client/ and build-logic/ untouched"
fi
exit "$fail"
