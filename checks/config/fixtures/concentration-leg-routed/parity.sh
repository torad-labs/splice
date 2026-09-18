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
# WHEN checks/concentration.py BECOMES .ts: the wall's inverse half pins the gate:concentration argv
# (a python interpreter, then concentration.py as the oracle). Those pins change in the SAME commit
# as the oracle, and these fixtures' py.out then describe the OLD argv — re-baseline them from the
# wall as it stood proven here, and say so where the change is recorded.
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
