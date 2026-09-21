#!/usr/bin/env bash
set -euo pipefail
grep -q "dangerously-skip-permissions" daemon/control/src/main/kotlin/splice/control/LaunchService.kt
# NOT -q. splice.kotlin-common.gradle.kts configures testLogging { events("failed") } with FULL
# exception format precisely so a CI-only failure carries its assertion message, and -q sits BELOW
# the lifecycle level those events print at, so it suppressed the one output that config exists to
# produce: this leg has been reporting "287 tests completed, 1 failed" with no name attached. A
# green run stays quiet either way, because only failed events are logged.
# THROUGH THE SLOT, never ./gradlew: `npm run gate` holds the slot only for its clean-check leg
# (checks/gate.sh:84) and releases it long before this ladder runs, so a bare call here can meet
# another seat's gradle in the same project dir — the race gradle-slot.sh exists to close. The
# script supplies --offline (off in CI) and --no-daemon, and wraps buildgate when the box has it.
bash checks/gradle-slot.sh oss-b :daemon-control:test
echo "VERIFY OSS-B: OK"
