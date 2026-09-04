#!/usr/bin/env bash
set -euo pipefail
# The audit is a network call to registry.npmjs.org, and the registry's advisory endpoint has
# hung for five minutes and then answered 503 mid-gate (PR #122's first run, 2026-09-04). Three
# attempts, each capped at one minute of fetch time, absorb an outage of that shape in under four
# minutes; a real critical advisory still fails all three and the leg stays red.
audit_ok=0
for attempt in 1 2 3; do
  if npm audit --audit-level=critical --fetch-timeout=60000; then
    audit_ok=1
    break
  fi
  echo "npm audit attempt $attempt failed — retrying in $((attempt * 20))s" >&2
  sleep $((attempt * 20))
done
[ "$audit_ok" = 1 ] || { echo "npm audit failed on three attempts" >&2; exit 1; }
! git ls-files | grep -q "^agents/crystallize-agent/"
test -f .github/dependabot.yml
grep -q "\"engines\"" package.json
echo "VERIFY OSS-I: OK"
