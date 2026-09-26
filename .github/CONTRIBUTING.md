# Contributing to splice

## Prerequisites

- Node 24
- Java 21 (JDK, e.g. Temurin)
- Bun 1.4.2 (the `packageManager` pin in package.json; the gate's Bun legs and the arch tests spawn it)

## The gates

Run before opening a PR — these are the same checks CI runs:

```bash
bun install --frozen-lockfile
npm run gate              # the complete local/CI gate
npm run gate:rules        # ast-grep walls, rule routing, config guard, coverage proof
npm run test:hooks        # the write-time wall hook's red-green arms
bun test tools/gate           # the gate CLI's own red-green arms
./gradlew check              # module-law + detekt + konsist + unit tests (Kotlin gateway)
npm run lint -w console && npm test -w console && ./gradlew :console:build
bun tools/gate audit         # the dependency audit
bun tools/release verify     # the release rehearsal: stage, accept, the launcher against the shim
```

`npm run gate` (`bun tools/gate run`: the Gradle ladder of tools/gate/config/ladder.json, then the release rehearsal) runs the complete list: Gradle module-law/detekt/tests,
ast-grep walls, hook tests, campaign walls, config guard, console lint/test (the bundle builds in the gradle tier)
with a committed-dist check, the dependency audit, the release-readiness law, and the staged
release acceptance. The individual commands are listed only so a contributor can run one in
isolation while iterating. The Gradle build is rooted at the repository root with its own
JDK 21 toolchain; its modules live under `gateway/`.

A green *diff* is not the bar — a green *merge* is.

## Walls doctrine

Write-time policy (ast-grep rules) and the commit gate run the SAME checker twice — read
`quality/rules/README.md` for the full rule inventory and authoring doctrine before adding or
changing a rule.

## PR title

The org-injected PR-title gate (check name `title`) enforces Conventional Commits on the **PR
title** — it is the squash-merge subject, so it becomes `main`'s history verbatim. This repo used
to ship `.github/workflows/pr-title.yml`; that workflow is deleted. The allowed types live once,
in `tools/gate/src/lib/conventional.ts`, mirroring the org gate. A second copy is how two types the org gate
rejects survived here after that deletion.

Check a title before opening the PR, the same way the PR template tells you to:

    bun tools/gate title "feat(scope): subject"

Scope is optional: `fix(walls): …`. Anything else fails the org check, which is a required check,
so the PR cannot merge.

**That file is the whole vocabulary.** Inventing a type that reads well — `harden(walls):`,
`verify(x):` — fails the check.

**Use an allowed type in your branch commit subject as well.** Only the title is linted, but the
squash subject does not always come from the title: #66 passed with `chore(...)` and landed on `main`
as `verify(...)`, its branch commit subject. Matching both is the only way to guarantee `main`'s
history complies.

## Landing on a version branch: trains

Work for an upcoming release targets its version branch (`feat/v0.4.0`), not `main`. Each change
keeps its own branch and PR and is reviewed on its own. Maintainers land them in **trains**
rather than one at a time:

1. Branch `train/v<version>-<N>` off the version branch's tip and merge two to six reviewed PRs into
   it with `git merge --no-ff` (subject: `chore(merge): <branch> into the v<version> train`).
2. Push it and open ONE PR against the version branch, titled
   `chore(merge): the v<version> train, part <N> — <what it carries>`.
3. CI's `gate` job on that exact head sha is the verdict; a local `npm run gate` is feedback. If it
   is red, fix forward on the train branch with one commit and let it re-gate; never take the
   train apart.
4. When it is green, fast-forward the version branch to exactly that sha:
   `git push origin <sha>:refs/heads/feat/v<version>`. The member PRs close as merged. Never merge
   after the run: a new merge is a new sha and needs its own gate.
5. Build anything you install or ship from a clean detached worktree of that sha
   (`git worktree add --detach <dir> <sha>`), never from a working tree.
6. Remove what landed. Each member PR's worktree and local branch go when its train lands, and
   whoever lands the train sweeps the rest: a local branch merged into the version branch, or one
   `git cherry` shows nothing left on, goes with its worktree once `git status` is clean and
   nothing runs in it.

Keep trains small: one red PR holds the whole train. A second train branched from the first can
gate at the same time (it contains the first), and whichever passes lands. The version branch
reaches `main` through one PR, and `main` reaches `prod` as described under [Releasing](#releasing).

## No CLA

Contributions are made under the project's [MIT license](../LICENSE) — MIT in, MIT out. No
contributor license agreement is required.

## Versioning

SemVer, currently pre-1.0 (`0.x`) — breaking changes may land on a minor bump until 1.0.0.
The root `package.json` version field itself is owned by a separate dependency-hygiene pass,
not this document.

### What compatibility means here

splice ships as an **application** — one shadow jar plus a launcher — and publishes no artifact
to any registry. The Kotlin `public` surface exists for the internal module graph (`:core`,
`:upstream`, the dialects), not for external compiled consumers, so changes to public
data-class shapes (constructor arity, `copy`/`componentN` signatures) are **not** treated as
breaking. Review findings about JVM ABI drift on these types have this standing answer: nothing
outside this repository links against them.

The contracts that ARE stable, and gated as such: the `/v1` Anthropic wire surface (frozen
migration oracle), the `/api/*` payload shapes (`WebuiContractTest`), the state-file names
(`StatePaths` header), and the TOML config keys. Break one of those and a test must move with it.

## Releasing

The `prod` branch is the release line, and **merging the `main -> prod` PR is the release
action** — GitHub does the rest, no local command involved:

1. Land a version-bump PR on `main` (all three sites move together: `Versions.kt`
   `GATEWAY_VERSION`, `app/src/main/dist/bin/splice-launch` `SPLICE_GATEWAY_VERSION`, `package.json`), with
   the `CHANGELOG.md` cut for the release: a `## splice vX.Y.Z — theme - date` section. The gate
   fails a bump without one (`tools/release/test/changelog.test.ts`), and that section is the body
   of the GitHub release (`bun tools/release notes`).
2. Open the promotion PR, base `prod`, head `main` — in the UI, or `npm run promote` which
   opens the same PR after a courtesy version preflight. `promotion-check` fails the PR before
   merge if the promoted version is already tagged (a promotion that would release nothing).
3. **Merge it with a merge commit** (never squash: squashing collapses main's promoted history
   into one alien commit on prod). The resulting push fires `release.yml` on `prod`: full gate,
   build, attestations, and the `vX.Y.Z` tag created at the promoted commit when the draft
   release publishes. The version is derived from the promoted code, so the tag can never
   disagree with what shipped.
