#!/usr/bin/env bash
# The ONE conventional-type list in this repo, and the preflight that enforces it locally.
#
# WHY THIS EXISTS (brain concept #924 — "you make drift not compile").
#
# A PR title with a bad type used to be caught only after the PR existed, by CI. Three structural
# reasons made producing one close to inevitable:
#
#   1. A PR TEMPLATE CANNOT CONSTRAIN A TITLE. GitHub templates populate the BODY only; there is no
#      title template. The one artifact meant to prevent this was incapable of it by construction —
#      and `gh pr create --body ...` never renders it anyway.
#   2. TWO ENFORCERS DISAGREED. This repo shipped .github/workflows/pr-title.yml allowing `release`
#      and `codex`, while the ORG-INJECTED org-pr-title.yml (not present in this repo, so invisible
#      here) rejects both and allows `style`. The repo's own template documented types that could
#      not merge. That workflow is now DELETED: the org gate is the single authority.
#   3. THE NEAREST SIGNAL MISLEADS. Over half of main's recent history uses types that fail the gate
#      (tracker 35, upstream 16, auth 11, walls 6, ...). Inferring the convention from `git log` —
#      the most natural move for a human or an agent — reliably produces an invalid title.
#
# So the list lives HERE, once, and is checked before a PR exists. The PR template deliberately does
# NOT restate it; a second copy is exactly the divergence described in (2).
#
# TYPES mirrors the org gate verbatim. If the org list changes, this is the single line to update.
#
# Usage:
#   bash checks/pr-title.sh "feat(scope): subject"   # validate an explicit title
#   bash checks/pr-title.sh                          # validate HEAD's commit subject (what the
#                                                    # gate runs; squash-merge makes the title the
#                                                    # commit, and they share the same convention)
set -uo pipefail

TYPES='build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test'

title="${1:-$(git log -1 --format=%s 2>/dev/null)}"

if [ -z "$title" ]; then
  echo "pr-title: no title given and no commit to read" >&2
  exit 1
fi

# type(optional-scope)!: subject — the Conventional Commits shape, matched exactly as the org gate
# matches it, so a title that passes here cannot fail there.
if printf '%s' "$title" | grep -qE "^(${TYPES})(\([^)]+\))?!?: .+"; then
  echo "  pr title: valid ($(printf '%s' "$title" | sed -E 's/^([a-z]+).*/\1/')) — ${title}"
  exit 0
fi

cat >&2 <<EOF
pr-title: INVALID conventional type.

  got:      ${title}
  expected: type: subject   (or  type(scope)!: subject )
  types:    ${TYPES//|/, }

This is the list the ORG gate enforces, and it is the only one that counts.
Do NOT infer the convention from \`git log\` — most of this repo's history predates the
gate and uses types (tracker, upstream, auth, walls, ...) that fail it.
EOF
exit 1
