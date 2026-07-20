#!/usr/bin/env bash
set -euo pipefail
bash -n checks/gate.sh
! grep -q "/opt/homebrew/opt/openjdk@21" checks/gate.sh
resolved="$(env -u JAVA_HOME bash checks/gate.sh --java-home-only)"
test -n "$resolved"
test -x "$resolved/bin/java"
test "$("$resolved/bin/java" -version 2>&1 | awk -F'"' '/ version "/ { print $2; exit }' | cut -d. -f1)" = 21
echo "VERIFY OSS-J: OK"
