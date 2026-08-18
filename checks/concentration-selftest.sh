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
  elif ! grep -qF "$2" "$tmp/out"; then
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
python3 - "$ORACLE" <<'PY'
import pathlib, re, sys

path = pathlib.Path(sys.argv[1])
pad = lambda m: m.group(1) + str(round(float(m.group(2)) * 2, 2)) + ","
text, n = re.subn(r'(\.kt",\n\s+)(\d+\.\d+),', pad, path.read_text(), count=1)
assert n == 1, "no CEILING_EXCEPTIONS ceiling found to pad"
path.write_text(text)
PY
oracle --ratchet --max-ratio 1.8
must_fail "3. PADDED CEILING arm — gate path" "PADDED CEILING"
oracle --json
must_fail "3. PADDED CEILING arm — --json" "PADDED CEILING"
oracle --top 5
must_fail "3. PADDED CEILING arm — plain table" "PADDED CEILING"
oracle --file UpstreamClient.kt
must_fail "3. PADDED CEILING arm — --file" "PADDED CEILING"
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
reset_config

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

if [ "$fail" -eq 0 ]; then
  note "concentration selftest: control green, 11 fixtures red for their stated reasons"
fi
exit "$fail"
