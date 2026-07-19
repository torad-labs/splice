#!/usr/bin/env bash
set -euo pipefail
! git ls-files | grep -q "capture/turn-"
! git ls-files | grep -q "capture/ab-results"
grep -qE "^\.env" .gitignore
grep -q "\.env\.example" .gitignore
test -f dev/release/history-rewrite-runbook.md
test -f .mailmap
echo "VERIFY OSS-A: OK"
