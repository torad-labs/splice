#!/usr/bin/env bash
set -euo pipefail
! grep -q "/opt/homebrew/opt/openjdk@21" tools/gate/src/lib/jdk.ts
resolved="$(env -u JAVA_HOME bun tools/gate run --java-home-only)"
test -n "$resolved"
test -x "$resolved/bin/java"
test "$("$resolved/bin/java" -version 2>&1 | awk -F'"' '/ version "/ { print $2; exit }' | cut -d. -f1)" = 21
test -s gradle/verification-metadata.xml
grep -q '<verify-metadata>true</verify-metadata>' gradle/verification-metadata.xml
grep -q '<sha256 value=' gradle/verification-metadata.xml
# Through the slot (see verify-OSS-B.sh).
bun tools/gate slot oss-j -- -q :core:compileKotlin --dependency-verification=strict
echo "VERIFY OSS-J: OK"
