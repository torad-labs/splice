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
# An EMPTY task list is DID NOT RUN, never PASSED (2026-09-18). `./gradlew` with no task argument
# runs the default task and prints BUILD SUCCESSFUL, so a verify line that lost its tasks to a typo
# came back green having compiled nothing, and the receipt recorded it as a pass. A gate that cannot
# tell "asked to do nothing" from "did everything" is a two-outcome gate; exit 2 is the third.
if [ "$#" -eq 0 ]; then
  echo "gradle-slot: $LABEL asked for NO gradle tasks — refusing to report a pass for a run that does nothing." >&2
  echo "gradle-slot: name the tasks, e.g. bash checks/gradle-slot.sh $LABEL :app:test :app:detekt" >&2
  exit 2
fi
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
# buildgate is this MACHINE's memory-containment wrapper (~/.local/bin/buildgate, a host build-reaper
# artifact), not a repo tool — nothing in the tree provides it. Calling it unconditionally made this
# script exit 127 anywhere it is absent, and since the 2026-09-17 law routes EVERY gradle run through
# here, that took `gradle clean check` and every gradle leg down on CI with `buildgate: command not
# found`. Same `command -v` guard checks/e2e/docker/run.sh:69 already uses for the same binary: the
# containment is a local nicety, the gradle run is the thing under test.
# --offline is a LOCAL nicety and CI is where it becomes a lie. This machine already holds every
# artifact, so refusing the network there keeps a seat's build off a flaky mirror. A runner starts
# from a restored cache that is keyed on the lockfiles and therefore always one dependency bump
# behind the tree: the 2026-09-20 red was `No cached version of org.junit:junit-bom:6.1.3 available
# for offline mode`, on the leg whose verdict IS the gate. Resolving dependencies is part of what
# CI is for, so the flag is dropped exactly where the cache cannot be trusted to be complete.
# Same shape as the buildgate guard above and the same lesson: a path every seat runs through must
# not carry one machine's assumptions (checks/e2e/docker/run.sh:69).
# An `if`, not `[ ... ] && offline=()`: under `set -e` an AND-OR list that short-circuits is only
# exempt while it is not the last command, so that spelling is one edit away from exiting 1 here.
offline=(--offline)
if [ -n "${CI:-}" ]; then
  offline=()
fi
if command -v buildgate >/dev/null; then
  buildgate ./gradlew "${offline[@]}" --no-daemon "$@"
else
  ./gradlew "${offline[@]}" --no-daemon "$@"
fi
rc=$?
echo "gradle-slot: $LABEL released — gradle free (exit $rc)" >&2
exit $rc
