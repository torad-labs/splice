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
bun tools/gate audit         # the dependency audit
bun tools/release verify     # the release rehearsal: stage, accept, the launcher against the shim
```

`npm run gate` (`bun tools/gate run`: the Gradle ladder of tools/gate/config/ladder.json, then the release rehearsal) runs the complete list: Gradle module-law/detekt/tests,
ast-grep walls, hook tests, config guard, the dependency audit, the release-readiness law, and the staged
release acceptance. The individual commands are listed only so a contributor can run one in
isolation while iterating. The Gradle build is rooted at the repository root with its own
JDK 21 toolchain; its modules live under `core/`, `integrations/`, `features/`, `app/`,
and `quality/`, as mapped in `settings.gradle.kts`.

A green *diff* is not the bar — a green *merge* is.

## Walls doctrine

Write-time policy (ast-grep rules) and the commit gate run the SAME checker twice — read
`quality/rules/README.md` for the full rule inventory and authoring doctrine before adding or
changing a rule.

## PR title

The org-injected PR-title gate (check name `title`) enforces Conventional Commits on the **PR
title**. Landing preserves the branch's commits, never squashes them. This repo used
to ship `.github/workflows/pr-title.yml`; that workflow is deleted. The allowed types live once,
in `tools/gate/src/lib/conventional.ts`, mirroring the org gate. A second copy is how two types the org gate
rejects survived here after that deletion.

Check a title before opening the PR, the same way the PR template tells you to:

    bun tools/gate title "feat(scope): subject"

Scope is optional: `fix(walls): …`. Anything else fails the org check, which is a required check,
so the PR cannot merge.

**That file is the whole vocabulary.** Inventing a type that reads well — `harden(walls):`,
`verify(x):` — fails the check.

**Use an allowed type in every commit subject as well.** The complete gate checks HEAD's subject
against the same vocabulary. Landing preserves those subjects because commits are never squashed.

## Working and landing on the shared branch

All seats work in one checkout on the same release branch (`feat/v0.4.0`), not in separate feature
branches or worktrees. Coordinate ownership by file and preserve every other seat's work.

1. Commit only your own files by explicit path: `git commit -m "docs(scope): subject" -- <paths>`.
   For new files, add only those paths and commit them in the same command. The index is shared,
   so leave nothing staged. Never use `git add -A`, `git commit -a`, stash, reset or switch the
   shared branch.
2. The maintainer pushes the shared branch and keeps one PR from it to `main`.
3. CI's `gate` job must pass on the exact SHA being landed and installed. Local gate runs are
   feedback, not the verdict. A red gate is fixed forward with a commit and another CI run.
4. Land by fast-forward or merge commit, never squash. If a merge creates a new SHA, that SHA
   needs its own passing CI gate before landing or installation.
5. Install the jar CI built: download the `splice-jar` artifact of the green gate run on that SHA
   (`gh run download <run> -n splice-jar`). Never build an install from the shared checkout, and
   never make a second checkout of any kind (worktree, clone, archive or copy), not even to build.

`main` reaches `prod` as described under [Releasing](#releasing).

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
   the `CHANGELOG.md` cut for the release: a `## splice vX.Y.Z: theme - date` section. The gate
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
