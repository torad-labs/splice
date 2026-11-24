# Goal: End-to-End Multi-Head `v0.6.4` -> `0.7.0` Upgrade Rehearsal, CI Upgrade Fix & Compatibility Hardening

Prove with real published `v0.6.4` binaries AND permanent Kotlin multi-head daemon tests that upgrading a live multi-head `0.6.4` Splice installation to `0.7.0` breaks NOTHING — and fix any real regression discovered during the rehearsal.

## Background & Gaps Discovered on `main`
1. **CI's Docker E2E has NEVER rehearsed upgrading from `v0.6.4` (or any 0.4.x–0.6.x release):**
   - `.github/workflows/e2e-docker.yml` and `tools/e2e/docker/run.sh` fetch the "from" release via `gh release download ... -p splice.jar`.
   - Since `v0.4.0` (Oct 31, 2026), published Splice releases host binaries at `https://splice.torad.ai/download/vX.Y.Z/` (`splice.jar`, `splice-launch`, `install.sh`, `sha256sums.txt`, `splice-mcp-holder`, `splice-mcp-front`) rather than GitHub Release assets.
   - As a result, CI has only rehearsed `v0.3.2 -> candidate`, never a modern `0.6.x` multi-head install upgrading to `0.7.0`.
2. **Existing `UpgradeFrom064DaemonTest.kt` only tests a single head (`[heads.realinfer]`) in-process** — there is NO end-to-end test covering a full multi-head, multi-provider `v0.6.4` home upgrading live (daemon handover `shim-27 -> shim-30`, existing wrappers, door routing, `check-config --fix` preserving marker comments, `splice upgrade` + `--aftercare` + `--rollback`, `retire`/`restore`, `/desktop` backward compat).

## Scope & Exact Deliverables

### 1. Fix Release Fetching in `tools/e2e/docker/run.sh` & `.github/workflows/e2e-docker.yml`
- Update `tools/e2e/docker/run.sh` so `--upgrade-from vX.Y.Z` (and `--release vX.Y.Z`) downloads published release artifacts from `https://splice.torad.ai/download/$TAG/` (`splice.jar`, `splice-launch`, `sha256sums.txt`, `install.sh`, plus optional `splice-mcp-holder`/`splice-mcp-front` if listed in `sha256sums.txt`), **verifying SHA-256 checksums against `sha256sums.txt`**, and falls back cleanly to `gh release download` for pre-0.4 releases (like `v0.3.2`) that only live on GitHub Releases.
- Keep `tools/e2e/docker/upgrade.sh` (`v0.3.2` legacy upgrade rehearsal) working without regression.
- Update `.github/workflows/e2e-docker.yml` so release discovery checks `https://splice.torad.ai/download/$tag/sha256sums.txt` in addition to GitHub assets, enabling CI to rehearse modern `0.6.x` releases automatically as well as `v0.3.2`.
  - **Important version-check note:** Do NOT permanently bump `GATEWAY_VERSION` in `Versions.kt`, `package.json`, or `splice-launch` on `main` (stay at `0.6.4` in committed code unless `main` is bumped separately for release). Ensure CI's automatic "newest other release" selection works cleanly both TODAY (while `main` reports `0.6.4`, picking `v0.6.3` or running the new multi-head `0.6.x` scenario cleanly) AND immediately after the `0.7.0` version bump (automatically picking `v0.6.4 -> 0.7.0`)!

### 2. Build & Run a Real Docker E2E Multi-Head `v0.6.4 -> 0.7.0` Upgrade Rehearsal (`tools/e2e/docker/upgrade-064.sh` or scenario in `run.sh`)
Create a dedicated, offline (`--network none`), receipt-producing Docker E2E upgrade scenario for modern `0.6.x` multi-head upgrades (e.g. `tools/e2e/docker/upgrade-064.sh`, invoked via `tools/e2e/docker/run.sh --upgrade-from v0.6.4` when `FROM` is `>= 0.6.0` or via `--scenario upgrade-064 --upgrade-from v0.6.4`).

**What the rehearsal MUST set up using the REAL published `v0.6.4` release (verified from `https://splice.torad.ai/download/v0.6.4/`):**
1. Install real `v0.6.4` with its own `install.sh` (verifying checksums, `shim-27`).
2. Seed a realistic **multi-head, multi-provider `0.6.4` `splice.toml`** that includes:
   - `claudex` (`openai-responses` codex mock)
   - `claude-grok` (`xai` / `grok-oauth` via `mock_subs.ts`)
   - `claude-kimi` (`kimi` / `kimi-oauth` via `mock_subs.ts`)
   - `claude-realinfer` (`openai-chat` custom provider with head-hosted `context_window`, `model_slots = { opus, sonnet, haiku }`, `system_prompt`, `system_prompt_mode = "append"`, `[heads.claude-realinfer.overrides] maxInflight = "3"`, custom `discovery_prefix = "realinfer--"`, wrapped in `# BEGIN team-managed` ... `# END team-managed` marker comments!)
   - A second head sharing the `realinfer` provider (`claude-realinfer-fast`, testing multi-head sibling behavior)
   - Real `v0.6.4` credentials (`grok.json`, `kimi.json`, `keys.toml`, `mgmt-key`), `config.json` knob patch, and a real `0.6.4` Desktop `/desktop` key or lane state if applicable.
3. Boot `v0.6.4` daemon, run real pre-upgrade turns across multiple heads (recording transcripts, session IDs, quota/perf readings, and a saved pre-upgrade running session recipe).
4. **Upgrade live over the running `v0.6.4` daemon** with the candidate build:
   - Test BOTH `install.sh` release-mode upgrade (piped stdin, `file://` base) AND `v0.6.4`'s own `splice upgrade` handover path if feasible in the offline container (e.g. loopback HTTP release server serving the candidate artifacts + `sha256sums.txt`, exercising `splice upgrade`, `--aftercare`, and `splice upgrade --rollback` back to `v0.6.4` and forward again!).
   - **Mandatory live `v0.6.4 -> 0.7.0` proof during this campaign:** During verification, build a temporary scratch candidate jar + shim bumped to `0.7.0-e2e` (NEVER commit the version bump!) and execute the FULL Docker E2E rehearsal from real published **`v0.6.4` -> `0.7.0-e2e`** to completion with `PASS` receipt — proving `shim-27 -> shim-30` live handover against the actual `v0.6.4` release! Also verify `v0.6.3 -> 0.6.4` (unbumped checkout) passes cleanly in Docker E2E so GitHub CI is 100% green right now!
5. **Assert every critical `0.6.4 -> 0.7.0` multi-head invariant post-upgrade:**
   - Every pre-upgrade config & credential file (`splice.toml`, `keys.toml`, `auth/*`, `mgmt-key`, transcripts) is byte-identical after install.
   - `splice check-config` exits `0` (warnings naming keys only, never values; zero boot refusals).
   - Live stale daemon (`v0.6.4`) is replaced cleanly on handover by the candidate (`shim-30`, `/health` version & readiness verified).
   - Every existing multi-head wrapper (`claudex`, `claude-grok`, `claude-kimi`, `claude-realinfer`, `claude-realinfer-fast`) still exists (nothing auto-retired!) and completes real turns after the upgrade.
   - Saved pre-upgrade running session recipe still completes a turn; pre-upgrade transcript resumes via `--resume <SID>`.
   - Door launch (`splice launch claude`) & `/door/v1/messages` + legacy `/desktop/v1/messages` route across ALL providers, serve old prefixes (`realinfer--...`), map `model_slots` tiers (`opus`, `sonnet`, `haiku`), and apply head fallback standing prompt & window.
   - `splice check-config --fix` migrates head-held provider settings cleanly while **preserving surrounding `# BEGIN team-managed` / `# END team-managed` marker comments**, leaves every head working afterward, and produces a clean subsequent `splice check-config`.
   - `splice claude retire <head>` + `splice claude restore` round-trip restores links & head settings cleanly.
   - `splice doctor` reports `0` failures (`✗`).
   - `splice status` lists daemon running and all heads cleanly.

### 3. Permanent Kotlin Multi-Head `0.6.4 -> 0.7.0` Daemon Integration Test
Add `app/src/test/kotlin/splice/app/desktop/UpgradeFrom064MultiHeadDaemonTest.kt` (running in every `./gradlew :app:test` without needing Docker) exercising:
- Full multi-provider, multi-head `0.6.4` `splice.toml` (`codex`/`openai-responses` + `openai-chat` + `anthropic-passthrough`, 4+ heads including two sibling heads sharing one provider, marker comments around head blocks, overrides, standing prompts, `model_slots`, custom `discovery_prefix`).
- `ConfigFindings.read` -> `Ready` with expected warnings (0 refusals, 0 leaked values).
- Live `Daemon` boot serving BOTH every head's own port AND `/door/v1/messages` AND `/desktop/v1/messages` across all providers.
- Running `ConfigFixOffer` / `check-config --fix` on the multi-head file -> verifies marker comments preserved byte-for-byte, rewritten TOML still boots cleanly, and both door and heads keep serving identically.
- `HeadBackup` (`retire` + `restore`) round-trip on multi-head setups.

### 4. Fix Any Real Breakage Found!
If running the live `v0.6.4` multi-head -> `0.7.0` rehearsal uncovers ANY real bug (in `install.sh`, `splice-launch`, `UpgradeDaemon`, `UpgradeAftercare`, `HeadFallbacks`, `ConfigFixOffer`, `InstallLinker`, `DoorRouting`, `ClaudeLane`, `Desktop`, etc.), **fix the root cause in this PR** and cover it with a permanent regression test!

## Verification & Quality Gates (Mandatory)
- **Cross-lab adversarial verification required** (builder and verifier from different model labs).
- **Run Docker E2E live on this machine:**
  1. Real `v0.6.4 -> 0.7.0-e2e` (using temporary un-committed version-bumped scratch jar+shim) — capture exact receipt summary in PR description!
  2. Unbumped checkout `v0.6.3 -> 0.6.4` (and verify `v0.3.2` legacy upgrade script wasn't broken) — so GitHub `e2e-docker` CI is verified green before push!
  3. Clean up any temporary scratch files and uncommitted receipts before commit (do NOT commit huge `tools/e2e/receipts/` artifacts).
- **Run Kotlin Gradle checks on Mac** (`on-mac ./gradlew :core:check :integrations-topology:check :features-launch:check :features-lifecycle:check :app:detekt :app:ktlintCheck :app:test --tests "*UpgradeFrom064*" --tests "*ConfigPreflight*" --tests "*UpgradeAftercare*" :quality-architecture:test`). NEVER run `./gradlew` locally on Linux.
- Run all repo gates:
  - `bun tools/gate module-law`
  - `bun tools/gate rules`
  - `bun tools/gate attribution`
  - `bun tools/gate title "<pr-title>"`
  - `gitleaks git . --log-opts="origin/main..HEAD" --redact`
- **Hard laws:**
  - `git diff --name-only origin/main...HEAD -- quality/architecture/src/test/resources/` MUST be completely empty (0 baseline edits!).
  - Do NOT commit a `0.7.0` version bump to `Versions.kt`, `package.json`, or `splice-launch` (keep `main` at `0.6.4` until release cut).
  - Conventional Commits on every commit subject and PR title; zero AI/model attribution anywhere.
  - Pass `PublicSourceNamesNoHostToolTest` and `ReleaseReadinessLawTest` (zero forbidden strings or disclaimers).
  - Never touch live `~/.claude`, `~/.gemini`, or running `spliced`.
