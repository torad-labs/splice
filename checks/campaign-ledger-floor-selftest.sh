#!/usr/bin/env bash
# Canary over the campaign-ledger-floor leg (DR-181).
#
# The leg exists because a green gate was not evidence of campaign memory. A canary is not
# optional here for exactly the same reason: an integrity check nobody has watched FAIL is
# indistinguishable from `exit 0`, and that is the failure mode the leg was written to end. So
# every arm below drives a SYNTHETIC violation into a throwaway copy of the tree and demands the
# check go red on it — including the boring case (arm 4, a ledger that is simply gone), which is
# the one a check written against its own allowlist waves straight through.
#
# Arms 5 and 6 are the controls, and they carry the weight: without them a check hardcoded to
# `exit 1` would satisfy arms 1-4 perfectly.
#
# Wholly non-destructive: every arm runs against a fixture tree under mktemp -d, and the real
# ledgers are verified byte-identical at the end.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

before=$(sha256sum checks/config/campaign-ledger-floor.json dev/campaigns/*.toml | sha256sum)

fail=0
pass=0

# Build a throwaway tree carrying the check, its floor file, and the ledgers it measures.
fixture() {
  local dir="$TMP/$1"
  rm -rf "$dir"
  mkdir -p "$dir/checks/config" "$dir/dev/campaigns"
  cp "$ROOT/checks/campaign-ledger-floor.py" "$dir/checks/"
  cp "$ROOT/dev/campaigns"/*.toml "$dir/dev/campaigns/"
  python3 "$dir/checks/campaign-ledger-floor.py" >/dev/null || return 1
  echo "$dir"
}

# arm <label> <expected: RED|GREEN> <fixture-dir> [required substring of the diagnosis]
#
# A RED arm may demand the WORDS, not just the exit code. A check that crashes on a KeyError also
# exits non-zero, so exit-code-only arms cannot tell a diagnosis from a stack trace — and an
# integrity leg whose evidence is "it crashed" tells the reader nothing about what was lost.
arm() {
  local label="$1" expect="$2" dir="$3" want="${4:-}" out rc
  out=$(python3 "$dir/checks/campaign-ledger-floor.py" --check 2>&1)
  rc=$?
  local got=GREEN
  [ "$rc" -ne 0 ] && got=RED
  if [ -n "$want" ] && [ "$got" = RED ] && [[ "$out" != *"$want"* ]]; then
    got="RED(undiagnosed)"
  fi
  if [ "$got" = "$expect" ]; then
    pass=$((pass + 1))
    printf '  %-6s %s\n' "$got" "$label"
  else
    fail=$((fail + 1))
    printf '  %-6s %s  *** expected %s ***\n%s\n' "$got" "$label" "$expect" "$out"
  fi
}

echo "campaign-ledger-floor selftest"

# 1 — THE INCIDENT. 180 rows replaced by a 15-row stranger that is itself perfectly valid TOML.
d=$(fixture truncated) || exit 1
python3 - "$d" <<'PY'
import sys, pathlib
led = pathlib.Path(sys.argv[1]) / "dev/campaigns/drift-repair.toml"
rows = led.read_text(encoding="utf-8").split("[[items]]")
led.write_text(rows[0] + "[[items]]".join(rows[1:16]), encoding="utf-8")
PY
arm "a ledger truncated to a fraction of its rows" RED "$d" "rows fell"

# 2 — the note-only truncation: every row header survives, every note under it is gone. Row count
# alone cannot see this, and the notes are where the campaign's reasoning actually lives.
d=$(fixture notes-stripped) || exit 1
python3 - "$d" <<'PY'
import sys, pathlib
led = pathlib.Path(sys.argv[1]) / "dev/campaigns/drift-repair.toml"
kept = [l for l in led.read_text(encoding="utf-8").splitlines(True) if not l.startswith("#")]
led.write_text("".join(kept), encoding="utf-8")
PY
arm "every row kept, every note deleted" RED "$d" "lines fell"

# 3 — a ledger on disk that no floor entry accounts for. Absence is not a disposition.
d=$(fixture unlisted) || exit 1
cp "$ROOT/dev/campaigns/bug-sweep.toml" "$d/dev/campaigns/zz-unaccounted.toml"
arm "a ledger present on disk with no recorded floor" RED "$d" "no recorded floor"

# 4 — THE BORING CASE: a whole ledger simply gone. A check that iterated its own allowlist and
# measured only what it found would report success over an emptied directory.
d=$(fixture vanished) || exit 1
rm "$d/dev/campaigns/drift-repair.toml"
arm "a recorded ledger deleted outright" RED "$d" "RECORDED BUT GONE"

# 5 — CONTROL: the campaign grew. Rows and notes added, nothing lost. Must stay green, or the
# leg blocks the ordinary act of doing the work it exists to protect.
d=$(fixture grown) || exit 1
{ printf '\n[[items]]\nid = "GROW1"\nphase = "x"\ntitle = "a row added the honest way"\nfiles = ["a"]\nstatus = "todo"\nverify = "true"\n'
  printf '# 2026-09-01 a dated note under it\n'; } >> "$d/dev/campaigns/drift-repair.toml"
arm "a campaign that grew rows and notes" GREEN "$d"

# 6 — CONTROL: the tree exactly as committed.
d=$(fixture pristine) || exit 1
arm "the tree as committed" GREEN "$d"

after=$(sha256sum checks/config/campaign-ledger-floor.json dev/campaigns/*.toml | sha256sum)
if [ "$before" != "$after" ]; then
  fail=$((fail + 1))
  echo "  *** the selftest modified real ledgers — it must be non-destructive ***"
fi

echo "campaign-ledger-floor selftest: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
