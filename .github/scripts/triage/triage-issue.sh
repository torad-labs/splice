#!/usr/bin/env bash
# Fetch → classify → label a single issue. One code path for the Actions trigger,
# the workflow_dispatch backfill, and local runs:
#
#   GH_REPO=torad-labs/splice .github/scripts/triage/triage-issue.sh 21
#
# The issue number is numeric-validated and pinned through every call; the only
# mutation verb in the whole pipeline is `gh issue edit --add-label`.
set -euo pipefail

n="${1:?usage: triage-issue.sh <issue-number>}"
case "$n" in (*[!0-9]*|'') echo "issue number must be numeric, got: $n" >&2; exit 2 ;; esac

dir="$(cd "$(dirname "$0")" && pwd)"
labels="$(gh issue view "$n" --json title,body,labels | "$dir/classify.sh")"

if [ -z "$labels" ]; then
  echo "issue #$n: no confident labels; leaving needs-triage for a human."
  exit 0
fi
echo "issue #$n: applying $labels"
gh issue edit "$n" --add-label "$labels"
