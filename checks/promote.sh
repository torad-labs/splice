#!/usr/bin/env bash
# Open the promotion PR (main -> prod). `npm run promote`, or skip this entirely and open the
# same PR in the GitHub UI — the script is convenience, not mechanism.
#
# THE MECHANISM IS GITHUB-NATIVE: merging the main -> prod PR is the promotion. The push that
# merge produces fires release.yml on prod, which derives the version FROM THE PROMOTED CODE
# (bin/splice-launch's SPLICE_GATEWAY_VERSION — the same marker accept.sh pins against the jar),
# re-runs the full gate, builds, attests, and creates the vX.Y.Z tag at the promoted commit when
# the draft release publishes. Nothing local touches prod, and no command "does the release".
#
# Merge the promotion PR with a MERGE COMMIT, never squash: squashing collapses all of main's
# promoted history into one alien commit on prod and breaks the next promotion's diff.
#
# The forgot-to-bump case is guarded twice, both server-side: promotion-check.yml fails the PR
# BEFORE merge when the version's tag already exists, and release.yml's resolve step refuses
# again at build time. The preflight here is a courtesy third look, not the net.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

git fetch -q origin

# Pin the SHA the version is read from — everything below (display, confirmation, push) refers to
# THIS commit, so what the operator confirms is exactly what ships (review of #86: the first-
# promotion push resolved origin/main at push time, after an interactive prompt of unbounded
# duration, so main advancing mid-prompt could silently change what got promoted).
main_sha="$(git rev-parse origin/main)"
version="$(git show "$main_sha:bin/splice-launch" | awk -F'"' '/^SPLICE_GATEWAY_VERSION="/ { print $2; exit }')"
[ -n "$version" ] || { echo "promote: could not read SPLICE_GATEWAY_VERSION from origin/main" >&2; exit 1; }

if git ls-remote --exit-code --tags origin "refs/tags/v${version}" >/dev/null 2>&1; then
  echo "promote: tag v${version} already exists — bump the version on main first (Versions.kt," >&2
  echo "         bin/splice-launch, package.json, package-lock.json move together)." >&2
  exit 1
fi

# prod is created on first use; a PR needs the base ref to exist. Seeding it at main's tip is a
# no-op promotion (same tree, no release fires until the NEXT prod push differs... it does fire —
# a push event is a push event). So seed from the CURRENT PROD-LESS state only via the API ref
# create, which is a push of main's tip and WILL fire release.yml once, releasing v<version>.
# That is the correct first promotion, stated rather than hidden.
if ! git ls-remote --exit-code origin refs/heads/prod >/dev/null 2>&1; then
  echo "promote: prod does not exist yet. Creating it IS the first promotion:"
  echo "         release.yml will fire and publish v${version} from ${main_sha}."
  if [ "${PROMOTE_YES:-0}" != "1" ]; then
    printf 'Type the version to confirm the FIRST promotion (%s): ' "$version"
    # `|| answer=""`: on EOF (non-tty, piped stdin) read fails and set -e would exit SILENTLY
    # before the abort message — turn it into the ordinary mismatch abort instead.
    read -r answer || answer=""
    [ "$answer" = "$version" ] || { echo "promote: aborted (typed '${answer:-<eof>}')" >&2; exit 1; }
  fi
  # Freshness check AT the point of creation: the confirmation prompt is unbounded, so require
  # that main still points at the confirmed SHA before pushing it. The push itself uses the
  # PINNED sha — never a re-resolved ref — so a race can only abort, never promote something the
  # operator did not see.
  git fetch -q origin
  if [ "$(git rev-parse origin/main)" != "$main_sha" ]; then
    echo "promote: main moved while you were confirming (now $(git rev-parse --short origin/main)," >&2
    echo "         confirmed ${main_sha}). Nothing pushed — re-run to promote the new tip." >&2
    exit 1
  fi
  git push origin "$main_sha:refs/heads/prod"
  echo "promote: prod created at ${main_sha} — release.yml is publishing v${version}."
  exit 0
fi

# Everything below needs the GitHub CLI. Guarded EXPLICITLY: under `set -e` a failing command
# substitution kills the script with no message at all — the sandbox test found exactly that
# (a broken gh made promote.sh exit 1 in complete silence).
command -v gh >/dev/null 2>&1 || {
  echo "promote: the GitHub CLI (gh) is required to open the promotion PR — https://cli.github.com" >&2
  exit 1
}
existing="$(gh pr list --base prod --head main --state open --json url --jq '.[0].url // empty')" || {
  echo "promote: gh failed listing PRs — check 'gh auth status'" >&2
  exit 1
}
if [ -n "$existing" ]; then
  echo "promote: a promotion PR is already open — merge it (merge commit, not squash): $existing"
  exit 0
fi

gh pr create --base prod --head main \
  --title "chore(release): promote main to prod (v${version})" \
  --body "$(printf 'Merging this PR IS the promotion: the resulting push to prod fires release.yml, which re-gates, builds, attests, and publishes **v%s** with the tag created at the promoted commit.\n\nMerge with a **merge commit**, not squash.' "$version")"
echo "promote: promotion PR opened — merging it releases v${version}."
