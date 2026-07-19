#!/usr/bin/env bash
set -euo pipefail
for f in SECURITY.md CONTRIBUTING.md CODE_OF_CONDUCT.md .github/PULL_REQUEST_TEMPLATE.md; do test -f "$f"; done
ls .github/ISSUE_TEMPLATE/ | grep -q .
echo "VERIFY OSS-G: OK"
