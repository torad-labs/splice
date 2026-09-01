#!/usr/bin/env bash
# checks/campaign-cli-selftest.sh — DR-184: run the ledger CLI's OWN regression suite in the gate.
#
# `manifest.py <ledger> selftest` is the only wall the campaign instrument has: the DR-181
# missing-ledger arms, the add/set-status/note/verdict/edit-fence/claim/next-packet round trips,
# the DR-183 usage arms. None of it was reachable from `bash checks/gate.sh`. The leg named
# "campaign selftest" two lines above this one in gate.sh is a DIFFERENT script —
# campaign_wall_gate.py --selftest, which checks WALL wiring and only reads the ledger through
# `manifest list`. So the CLI that owns every campaign's memory had a suite that ran when a session
# remembered to type it, which is the same as not having one. That is the exact shape DR-181 exists
# to punish: a green gate said nothing about the artifact whose whole purpose is to outlive the
# session that wrote it.
#
# EVERY LEDGER, NOT ONE. The suite copies the ledger it is pointed at into each of its fixtures, so
# its verdict used to inherit that ledger's shape — wiring this leg for the first time immediately
# found it passing on 8 of the 10 ledgers here and failing on the 2 with no [campaign].name, one
# with a raw KeyError and one with a sys.exit escaping mid-arm. DR-184 made the fixtures supply
# what they need, and sweeping all of them is what proves that independence still holds rather than
# asserting it. Ten runs of a few seconds each.
#
# The ledgers are also asserted BYTE-IDENTICAL afterwards: the suite must work on copies, and a
# suite that edits the campaign memory it is validating would be the worst possible regression in
# this file's neighbourhood. Zero ledgers is a hard failure, not a silent skip — an empty
# denominator is how a check passes by measuring nothing.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

shopt -s nullglob
ledgers=(dev/campaigns/*.toml)
shopt -u nullglob

if [ ${#ledgers[@]} -eq 0 ]; then
  echo "campaign-cli-selftest: no ledger under dev/campaigns/ to run the CLI selftest against." >&2
  echo "  The suite needs at least one existing ledger (it copies it into its own fixtures)." >&2
  exit 1
fi

failed=0
for ledger in "${ledgers[@]}"; do
  before="$(sha256sum "$ledger" | cut -d' ' -f1)"
  if ! out="$(python3 dev/campaigns/manifest.py "$ledger" selftest 2>&1)"; then
    echo "campaign-cli-selftest: FAILED against $ledger" >&2
    printf '%s\n' "$out" | tail -20 >&2
    failed=1
  fi
  after="$(sha256sum "$ledger" | cut -d' ' -f1)"
  if [ "$before" != "$after" ]; then
    echo "campaign-cli-selftest: the suite MUTATED $ledger — it must work on copies only." >&2
    echo "  before $before" >&2
    echo "  after  $after" >&2
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "campaign-cli-selftest: OK (${#ledgers[@]} ledger(s), all green, all byte-identical)"
