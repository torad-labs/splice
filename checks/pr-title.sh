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

title="${1:-}"

if [ -z "$title" ]; then
  # The no-arg mode judges HEAD's subject — which is only meaningful LOCALLY, before a PR exists.
  # In Actions there are exactly two contexts, and neither has an authored subject worth judging
  # (both failures below were SHIPPED on this very branch, not hypothesized):
  #
  #   - a PR run checks out the synthetic merge ref, subject "Merge <sha> into <sha>" — and no
  #     git-side merge detection can even fire there, because actions/checkout's default
  #     fetch-depth:1 grafts the tip PARENTLESS (verified against a --depth 1 clone: `rev-list
  #     --parents` prints one word and `%P` is empty). The ORG title gate validates the real PR
  #     title on that event; this script cannot and need not.
  #   - a push-to-main run sees the just-landed SQUASH commit. GitHub derives a single-commit
  #     PR's squash subject from the COMMIT, not the validated PR title (how "harden(ci): ..."
  #     landed on main from #83 after its title was fixed), so failing here is retroactive noise
  #     about history nobody can amend.
  #
  # So: in Actions, the no-arg mode always defers to the org gate. An EXPLICIT title argument is
  # still validated anywhere, CI included.
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    echo "  pr title: CI run, skipped (the org gate validates the PR title; this check is the local preflight)"
    exit 0
  fi
  # Local merge tips carry no authored subject either. `--parents` prints "<sha> <parent>...";
  # >2 words = 2+ parents. NB: adding `--count` silently defeats it — it replaces the output
  # with a bare "1" (shipped that way once).
  if [ "$(git rev-list --parents -n1 HEAD 2>/dev/null | wc -w)" -gt 2 ]; then
    echo "  pr title: merge commit, skipped (nothing authored to judge)"
    exit 0
  fi
  title="$(git log -1 --format=%s 2>/dev/null)"
fi

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
