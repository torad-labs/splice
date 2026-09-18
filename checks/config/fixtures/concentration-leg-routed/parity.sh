#!/usr/bin/env bash
# checks/config/fixtures/concentration-leg-routed/parity.sh — the PARITY SUITE for
# checks/config/concentration-leg-routed.ts, and the only Python oracle that wall will ever have.
#
# The wall was ported from concentration-leg-routed.py (V4-145, 2026-09-18), and that .py is gone.
# cases.tar.gz holds 2664 fixture repos (package.json + checks/gate.sh): 148 script bodies x 18 gate
# shapes covering every bypass in the wall's docstring, each with py.out = the Python original's
# stdout+stderr+rc, frozen on 2026-09-18. So no Python is needed to re-run this, and none exists to
# regenerate it: a change to the wall is proven behaviour-preserving by running it here, or it is
# argued, not proven. Keep the fixtures committed.
#
#   bash parity.sh                     -> the landed wall; expect cases=2664 mismatches=0
#   bash parity.sh <wall.ts>           -> any other copy (a mutant, or a proposed change)
#   bash parity.sh <wall.ts> --first   -> stop at the first mismatch (mutation testing)
#
# Recorded 2026-09-18: 2664/2664 identical, re-run at landing against the committed pyshim.ts in
# 2m58s; 9/9 mutants killed (comments off, any npm flag, no nesting, no heredoc skip, any
# interpreter, many ratchets, no brace depth, split on \n only, lenient float). Not a gate leg: at
# three minutes it is a tool for whoever changes the wall, and the wall's own selftest coverage runs
# in the gate through checks/concentration-selftest.sh.
#
# RE-BASELINED 2026-09-18 (V4-158), when the oracle moved to checks/concentration.ts and the wall's
# inverse-half pins (runtime, then the oracle as argv[1]) moved with it in the same commit. The
# corpus was carried across MECHANICALLY, never regenerated: a swap that is a bijection on the
# tokens the wall grades, applied to each case's gate:concentration value and its py.out (gate.sh
# untouched) —
#   R1  py.out only: the old wall's interpreter refusal -> "does not run bun"       1170 hits
#   R2  checks/concentration.py <-> checks/concentration.ts                          5094 hits
#   R3  every whole-token spelling the old PYTHON pin accepted -> bun, and bun -> the
#       old interpreter's plain spelling                                             4266 hits
# 2664 cases in, 2664 out: 1818 transformed, 846 untouched, 1206 whose script body carried a pin
# token; no case unmappable. A SWAP rather than a one-way rewrite because the corpus already held
# `bun checks/concentration.ts --ratchet --max-ratio 1.8`, REJECTED by the old wall — a one-way
# rewrite would have handed the new wall a case whose expectation silently flipped; the swap maps it
# to the old interpreter's spelling, which the new wall must reject with the same message. Proof at
# re-baseline: the moved wall passes the transformed corpus; the pre-move wall fails it.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
wall=${1:-$REPO/checks/config/concentration-leg-routed.ts}
first=${2:-}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
tar -C "$tmp" -xzf "$HERE/cases.tar.gz"
root=$tmp/root
mkdir -p "$root/checks/config" "$root/checks/e2e"
cp "$wall" "$root/checks/config/concentration-leg-routed.ts"
cp "$REPO"/checks/e2e/pyjson.ts "$REPO"/checks/e2e/pyshim.ts "$root/checks/e2e/"
fail=0
total=0
for case in "$tmp"/cases/*; do
  total=$((total + 1))
  cp "$case/package.json" "$root/package.json"
  cp "$case/gate.sh" "$root/checks/gate.sh"
  ts=$(cd "$root" && bun checks/config/concentration-leg-routed.ts 2>&1; echo "rc=$?")
  if [ "$(cat "$case/py.out")" != "$ts" ]; then
    fail=$((fail + 1))
    echo "== DIFF $(basename "$case")"
    diff <(cat "$case/py.out") <(echo "$ts") | head -20
    [ "$first" = "--first" ] && break
  fi
done
echo "cases=$total mismatches=$fail"
[ "$fail" -eq 0 ]
