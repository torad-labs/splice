#!/usr/bin/env bash
set -euo pipefail
test 0 -eq "$(git ls-files .claude/skills .claude/ledger-diffs .claude/agents .claude/workflows .claude/commands .claude/mcp.json | wc -l)"
git ls-files --error-unmatch .claude/settings.json >/dev/null
git ls-files .claude/hooks | grep -q orchestrator.py
npm run test:hooks
echo "VERIFY OSS-K: OK"
