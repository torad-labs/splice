# History rewrite record and residual-exposure runbook

The canonical `torad-labs/splice` history was rewritten on 2026-07-19 and the
capture paths below are no longer reachable from canonical `main`. This file now
records what was done and what ordinary Git operations cannot remove. Do not run
a second rewrite or force-push as routine cleanup.

## Original exposure

Old commit `4ca99f7` ("cache-replay: capture→dual-replay A/B on the live proxy
path") added:

- `experiments/cache-replay/capture/turn-001.json` … `turn-010.json` — raw
  Anthropic request bodies from a live `claudex` capture, including the
  operator's private global `CLAUDE.md` instructions and `/home/<user>/...`
  filesystem paths.
- `experiments/cache-replay/capture/ab-results.log` — a run log with the same
  `/home/<user>/...` path exposure (a `capture_env=` line naming the
  operator's local job directory).

Scanned for API keys/bearer tokens (`sk-ant-`, `Bearer `, `api_key`) — none
found in either. The exposure was operator identity/path/instruction content,
not credentials. The rewrite removed these 11 files from all canonical refs and
replaced the public `main` lineage. The old GitHub object remains an exposure;
see the residual state below.

## Completed and residual state

- Completed: the canonical `main` and release source lineage no longer contain
  the capture files, and normal clones of current refs do not fetch them.
- Residual: GitHub still serves old commit `4ca99f7bc0b9382d0c21d2cded8cdb59d7a85456`
  through its object API, and raw URLs for the old capture JSON/log have returned
  HTTP 200 after the rewrite.
- Residual: stale pre-rewrite pull-request refs and external clones/forks may
  retain old commits and the historical author email.

Removing GitHub-retained commit/blob/raw-cache and foreign/fork refs is a
**GitHub Support dependency**, not something another force-push can prove. A
support request should include the full old commit SHA and the affected raw
paths above. Keep treating the content as exposed even if those URLs later stop
responding.

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

## Historical rewrite procedure (already executed; do not repeat)

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

## Force-push aftermath

The operator performed the one-time canonical replacement. Pre-rewrite clones,
forks, and pull-request refs therefore diverge from current `main`; stale PRs
must be closed/recreated from the new lineage. Branch protection and force-push
blocking were re-enabled after the replacement. Do not retarget old release tags
or perform another rewrite to chase provider-side caches.

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

Canonical rewrite completed 2026-07-19. Residual GitHub object/cache removal remains external.
