# Contributing to splice

## Prerequisites

- Node 24
- Java 21 (JDK, e.g. Temurin)
- Python 3 (hook tests)

## The gates

Run before opening a PR — these are the same checks CI runs:

```bash
npm ci
npm run gate              # the complete local/CI gate
npm run gate:rules        # ast-grep walls: tree scan + rule red/green cases
npm run test:hooks        # orchestrator hook test suite
bash checks/config-guard.sh   # rules that guard the rules
cd gateway && ./gradlew check # module-law + detekt + konsist + unit tests (Kotlin gateway)
npm test -w server
npm run lint -w webui && npm test -w webui && npm run build -w webui
npm run oss:verify
```

`npm run gate` (`checks/gate.sh`) runs the complete list: Gradle module-law/detekt/tests,
ast-grep walls, hook tests, config guard, the legacy server suite, webui lint/test/build
with a committed-dist check, staged release acceptance, dependency audit, and every OSS
readiness check. The individual commands are listed only so a contributor can run one in
isolation while iterating. The gateway is a nested Gradle build under `gateway/` with its
own JDK 21 toolchain, not a git submodule.

A green *diff* is not the bar — a green *merge* is.

## Walls doctrine

Write-time policy (ast-grep rules) and the commit gate run the SAME checker twice — read
`.rules/README.md` for the full rule inventory and authoring doctrine before adding or
changing a rule.

## PR title

CI (`.github/workflows/pr-title.yml`) enforces Conventional Commits on the **PR title** — it is the
squash-merge subject, so it becomes `main`'s history verbatim. Only these types pass:

`feat` · `fix` · `docs` · `test` · `build` · `ci` · `chore` · `perf` · `refactor` · `revert` ·
`release` · `codex`

Scope is optional: `fix(walls): …`. Anything else fails the `lint` check, which is a required check,
so the PR cannot merge.

**This list is the whole vocabulary.** Inventing a type that reads well — `harden(walls):`,
`verify(x):`, `style(y):` — fails the check.

**Use an allowed type in your branch commit subject as well.** Only the title is linted, but the
squash subject does not always come from the title: #66 passed with `chore(...)` and landed on `main`
as `verify(...)`, its branch commit subject. Matching both is the only way to guarantee `main`'s
history complies.

## No CLA

Contributions are made under the project's [MIT license](LICENSE) — MIT in, MIT out. No
contributor license agreement is required.

## Versioning

SemVer, currently pre-1.0 (`0.x`) — breaking changes may land on a minor bump until 1.0.0.
The root `package.json` version field itself is owned by a separate dependency-hygiene pass,
not this document.

### What compatibility means here

splice ships as an **application** — one shadow jar plus a launcher — and publishes no artifact
to any registry. The Kotlin `public` surface exists for the internal module graph (`:core`,
`:provider-spi`, the dialects), not for external compiled consumers, so changes to public
data-class shapes (constructor arity, `copy`/`componentN` signatures) are **not** treated as
breaking. Review findings about JVM ABI drift on these types have this standing answer: nothing
outside this repository links against them.

The contracts that ARE stable, and gated as such: the `/v1` Anthropic wire surface (frozen
migration oracle), the `/api/*` payload shapes (`WebuiContractTest`), the state-file names
(`StatePaths` header), and the TOML config keys. Break one of those and a test must move with it.

## Releasing

The `prod` branch is the release line, and **merging the `main -> prod` PR is the release
action** — GitHub does the rest, no local command involved:

1. Land a version-bump PR on `main` (all four sites move together: `Versions.kt`
   `GATEWAY_VERSION`, `bin/splice-launch` `SPLICE_GATEWAY_VERSION`, `package.json`,
   `package-lock.json`), with the `CHANGELOG.md` cut for the release.
2. Open the promotion PR, base `prod`, head `main` — in the UI, or `npm run promote` which
   opens the same PR after a courtesy version preflight. `promotion-check` fails the PR before
   merge if the promoted version is already tagged (a promotion that would release nothing).
3. **Merge it with a merge commit** (never squash: squashing collapses main's promoted history
   into one alien commit on prod). The resulting push fires `release.yml` on `prod`: full gate,
   build, attestations, and the `vX.Y.Z` tag created at the promoted commit when the draft
   release publishes. The version is derived from the promoted code, so the tag can never
   disagree with what shipped.
