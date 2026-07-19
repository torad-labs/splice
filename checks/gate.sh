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

# Splice requires JDK 21 (module law + toolchain). Resolve JAVA_HOME so a caller override always
# wins, any other JDK 21 already on PATH is picked up automatically, and the historical
# Apple-Silicon Homebrew default still works where it exists — only fail if none of those exist.
homebrew_prefix="/opt/homebrew/opt"
homebrew_jdk21="${homebrew_prefix}/openjdk@21/libexec/openjdk.jdk/Contents/Home"

path_java_major() { # major version reported by `java` on PATH, or empty if there is none
  command -v java >/dev/null 2>&1 || return 0
  java -version 2>&1 | awk -F'"' '/ version "/ { print $2; exit }' | cut -d. -f1
}

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  : # caller-provided JAVA_HOME wins
elif [ "$(path_java_major)" = "21" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
elif [ -x "${homebrew_jdk21}/bin/java" ]; then
  JAVA_HOME="$homebrew_jdk21"
else
  echo "GATE: FAIL — JDK 21 required, none found (checked \$JAVA_HOME, PATH java, Homebrew openjdk@21)." >&2
  echo "  Install a JDK 21 and re-run, e.g.:" >&2
  echo "    macOS:  brew install openjdk@21" >&2
  echo "    Debian/Ubuntu: sudo apt install openjdk-21-jdk" >&2
  echo "    or download from https://adoptium.net/temurin/releases/?version=21" >&2
  exit 1
fi
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
