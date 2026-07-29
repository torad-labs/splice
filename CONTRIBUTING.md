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
`verify(x):`, `style(y):` — fails, and because squash-merge replaces your branch commits with the PR
title, `main`'s history gives no hint that the wider vocabulary was ever rejected. Branch commit
messages are not linted; only the title is.

## No CLA

Contributions are made under the project's [MIT license](LICENSE) — MIT in, MIT out. No
contributor license agreement is required.

## Versioning

SemVer, currently pre-1.0 (`0.x`) — breaking changes may land on a minor bump until 1.0.0.
The root `package.json` version field itself is owned by a separate dependency-hygiene pass,
not this document.
