# Changelog

## splice v0.1.1 — release integrity and supported defaults - 2026-07-19

### Fixed

- The release installer now fetches and verifies both the fat JAR and launch shim, fails on
  missing or mismatched assets, rejects dangling wrapper links, and is safe when piped through
  stdin. CI and publication run the same hermetic staged-bundle install test.
- Fresh installs now materialize a supported OpenRouter API-key topology. Codex, Grok, and Kimi
  OAuth implementations remain available only as explicitly configured experimental opt-ins.

### Added

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
