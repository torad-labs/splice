#!/usr/bin/env bash
# gradle-slot.sh — ONE gradle at a time per worktree, enforced by a lock instead of by convention.
#
# WHY (2026-09-17, ARCH-AUDIT fix wave): seats probed for a free slot and started gradle, and two
# seats that probed in the same second both saw "free" — two GradleDaemons ran concurrently in one
# project dir (`--no-daemon` still runs a single-use daemon process), clobbering build/ outputs and
# handing each other false reds. A probe cannot close a race; a lock can. Every seat runs gradle
# THROUGH this script and blocks in a queue until the previous run exits.
#
# USAGE: bash checks/gradle-slot.sh <label> <gradle args...>
#   bash checks/gradle-slot.sh V4-108 :app:test --tests 'DaemonStop*'
# The label is written to the holder file so a waiting seat can see WHO holds the slot and since when.
set -euo pipefail
LABEL="${1:?label (row id or seat)}"; shift
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="${GRADLE_SLOT_LOCK:-$ROOT/gateway/.gradle-slot.lock}"
HOLDER="$LOCK.holder"
WAIT="${GRADLE_SLOT_WAIT_S:-3600}"
exec 9>"$LOCK"
if ! flock -w 1 9; then
  echo "gradle-slot: waiting (held by: $(cat "$HOLDER" 2>/dev/null || echo unknown))" >&2
  flock -w "$WAIT" 9 || { echo "gradle-slot: gave up after ${WAIT}s (held by: $(cat "$HOLDER" 2>/dev/null || echo unknown))" >&2; exit 75; }
fi
echo "$LABEL pid=$$ since=$(date -Is)" >"$HOLDER"
trap 'rm -f "$HOLDER"' EXIT
cd "$ROOT/gateway"
echo "gradle-slot: $LABEL holds the slot — gradle busy" >&2
buildgate ./gradlew --offline --no-daemon "$@"
rc=$?
echo "gradle-slot: $LABEL released — gradle free (exit $rc)" >&2
exit $rc
