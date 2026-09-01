# Changelog

## splice v0.3.0-beta.1 — native Claude auth and a hardened multi-head gateway - 2026-08-30

### Added

- **`claude-splice`, the native-auth Claude head.** Claude Code keeps its own Anthropic login and
  sends the caller credential through the local passthrough head; splice never stores, reads,
  refreshes, or logs that credential. The management key is not reused on this route.
- **Per-model context windows in the live model picker.** Each configured row reports its effective
  window without spelling a model above its real backend ceiling, so `/model` can switch windows
  without restarting Claude Code.
- Provider OAuth sign-in plans for ChatGPT, Grok, and Kimi now resolve from the configured auth kind,
  with deterministic matrix coverage for every supported head.

### Changed

- Provider-native readable reasoning remains visible as thinking blocks, while
  `mirror_reasoning` is locked off after every configuration layer. TOML, state, environment, runtime
  PATCH, and direct construction cannot enable synthetic transcript reinjection.
- The release pipeline now accepts SemVer prerelease tags and marks versions containing `-` as GitHub
  prereleases. Beta installs use a version-pinned URL; the stable `latest` installer remains stable-only.

### Fixed

- **Codex compaction no longer dies at the idle cap while the model is still reading.** The stall
  watchdog switched to its short `streamIdleMs` tier on the first upstream byte, and on the
  Responses API that byte is the `response.created` handshake, not output — so a compaction that
  reasoned silently over a large transcript for longer than 180s was aborted and re-sent cold by the
  client, in a loop (109 stalls on one head in a single day). The tier now follows the first client
  content frame: until the client has seen output the silence is judged on `firstByteTimeoutMs`,
  after it on `streamIdleMs`, on both the SSE and WebSocket transports. A compact turn's pre-output
  silence is bounded by `upstreamTimeoutMs` alone (compactions on the corrected tier still
  died silent at the 300s cap). The stall message names the tier that actually fired.
- **A content-policy refusal is terminal, not retried.** ChatGPT's `cyber_policy` flag (and the
  Responses API's documented prompt refusals: `invalid_prompt`, `bio_policy`,
  `image_content_policy_violation`) reached Claude Code as a retryable `api_error`, so every refusal
  became a backoff storm of the same multi-megabyte transcript. They now surface as
  `invalid_request_error` with the vendor's own remedy text, matching the HTTP 400 the vendor returns
  for the same refusal pre-stream.
- Refresh failures for Codex, Grok, and Kimi no longer risk logging vendor response bodies, and
  KeyStore values containing `#`, quotes, or backslashes round-trip without corruption.
- Request-body torn wakeups become an Anthropic-shaped HTTP 400 without swallowing genuine coroutine
  cancellation; chat and Responses stream translators also stop draining runaway producers.
- A newly created Responses WebSocket can no longer evict itself while older pooled sockets are busy.
- Session-registry migration now preflights destination collisions, rolls back earlier transfers after
  a later failure, preserves stale links when replacement fails, and retains cross-filesystem support.
- OAuth callback paste handling no longer double-encodes URLs, and stopped auth-probe loops cannot
  restart themselves after shutdown.
- Repeated statusline ticks reuse a bounded branch cache instead of spawning an uncached Git process
  every time.
- The release gate now rejects invalid SemVer tags and a mutated prerelease flag; the concentration
  gate rejects masked commands and contradictory ratchet modes; the head-E2E gate rejects unmatched
  head selectors and duplicate stream terminals. All previously reported false green.

### Security

- Client-auth providers reject configured `Authorization` and `x-api-key` headers case-insensitively,
  preventing a splice-held upstream credential from sharing the local management-gate bypass.
- OAuth wrapper overrides are restricted to portable command names; paths, shell syntax, whitespace,
  blank names, and option-like names are rejected before launch.

## splice v0.2.0 — reasoning continuity, the cache-drain fix, and every-head login - 2026-08-02

### Changed — BREAKING

- The OpenRouter head's wrapper command is renamed `claudeor` -> `claude-openrouter`, matching
  `claude-grok` / `claude-kimi`. **Existing installs keep a stale `claudeor` symlink**: `install
  --all` links the topology's commands but never prunes one whose name disappeared, so the old
  wrapper survives and resolves to no head. Remove it once: `rm ~/.local/bin/claudeor`.

### Added

- **Gateway-held reasoning cache** (codex provider, default on): each turn's
  `reasoning.encrypted_content` envelopes are held in memory per conversation and replayed on tool
  round-trips, restoring the reasoning continuity the codex CLI gets natively. Retention is
  activity-based with hard caps (256 rounds / 64 MB across a head, whole-conversation eviction);
  the envelopes are opaque ciphertext, never written to disk, and scoped to their conversation so
  concurrent sessions can never receive each other's. `quirks = { reasoning_cache = false }`
  disables it. Documented in SECURITY.md.
- **Deferred tool surface**: responses-dialect heads advertise a small eager slice of the tool
  surface and defer the rest behind a gateway-answered `tool_search` — the model asks, the gateway
  answers from the deferred inventory, and the continuation round is invisible to the client. Cuts
  tens of KB from every upstream request; across a full daemon log, well under 1% of turns needed a
  search round.
- **Responses WebSocket transport** (opt-in, `websocket = true`, default off): rides the upstream
  v2 WebSocket with `previous_response_id` chaining, sending per-round deltas instead of the full
  replay. Cuts wire bytes and prefix drift. Measured NOT to reduce billed input tokens — the
  receipt (`gateway/spikes/results/responses-websocket.md`) is explicit — so this is a latency and
  robustness lever, not a quota one.
- **Mid-stream re-anchoring**: a turn torn by a provider brownout after frames were already
  forwarded is re-anchored upstream and continued instead of failed.
- **Turn-scoped summary dedup**, and continuation rounds no longer re-request reasoning summaries:
  detailed reasoning on every round with zero duplicated summary text.
- **Loop guard**: a circuit breaker for the identical-failed-tool-call pathology, which previously
  burned rounds repeating a call that could never succeed.
- **API-key store and token capture** for api-key heads: `splice key`, and paste-to-store during
  `/login` for providers whose token shape splice knows (today: OpenRouter).
- `/login` now reports its outcome back INTO the session. The sign-in runs detached, so everything
  it printed was lost and the session never learned whether it worked; it writes a one-line receipt
  that the head's `/login` hook reads and consumes on the next prompt. This is the only channel
  that can confirm a kimi login at all — an RFC 8628 device flow has no browser redirect to render
  a page in, which is why opencode and Kilo Code both confirm in-client rather than via a callback.
  Failures are reported too, which is the case that previously said nothing at all.
- The browser login accepts a PASTED redirect URL (or a bare code) on stdin, racing the loopback
  callback. A loopback can simply never arrive — browser on another machine, SSH, a container
  without shared localhost — and the only outcome was a silent five-minute timeout. xAI's own CLI
  accepts both channels for this reason.

### Fixed

- **The prompt-cache drain.** The reasoning cache expired envelopes on ACTIVE conversations, which
  rewrote the replayed prompt prefix mid-conversation and invalidated the provider's prefix cache
  turn after turn — measured at 350,920,932 wasted input tokens across 7,056 turns in one window,
  a 66.6% hit rate against 98.0% (grok) and 96.3% (kimi) on the same daemon
  (`gateway/spikes/results/prompt-cache-drain.md`). Envelopes now expire wholesale on idle
  conversations only; the measured hit rate recovered to ~90%.
- responses-lite turns send `tool_choice=auto` — fixes broken tool-calling on gpt-5.6.
- `tool_search_call.arguments` is emitted as a JSON object, not a string.
- Catalog membership recognizes `[1m]`-suffixed models — unbreaks kimi k3.
- The paste-capture hook is installed ONLY while an api-key head's key is missing. On a configured
  head it was pure downside: a bare `sk-or-…` message was swallowed and stored, silently
  overwriting a working credential, and the message never reached the model — so merely discussing
  a key by pasting one broke the session's auth. The key-missing advertiser was already gated this
  way; the hook that acts on the paste was not.
- `install.sh` now detects a stale `claudeor` symlink left by the rename and prints the one command
  that clears it. It does not delete anything: that bin dir holds links splice did not create. The
  notice is scoped to a symlink pointing at splice's own launch shim, so a user's unrelated
  `claudeor` script is never mentioned.
- The OAuth callback page said "close this tab and head back to your terminal", but `/login` is
  usually invoked from inside a session where there is no terminal to return to. It now names the
  destination, matching what xAI's CLI does ("You can close this window and return to Grok Build").
- `/login` on an api-key head promised "a masked terminal prompt is asking for your key" while
  spawning `<command> login` DETACHED with output to `/dev/null`. Detached means no TTY, so
  `System.console()` was null, the CLI printed its pipe-instead hint into the void, and the
  promised prompt could never appear — the user waited on nothing. A head that can capture a
  pasted token is now told the path that actually works (paste it as a message; splice stores it
  and blocks it before it reaches the model), the residual is stated plainly (the session log on
  disk still records the pasted line), and nothing is spawned.
- `/login` still works for EVERY head — each has its own sign-in path, and being in the topology
  is what makes it supported. Only the wording differs: a head whose token shape splice knows
  gets the in-session paste path; one it does not gets pointed at `<command> login` in a terminal,
  with the reason stated. Capture patterns stay deliberately one-provider-at-a-time (today:
  OpenRouter) — that scoping applies to CAPTURE, never to whether `/login` exists.
- The frozen migration oracle's `--check` mode never compared against the committed fixtures, so
  no behaviour drift could fail it despite being wired as a verification gate. It now diffs fixture
  bytes, the vendored mock's checksum, and the scenario roster in both directions.
- Three ast-grep walls were narrower than their own messages claimed: the cancellation wall
  accepted a type check without the rethrow it demands; the `pkill` wall fired on unrelated string
  concatenation in exec arguments; the silent-`Result`-collapse wall missed `var` bindings.

### Security

- **Wall grants are signed.** The write-time gate that protects `.rules/`, `.claude/hooks/`,
  `.claude/settings.json` and `sgconfig.yml` trusted `.claude/state/walls-grant.json` on sight,
  and that path was not itself walled — so an assistant could write its own grant record and open
  every wall in a single tool call, leaving no git trace (the file is gitignored). "Operator-only
  by construction" held for *issuing* a grant and not for the record the gate *trusts*, which is
  the half that matters.

  Grants are now HMAC-SHA256 signed and the signature is verified before the expiry field is read.
  The key lives outside the repo (`~/.local/state/splice/walls-grant.key`, mode 0600) and is
  created only by `dev/walls-grant/install.sh` — never by the gate, since a verifier that can mint
  its own key proves nothing. The grant record is refused as a tool-write target unconditionally,
  so a grant cannot extend itself. Grants may now be scoped to specific wall paths, and the issuing
  session id is part of the signed payload, so the audit record says who opened a wall and from
  where rather than only when it expires. Every failure mode — missing key, bad signature, expired,
  out of scope — leaves the wall shut.

  Known limitation, deliberately recorded rather than implied away: `Bash` tool calls do not pass
  through the write-time hook, so a key readable by the same process it defends raises the bar
  without sealing it. See the header of `.claude/hooks/lib/walls_grant.py`.

- The `/grant` installer no longer reports a repo carrying the pre-signature gate as already
  installed; it refuses loudly rather than silently leaving a forgeable gate in place. Re-running
  it also no longer revokes an active grant out from under the operator.
- Closed every open CodeQL alert: a measured ReDoS, an unescaped OAuth-callback echo, error
  de-leaking, and the legacy Node log-tail endpoints reflecting exception text — errno plus the
  absolute host path — back to clients. Detail goes to stderr, a fixed string to the wire; the
  Kotlin gateway's own log endpoint was never affected.
- Dependency floors: netty 4.2.16.Final and jackson on the Gradle plugin classpath — the alerts
  Dependabot could not raise PRs for.
- The secret-scan allowlist is now GENERATED from a TOML source. The three `grep -vEf` hazards
  that blinded the scan during review (an unanchored entry, prose acting as a live regex, an
  invalid ERE breaking the whole pattern file) are inexpressible rather than merely detected, and
  a canary self-test guards the generator's output in the gate.

## splice v0.1.1 — release integrity and supported defaults - 2026-07-21

### Fixed

- Launching a head by its wrapper command now works when the command differs from the topology
  key — the starter's supported route (`openrouter` head, `claudeor` command) failed its very
  first launch with "head not launchable". `/launch`, `login`, `install <head>`, and
  `uninstall <head>` accept either name now.
- The release installer now fetches and verifies both the fat JAR and launch shim, fails on
  missing or mismatched assets, rejects dangling wrapper links, and is safe when piped through
  stdin. CI and publication run the same hermetic staged-bundle install test.
- Fresh installs now materialize a supported OpenRouter API-key topology. Codex, Grok, and Kimi
  OAuth implementations remain available only as explicitly configured experimental opt-ins.
- `splice doctor` now reports management-key coverage: a missing mgmt-key while the daemon runs is
  a failure ("admin endpoints will 401") rather than "Everything checks out", and the state dir and
  `daemon.lock` paths are shown for orientation.
- `splice doctor`'s split-brain check no longer vanishes silently when the daemon is up but its
  side can't be read (no mgmt-key or `/api/auth` unreachable) — it now emits an explicit warning
  instead of quietly skipping exactly when the daemon is busiest.
- `splice restart` no longer false-fails when the daemon drops the shutdown connection during a
  graceful teardown: the health poll, not the POST result, decides whether the daemon stopped.
- `splice doctor`/`restart` no longer render a foreign listener's `{"version": null}` as the
  literal string "null" (JsonNull-filtered read).
- `splice doctor`'s daemon and auth sections now resolve the control port and probe `/health` once
  through the injected environment reader, so the hermetic tests no longer depend on an ambient
  local daemon.
- `experiments/cache-replay/real-ab.sh` derives its repo root from its own location instead of a
  hardcoded personal path (the last stray reference to the project's pre-rename directory).

### Added

- The installer now preflights the whole machine before doing anything: platform detection
  (Linux/macOS native, Windows pointed at WSL2 with exact guidance), Java 21+ as a hard
  requirement, and every runtime dependency (curl, python3, node, Claude Code) verified with the
  exact per-package-manager fix — offered interactively with consent, printed otherwise. The
  install finishes by running `splice doctor`, so it ends on a verified state, not a hopeful one.
- README: a requirements table with per-OS fixes, three install paths (release one-liner, from
  source, and a copy-paste prompt that lets a coding agent drive the whole install-and-verify
  loop against `splice doctor`'s fix lines).
- `splice doctor` now actually diagnoses: five sections (prerequisites, installation,
  configuration, daemon, auth) with an actionable fix line under every failing check, and exit 1
  only on real failures. It detects the exported-after-boot trap — an API key visible in the
  shell but not to the running daemon — by comparing both sides.
- `splice restart` — stop the daemon (stale or current) and cold-start it with the invoking
  shell's environment; the documented fix for a key exported after the daemon booted.
- Launching a head whose upstream credentials are absent now warns, naming the missing env var
  (or login command) and the fix, instead of failing silently upstream on the first request.
- The release installer preflights `gh` presence and authentication before downloading anything —
  provenance verification needs an authenticated GitHub CLI, and learning that after the download
  was the worst first-run moment.
- Release bundles and the shaded JAR include the project license, third-party notices, provenance,
  a CycloneDX 1.6 SBOM, and an exact runtime dependency-license inventory. Publication fails on
  unresolved licenses or sidecar/JAR/checksum drift.
- CodeQL, dependency review, artifact provenance attestations, release-version validation, and
  bounded/concurrent CI release jobs.
- Gateway hardening: an 8 MB cap on incoming request bodies, rejected with HTTP 413 when exceeded;
  a bounded request-materialization gate limiting how many requests can be decoded/translated
  concurrently; SSE frame-size limits on data read from upstream; and a cap on upstream
  error-response bodies (64 KB) before they're surfaced to the client.
- Ceilings on configuration values — ports, fold rounds/tier, and max inflight/queued — so
  out-of-range operator or environment input can no longer reach the runtime uncapped.

### Changed

- The ChatGPT, Grok, and Kimi subscription routes are now presented as what they are: the
  primary routes splice was built for — unofficial, at your own risk — rather than
  "experimental" afterthoughts. The API-key routes remain the zero-config starter. The
  OSS posture check now pins the risk language instead of the word "experimental".
- Public reasoning language now describes provider-generated summaries without implying access to
  raw, private, or exact chain-of-thought.
- Reasoning replay now ships off. Measurement showed that replay encouraged reuse of thin prior
  thinking; `CLAUDEX_REPLAY_REASONING=1` remains available as an explicit cache-warmth trade-off.
- A rate-limited (429) turn now terminates immediately instead of retrying in-gateway, so the
  client re-sends; a real 429 arms a shared per-account cooldown so concurrent turns fail fast
  together instead of each burning its own retries against the same limited account.

## splice — codex-proxy v35, claudithos removed, renamed from "mythos" - 2026-07-15

Public release under the new name **splice** (was "mythos", which collided with Anthropic's
model line). Two functional changes ship alongside the rename.

### Fixed

- **Compaction re-read the whole transcript cold and drained quota (codex-proxy v35).** The
  stream idle-watchdog was reaping big-context compaction PREFILLS: a ~160k compaction is
  silent for minutes while the backend prefills before its first byte, and the watchdog's
  `streamIdleMs` treated that silence as a zombie and aborted — so every compaction died
  mid-prefill and retried, re-reading the transcript uncached each attempt. The idle abort now
  uses `firstByteTimeoutMs` until the first byte arrives; `streamIdleMs` applies only once
  streaming has actually started. Compaction also inherits the session's own model AND reasoning
  effort — a mismatch on either invalidates the prompt cache.

### Removed

- **The `claudithos` head** (a Claude-on-Claude memory-architecture experiment, port 3098): the
  launcher arm, proxy branch, auth panel, `claudithosMode` config knob, and its tests are gone.
  The stack is now the `claudex`/codex head plus a scaffolded Grok head.

## splice — control server v1 (spliced) - 2026-07-15

Split the dashboard out of the proxies into a centralized control plane. Each head
(codex, grok later) used to serve its own single-head `/dashboard` +
`/mgmt`; now a loopback control server (`spliced`, :3096) hosts ONE dashboard over
an aggregated `/api/*` spanning every head. The heads keep `/mgmt` as their machine
interface but no longer serve a dashboard.

### Added

- **`spliced` control server** (`src/control-server.mjs` + `src/control/api.mjs`,
  loopback :3096, `controlPort`). Bearer-guarded `/api/*` sharing the proxies'
  mgmt-key: `GET /api/status`, `GET /api/heads` + `POST /api/heads/:head/{start,
  stop,restart}` (full lifecycle), `GET|PATCH /api/config`, `GET /api/usage`,
  `GET /api/auth` + `POST /api/auth/:head/{refresh,login}`, `GET /api/compact`,
  `GET /api/logs/:head`. Serves the dashboard at `/`. Mints the mgmt-key at boot.
- **Shared head lifecycle** (`launcher/heads.mjs`): the head registry + health /
  spawn / kill / start / stop / restart, used by BOTH the CLI launcher and the
  control server so process logic is never forked. Control-side spawns strip the
  config env (`CONFIG_ENV_NAMES`) so `config.json` — the dashboard's source of
  truth — wins over a stale inherited value.
- **Soft-warn usage caps** (`src/usage/warn.mjs`; `usageWarnPct` /
  `usageWarnTokens5h`): never blocks. Classifies each head's headroom ok / warn /
  critical from the rate-limit remaining, with a 5h output-token cap as fallback.
  Feeds a dashboard banner and a subtle statusline `⚠` that stays hidden until near
  the cap.
- **`claudex dashboard`**: ensures spliced is up and
  opens the browser; the launcher also best-effort-starts spliced alongside any
  head launch (non-blocking).
- **Multi-head dashboard** (`webui/`, FSD React): a fleet of instrument head-plates
  (live status + start / stop / restart with a two-step confirm on the destructive
  actions + a per-head usage meter tinted by warn level), per-head auth cards
  (codex Sign-in-with-ChatGPT + refresh, claude plain-claude + refresh), and a
  shared config editor with layer provenance and guided enum dropdowns. Retired the
  single-head models / reasoning / proxy-status surfaces.

### Changed

- `codex-proxy.mjs` no longer serves `/dashboard` (it
  moved to spliced); it keeps `/mgmt`. Dashboard config changes reach a running
  head through a `PATCH /mgmt/config` fan-out (the runtime layer, which beats the
  launcher's env pin), falling back to writing the config file when no head is up.

## splice — codex-proxy v31 - 2026-07-14

Codex-parity prompt-cache warmth for the claudex head. Native Codex keeps the
backend prompt cache hot with three coupled mechanisms
(`codex-rs/core/src/client.rs`): `include=["reasoning.encrypted_content"]`,
`store=false`, and a stable `prompt_cache_key = session_id`. claudex sent none of
them, so the growing conversation prefix went cold every turn (no cached-input
discount, higher latency) — acute once account-level limits made cache hits
load-bearing. This ships all three, on by default, without abandoning the mirror.

### Added

- **Reasoning replay (default on, `replayReasoning`).** The backend's encrypted
  reasoning rides through the transcript as a `redacted_thinking` block
  (`reasoning/replay.mjs`, tag `splice-reasoning` v1) and decodes back into a
  Responses `reasoning` input item, so the reasoning KV / prompt-cache prefix
  stays byte-stable across the agent loop. Emitted on both response paths — the
  stream path via the sole SSE emitter (`addRedactedThinking`), the non-stream
  path via `translateResponse`. Never on compact. Opt out with
  `CLAUDEX_REPLAY_REASONING=0` to run the pure distillation loop.
- **`prompt_cache_key` (always on).** `splice-<sha256(first user message)[:32]>`
  — keyed on the first user message: stable for the whole conversation and immune
  to per-turn system-reminder drift (keying on the system prompt would bust it
  every turn). Routes every turn of one conversation to the same cache shard.
- Both run ALONGSIDE the mirror (L2), unchanged: the mirror carries the reasoning
  SUMMARY to the model as readable text; replay carries the ENCRYPTED reasoning to
  the backend. Different channels — they compose, they don't compete.

### Changed

- **L1 retired.** The former locked invariant "no reasoning-item replay" (the bet
  that per-turn amnesia beat replay on the hardest multi-day work) is overturned:
  the power came from the mirror, not from dropping replay. The
  `l1-no-reasoning-replay` wall + rule-test are removed; the L1 behavioral test is
  replaced by a replay round-trip / gating / cache-key / both-channels-coexist
  suite; orchestrator routing tests re-pointed to L3. The pure-amnesia A/B is
  preserved behind the flag.
- `replayReasoning` is a hot-applicable config knob (defaults ← file ← env
  `CLAUDEX_REPLAY_REASONING` ← runtime PATCH) — toggle the A/B live from the
  dashboard, no restart.

### Gates

- server 87/87 (10 new), gate:rules 10 rules green, test:hooks 13/13, webui
  lint+test+build green, `webui/dist` byte-unchanged.

## splice — codex-proxy v30 - 2026-07-13

Productization: the 1783-line `codex-proxy.mjs` v29 (which diverged through six
local versions in two days inside a forked npm package) becomes this repo —
npm workspaces `server/` + `webui/`, 18 server modules, walls-first ast-grep
policy, a bearer-guarded management plane, and a committed single-file
dashboard. All three autocompact locks fixed as part of the move:

### Fixed

- **Autocompact trigger never fired** (binary trace, Claude Code v2.1.207):
  Claude Code hard-skips autocompact when it cannot resolve an explicit
  context window for the model; no claudex model matches its tables and only
  `CLAUDE_CODE_AUTO_COMPACT_WINDOW` un-gates it. The launcher now sets it
  (resolved window, floor 100k) alongside the kept
  `CLAUDE_CODE_MAX_CONTEXT_TOKENS` and `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=85`.
- **Overflow on the SSE path** (Eli P0): the "prompt is too long" rewrite
  existed only on the HTTP non-ok path; live failures arrive via SSE
  `response.failed` and became raw `api_error` (hard error, no compaction).
  ONE `classifyUpstreamFailure(kind, text, status)` now serves both
  transports; overflow order also fixes v29's auth-regex shadowing of
  wordings containing "tokens".
- **Compaction detection inverted** (Eli P0/P1 + trace): real compaction
  requests DO carry tools; v29's `tools.length>0 → false` guard rejected the
  real shape, and a tooled compaction could answer with `tool_use`, gating
  the promote-to-text net off. `classifyCompact` is now a tools-agnostic
  positive-marker classifier (the verbatim summarizer prompt); on detect the
  builder strips tools upstream. A shadow classifier logs
  `{has_marker, tool_count, sys_len}` on EVERY request, and a canary test
  pins the marker sentence.
- Dead claude-* passthrough is an honest error; every `listen()` binds
  127.0.0.1 explicitly; context windows resolve exact-match + explicit prefix
  rules (no substring fuzz); `body.__claudex*` magic props replaced by the
  pure `{req, meta}` translation contract; mirror/promote/honesty thresholds
  named in one place; kill-stale is a pgrep/lsof loop excluding own PID
  (never `pkill -f`), and a surviving wrong-version proxy is a loud failure,
  never the EADDRINUSE silent-exit version-loop.

### Added

- Layered hot config (defaults ← state file ← env ← runtime PATCH) read per
  request; `/mgmt/*` management plane on both proxies (status, config
  round-trip, usage, compact + shadow, auth + refresh, logs, models);
  `/dashboard` serving the committed single-file WebUI (React 19 + Zustand,
  FSD lint-enforced, Torad tokens, Reasoning + Compaction instrument pages).
- Launchers: `ensure-proxy` (health/version handshake), `assemble-env`
  (section-aware TOML replacing the sed that leaked [profile] values;
  models_cache + ceiling resolution), `prepare-config` (config-dir isolation
  half of claudex-prepare), thin `bin/claudex` exec-env shim. Proxy logs move to
  `~/.claude-codex/logs/` (out of /tmp).
- Walls: single Python hook orchestrator routing every write-time policy to
  ast-grep rules (L1/L2/L3 structural invariants, loopback bind, magic
  props, pkill, FSD fetch gate, em-dash copy gate, CSS token scales), same
  rules re-run by `npm run gate:rules` and CI.

### Left behind (deliberate)

`claude-wrapper` (pinned proxy v6 vs real v29 — abandoned), `set-model-mode`
(SMELTER-coupled; only the pure config-isolation helpers were extracted),
`build-codex-server` (bundled a nonexistent file), `lib/auto-update` (npm
self-update + network call per launch), `bin/claude-codex`.

---

## Inherited history (codex-for-claude-code local fork)

> Provenance + external upstream license clearance for this inherited lineage: see [PROVENANCE.md](PROVENANCE.md).

## local codex-proxy v29 - 2026-07-13

### Fixed

- **Large-context and compaction requests aborted mid-prefill** ("operation was aborted", 31× in one session vs 5 genuine over-window). The v25 first-byte timeout was 90s, but a near-window prompt or a compaction re-sending the whole transcript legitimately takes minutes to prefill before the first token. Raised to **300s** (`CLAUDEX_FIRST_BYTE_TIMEOUT_MS`), still catching a truly-dead connect. This was the dominant cause of "autocompact not working" — the compaction request itself was being killed before it could respond.

### Changed (launcher)

- **Reverted the reported context window 220k → real 272k, kept autocompact at 85%.** The over-window 502s were caused by Claude Code's **autocompact thrashing guard** (it disables autocompact after the context refills within 3 turns of a compact, 3× in a row — triggered by large tool-result reads), not by the threshold. A *lower* reported window fires autocompact more often, leaving fewer turns before a big read refills it → more thrashing → autocompact disabled → session grows unbounded → 502. Reporting the real 272k fires at ~231k with ~4 turns of headroom, above the 3-turn thrash trigger.

## local codex-proxy v28 - 2026-07-13

### Fixed

- **Only one codex model (the pinned default) showed in the `/model` picker.** `additionalModelOptionsCache` is replaced wholesale on every bootstrap and `ANTHROPIC_CUSTOM_MODEL_OPTION` is singular; the only durable way to list N custom models is **gateway model discovery**. The proxy serves `GET /v1/models` (launcher sets `CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1`); Claude Code drops ids not matching `/^(claude|anthropic)/i`, so codex ids are wrapped (`gpt-5.6-luna` → `claude-codex--gpt-5.6-luna`) and unwrapped on the way in. The pinned default is excluded from discovery to avoid a duplicate.

## local codex-proxy v27 - 2026-07-13

### Fixed

- **Claude Code `/effort` picker was ignored.** The picker arrives as `thinking.budget_tokens`; the resolution chain put env (config.toml `model_reasoning_effort = "max"`) above it. Reordered: explicit body effort field > harness picker > env fallback > `high`.
- **`SHOW_REASONING=text` force-raised a deliberate low pick.** The visibility floor now only bumps `none`/`minimal`/absent → `low` and never overrides an explicit selection.

## local codex-proxy v26 - 2026-07-13

### Fixed

- **Compaction "response exceeded N output token maximum"**: the ChatGPT backend rejects token-limit params so generation is uncapped, and reasoning tokens count in `output_tokens` — an undetected max-effort compaction tripped Claude Code's output guard. Reported `output_tokens` is now clamped to the client's `max_tokens`, with a stderr diagnostic on every clamp.

## local codex-proxy v25 - 2026-07-13

### Fixed

- **Multi-part reasoning summaries**: per-part `done` events closed the thinking block after part 1 (protocol violation; visible thinking truncated). Blocks close only on `output_item.done`; parts separated with blank lines in the thinking stream and the mirror.
- **Honest failures**: `response.failed`/`response.error`, idle-aborted streams, and streams ending without `response.completed` emit an SSE `error` event instead of a clean empty `end_turn`. Empty compacts and fully-empty completions are errors too.
- **Wire framing**: `res.end()` in the same tick as a corked SSE write put the terminal chunk before the buffered frame (raw socket capture). All stream ends drain the cork queue first.
- **Client abort**: `AbortController.abort()` replaces `body.cancel()` (which rejects unhandled under an active reader lock and leaves upstream streaming).
- **Non-stream path** collapsed onto the shared translator: fixes per-chunk UTF-8 corruption, `name: undefined` tool calls, and function_calls missing from final-output harvest.
- **Compact detection**: removed the tertiary "huge toolless dump" heuristic (misfired on WebFetch-style utility calls).
- **State files** moved from `$CWD/.smt/state` to `~/.claude-codex/state/`.

### Added

- Image passthrough (incl. images inside `tool_result`); `[document omitted]` markers; 401 single-flight OAuth refresh via the Codex CLI client id; first-byte timeout; `response.incomplete` → `stop_reason: max_tokens`; context-overflow errors rewritten to Anthropic's "prompt is too long" phrasing; the 11-test behavior suite.

### Deliberate non-goals (operator-locked)

- **Reasoning-item replay (`include: ["reasoning.encrypted_content"]`) is intentionally NOT implemented.** The no-replay + mirror configuration forces re-derivation from transcript evidence at every tool boundary while distilled conclusions persist via the mirrored summaries — operator A/B experience shows this outperforming native Codex CLI (which replays) on hard multi-day debugging. Do not "fix" this. Revisit only as an explicit, measured A/B on disposable tasks.
