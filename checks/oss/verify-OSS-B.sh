#!/usr/bin/env bash
set -euo pipefail
grep -q "dangerously-skip-permissions" gateway/control/src/main/kotlin/splice/control/LaunchService.kt
( cd gateway && ./gradlew -q :control:test )
echo "VERIFY OSS-B: OK"
