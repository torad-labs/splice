# Contributing to splice

## Prerequisites

- Node 24
- JDK 21, anywhere Gradle's toolchain detection looks (`~/.jdks`, `~/.gradle/jdks`, `~/.sdkman`, `~/.asdf`,
  `~/Library/Java/JavaVirtualMachines` where IntelliJ downloads on macOS, `/usr/lib/jvm` and `/usr/lib64/jvm`, `/usr/java`,
  `/usr/local/java`, `/opt/java`, and `/Library/Java/JavaVirtualMachines`, where `brew install --cask temurin@21` puts it;
  `GRADLE_USER_HOME`, `SDKMAN_CANDIDATES_DIR` and `ASDF_DATA_DIR` move those folders as they do for Gradle), on your PATH, or
  named in `~/.gradle/gradle.properties` as
  `org.gradle.java.installations.paths=/path/to/jdk-21`. The gate also searches `~/jdks`, but a plain `./gradlew` sees a JDK
  kept there only through that pin. A JRE 21 is not enough: the build and the gate need `bin/javac` beside `bin/java`.
  Your default `java` may be newer: the build compiles and tests on the 21 (`jvmToolchain(21)`), and the gate, the hooks'
  linters and the release launcher resolve the same 21 by themselves; `bun tools/gate run --java-home-only` prints which one.
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
title**. A PR lands on `main` as one squashed commit, because `main` requires a linear history, and
GitHub takes that commit's subject from the PR title or from a branch commit. This repo used
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
against the same vocabulary when it runs locally. The squash can take its subject from either source,
so a branch commit with a type the vocabulary rejects can reach `main` even when the PR title passed.

## Working and landing on the shared branch

All seats work in one checkout on the same branch (the one the project's lead names; no seat opens its own
feature branch or worktree, and there is no standing `dev` branch). Coordinate ownership by file and preserve
every other seat's work.

1. Commit only your own files by explicit path: `git commit -m "docs(scope): subject" -- <paths>`.
   For new files, add only those paths and commit them in the same command. The index is shared,
   so leave nothing staged. Never use `git add -A`, `git commit -a`, stash, reset or switch the
   shared branch.
2. Any seat pushes the shared branch and opens the PR from it to `main`; there is no single driver.
3. The required checks on `main` are `gate`, `codeql-java-kotlin`, `codeql-javascript-typescript` and
   `dependency-review`. Local gate runs are feedback, not the verdict: the `gate` job must pass on the
   head being landed. A red check is fixed forward with a commit and another run.
4. Land with a squash merge: `main` requires a linear history, which rules out a merge commit. The commit
   that results is the SHA that gets installed.
5. Install the jar CI built: download the `splice-jar` artifact of the green `ci` run on that SHA
   (`gh run download <run> -n splice-jar`). When CI cannot run, build that SHA in a throwaway tree under
   `/tmp` and delete the tree once the install is verified. Never build an install from the shared
   checkout, and never keep a second checkout of any kind (worktree, clone, archive or copy) beyond that.

`main` reaches `prod` as described under [Releasing](#releasing).

## No CLA

Contributions are made under the project's [MIT license](../LICENSE) — MIT in, MIT out. No
contributor license agreement is required.

## Versioning

SemVer, currently pre-1.0 (`0.x`) — breaking changes may land on a minor bump until 1.0.0.
The root `package.json` version field is the build's version: Gradle reads it for every module, and the release
gate refuses a tag that is not `v` plus it. It moves in the version bump with the other two sites (Releasing, step 1).

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

The `prod` branch is the release line, and **merging the `main -> prod` PR starts the release**.
GitHub builds and attests it as a draft; the maintainer's machine then puts the files on
splice.torad.ai/download and publishes the draft, which creates the tag. Nothing is attached to the
GitHub release itself, only its notes:

1. Land a version-bump PR on `main` (all three sites move together: `Versions.kt`
   `GATEWAY_VERSION`, `app/src/main/dist/bin/splice-launch` `SPLICE_GATEWAY_VERSION`, `package.json`), with
   the `CHANGELOG.md` cut for the release: a `## splice vX.Y.Z: theme - date` section. The gate
   fails a bump without one (`tools/release/test/changelog.test.ts`), and that section is the body
   of the GitHub release (`bun tools/release notes`). GitHub refuses a body over 125,000 characters, so a section
   too long to fit with room for the generated PR list is cut at its `### Added` heading and ends with a link to the
   full section; the tool, and so the gate, fails when even the cut does not fit.
2. Open the promotion PR, base `prod`, head `main` — in the UI, or `npm run promote` which
   opens the same PR after a courtesy version preflight. `promotion-check` fails the PR before
   merge if the promoted version is already tagged (a promotion that would release nothing).
3. **Merge it with a merge commit** (never squash: squashing collapses main's promoted history
   into one alien commit on prod; `prod` has no linear-history rule, so GitHub allows it). The
   resulting push fires `release.yml` on `prod`: full gate, build, `release accept`, attestations, and a
   draft GitHub release that carries the `CHANGELOG.md` notes and no file. The version is derived from
   the promoted code, so the tag can never disagree with what shipped.
4. Once that run is green, publish it from the maintainer's machine:

   ```bash
   bun tools/release publish X.Y.Z --run <release.yml run id>
   ```

   The run id is `gh run list --workflow release.yml --branch prod --limit 1 --json databaseId --jq '.[0].databaseId'`.
   The command downloads the run's accepted bundle, refuses any file whose `gh attestation verify` does not
   bind it to `release.yml`, uploads every file to splice.torad.ai/download (R2 through the S3 API, with a
   bucket-scoped token from the keyring, never one in the shell or the repository), writes `releases.json` last,
   and only then runs `gh release edit vX.Y.Z --draft=false`, which creates the `vX.Y.Z` tag at the promoted
   commit. `install.sh` and `splice upgrade` read from that site, not from GitHub.

### Betas

A beta (`X.Y.Z-beta.N`) is cut from `origin/main` on the maintainer's machine, outside `release.yml`:
`bun tools/release beta X.Y.Z-beta.N`. It needs the same three-site version bump and `CHANGELOG.md`
section on `main` first, builds the jar and bundle in a throwaway tree under `/tmp`, runs `release accept`,
publishes the files on splice.torad.ai/download and creates a GitHub prerelease with the notes alone. It
carries no attestation, so `install.sh` run with a signed-in `gh` refuses it; the notes say so and give the
install command for a signed-out `gh`.
