#!/usr/bin/env bash
# Promote main -> prod: the one release action. `npm run promote`.
#
# The prod branch is the release line (fleet's pattern): pushing it fires release.yml, which
# derives the version FROM THE PROMOTED CODE (bin/splice-launch's SPLICE_GATEWAY_VERSION — the
# same marker accept.sh pins against the jar), gates, builds, attests, and creates the vX.Y.Z tag
# at the promoted commit when the draft publishes. The tag is an ARTIFACT of promotion, never a
# second manual pointer that can disagree with it.
#
# This script promotes the REMOTE main, not the local checkout — a promotion must never depend on
# (or accidentally include) local state. It refuses when:
#   - prod exists but is not an ancestor of main (someone committed to prod directly; prod is a
#     pure fast-forward of main by construction, so a diverged prod is a broken invariant, not a
#     merge problem to paper over);
#   - the promoted version's tag already exists (promotion without a version bump releases
#     nothing — bump on main first, like the v0.2.0 PR).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

git fetch -q origin

main_sha="$(git rev-parse origin/main)"
version="$(git show origin/main:bin/splice-launch | awk -F'"' '/^SPLICE_GATEWAY_VERSION="/ { print $2; exit }')"
[ -n "$version" ] || { echo "promote: could not read SPLICE_GATEWAY_VERSION from origin/main" >&2; exit 1; }

if git ls-remote --exit-code --tags origin "refs/tags/v${version}" >/dev/null 2>&1; then
  echo "promote: tag v${version} already exists — bump the version on main first (Versions.kt," >&2
  echo "         bin/splice-launch, package.json, package-lock.json move together)." >&2
  exit 1
fi

if git ls-remote --exit-code origin refs/heads/prod >/dev/null 2>&1; then
  prod_sha="$(git ls-remote origin refs/heads/prod | cut -f1)"
  if ! git merge-base --is-ancestor "$prod_sha" "$main_sha"; then
    echo "promote: prod ($prod_sha) is not an ancestor of main — it has diverged." >&2
    echo "         prod is a pure fast-forward of main; inspect how it diverged before forcing anything." >&2
    exit 1
  fi
  span="$(git rev-list --count "$prod_sha".."$main_sha")"
else
  prod_sha="(new branch)"
  span="all history"
fi

echo "promote: main -> prod"
echo "  releases:  v${version}"
echo "  commit:    ${main_sha}"
echo "  prod was:  ${prod_sha}  (${span} commits promoted)"
if [ "${PROMOTE_YES:-0}" != "1" ]; then
  printf 'Type the version to confirm (%s): ' "$version"
  read -r answer
  [ "$answer" = "$version" ] || { echo "promote: aborted (typed '$answer')" >&2; exit 1; }
fi

git push origin "$main_sha:refs/heads/prod"
echo "promote: pushed — release.yml is now gating, building, attesting, and publishing v${version}."
echo "         Watch it: gh run watch \$(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
