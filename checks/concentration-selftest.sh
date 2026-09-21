#!/usr/bin/env bash
# checks/concentration-selftest.sh — red-green proof for the concentration oracle AND for the guard
# that keeps its leg routed, run by the gate.
#
# WHY THIS EXISTS. Every other checker this repo wires has a paired selftest leg — catalog metadata,
# the secret-scan allowlist, the campaign walls — and the concentration oracle shipped with neither.
# That is not a tidiness gap, it is the reason a blocker survived into the branch: the oracle's
# red-proofs were hand-run transcripts pasted into a ledger note, so when the routing guard was
# written with raw substring tests, NOTHING re-ran them, and both halves of it turned out to be
# defeated by a single `#`:
#
#     "gate:concentration": "true # bun checks/concentration.ts --ratchet --max-ratio 1.8"
#     run "concentration"  true  # gate:concentration disabled pending investigation
#
# Both kept every required substring while executing `true`. A transcript in a ledger cannot notice
# that; a fixture in the gate can. Items 4, 5 and 6 below are those exact bypasses, wired.
#
# WHAT IT ENCODES — six fixtures, each of which MUST exit non-zero, and each of which asserts the
# REASON as well as the exit code. A fixture that goes red for the wrong reason is a fixture that
# has stopped testing anything, which is the failure mode this whole file is about:
#
#   1  a synthetic band-HIGH file with the baseline unchanged   -> REGRESSION arm
#   2  RATCHET_MAX_HIGH held above the measured count           -> SLACK arm
#   3  a ceiling recorded above its file's measured ratio       -> PADDED CEILING arm, in every mode
#   4  gate:concentration rewritten to `true`                   -> routing guard, inverse half
#   5  gate:concentration defanged by a shell comment           -> routing guard, inverse half
#   6  the concentration leg removed from / commented out of gate.sh -> routing guard, forward half
#  13  85 band-low files in ONE package, file census green      -> PACKAGE REGRESSION arm (V4-93)
#  14  PACKAGE_MAX_FILES held above the measured worst package   -> PACKAGE SLACK arm (V4-93)
#  16  SRC_GLOBS pointed at nothing                             -> empty-census refusal (V4-93)
#
# plus the GREEN arms, which assert a census rather than a refusal: 7/7b (the type census against
# ast-grep's own AST) and 15 (the PACKAGE census's files / summed C / median C for a planted
# package). A refusal-only selftest proves a wall can fail and never that it measures anything.
#
# EVERYTHING RUNS OUT OF TREE. The harness is a mktemp -d containing COPIES of the two checkers and
# of package.json / checks/gate.sh, plus one SYMLINK per gateway module — so the oracle measures the
# real source (ROOT is derived from its own import.meta.path, so a copy under $tmp/checks measures $tmp)
# while every mutation lands on a throwaway. Nothing is ever written into gateway/, and the working
# tree is not touched at all.
#
# THE CONTROL COMES FIRST and is not decoration: each fixture claims "this mutation turns green into
# red", which is worth nothing unless the unmutated harness is green. If the control fails, this
# script says so and every fixture below it is reported as unproven rather than passing by accident.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
err() { echo "  ✗ concentration-selftest: $1"; fail=1; }
note() { printf '  %s\n' "$1"; }

ORACLE="$tmp/checks/concentration.ts"
ROUTING="$tmp/checks/config/concentration-leg-routed.ts"
SYNTH="$tmp/gateway/zz-selftest-synthetic/src/main/kotlin/splice/selftest"

# ── harness ───────────────────────────────────────────────────────────────────────────────────
mkdir -p "$tmp/checks/config" "$tmp/tools/e2e/src/compat" "$tmp/gateway"
# The routing wall (V4-145) and the oracle (V4-158) are bun, and both import the Python-semantics
# shims from tools/e2e/src/compat/.
cp "$ROOT/tools/e2e/src/compat/python-json.ts" "$ROOT/tools/e2e/src/compat/python-values.ts" "$tmp/tools/e2e/src/compat/"
# THE LINK SET COMES FROM settings.gradle.kts, never from one directory (restructure PR 3 moves the
# modules out of gateway/ one commit at a time): a harness that measures a tree with a module
# missing hands its control a red that reads exactly like a real regression — or, worse, a green
# over a smaller tree. Every module home the build declares is linked; none is spelled here.
module_dirs="$(grep -oE 'projectDir = file\("[^"]+"\)' "$ROOT/settings.gradle.kts" | sed -E 's/.*file\("([^"]+)"\)/\1/' | sort -u)"
[ -n "$module_dirs" ] || { echo "  ✗ concentration-selftest: settings.gradle.kts states no projectDir — nothing to link"; exit 1; }
for dir in $module_dirs; do
  [ -d "$ROOT/$dir/src/main" ] || continue
  mkdir -p "$tmp/$(dirname "$dir")"
  [ -e "$tmp/$dir" ] || ln -s "$ROOT/$dir" "$tmp/$dir"
done
[ -e "$tmp/core/src/main" ] || { echo "  ✗ concentration-selftest: :core is not linked — the harness lost the first module that moved out of gateway/"; exit 1; }
[ -e "$tmp/client/src/main" ] || { echo "  ✗ concentration-selftest: :client is not linked — the harness lost a module home"; exit 1; }

reset_oracle() { cp "$ROOT/checks/concentration.ts" "$ORACLE"; }
reset_config() {
  cp "$ROOT/package.json" "$tmp/package.json"
  cp "$ROOT/checks/gate.sh" "$tmp/checks/gate.sh"
  cp "$ROOT/checks/config/concentration-leg-routed.ts" "$ROUTING"
}
reset_oracle
reset_config

rc=0
oracle() { bun "$ORACLE" "$@" >"$tmp/out" 2>&1; rc=$?; }
routing() { bun "$ROUTING" >"$tmp/out" 2>&1; rc=$?; }
# step <args> <<'TS' ... TS — run a fixture-building step, a TypeScript program on stdin. The
# oracle is imported by absolute path, so a step measures exactly the copy the fixture mutates.
step() { cat >"$tmp/step.ts" && bun "$tmp/step.ts" "$@"; }
# replace_once <file> <multiline regex> <replacement> <what> — the one-match edit every constant
# fixture needs. Zero or several matches is a fixture that cannot be built, and says so, rather than
# a no-op that falls through to a green unmutated run.
replace_once() {
  bun -e '
    const fs = require("fs");
    const [path, source, replacement, what] = process.argv.slice(1);
    const text = fs.readFileSync(path, "utf8");
    const hits = text.match(new RegExp(source, "gm")) ?? [];
    if (hits.length !== 1) {
      console.error(`${what} matched ${hits.length} times — the fixture cannot be built`);
      process.exit(1);
    }
    fs.writeFileSync(path, text.replace(new RegExp(source, "m"), () => replacement));
  ' "$@"
}

must_fail() { # must_fail <label> <substring the failure must name>
  if [ "$rc" -eq 0 ]; then
    err "$1 — MUST exit non-zero, exited 0. The arm it is supposed to prove is not enforcing."
  elif ! grep -qF -- "$2" "$tmp/out"; then
    err "$1 — exited $rc, but not for the stated reason (expected '$2'): $(head -3 "$tmp/out" | tr '\n' ' ')"
  else
    note "✓ $1 (exit $rc)"
  fi
}

# ── control ───────────────────────────────────────────────────────────────────────────────────
oracle --ratchet --max-ratio 1.8
if [ "$rc" -ne 0 ]; then
  err "CONTROL: the unmutated tree must be GREEN before any fixture below means anything (exit $rc): $(tail -4 "$tmp/out" | tr '\n' ' ')"
fi
routing
if [ "$rc" -ne 0 ]; then
  err "CONTROL: the unmutated package.json + gate.sh must be GREEN (exit $rc): $(tail -3 "$tmp/out" | tr '\n' ' ')"
fi
if [ "$fail" -ne 0 ]; then
  echo "  ✗ concentration-selftest: control failed — the six fixtures below are UNPROVEN, not passing"
  exit 1
fi

# ── 1. REGRESSION arm: a new band-HIGH file, baseline unchanged ────────────────────────────────
# The fixture brings its OWN neighbourhood — one tiny splice.selftest.neighbour marker that the god
# file imports — and imports no real package at all. That is not tidiness, it is the only way this
# fixture measures one arm:
#
#   A file needs a private neighbourhood so the tree stays untouched (with none it is graded
#   against the global median since DR-117 — arm 12 owns that wall; this arm needs the god file's
#   denominator under its own control), and `neighbours` is symmetric — "packages I import" AND
#   "packages that import me" —
#   so every real package a synthetic file imports gains a synthetic neighbour and MOVES. Both
#   directions were measured here while writing this. Importing splice.spi made the synthetic file a
#   neighbour of UpstreamClient and pushed its denominator 52.0 -> 53.5, ratio 2.79 -> 2.71, tripping
#   the PADDED CEILING arm on a file the fixture never meant to touch. Avoiding just the excepted
#   packages was not enough either: a C=436 synthetic file raised the denominators of all twelve
#   packages it imported and knocked CodexAuthProvider OUT of HIGH, 3.2 -> 2.64, so band HIGH went
#   9 -> 9 and the REGRESSION arm silently proved nothing.
#
# With a private neighbourhood the measured collateral is exactly zero: no real file changes band,
# and no real file's ratio moves by any amount. This is the oracle's own documented denominator
# property arriving from a third direction — adding a neighbouring package raises a divisor exactly
# as splitting one lowers it — and a selftest that perturbs the tree it is measuring cannot tell you
# which arm went red.
mkdir -p "$SYNTH/neighbour"
step "$ORACLE" "$SYNTH" <<'TS'
import { writeFileSync } from "node:fs";

const oracle = await import(process.argv[2]);
const out = process.argv[3];

// Size the god object from the tree rather than hardcoding a class count, so this fixture cannot
// quietly stop reaching HIGH as the tree grows. Its denominator is its private neighbour's C (8.5),
// which sits below the global floor, so the floor is what it is graded against: floor = 0.5 * the
// median package C, and C = 8.5 * classes + 8. Aim at twice the 3.0 HIGH threshold for margin.
const byPackage = new Map<string, number[]>();
for (const row of oracle.collect()) byPackage.set(row.package, [...(byPackage.get(row.package) ?? []), row.C]);
const globalMedian = oracle.median([...byPackage.values()].map((cs) => oracle.median(cs)));
const classes = Math.max(20, Math.ceil((6 * 0.5 * globalMedian - 8) / 8.5));

writeFileSync(`${out}/neighbour/SelftestMarker.kt`, "package splice.selftest.neighbour\nclass SelftestMarker(val v: String)\n");
const body = ["package splice.selftest", "import splice.selftest.neighbour.SelftestMarker"];
for (let n = 1; n <= classes; n++) body.push(`class SelftestGod${n}(val v: String)`);
writeFileSync(`${out}/SelftestGodObject.kt`, body.join("\n") + "\n");
TS

# The fixture has to BE a god object before its redness proves anything about god objects.
oracle --file SelftestGodObject.kt
if ! grep -q '"band": "HIGH"' "$tmp/out"; then
  err "fixture 1 synthetic file did not reach band HIGH — the REGRESSION arm would be proving nothing: $(head -20 "$tmp/out" | tr '\n' ' ')"
fi
oracle --ratchet --max-ratio 1.8
must_fail "1. REGRESSION arm — a new band-HIGH file with the baseline unchanged" "REGRESSION: band HIGH rose"
grep -q "SelftestGodObject.kt" "$tmp/out" ||
  err "1. REGRESSION arm — the gated HIGH list does not name the synthetic file, so the arm went red for something other than the god object this fixture planted"
rm -rf "$tmp/gateway/zz-selftest-synthetic"

# ── 2. SLACK arm: a baseline held above the measured count ─────────────────────────────────────
if ! replace_once "$ORACLE" '^export const RATCHET_MAX_HIGH = \d+' 'export const RATCHET_MAX_HIGH = 99' "the RATCHET_MAX_HIGH assignment"; then
  err "2. SLACK arm — fixture could not be built"
else
  oracle --ratchet --max-ratio 1.8
  must_fail "2. SLACK arm — RATCHET_MAX_HIGH held above the measured count" "SLACK: band HIGH fell"
fi
reset_oracle

# ── 3. PADDED CEILING arm: a ceiling recorded above its file's measured ratio ──────────────────
# The defect this arm exists for was live: UpstreamClient's ceiling sat at 6.14 against a file
# measuring 2.79. Asserted in EVERY mode, because the check lives in exception_errors() precisely so
# that it cannot go quiet the moment somebody runs something other than the gate.
#
# HD-25 emptied CEILING_EXCEPTIONS. The arm must still prove PADDED CEILING, so it injects a
# synthetic padded entry into the COPIED oracle against a real measured file. It must not depend
# on a leftover production ceiling (that is the laundry this list exists to prevent), and a
# failed injection must not fall through to a green unmutated run: this script has no `set -e`
# because `oracle` is expected to fail, so a failed assertion used to exit 1 and then the
# unmutated tree ran green.
if ! step "$ORACLE" <<'TS'
import { readFileSync, writeFileSync } from "node:fs";

const path = process.argv[2];
let text = readFileSync(path, "utf8");
const empty = "export const CEILING_EXCEPTIONS: [string, number, string][] = [];";
const injected = `export const CEILING_EXCEPTIONS: [string, number, string][] = [
  ["upstream/src/main/kotlin/splice/upstream/transport/UpstreamClient.kt", 9.99, "2099-01-01: selftest padded fixture"],
];`;
if (text.split(empty).length === 2) {
  text = text.replace(empty, () => injected);
} else {
  const ceiling = /(\.kt",\s+)(\d+\.\d+),/;
  if (!ceiling.test(text)) throw new Error("no CEILING_EXCEPTIONS ceiling found to pad");
  text = text.replace(ceiling, (_, head, value) => `${head}${Math.round(Number(value) * 200) / 100},`);
}
writeFileSync(path, text);
TS
then
  err "3. PADDED CEILING arm — fixture could not be built"
else
  oracle --ratchet --max-ratio 1.8
  must_fail "3. PADDED CEILING arm — gate path" "PADDED CEILING"
  oracle --json
  must_fail "3. PADDED CEILING arm — --json" "PADDED CEILING"
  oracle --top 5
  must_fail "3. PADDED CEILING arm — plain table" "PADDED CEILING"
  oracle --file UpstreamClient.kt
  must_fail "3. PADDED CEILING arm — --file" "PADDED CEILING"
fi
reset_oracle

# ── 4/5. routing guard, inverse half: the leg definition stops invoking the oracle ─────────────
set_script() { # set_script <new body for package.json's gate:concentration>
  replace_once "$tmp/package.json" '("gate:concentration": )"[^"]*"' \
    "\"gate:concentration\": $(bun -e 'process.stdout.write(JSON.stringify(process.argv[1]))' "$1")" \
    "package.json's gate:concentration script"
}

set_script 'true'
routing
must_fail "4. gate:concentration rewritten to 'true'" "does not run bun"

# The blocker itself: every required substring survives, in a comment the shell throws away.
set_script 'true # bun checks/concentration.ts --ratchet --max-ratio 1.8'
routing
must_fail "5. gate:concentration defanged by a shell comment" "does not run bun"

# The original one-line defang the guard was written for, kept so it cannot regress either.
set_script 'bun checks/concentration.ts --top 5'
routing
must_fail "5b. gate:concentration downgraded to --top 5" "does not pass --ratchet"

# The oracle argv is present and valid, but a shell control operator masks any ratchet failure.
set_script 'bun checks/concentration.ts --ratchet --max-ratio 1.8 || true'
routing
must_fail "5c. gate:concentration masks the oracle exit with shell control" "unsupported or trailing token"
reset_config

# --since returns before the ratchet branch; combining them must be refused, not silently downgraded
# to a read-only movement report.
oracle --ratchet --since HEAD --max-ratio 1.8
must_fail "5d. --since cannot silently override --ratchet" "--ratchet and --since are mutually exclusive"

# ── 6. routing guard, forward half: gate.sh stops running the leg ─────────────────────────────
# rewrite_leg <mode> — the gate.sh mutations of 6, 6b, 6c and 10, on the harness copy.
#   delete  every line naming gate:concentration goes
#   true    the `run` leg becomes `true`, the script name kept in a trailing comment
#   dead    the `run` leg is wrapped in `if false; then ... fi`
#   hijack  the `run` leg gains npm middle flags that re-point and disarm it
# Each asserts the leg it rewrites exists exactly once, so a fixture that finds nothing to mutate
# fails to build instead of running the unmutated gate.sh.
rewrite_leg() {
  step "$tmp/checks/gate.sh" "$1" <<'TS'
import { readFileSync, writeFileSync } from "node:fs";

const [path, mode] = process.argv.slice(2);
const lines = readFileSync(path, "utf8").split(/(?<=\n)/);
const isLeg = (line: string) => line.includes("gate:concentration") && line.trimStart().startsWith("run ");
if (mode === "delete") {
  const kept = lines.filter((line) => !line.includes("gate:concentration"));
  if (kept.length === lines.length) throw new Error("gate.sh has no concentration leg to remove");
  writeFileSync(path, kept.join(""));
} else {
  if (lines.filter(isLeg).length !== 1) throw new Error("gate.sh has no single `run` leg naming gate:concentration");
  const replace: Record<string, (line: string) => string> = {
    true: () => 'run "concentration"  true  # gate:concentration disabled pending investigation\n',
    dead: (line) => `if false; then\n${line}fi\n`,
    hijack: () => 'run "concentration"  npm run --prefix /tmp --if-present gate:concentration\n',
  };
  writeFileSync(path, lines.map((line) => (isLeg(line) ? replace[mode](line) : line)).join(""));
}
TS
}

rewrite_leg delete
routing
must_fail "6. the concentration leg removed from gate.sh entirely" "does not run 'gate:concentration'"
reset_config

# The forward half of the blocker: the leg still LOOKS routed, and runs `true`.
rewrite_leg true
routing
must_fail "6b. the gate.sh leg replaced by 'true', script name left in a trailing comment" "does not run 'gate:concentration'"
reset_config

# DR-114: the reachability half. Wrapping the leg in dead control flow keeps every token of the
# line intact — the guard's line-by-line tokenizer saw a perfect `run` leg while bash never
# executes it. Deletion (6), replacement (6b) and flag-hijack (10) all leave this hole open.
rewrite_leg dead
routing
must_fail "6c. the gate.sh leg wrapped in 'if false; then ... fi' (dead but token-identical)" "control structure"
reset_config

# ── 7. census: every Kotlin type spelling is counted, nested types reported (DR-51) ──────────
# Red on the pre-DR-51 oracle: `fun interface` failed TYPE_DECL (no `fun ` modifier), annotation
# classes still fail both top-level and nested regexes, and no nested_types field existed at all.
SYNTH2="$tmp/gateway/zz-selftest-census/src/main/kotlin/splice/selftest2"
mkdir -p "$SYNTH2"
cat > "$SYNTH2/SelftestCensus.kt" <<'KT'
package splice.selftest2
fun interface SelftestSeam { fun run(): Int }
annotation class SelftestMarker
class SelftestHost(val v: String) {
    annotation class NestedMarker
    class Nested(val x: Int)
}
KT
oracle --file SelftestCensus.kt
if [ "$rc" -ne 0 ]; then
  err "7. census arm — --file SelftestCensus.kt must succeed (exit $rc): $(head -3 "$tmp/out" | tr '\n' ' ')"
elif ! grep -q '"types": 3' "$tmp/out"; then
  err "7. census arm — fun interface and annotation class must be counted as TYPEs (want types=3): $(grep -E '\"(types|C)\"' "$tmp/out" | tr '\n' ' ')"
elif ! grep -q '"nested_types": 2' "$tmp/out"; then
  err "7. census arm — nested class and annotation class must be REPORTED (want nested_types=2): $(grep nested "$tmp/out" | tr '\n' ' ')"
elif ! grep -q '"C": 27.0' "$tmp/out"; then
  err "7. census arm — C must bill all three top-level types at 8 and nested types at 0 (want 27.0): $(grep '\"C\"' "$tmp/out" | tr '\n' ' ')"
else
  note "✓ 7. census: fun interface and annotation classes counted; nested types reported unbilled"
fi
rm -rf "$tmp/gateway/zz-selftest-census"

# The live denominator comes from the Kotlin AST, not TYPE_DECL itself: every annotation-class node
# ast-grep finds in production must be recognized in the matching top-level/nested census lane.
# This keeps a newly added spelling visible without adding it to a second hand-authored allowlist.
if ! command -v ast-grep >/dev/null 2>&1; then
  err "7b. source annotation census — ast-grep is unavailable, so the external denominator cannot run"
elif ! ast-grep run --kind class_declaration --lang kotlin --json=compact \
  $(for dir in $module_dirs; do [ -d "$ROOT/$dir/src/main" ] && printf '%s ' "$ROOT/$dir/src/main"; done) >"$tmp/annotation-ast.json"
then
  err "7b. source annotation census — ast-grep could not enumerate production class declarations"
elif ! step "$ORACLE" "$tmp/annotation-ast.json" >"$tmp/annotation-check" 2>&1 <<'TS'
import { readFileSync } from "node:fs";

const oracle = await import(process.argv[2]);
type Node = { file: string; text: string; range: { start: { line: number; column: number } } };
const nodes = (JSON.parse(readFileSync(process.argv[3], "utf8")) as Node[])
  .filter((node) => /\bannotation\s+class\b/.test(node.text));
const problems: string[] = [];
if (!nodes.length) problems.push("AST source denominator found zero annotation classes — refusing a vacuous pass");
for (const node of nodes) {
  const column = node.range.start.column;
  const measured = oracle.measure(node.file, " ".repeat(column) + node.text);
  const lane = column === 0 ? "types" : "nested_types";
  if (measured[lane] !== 1) {
    problems.push(`${node.file}:${node.range.start.line + 1} is an AST annotation class but ${lane}=${measured[lane]}`);
  }
}
for (const problem of problems) console.log(problem);
console.log(`AST annotation denominator: ${nodes.length} declaration(s)`);
process.exit(problems.length ? 1 : 0);
TS
then
  err "7b. source annotation census — regex disagrees with the AST denominator: $(tail -5 "$tmp/annotation-check" | tr '\n' ' ')"
else
  note "✓ 7b. every source-derived AST annotation class lands in the matching census lane"
fi

# ── 8. --file ambiguity is exit 2, never a silent pick (DR-51) ────────────────────────────────
mkdir -p "$tmp/gateway/zz-selftest-twin-a/src/main/kotlin/splice/twina" \
         "$tmp/gateway/zz-selftest-twin-b/src/main/kotlin/splice/twinb"
printf 'package splice.twina\nclass TwinA(val v: Int)\n' > "$tmp/gateway/zz-selftest-twin-a/src/main/kotlin/splice/twina/SelftestTwin.kt"
printf 'package splice.twinb\nclass TwinB(val v: Int)\n' > "$tmp/gateway/zz-selftest-twin-b/src/main/kotlin/splice/twinb/SelftestTwin.kt"
oracle --file SelftestTwin.kt
must_fail "8. duplicate --file basename must be refused as ambiguous" "is ambiguous"
if ! grep -q "zz-selftest-twin-a" "$tmp/out" || ! grep -q "zz-selftest-twin-b" "$tmp/out"; then
  err "8. ambiguity arm — the refusal must NAME both matching files: $(tail -4 "$tmp/out" | tr '\n' ' ')"
fi
rm -rf "$tmp/gateway/zz-selftest-twin-a" "$tmp/gateway/zz-selftest-twin-b"

# ── 9. every emitted ratio reproduces from its own row (DR-51) ────────────────────────────────
# Red on the pre-DR-51 oracle: 18 of 428 live rows divided the UNROUNDED denominator, so
# C / the printed denominator gave a different ratio than the row carried.
oracle --json
if [ "$rc" -ne 0 ]; then
  err "9. reproducibility arm — --json must succeed (exit $rc)"
elif ! step "$ORACLE" "$tmp/out" <<'TS'
import { readFileSync } from "node:fs";

const oracle = await import(process.argv[2]);
const rows = JSON.parse(readFileSync(process.argv[3], "utf8"));
const bad = rows.filter((r: { denominator: number; ratio: number; C: number }) =>
  r.denominator && r.ratio !== oracle.pyRound(r.C / r.denominator, 2));
process.exit(bad.length ? 1 : 0);
TS
then
  err "9. reproducibility arm — some row's ratio does not equal round(C / denominator, 2): the gate's arithmetic cannot be reproduced from its own output"
else
  note "✓ 9. every row's ratio reproduces from its printed C and denominator"
fi

# ── 10. routing guard: npm middle flags are pinned (DR-51) ────────────────────────────────────
# Red on the pre-DR-51 guard: --prefix re-points npm at a package.json the inverse half never
# validates, and --if-present turns the missing script into exit 0 — both halves stayed green.
rewrite_leg hijack
routing
must_fail "10. npm middle flags (--prefix /tmp --if-present) must not count as a routing" "does not run 'gate:concentration'"
reset_config

# ── 11. --since: added/deleted files and SIGNED cause shares (DR-51) ──────────────────────────
# A tiny self-contained git repo, so collect_ref has a real HEAD to archive. Red on the
# pre-DR-51 oracle: the intersection loop dropped the added and deleted rows entirely, and abs()
# shares could not go negative, so an own-C FALL during a ratio RISE read as a positive share.
G="$tmp/since-repo"
mkdir -p "$G/checks" "$G/tools/e2e/src/compat" "$G/gateway/m1/src/main/kotlin/splice/a" "$G/gateway/m2/src/main/kotlin/splice/b"
cp "$ROOT/checks/concentration.ts" "$G/checks/concentration.ts"
cp "$ROOT/tools/e2e/src/compat/python-json.ts" "$ROOT/tools/e2e/src/compat/python-values.ts" "$G/tools/e2e/src/compat/"
{ printf 'package splice.a\nimport splice.b.SelftestMarkerB\nclass A0(val v: Int)\n'; for i in $(seq 1 40); do printf 'class AF%s(val v: Int)\n' "$i"; done; } > "$G/gateway/m1/src/main/kotlin/splice/a/A.kt"
{ printf 'package splice.b\nclass SelftestMarkerB(val v: Int)\n'; for i in $(seq 1 200); do printf 'class BF%s(val v: Int)\n' "$i"; done; } > "$G/gateway/m2/src/main/kotlin/splice/b/B.kt"
printf 'package splice.b\nclass Doomed(val v: Int)\n' > "$G/gateway/m2/src/main/kotlin/splice/b/Doomed.kt"
git -C "$G" -c init.defaultBranch=selftest init -q
git -C "$G" add -A
git -C "$G" -c user.email=selftest@invalid -c user.name=selftest commit -qm fixture
# Working-tree movement: A loses a line (own C FALLS), B loses most of its bulk (the neighbour
# median COLLAPSES, so A's ratio RISES on an opposing own factor), one file is added, one deleted.
{ printf 'package splice.a\nimport splice.b.SelftestMarkerB\nclass A0(val v: Int)\n'; for i in $(seq 1 39); do printf 'class AF%s(val v: Int)\n' "$i"; done; } > "$G/gateway/m1/src/main/kotlin/splice/a/A.kt"
printf 'package splice.b\nclass SelftestMarkerB(val v: Int)\n' > "$G/gateway/m2/src/main/kotlin/splice/b/B.kt"
printf 'package splice.a\nclass Fresh(val v: Int)\n' > "$G/gateway/m1/src/main/kotlin/splice/a/Fresh.kt"
rm "$G/gateway/m2/src/main/kotlin/splice/b/Doomed.kt"
bun "$G/checks/concentration.ts" --since HEAD --json >"$tmp/out" 2>&1; rc=$?
if [ "$rc" -ne 0 ]; then
  err "11. --since arm — the fixture repo run must succeed (exit $rc): $(head -3 "$tmp/out" | tr '\n' ' ')"
elif ! step "$tmp/out" <<'TS'
import { readFileSync } from "node:fs";

type Move = { file: string; cause: string; own_share: number | null; neighbourhood_share: number | null;
  ratio_before: number | null; ratio_after: number | null };
const rows = new Map((JSON.parse(readFileSync(process.argv[2], "utf8")) as Move[]).map((r) => [r.file, r]));
const a = rows.get("gateway/m1/src/main/kotlin/splice/a/A.kt");
const fresh = rows.get("gateway/m1/src/main/kotlin/splice/a/Fresh.kt");
const doomed = rows.get("gateway/m2/src/main/kotlin/splice/b/Doomed.kt");
const problems: string[] = [];
if (fresh === undefined || fresh.cause !== "added") problems.push(`added file missing or mislabelled: ${JSON.stringify(fresh)}`);
if (doomed === undefined || doomed.cause !== "deleted") problems.push(`deleted file missing or mislabelled: ${JSON.stringify(doomed)}`);
if (a === undefined) {
  problems.push("A.kt (the signed-share case) did not appear in the movement report");
} else {
  if ((a.ratio_after as number) <= (a.ratio_before as number)) {
    problems.push(`fixture defect: A's ratio must RISE, got ${a.ratio_before} -> ${a.ratio_after}`);
  }
  if (a.cause !== "neighbourhood") problems.push(`A's move is denominator-dominated; cause must be neighbourhood, got ${a.cause}`);
  if (a.own_share === null || a.own_share >= 0) {
    problems.push(`A's own C FELL while its ratio ROSE — the signed own share must be NEGATIVE, got ${a.own_share} (abs() shares cannot say this)`);
  }
  if (a.own_share !== null && Math.abs(a.own_share + (a.neighbourhood_share as number) - 1) > 0.002) {
    problems.push(`shares must sum to 1: ${a.own_share} + ${a.neighbourhood_share}`);
  }
}
for (const p of problems) console.log(p);
process.exit(problems.length ? 1 : 0);
TS
then
  err "11. --since arm — added/deleted rows or signed shares wrong: $(tail -5 "$tmp/out" | tr '\n' ' ')"
else
  note "✓ 11. --since reports added/deleted files, and cause shares are signed"
fi
rm -rf "$G"

# ── 12. zero-neighbour god object must not grade low (DR-117) ─────────────────────────────────
# Red on the pre-DR-117 oracle: a file importing no splice package and imported by nobody was
# graded against its OWN C — ratio pinned to 1.0, band low forever regardless of size, so a
# self-contained 800-line god file (vendored codec, standalone tool) passed ratchet, --max-ratio
# and --file in every band. Post-fix the zero-neighbour fallback denominator is the GLOBAL median,
# so this file bands HIGH and the ratchet REGRESSION arm names it. Pre-fix this arm cannot
# false-green: either the ratchet exits 0 (must_fail fires) or a collateral regression trips it
# without SelftestLoneGod.kt in the HIGH list (the grep fires).
LONE="$tmp/gateway/zz-selftest-lone/src/main/kotlin/splice/selftest/lone"
mkdir -p "$LONE"
step "$ORACLE" "$LONE" <<'TS'
import { writeFileSync } from "node:fs";

const oracle = await import(process.argv[2]);
const out = process.argv[3];

// Same census arithmetic as fixture 1 (C = 8.5 * classes + 8), but sized against the global median
// itself: that IS the post-fix denominator for a file with no neighbours. Twice the 3.0 HIGH
// threshold for margin. No import line and no importers, on purpose — the emptiness is the arm.
const byPackage = new Map<string, number[]>();
for (const row of oracle.collect()) byPackage.set(row.package, [...(byPackage.get(row.package) ?? []), row.C]);
const globalMedian = oracle.median([...byPackage.values()].map((cs) => oracle.median(cs)));
const classes = Math.max(20, Math.ceil((6 * globalMedian - 8) / 8.5));

const body = ["package splice.selftest.lone"];
for (let n = 1; n <= classes; n++) body.push(`class SelftestLone${n}(val v: String)`);
writeFileSync(`${out}/SelftestLoneGod.kt`, body.join("\n") + "\n");
TS
oracle --ratchet --max-ratio 1.8
must_fail "12. zero-neighbour god object must reach band HIGH (DR-117)" "REGRESSION: band HIGH rose"
grep -q "SelftestLoneGod.kt" "$tmp/out" ||
  err "12. zero-neighbour arm — the gated HIGH list does not name SelftestLoneGod.kt, so the arm went red for something other than the lone god object it planted"
rm -rf "$tmp/gateway/zz-selftest-lone"

# -- 13. PACKAGE SCALE: a clumped package is red even with the file census green (V4-93) -------
# The plane the file census cannot see: 85 files in ONE package, every one of them band low, the
# whole file-scale gate green. Pre-V4-93 this tree passed that with zero findings.
#
# THE FIXTURE IS SIZED, not arbitrary, for the same reason fixture 1 brings its own neighbourhood.
# The oracle's global median is the median of the per-package medians, so ADDING a package moves it
# and therefore moves the floor every zero-neighbour file is graded against. Each planted file is
# built to C = 45.5 (logic 75 plus one type), which is where this tree's global median already sits
# (45.625 over 52 packages), so the median moves by 0.125 and the floor by 0.06. MEASURED
# collateral at that size: exactly ONE real file's ratio moves at all
# (fir-checks/MustConsumeDiscardChecker.kt 1.52 -> 1.53), no real file changes band, and band HIGH
# stays 0 -- so when this arm goes red it is the package plane talking and not a perturbation of
# the plane it is not testing. Both facts are asserted below rather than trusted.
CLUMP="$tmp/gateway/zz-selftest-clump/src/main/kotlin/splice/selftestclump"
mkdir -p "$CLUMP"
step "$ORACLE" "$CLUMP" <<'TS'
import { writeFileSync } from "node:fs";

const oracle = await import(process.argv[2]);
const out = process.argv[3];

// One file over the gated baseline: the arm is "a package absorbed a file nothing recorded".
const count = oracle.PACKAGE_MAX_FILES + 1;
// C = 0.5*logic + 3*non_type_exports + 8*concerns; one type, no splice imports, no top-level
// funs => C = 0.5*logic + 8. logic 75 => 45.5, this tree's own global median.
for (let n = 0; n < count; n++) {
  const body = ["package splice.selftestclump", `class SelftestClump${n}(val v: String) {`];
  for (let i = 0; i < 73; i++) body.push(`    fun f${i}(): Int = ${i}`);
  body.push("}");
  writeFileSync(`${out}/SelftestClump${n}.kt`, body.join("\n") + "\n");
}
TS
oracle --ratchet --max-ratio 1.8
must_fail "13. PACKAGE REGRESSION — 85 band-low files in one package, file census green" "PACKAGE REGRESSION"
grep -q "splice.selftestclump" "$tmp/out" ||
  err "13. PACKAGE arm — the failure does not NAME the clumped package, so it went red for something else"
if grep -q "REGRESSION: band HIGH rose" "$tmp/out"; then
  err "13. PACKAGE arm — the FILE plane also went red, so this fixture is perturbing the plane it is not testing"
fi
# The assertion is "the FILE plane did not move", not "the file plane reads zero": the clump is
# band-LOW by construction, so measured must equal the oracle's own RATCHET_MAX_HIGH whatever that
# number is. Pinning the literal 0 made this arm fail the day the baseline was re-measured
# (2026-09-20, the :client extraction) for a reason that has nothing to do with what it proves.
high_baseline="$(bun -e 'console.log((await import(process.argv[1])).RATCHET_MAX_HIGH)' "$ORACLE")"
grep -qE "band HIGH +baseline +${high_baseline} +measured +${high_baseline}" "$tmp/out" ||
  err "13. PACKAGE arm — band HIGH moved off its baseline ($high_baseline), so the arm does not prove the file census passes over a clump: $(grep -m1 'band HIGH' "$tmp/out")"
rm -rf "$tmp/gateway/zz-selftest-clump"

# -- 14. PACKAGE SLACK: a baseline held above the measured worst package -----------------------
if ! replace_once "$ORACLE" '^export const PACKAGE_MAX_FILES = \d+' 'export const PACKAGE_MAX_FILES = 999' "the PACKAGE_MAX_FILES assignment"; then
  err "14. PACKAGE SLACK — fixture could not be built"
else
  oracle --ratchet --max-ratio 1.8
  must_fail "14. PACKAGE SLACK — PACKAGE_MAX_FILES held above the measured worst package" "PACKAGE SLACK"
fi
reset_oracle

# -- 15. the package CENSUS itself, green and arithmetically checkable -------------------------
# A gated number whose census cannot be reproduced from the run's own output is not auditable --
# the same rule that makes REPORT-THE-DIVISOR mandatory on the file plane. Three files of known C
# in one package, and all three columns asserted.
SMALL="$tmp/gateway/zz-selftest-census-pkg/src/main/kotlin/splice/selftestsmall"
mkdir -p "$SMALL"
for n in 0 1 2; do
  printf 'package splice.selftestsmall\nclass SelftestSmall%s(val v: String)\n' "$n" > "$SMALL/SelftestSmall$n.kt"
done
oracle --packages
if [ "$rc" -ne 0 ]; then
  err "15. package census — --packages must succeed (exit $rc): $(head -3 "$tmp/out" | tr '\n' ' ')"
elif ! grep -qE 'splice\.selftestsmall +3 +25\.5 +8\.5' "$tmp/out"; then
  err "15. package census — want 'splice.selftestsmall 3 files, sum C 25.5, median 8.5', got: $(grep selftestsmall "$tmp/out" | tr '\n' ' ')"
else
  note "ok 15. the package census reports files, summed C and median C for a planted package"
fi
rm -rf "$tmp/gateway/zz-selftest-census-pkg"

# -- 16. the BORING case: an empty package census must REFUSE, not pass (CLAUDE.md s24) --------
# The file plane already passes over an empty tree -- high == baseline and no debt -- so without
# this arm a lost SRC_GLOBS would read as a perfectly clean repo.
if ! replace_once "$ORACLE" '^export const SRC_GLOBS = .*$' 'export const SRC_GLOBS = ["gateway/*/src/nowhere"];' "the SRC_GLOBS assignment"; then
  err "16. empty package census — fixture could not be built"
else
  oracle --ratchet --max-ratio 1.8
  must_fail "16. an empty package census must REFUSE rather than report a clean tree" "the census is EMPTY"
fi
reset_oracle

if [ "$fail" -eq 0 ]; then
  note "concentration selftest: control green, 16 mutation fixtures red for their stated reasons, 7 census arms green"
fi
exit "$fail"
