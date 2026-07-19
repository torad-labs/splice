#!/usr/bin/env bash
set -euo pipefail
npm audit --audit-level=critical
! git ls-files | grep -q "^agents/crystallize-agent/"
test -f .github/dependabot.yml
grep -q "\"engines\"" package.json
echo "VERIFY OSS-I: OK"
