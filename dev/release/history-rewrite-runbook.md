# History rewrite runbook — operator-only, NOT executed

This document is reference material for the human operator. It is a plan,
not a script: nothing in this file has been run. No agent may execute the
git history rewrite or force-push described below (OSS campaign law: agents
never push, never rewrite history, never change GitHub settings).

## Exposure

Commit `4ca99f7` ("cache-replay: capture→dual-replay A/B on the live proxy
path") is reachable from public `main` on `torad-labs/splice`. It added:

- `experiments/cache-replay/capture/turn-001.json` … `turn-010.json` — raw
  Anthropic request bodies from a live `claudex` capture, including the
  operator's private global `CLAUDE.md` instructions and `/home/<user>/...`
  filesystem paths.
- `experiments/cache-replay/capture/ab-results.log` — a run log with the same
  `/home/<user>/...` path exposure (e.g.
  `capture_env=/home/<user>/.claude/jobs/fb8f646c/tmp/cacheab/capture`).

Scanned for API keys/bearer tokens (`sk-ant-`, `Bearer `, `api_key`) — none
found in either. The exposure is operator identity/path/instruction content,
not credentials. OSS-A removes these 11 files from the tip with `git rm`;
they remain in the git object history at `4ca99f7` and in every tree that
references them until a history rewrite is performed.

## What a rewrite CANNOT recall

Rewriting history (filter-repo/BFG below) only rewrites the objects in the
canonical repo. It cannot reach:

- **Existing clones and forks** — anyone who cloned/fetched before the
  rewrite keeps the old objects locally, indefinitely, with no way for the
  operator to force deletion.
- **GitHub's caches** — cached raw blob URLs, PR diff views, and code-search
  index entries can outlive the object being rewritten away, on a timescale
  GitHub controls, not the operator's.
- **Any downstream copy** — CI artifact caches, mirrors, or anything that
  fetched the blob before the rewrite.

Treat the exposure as permanent for practical purposes; a rewrite reduces
future exposure surface, it does not undo past access.

## Rewrite options (operator choice — NOT executed here)

**git filter-repo** (recommended — actively maintained, path-based):

```
git filter-repo --invert-paths \
  --path experiments/cache-replay/capture/turn-001.json \
  --path experiments/cache-replay/capture/turn-002.json \
  --path experiments/cache-replay/capture/turn-003.json \
  --path experiments/cache-replay/capture/turn-004.json \
  --path experiments/cache-replay/capture/turn-005.json \
  --path experiments/cache-replay/capture/turn-006.json \
  --path experiments/cache-replay/capture/turn-007.json \
  --path experiments/cache-replay/capture/turn-008.json \
  --path experiments/cache-replay/capture/turn-009.json \
  --path experiments/cache-replay/capture/turn-010.json \
  --path experiments/cache-replay/capture/ab-results.log
```

Rewrites every commit that touched these paths, including `4ca99f7`.

**BFG Repo-Cleaner** (simpler for a pure "delete these blobs everywhere" job):

```
bfg --delete-files 'turn-0*.json' --delete-files ab-results.log
```

Faster on large histories; less precise than filter-repo about path scoping.

Either tool changes every commit SHA after `4ca99f7`. That is why:

## Force-push is an operator decision

A rewrite is only visible on the remote after a force-push (`git push
--force` to `main`). This campaign's agents never do this (LAW: no push, no
history rewrite, no GitHub settings changes). Force-pushing:

- Invalidates every existing clone/fork's ancestry versus the new `main`.
- Breaks in-flight PRs/branches based on pre-rewrite SHAs.
- Requires coordinating with anyone else with push/fetch access before doing
  it.

The operator decides if/when to run the rewrite plus force-push, weighing
the "cannot recall" caveats above against the value of a clean history.

## .mailmap limits

`.mailmap` (this campaign, repo-root `.mailmap`) only changes how `git log`,
`git shortlog`, and GitHub's UI **display** author identity. It does NOT
rewrite commit objects — the original `<redacted-email>` email
stays in the git object history and in every existing clone, mailmap or not.
Presentation only.

## Credential rotation checklist (pointer)

No live keys/tokens were found in the exposed files (see Exposure above). If
the operator's own audit finds otherwise, or as a general precaution after
any accidental exposure event:

1. Rotate any Anthropic API key that was active during the `4ca99f7` window
   (2026-07-15).
2. Rotate any other secret that could plausibly have transited the same
   proxy path (`experiments/cache-replay/real-ab.sh`, `:3097`/`:3099`).
3. Check provider-side access logs for the rotation window for unexpected
   use.

---

NOT executed. This file is documentation only.
