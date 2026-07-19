#!/usr/bin/env bash
set -euo pipefail
bash -n checks/gate.sh
! grep -q "/opt/homebrew/opt/openjdk@21" checks/gate.sh
env -u JAVA_HOME npm run gate
echo "VERIFY OSS-J: OK"
