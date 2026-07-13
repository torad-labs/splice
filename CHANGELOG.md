# Changelog

## mythos — codex-proxy v30 / claudithos v3 - 2026-07-13

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
- Launchers: `ensure-proxy` (health/version handshake, claudithos arm
  hot-switch via mgmt PATCH), `assemble-env` (section-aware TOML replacing
  the sed that leaked [profile] values; models_cache + ceiling resolution),
  `prepare-config` (config-dir isolation half of claudex-prepare), thin
  `bin/{claudex,claudithos}` exec-env shims. Proxy logs move to
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

## claudithos v2 - 2026-07-13

### Fixed

- **Tool-use loop fragmented in mirror mode** (v1 bug, caught in first live session): v1 injected the `[reasoning summary]` block into the live response stream, including `tool_use` turns — parallel tool calls dropped, turns stopped early. B (the reasoning notebook) now lives entirely in the REQUEST transform: past `thinking` becomes text on replay; the response stream is a byte-faithful passthrough in all three arms.

## claudithos v1 - 2026-07-13

New experiment tool: `claudithos` launcher + proxy (port 3098). Runs Claude Code on Claude (Fable) through a memory-architecture transform to test whether the claudex/Sol "mythos" configuration reproduces within the Claude family. Three arms through the SAME proxy: `native` (control), `amnesia` (A: thinking dropped + tool exchanges textualized on replay), `mirror` (A+B, default: amnesia + thinking persists as `[reasoning summary]` text). EXPERIMENT TOOL — disposable tasks only.

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
