#!/usr/bin/env bash
set -euo pipefail
# The audit is a network call to registry.npmjs.org's advisory endpoint, which has hung for five
# minutes and then answered 503 mid-gate (PR #122's first run, 2026-09-04). Three attempts, each
# capped at one minute by `timeout`, absorb an outage of that shape in under four minutes; a real
# critical advisory still fails all three and the leg stays red. `bun audit` reads bun.lock — the
# only lockfile since restructure PR 1 deleted package-lock.json — and, like npm's, exits non-zero
# only for an advisory at or above --audit-level.
audit_ok=0
for attempt in 1 2 3; do
  if timeout 60 bun audit --audit-level=critical; then
    audit_ok=1
    break
  fi
  echo "bun audit attempt $attempt failed — retrying in $((attempt * 20))s" >&2
  sleep $((attempt * 20))
done
[ "$audit_ok" = 1 ] || { echo "bun audit failed on three attempts" >&2; exit 1; }
! git ls-files | grep -q "^agents/crystallize-agent/"
test -f .github/dependabot.yml
grep -q "\"engines\"" package.json
echo "VERIFY OSS-I: OK"
