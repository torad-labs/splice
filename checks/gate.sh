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

# Splice requires JDK 21 (module law + toolchain). Resolve JAVA_HOME from the running VM's own
# java.home property rather than OS/package-manager-specific paths. This works for Linux and macOS
# launchers, including /usr/bin/java, without non-portable readlink flags.

java_major() {
  "$1" -version 2>&1 | awk -F'"' '/ version "/ { print $2; exit }' | cut -d. -f1
}

path_java_major() { # major version reported by `java` on PATH, or empty if there is none
  command -v java >/dev/null 2>&1 || return 0
  java_major "$(command -v java)"
}

path_java_home() {
  java -XshowSettings:properties -version 2>&1 |
    awk -F' = ' '/^[[:space:]]*java.home = / { print $2; exit }'
}

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ] &&
  [ "$(java_major "${JAVA_HOME}/bin/java")" = "21" ]; then
  : # caller-provided JAVA_HOME wins
elif [ "$(path_java_major)" = "21" ]; then
  JAVA_HOME="$(path_java_home)"
else
  echo "GATE: FAIL — JDK 21 required, none found (checked \$JAVA_HOME and PATH java)." >&2
  echo "  Install a JDK 21 and re-run, e.g.:" >&2
  echo "    macOS:  brew install openjdk@21" >&2
  echo "    Debian/Ubuntu: sudo apt install openjdk-21-jdk" >&2
  echo "    or download from https://adoptium.net/temurin/releases/?version=21" >&2
  exit 1
fi
export JAVA_HOME

if [ "${1:-}" = "--java-home-only" ]; then
  printf '%s\n' "$JAVA_HOME"
  exit 0
fi

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
run "gradle clean check" bash -c 'cd gateway && ./gradlew clean check'
run "ast-grep walls" npm run --silent gate:rules
run "hook tests"     npm run --silent test:hooks
run "config guard"   bash checks/config-guard.sh
run "server tests"   npm test -w server
run "webui lint"     npm run lint -w webui
run "webui tests"    npm test -w webui
webui_dist_before="$(mktemp)"
if ! cp webui/dist/index.html "$webui_dist_before"; then
  echo "  ✗ webui dist snapshot (committed bundle missing)"
  fail=1
fi
run "webui build"    npm run build -w webui
run "webui dist"     cmp -s "$webui_dist_before" webui/dist/index.html
rm -f "$webui_dist_before"
run "OSS readiness"  bash checks/oss/run.sh

echo
if [ "$fail" -eq 0 ]; then
  echo "GATE: PASS"
else
  echo "GATE: FAIL"
fi
exit "$fail"
