#!/usr/bin/env bash
# checks/campaign-cli-selftest.sh — DR-184: run the ledger CLI's OWN regression suite in the gate.
#
# The CLI's selftest is the only wall the campaign instrument has. None of it was reachable from
# `bash checks/gate.sh` until this leg. The leg named "campaign selftest" two lines above this one
# in gate.sh is a DIFFERENT script — campaign_wall_gate --selftest, which checks WALL wiring and only
# reads the ledger through `manifest list`. So the CLI that owns every campaign's memory had a suite
# that ran when a session remembered to type it, which is the same as not having one. That is the
# exact shape DR-181 exists to punish: a green gate said nothing about the artifact whose whole
# purpose is to outlive the session that wrote it.
#
# TWO HALVES, BECAUSE THE BUN CLI SPLIT WHAT ONE PYTHON RUN USED TO DO (V4-143, 2026-09-18).
# manifest.py's selftest COPIED the ledger it was pointed at into its fixtures, so running it once
# per ledger checked both the CLI and every real ledger's shape. The bun CLI's selftest runs one
# synthetic ledger and ignores the path it is handed, so the old loop would have run the same suite
# thirteen times and exercised no real ledger at all — green while testing nothing, in the row whose
# job is replacing the tool this leg checks. So:
#
#   1. THE SUITE, ONCE: `manifest.ts <ledger> selftest` — the CLI's own arms.
#   2. EVERY REAL LEDGER, ON A COPY: `validate`, then a note round-trip through the CLI, and the copy
#      must differ from a pristine copy by EXACTLY ONE ADDED LINE carrying the marker. That line is
#      the whole contract the ledger's diffability rests on: the writer is line-surgical, never a
#      TOML serialiser, and a round-trip through a serialiser would still parse and would still pass
#      any check that compares parsed values. The diff is copy against copy, never against the live
#      file, so a seat writing the ledger mid-run cannot redden this leg.
#
# EVERY LEDGER, NOT ONE: wiring the old leg for the first time found it passing on 8 of the 10 ledgers
# here and failing on the 2 with no [campaign].name. A ledger with no row to round-trip through is a
# failure by name, not a skip. The real ledgers are also asserted BYTE-IDENTICAL afterwards: a leg
# that edits the campaign memory it is validating would be the worst possible regression in this
# file's neighbourhood. Zero ledgers is a hard failure — an empty denominator is how a check passes by
# measuring nothing.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

CLI=(bun .dev/campaigns/manifest.ts)
MARKER="campaign-cli-selftest round-trip"

shopt -s nullglob
ledgers=(.dev/campaigns/*.toml)
shopt -u nullglob

if [ ${#ledgers[@]} -eq 0 ]; then
  echo "campaign-cli-selftest: no ledger under .dev/campaigns/ to run the CLI against." >&2
  echo "  The leg round-trips a note through every real ledger; with none there is nothing measured." >&2
  exit 1
fi

scratch="$(mktemp -d -t campaign-cli-selftest-XXXXXX)"
trap 'rm -rf "$scratch"' EXIT

declare -A before
for ledger in "${ledgers[@]}"; do
  before[$ledger]="$(sha256sum "$ledger" | cut -d' ' -f1)"
done

failed=0

# 1. THE SUITE, ONCE.
if ! out="$("${CLI[@]}" "${ledgers[0]}" selftest 2>&1)"; then
  echo "campaign-cli-selftest: the CLI's own selftest FAILED" >&2
  printf '%s\n' "$out" | tail -20 >&2
  failed=1
fi

# 2. EVERY REAL LEDGER, ON A COPY.
for ledger in "${ledgers[@]}"; do
  name="$(basename "$ledger")"
  mkdir -p "$scratch/pristine" "$scratch/work"
  cp "$ledger" "$scratch/pristine/$name"
  cp "$ledger" "$scratch/work/$name"
  copy="$scratch/work/$name"

  if ! out="$("${CLI[@]}" "$copy" validate 2>&1)"; then
    echo "campaign-cli-selftest: FAILED to validate $ledger" >&2
    printf '%s\n' "$out" | tail -20 >&2
    failed=1
    continue
  fi

  id="$("${CLI[@]}" "$copy" list --plain 2>/dev/null | awk 'NR == 1 { print $1 }')"
  if [ -z "$id" ]; then
    echo "campaign-cli-selftest: $ledger has no row to round-trip a note through — nothing measured." >&2
    failed=1
    continue
  fi

  if ! out="$("${CLI[@]}" "$copy" note "$id" "$MARKER" 2>&1)"; then
    echo "campaign-cli-selftest: FAILED to write a note to $ledger ($id), on a copy" >&2
    printf '%s\n' "$out" | tail -20 >&2
    failed=1
    continue
  fi

  delta="$(diff "$scratch/pristine/$name" "$copy")"
  added="$(printf '%s\n' "$delta" | grep -c '^>')"
  removed="$(printf '%s\n' "$delta" | grep -c '^<')"
  carried="$(printf '%s\n' "$delta" | grep -cF "$MARKER")"
  if [ "$added" -ne 1 ] || [ "$removed" -ne 0 ] || [ "$carried" -ne 1 ]; then
    echo "campaign-cli-selftest: a note on $ledger ($id) did not add EXACTLY ONE line carrying it" >&2
    echo "  added $added, removed $removed, lines carrying the note $carried — the writer must be line-surgical" >&2
    printf '%s\n' "$delta" | head -20 >&2
    failed=1
  fi
done

for ledger in "${ledgers[@]}"; do
  after="$(sha256sum "$ledger" | cut -d' ' -f1)"
  if [ "${before[$ledger]}" != "$after" ]; then
    echo "campaign-cli-selftest: the leg MUTATED $ledger — it must work on copies only." >&2
    echo "  before ${before[$ledger]}" >&2
    echo "  after  $after" >&2
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "campaign-cli-selftest: OK (suite green; ${#ledgers[@]} ledger(s) validated, each round-tripped one line, all byte-identical)"
