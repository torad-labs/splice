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
#     "gate:concentration": "true # python3 checks/concentration.py --ratchet --max-ratio 1.8"
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
#
# EVERYTHING RUNS OUT OF TREE. The harness is a mktemp -d containing COPIES of the two checkers and
# of package.json / checks/gate.sh, plus one SYMLINK per gateway module — so the oracle measures the
# real source (ROOT is derived from its own __file__, so a copy under $tmp/checks measures $tmp)
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

ORACLE="$tmp/checks/concentration.py"
ROUTING="$tmp/checks/config/concentration-leg-routed.py"
SYNTH="$tmp/gateway/zz-selftest-synthetic/src/main/kotlin/splice/selftest"

# ── harness ───────────────────────────────────────────────────────────────────────────────────
mkdir -p "$tmp/checks/config" "$tmp/gateway"
for main in "$ROOT"/gateway/*/src/main; do
  [ -d "$main" ] || continue
  mod="${main#"$ROOT"/gateway/}"
  mod="${mod%%/*}"
  ln -s "$ROOT/gateway/$mod" "$tmp/gateway/$mod"
done
[ -e "$tmp/gateway/core" ] || { echo "  ✗ concentration-selftest: no gateway modules found under $ROOT"; exit 1; }

reset_oracle() { cp "$ROOT/checks/concentration.py" "$ORACLE"; }
reset_config() {
  cp "$ROOT/package.json" "$tmp/package.json"
  cp "$ROOT/checks/gate.sh" "$tmp/checks/gate.sh"
  cp "$ROOT/checks/config/concentration-leg-routed.py" "$ROUTING"
}
reset_oracle
reset_config

rc=0
oracle() { python3 "$ORACLE" "$@" >"$tmp/out" 2>&1; rc=$?; }
routing() { python3 "$ROUTING" >"$tmp/out" 2>&1; rc=$?; }

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
#   A file needs neighbours to have a ratio (with none it is graded against itself and can never
#   reach HIGH), but `neighbours` is symmetric — "packages I import" AND "packages that import me" —
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
python3 - "$ORACLE" "$SYNTH" <<'PY'
import importlib.util, math, pathlib, statistics, sys

spec = importlib.util.spec_from_file_location("concentration_selftest_oracle", sys.argv[1])
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)
out = pathlib.Path(sys.argv[2])

# Size the god object from the tree rather than hardcoding a class count, so this fixture cannot
# quietly stop reaching HIGH as the tree grows. Its denominator is its private neighbour's C (8.5),
# which sits below the global floor, so the floor is what it is graded against: floor = 0.5 * the
# median package C, and C = 8.5 * classes + 8. Aim at twice the 3.0 HIGH threshold for margin.
by_package: dict[str, list[float]] = {}
for row in oracle.collect():
    by_package.setdefault(row["package"], []).append(row["C"])
global_median = statistics.median([statistics.median(cs) for cs in by_package.values()])
classes = max(20, math.ceil((6 * 0.5 * global_median - 8) / 8.5))

(out / "neighbour/SelftestMarker.kt").write_text(
    "package splice.selftest.neighbour\nclass SelftestMarker(val v: String)\n"
)
body = ["package splice.selftest", "import splice.selftest.neighbour.SelftestMarker"]
body += [f"class SelftestGod{n}(val v: String)" for n in range(1, classes + 1)]
(out / "SelftestGodObject.kt").write_text("\n".join(body) + "\n")
PY

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
python3 - "$ORACLE" <<'PY'
import pathlib, re, sys

path = pathlib.Path(sys.argv[1])
text, n = re.subn(r"^RATCHET_MAX_HIGH = \d+", "RATCHET_MAX_HIGH = 99", path.read_text(), count=1, flags=re.M)
assert n == 1, "RATCHET_MAX_HIGH assignment not found — the SLACK fixture cannot be built"
path.write_text(text)
PY
oracle --ratchet --max-ratio 1.8
must_fail "2. SLACK arm — RATCHET_MAX_HIGH held above the measured count" "SLACK: band HIGH fell"
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
# because `oracle` is expected to fail, so a python assert used to exit 1 and then the unmutated
# tree ran green.
if ! python3 - "$ORACLE" <<'PY'
import pathlib, re, sys

path = pathlib.Path(sys.argv[1])
text = path.read_text()
empty = "CEILING_EXCEPTIONS: list[tuple[str, float, str]] = []"
injected = """CEILING_EXCEPTIONS: list[tuple[str, float, str]] = [
    ("gateway/provider-spi/src/main/kotlin/splice/spi/UpstreamClient.kt", 9.99, "2099-01-01: selftest padded fixture"),
]"""
if empty in text:
    text = text.replace(empty, injected, 1)
else:
    pad = lambda m: m.group(1) + str(round(float(m.group(2)) * 2, 2)) + ","
    text, n = re.subn(r'(\.kt",\n\s+)(\d+\.\d+),', pad, text, count=1)
    assert n == 1, "no CEILING_EXCEPTIONS ceiling found to pad"
path.write_text(text)
PY
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
  python3 - "$tmp/package.json" "$1" <<'PY'
import json, pathlib, re, sys

path = pathlib.Path(sys.argv[1])
text, n = re.subn(
    r'("gate:concentration": )"[^"]*"',
    lambda m: m.group(1) + json.dumps(sys.argv[2]),
    path.read_text(),
    count=1,
)
assert n == 1, "package.json has no gate:concentration script to rewrite"
path.write_text(text)
PY
}

set_script 'true'
routing
must_fail "4. gate:concentration rewritten to 'true'" "does not run a python interpreter"

# The blocker itself: every required substring survives, in a comment the shell throws away.
set_script 'true # python3 checks/concentration.py --ratchet --max-ratio 1.8'
routing
must_fail "5. gate:concentration defanged by a shell comment" "does not run a python interpreter"

# The original one-line defang the guard was written for, kept so it cannot regress either.
set_script 'python3 checks/concentration.py --top 5'
routing
must_fail "5b. gate:concentration downgraded to --top 5" "does not pass --ratchet"

# The oracle argv is present and valid, but a shell control operator masks any ratchet failure.
set_script 'python3 checks/concentration.py --ratchet --max-ratio 1.8 || true'
routing
must_fail "5c. gate:concentration masks the oracle exit with shell control" "unsupported or trailing token"
reset_config

# --since returns before the ratchet branch; combining them must be refused, not silently downgraded
# to a read-only movement report.
oracle --ratchet --since HEAD --max-ratio 1.8
must_fail "5d. --since cannot silently override --ratchet" "--ratchet and --since are mutually exclusive"

# ── 6. routing guard, forward half: gate.sh stops running the leg ─────────────────────────────
python3 - "$tmp/checks/gate.sh" <<'PY'
import pathlib, sys

path = pathlib.Path(sys.argv[1])
lines = path.read_text().splitlines(keepends=True)
kept = [line for line in lines if "gate:concentration" not in line]
assert len(kept) < len(lines), "gate.sh has no concentration leg to remove"
path.write_text("".join(kept))
PY
routing
must_fail "6. the concentration leg removed from gate.sh entirely" "does not run 'gate:concentration'"
reset_config

# The forward half of the blocker: the leg still LOOKS routed, and runs `true`.
python3 - "$tmp/checks/gate.sh" <<'PY'
import pathlib, sys

path = pathlib.Path(sys.argv[1])
defanged = 'run "concentration"  true  # gate:concentration disabled pending investigation\n'
out, n = [], 0
for line in path.read_text().splitlines(keepends=True):
    if "gate:concentration" in line and line.lstrip().startswith("run "):
        out.append(defanged)
        n += 1
    else:
        out.append(line)
assert n == 1, "gate.sh has no `run` leg naming gate:concentration"
path.write_text("".join(out))
PY
routing
must_fail "6b. the gate.sh leg replaced by 'true', script name left in a trailing comment" "does not run 'gate:concentration'"
reset_config

# DR-114: the reachability half. Wrapping the leg in dead control flow keeps every token of the
# line intact — the guard's line-by-line tokenizer saw a perfect `run` leg while bash never
# executes it. Deletion (6), replacement (6b) and flag-hijack (10) all leave this hole open.
python3 - "$tmp/checks/gate.sh" <<'PY'
import pathlib, sys

path = pathlib.Path(sys.argv[1])
out, n = [], 0
for line in path.read_text().splitlines(keepends=True):
    if "gate:concentration" in line and line.lstrip().startswith("run "):
        out.append("if false; then\n")
        out.append(line)
        out.append("fi\n")
        n += 1
    else:
        out.append(line)
assert n == 1, "gate.sh has no `run` leg naming gate:concentration"
path.write_text("".join(out))
PY
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
  "$ROOT"/gateway/*/src/main >"$tmp/annotation-ast.json"
then
  err "7b. source annotation census — ast-grep could not enumerate production class declarations"
elif ! python3 - "$ORACLE" "$tmp/annotation-ast.json" >"$tmp/annotation-check" 2>&1 <<'PY'
import importlib.util
import json
import pathlib
import re
import sys

spec = importlib.util.spec_from_file_location("concentration_annotation_census", sys.argv[1])
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)
nodes = [
    node
    for node in json.loads(pathlib.Path(sys.argv[2]).read_text())
    if re.search(r"\bannotation\s+class\b", node["text"])
]
problems = []
if not nodes:
    problems.append("AST source denominator found zero annotation classes — refusing a vacuous pass")
for node in nodes:
    column = node["range"]["start"]["column"]
    measured = oracle.measure(node["file"], " " * column + node["text"])
    lane = "types" if column == 0 else "nested_types"
    if measured[lane] != 1:
        problems.append(
            f"{node['file']}:{node['range']['start']['line'] + 1} is an AST annotation class "
            f"but {lane}={measured[lane]}"
        )
for problem in problems:
    print(problem)
print(f"AST annotation denominator: {len(nodes)} declaration(s)")
sys.exit(1 if problems else 0)
PY
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
elif ! python3 - "$tmp/out" <<'PY'
import json, sys
rows = json.load(open(sys.argv[1]))
bad = [r["file"] for r in rows if r["denominator"] and r["ratio"] != round(r["C"] / r["denominator"], 2)]
sys.exit(1 if bad else 0)
PY
then
  err "9. reproducibility arm — some row's ratio does not equal round(C / denominator, 2): the gate's arithmetic cannot be reproduced from its own output"
else
  note "✓ 9. every row's ratio reproduces from its printed C and denominator"
fi

# ── 10. routing guard: npm middle flags are pinned (DR-51) ────────────────────────────────────
# Red on the pre-DR-51 guard: --prefix re-points npm at a package.json the inverse half never
# validates, and --if-present turns the missing script into exit 0 — both halves stayed green.
python3 - "$tmp/checks/gate.sh" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
hijacked = 'run "concentration"  npm run --prefix /tmp --if-present gate:concentration\n'
out, n = [], 0
for line in path.read_text().splitlines(keepends=True):
    if "gate:concentration" in line and line.lstrip().startswith("run "):
        out.append(hijacked)
        n += 1
    else:
        out.append(line)
assert n == 1, "gate.sh has no `run` leg naming gate:concentration"
path.write_text("".join(out))
PY
routing
must_fail "10. npm middle flags (--prefix /tmp --if-present) must not count as a routing" "does not run 'gate:concentration'"
reset_config

# ── 11. --since: added/deleted files and SIGNED cause shares (DR-51) ──────────────────────────
# A tiny self-contained git repo, so collect_ref has a real HEAD to archive. Red on the
# pre-DR-51 oracle: the intersection loop dropped the added and deleted rows entirely, and abs()
# shares could not go negative, so an own-C FALL during a ratio RISE read as a positive share.
G="$tmp/since-repo"
mkdir -p "$G/checks" "$G/gateway/m1/src/main/kotlin/splice/a" "$G/gateway/m2/src/main/kotlin/splice/b"
cp "$ROOT/checks/concentration.py" "$G/checks/concentration.py"
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
python3 "$G/checks/concentration.py" --since HEAD --json >"$tmp/out" 2>&1; rc=$?
if [ "$rc" -ne 0 ]; then
  err "11. --since arm — the fixture repo run must succeed (exit $rc): $(head -3 "$tmp/out" | tr '\n' ' ')"
elif ! python3 - "$tmp/out" <<'PY'
import json, sys
rows = {r["file"]: r for r in json.load(open(sys.argv[1]))}
a = rows.get("gateway/m1/src/main/kotlin/splice/a/A.kt")
fresh = rows.get("gateway/m1/src/main/kotlin/splice/a/Fresh.kt")
doomed = rows.get("gateway/m2/src/main/kotlin/splice/b/Doomed.kt")
problems = []
if fresh is None or fresh["cause"] != "added":
    problems.append(f"added file missing or mislabelled: {fresh}")
if doomed is None or doomed["cause"] != "deleted":
    problems.append(f"deleted file missing or mislabelled: {doomed}")
if a is None:
    problems.append("A.kt (the signed-share case) did not appear in the movement report")
else:
    if a["ratio_after"] <= a["ratio_before"]:
        problems.append(f"fixture defect: A's ratio must RISE, got {a['ratio_before']} -> {a['ratio_after']}")
    if a["cause"] != "neighbourhood":
        problems.append(f"A's move is denominator-dominated; cause must be neighbourhood, got {a['cause']}")
    if a["own_share"] is None or a["own_share"] >= 0:
        problems.append(
            f"A's own C FELL while its ratio ROSE — the signed own share must be NEGATIVE, got "
            f"{a['own_share']} (abs() shares cannot say this)")
    if a["own_share"] is not None and abs(a["own_share"] + a["neighbourhood_share"] - 1) > 0.002:
        problems.append(f"shares must sum to 1: {a['own_share']} + {a['neighbourhood_share']}")
for p in problems:
    print(p)
sys.exit(1 if problems else 0)
PY
then
  err "11. --since arm — added/deleted rows or signed shares wrong: $(tail -5 "$tmp/out" | tr '\n' ' ')"
else
  note "✓ 11. --since reports added/deleted files, and cause shares are signed"
fi
rm -rf "$G"

if [ "$fail" -eq 0 ]; then
  note "concentration selftest: control green, 12 mutation fixtures red for their stated reasons, 6 DR-51 arms green"
fi
exit "$fail"
