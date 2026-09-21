#!/usr/bin/env bash
set -euo pipefail
test 0 -eq "$(git ls-files .claude/skills .claude/ledger-diffs .claude/agents .claude/workflows .claude/commands .claude/mcp.json | wc -l)"
git ls-files --error-unmatch .claude/settings.json >/dev/null
grep -q 'tools/gate rules --stdin pretooluse' .claude/settings.json
git ls-files --error-unmatch tools/gate/src/lib/hook.ts >/dev/null
npm run test:hooks
echo "VERIFY OSS-K: OK"
