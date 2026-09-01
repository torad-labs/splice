#!/usr/bin/env bash
# checks/campaign-cli-selftest-canary.sh — the canary for checks/campaign-cli-selftest.sh.
#
# gate.sh's own words, a few lines above where that leg is wired: "the leg guards the tree, this
# canary guards the LEG. It shipped without one, and that is precisely why a routing guard defeated
# by a single `#` survived into the branch." A leg that cannot fail is a leg that reports PASS
# forever, which is the failure DR-184 exists to end — so it ships with its own red-proofs.
#
# Method: fixture ROOTs under mktemp -d, each with a checks/ and a dev/campaigns/, and a STUB
# manifest.py whose behaviour is chosen by the ledger's filename. The subject under test is the
# LEG's logic — enumerate, run, judge, assert byte-identical — never manifest.py, which has its own
# suite (that is the whole point of the leg). Nothing here touches the real repo: the arms read
# $ROOT only to copy the leg out of it, and the real ledgers' sha256s are compared before and
# after to prove it.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEG="$ROOT/checks/campaign-cli-selftest.sh"

before_real="$(sha256sum "$ROOT"/dev/campaigns/*.toml | sha256sum | cut -d' ' -f1)"

fixtures=""
cleanup() {
  for dir in $fixtures; do rm -rf "$dir"; done
}
trap cleanup EXIT

# A fixture root: checks/<the real leg> + dev/campaigns/<stub manifest.py> + the named ledgers.
make_root() {
  local root
  root="$(mktemp -d -t campaign-cli-canary-XXXXXX)"
  fixtures="$fixtures $root"
  mkdir -p "$root/checks" "$root/dev/campaigns"
  cp "$LEG" "$root/checks/campaign-cli-selftest.sh"
  cat >"$root/dev/campaigns/manifest.py" <<'STUB'
import sys
# Stub: the canary is testing the LEG, not the CLI. Behaviour is keyed on the ledger's name so a
# fixture tree can spell "this one fails" and "this one edits the ledger it was handed".
ledger = sys.argv[1]
if ledger.endswith("fails.toml"):
    print("stub: pretending the CLI suite failed", file=sys.stderr)
    sys.exit(1)
if ledger.endswith("mutates.toml"):
    with open(ledger, "a", encoding="utf-8") as handle:
        handle.write("# the suite wrote to the ledger it was validating\n")
print("stub: selftest OK")
STUB
  for name in "$@"; do
    printf '[campaign]\nname = "%s"\n' "$name" >"$root/dev/campaigns/$name.toml"
  done
  echo "$root"
}

pass=0
fail=0
arm() { # arm <name> <expect: RED|GREEN> <root> [diagnosis substring]
  local name="$1" expect="$2" root="$3" want="${4:-}" out status
  out="$(bash "$root/checks/campaign-cli-selftest.sh" 2>&1)"
  status=$?
  local got; got=$([ $status -eq 0 ] && echo GREEN || echo RED)
  if [ "$got" != "$expect" ]; then
    echo "FAIL  $name — expected $expect, got $got"
    printf '%s\n' "$out" | sed 's/^/      /'
    fail=$((fail + 1))
    return
  fi
  if [ -n "$want" ] && ! printf '%s' "$out" | grep -qF "$want"; then
    echo "FAIL  $name — $expect, but undiagnosed: wanted \"$want\""
    printf '%s\n' "$out" | sed 's/^/      /'
    fail=$((fail + 1))
    return
  fi
  echo "ok    $name ($expect)"
  pass=$((pass + 1))
}

# RED 1 — an empty denominator. A leg that skips when there is nothing to measure passes forever
# the day the ledgers move; this is the boring case that gets waved through.
empty_root="$(make_root)"
arm "no ledger on disk is a hard failure" RED "$empty_root" "no ledger under dev/campaigns/"

# RED 2 — a failing suite must fail the leg, and the leg must NAME the ledger. Placed LAST
# alphabetically on purpose: a leg that checks only the first ledger would report green here, so
# this arm is also the proof that the sweep's denominator is every ledger, not ledgers[0].
tail_fail_root="$(make_root aaa-healthy zzz-fails)"
arm "a failing suite on a NON-first ledger fails the leg" RED "$tail_fail_root" "zzz-fails.toml"

# RED 3 — the same failure at the head of the list, so neither end is a blind spot.
head_fail_root="$(make_root aaa-fails zzz-healthy)"
arm "a failing suite on the first ledger fails the leg" RED "$head_fail_root" "aaa-fails.toml"

# RED 4 — a suite that EDITS the campaign memory it is validating. The one regression that would
# be worse than no leg at all, and invisible to an exit-code-only check: the stub exits 0.
mutate_root="$(make_root aaa-healthy zzz-mutates)"
arm "a suite that mutates a ledger fails the leg" RED "$mutate_root" "MUTATED"

# GREEN — the control the four reds are measured against.
healthy_root="$(make_root aaa-healthy mmm-healthy zzz-healthy)"
arm "a healthy tree passes" GREEN "$healthy_root" "3 ledger(s), all green, all byte-identical"

after_real="$(sha256sum "$ROOT"/dev/campaigns/*.toml | sha256sum | cut -d' ' -f1)"
if [ "$before_real" != "$after_real" ]; then
  echo "FAIL  the canary itself touched the REAL ledgers"
  fail=$((fail + 1))
else
  echo "ok    real ledgers byte-identical ($before_real)"
  pass=$((pass + 1))
fi

echo "campaign-cli-selftest-canary: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
