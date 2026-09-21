#!/usr/bin/env bash
set -euo pipefail
! grep -rnE "= 39[0-9]{3}" gateway --include="*.kt" | grep -q .
test -f daemon/head/src/testFixtures/kotlin/splice/head/TestNet.kt
! grep -rn "Thread.sleep(1100)" gateway --include="*.kt" | grep -q .
echo "VERIFY OSS-M: OK"
