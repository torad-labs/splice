#!/usr/bin/env bash
# checks/gate.sh — the ONE local gate. Runs the SAME checks CI runs and prints a single, unmissable
# GATE: PASS / GATE: FAIL derived from the REAL exit codes.
#
# Never trust a filtered `gradle | grep | tail` exit — a wrapped pipeline's status masked BUILD
# FAILED twice this session (the monitor grepping raw output caught the truth both times). This
# script captures each command's own exit code and refuses to report PASS unless every one is 0.
#
# Run: `npm run gate`  or  `bash checks/gate.sh`
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Splice requires JDK 21 (module law + toolchain). Default to the Homebrew openjdk@21 if the caller
# hasn't pinned JAVA_HOME (CI sets it via setup-java).
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME

fail=0
run() { # run <label> <cmd...> — runs the command, records its REAL exit, never masks it
  echo "── $1 ──"
  if "${@:2}"; then
    echo "  ✓ $1"
  else
    echo "  ✗ $1 (exit $?)"
    fail=1
  fi
}

echo "══ splice gate ══  (JAVA_HOME=$JAVA_HOME)"
run "gradle check"   bash -c 'cd gateway && ./gradlew check'
run "ast-grep walls" npm run --silent gate:rules
run "hook tests"     npm run --silent test:hooks
run "config guard"   bash checks/config-guard.sh

echo
if [ "$fail" -eq 0 ]; then
  echo "GATE: PASS"
else
  echo "GATE: FAIL"
fi
exit "$fail"
