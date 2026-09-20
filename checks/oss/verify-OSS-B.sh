#!/usr/bin/env bash
set -euo pipefail
grep -q "dangerously-skip-permissions" gateway/control/src/main/kotlin/splice/control/LaunchService.kt
# NOT -q. splice.kotlin-common.gradle.kts configures testLogging { events("failed") } with FULL
# exception format precisely so a CI-only failure carries its assertion message, and -q sits BELOW
# the lifecycle level those events print at, so it suppressed the one output that config exists to
# produce: this leg has been reporting "287 tests completed, 1 failed" with no name attached. A
# green run stays quiet either way, because only failed events are logged.
( cd gateway && ./gradlew :control:test )
echo "VERIFY OSS-B: OK"
