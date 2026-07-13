# mythos — productize the claudex/claudithos proxy stack

Master plan (operator-supplied, 2026-07-13). Repo root: this directory
(`~/Documents/dev/projects/mythos/repo`), npm workspaces `server/` + `webui/`.
Named for the load-bearing per-turn-amnesia + reasoning-mirror distillation
loop. Two heads stay: **claudex** (Codex upstream, :3099) and **claudithos**
(Claude upstream, :3098, experiment arms).

## Context

codex-proxy.mjs (1783 lines, v29) lives in a forked npm package's scripts/ dir
and diverged through 6 local versions in 2 days; daily load-bearing. Inputs: an
Eli (opus) end-to-end review, a binary trace of Claude Code v2.1.207, and the
operator-chosen reference router-for-me/Cli-Proxy-API-Management-Center (React
WebUI over a proxy's bearer-guarded management API, built to a single inlined
HTML the proxy serves itself).

The autocompact bug is settled — three independent locks all failed; all three
are fixed in this migration (never patched into the old tree):

1. **Trigger never fired** (binary trace): Claude Code hard-skips autocompact
   when it can't resolve an explicit context window for the model (env →
   settings → hardcoded model tables). No claudex model matches →
   `source:"auto"` → skipped before any token math. Proxy saw 0 compact
   requests EVER. Only `CLAUDE_CODE_AUTO_COMPACT_WINDOW` un-gates it
   (`CLAUDE_CODE_MAX_CONTEXT_TOKENS` does not, and is ignored for
   claude-*-prefixed names).
2. **Reactive fallback broken** (Eli P0): the "prompt is too long" rewrite
   exists only on the HTTP non-ok path (v29 :203, sole call site :1173). Live
   failures arrive via SSE `response.failed` (:1529-1534) whose separate inline
   classifier has no overflow rewrite → raw api_error → hard error, no
   compaction. Confirmed in the live log.
3. **Detection inverted** (Eli P0/P1 + binary trace): compaction requests DO
   carry tools (Read+ToolSearch+MCP by default), so isCompactRequest's
   `tools.length>0 → false` guard (:444, dup :969) rejects the real shape.
   Worst case: Codex answers a compaction with tool_use and the
   promote-to-text net is gated by `!hasToolUse` → empty summary.
   Authoritative signal: verbatim system prompt "You are a helpful AI
   assistant tasked with summarizing conversations." (identical auto + manual,
   streaming, no distinguishing header).

## Locked invariants → structural (L1–L4)

1. **No reasoning-item replay** — translate-request never sets `include`;
   permanent test `assert(buildRequest(any).include === undefined)`.
2. **Mirror never regresses** — ONE mirrorInto() in
   server/src/reasoning/mirror.mjs called by both stream and non-stream paths
   (v29 duplicated :1616/:1702); test asserts both paths call it.
3. **Honest failures** — anthropic/sse.mjs is the only module that can emit
   message_stop; end_turn reachable only via emitTerminal(reason); failures
   only via emitError.
4. **No fake summaries** — empty-in → empty-out test; only literals emitted
   are error strings/markers.

## Structure

```
repo/
├── package.json                # workspaces: server, webui
├── README.md, CHANGELOG.md (migrate v25–v29 + claudithos + locked non-goals),
│   AGENTS.md (invariants L1–L4, mgmt API, contracts)
├── .github/workflows/ci.yml    # lint + test server & webui + webui build
├── server/                     # Node, no build step, dep: undici; node --test
│   ├── src/
│   │   ├── config.mjs                   # LAYERED config: defaults ← file ← env (bootstrap) ← runtime PATCH; getConfig() per request, no frozen module consts
│   │   ├── http/server.mjs              # routing, body read, cork/uncork, endResponse framing; binds 127.0.0.1 ONLY
│   │   ├── anthropic/sse.mjs            # sole Anthropic wire emitter (L3)
│   │   ├── reasoning/mirror.mjs         # extract/mirrorInto + named thresholds (L2)
│   │   ├── codex/translate-request.mjs  # Messages→Responses, PURE {req, meta} (kills body.__claudex*); L1 guard
│   │   ├── codex/translate-response.mjs # terminal object→Anthropic msg (pickModelText, ensureTextFromThinking) (L4)
│   │   ├── codex/stream.mjs             # Responses-SSE→Anthropic-SSE state machine (emits only via sse.mjs)
│   │   ├── codex/compact.mjs            # classifyCompact (positive marker, tools-agnostic) + shouldForceText + shadow logging + stats
│   │   ├── codex/errors.mjs             # ONE classifyUpstreamFailure(kind,text,status) for HTTP AND SSE (fixes P0 #2)
│   │   ├── auth/codex-oauth.mjs         # auth cache + single-flight 401 refresh
│   │   ├── auth/claude-oauth.mjs        # claudithos credential read
│   │   ├── upstream/fetch.mjs           # undici Agent, timeouts, first-byte watchdog
│   │   ├── upstream/gate.mjs            # inflight gate, live map, snapshot, retries
│   │   ├── usage/hud.mjs                # usage payload, ratelimit persistence, clampOutputTokens
│   │   ├── models/codex-models.mjs      # catalog + context windows (exact-match + explicit prefix rules) + discovery wrap/unwrap + /v1/models
│   │   ├── mgmt/api.mjs                 # management API, bearer key from state dir
│   │   ├── mgmt/dashboard.mjs           # serves webui/dist single-file HTML at /dashboard
│   │   ├── mythos/transform.mjs         # claudithos A/B transforms (pure transformMessages)
│   │   ├── codex-proxy.mjs              # entry, PROXY_VERSION '30'
│   │   └── claudithos-proxy.mjs         # entry, VERSION '3'
│   ├── launcher/
│   │   ├── ensure-proxy.mjs   # shared: health check, version handshake, kill-stale (pgrep loop, never pkill -f self-match), restart
│   │   ├── assemble-env.mjs   # section-aware TOML parse (replaces broken sed), models_cache read, context-window resolution, env map
│   │   └── prepare-config.mjs # slimmed claudex-prepare: config-isolation half only (~40 pure lines from set-model-mode)
│   └── test/                  # ported integration suites + per-module units
├── webui/                     # React 19 + Vite + Zustand + TS, vite-plugin-singlefile
│   ├── src/app|pages|widgets|features|entities|shared   # FSD layers (below)
│   ├── eslint.config.mjs      # typescript-eslint strict + eslint-plugin-boundaries
│   ├── tests/                 # vitest: stores, api client, config validation
│   └── dist/index.html        # committed single-file build artifact
└── bin/
    ├── claudex                # ~15-line bash shim: true `exec env …` (→ ~/.local/bin)
    └── claudithos
```

Carry: both proxies, all three test files, codex-models, compact-stats reader,
slimmed claudex-prepare. Leave behind: claude-wrapper (pins proxy v6 —
abandoned), set-model-mode (SMELTER-coupled; extract only pure helpers),
build-codex-server (bundles a nonexistent file), lib/auto-update, bin/claude-codex.

## Management plane

Bearer-guarded (`Authorization: Bearer <key>`, generated once into
`~/.claude-codex/state/mgmt-key`), loopback-only, namespace `/mgmt/*` on both
proxies (shared module):

- GET /mgmt/status — version, uptime, mode/arm, gate snapshot, live in-flight
  (phase, model, effort, elapsed)
- GET|PATCH /mgmt/config — runtime knobs hot-applied (effort, summary,
  showReasoning, pinned model, maxInflight, timeouts, debug); persisted to
  ~/.claude-codex/state/config.json; restart-required fields flagged
- GET /mgmt/usage — codex-usage + ratelimit state
- GET /mgmt/compact — compact events + shadow-classifier tail
  (predicted-vs-observed instrument, visible)
- GET /mgmt/auth + POST /mgmt/auth/refresh — token expiry, last refresh, masked account
- GET /mgmt/logs?tail=N — from ~/.claude-codex/logs/ (logs move out of /tmp)
- GET /mgmt/models — catalog, windows, pinned, discovery list

WebUI pages consume exactly these. Reasoning page (mirror mode/effort/summary
controls + live mirror confirmation) and Compaction page (detection verdicts
feed) surface OUR invariants. Config editing gets the reference's diff-modal +
confirm pattern.

## WebUI architecture (lint-enforced FSD)

- Layers, strictly unidirectional: app → pages → widgets → features →
  entities → shared. No same-layer cross-imports.
- Slices export through index.ts barrels; deep imports are lint errors.
- UI strictly via state: components render Zustand store state; entity api
  segments are the only code touching the mgmt HTTP client; local useState for
  ephemeral input only. Flow: user action → feature action → api service →
  store → view.
- Enforced by ESLint flat config: typescript-eslint strict +
  eslint-plugin-boundaries (elements per layer + api/model/ui segments,
  element-types default "disallow" with explicit allow-lists,
  no-unknown-files), no-restricted-imports forcing barrels, HTTP client import
  restricted to entity api segments. Lint is a hard CI gate.

## Design language

Operator instrument panel, single expert operator; Torad brand tokens in the
labs (Instrument/Signal) register; mono-led, dense, hand-authored; dials
VARIANCE 4–5 / MOTION 2–3 / DENSITY 7–8 (hairline dividers over cards,
--font-mono for every number). Precedence: Torad tokens → labs register →
frontend-design (one committed direction) → taste-skill (mechanical gates; its
dashboard carve-out overridden by locked no-component-library decision).

- One theme system (light-dark(), both Plate registers, color-scheme).
- One accent page-wide (vermilion action/focus; cyan/gold-dim are DATA inks
  --data-info/-amber/-pos/-neg, never decorative).
- Shape lock: --radius-1 (2px) instruments, --radius-0 wells.
- Motion only as state feedback (--dur-1/2, --ease-snap);
  prefers-reduced-motion collapses all; no infinite loops.
- Full state cycles: skeletons shaped like final layout, composed empty
  states, inline errors; :active tactile press.
- Copy: functional labels, zero em-dashes in UI text, real numbers from the
  mgmt API only.
- Type: IBM Plex Mono leads; Fraunces only as small accents.
- Contrast: WCAG AA in BOTH registers.
- Tokens: torad-tokens.css vendored verbatim; NO Torad component CSS;
  composition page-owned under .myx-* namespace.
- Spacing/size (operator emphasis): all spacing from --space-0..12, all font
  sizes from --text-0..10; zero ad-hoc px/rem (wall: webui-css-tokens-only).
  Data surfaces --text-2/3, annotations --text-1, titles --text-5/6; rhythm
  --space-2/3 inside instruments, --space-5/6 between.
- A11y: semantic headings, keyboard order = source order, visible focus.
- Fonts embedded in single-file build (Plex Mono woff2 → data URI; serif falls
  back to system).

## Fixes folded into the move

- Autocompact trigger: claudex shim env adds
  `CLAUDE_CODE_AUTO_COMPACT_WINDOW="$CONTEXT_WINDOW"` (272000; floor 100k).
  Keep `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=85` (fires ~231k; min'd vs window−13k).
  Keep `CLAUDE_CODE_MAX_CONTEXT_TOKENS` (honored for unwrapped gpt-* names).
- Overflow on SSE path: single classifyUpstreamFailure for HTTP +
  response.failed; overflow → invalid_request_error + "prompt is too long".
- Compaction detection: collapse 3 decision sites (:444/:525/:969) into ONE
  classifyCompact(body) — positive marker = verbatim summarizer prompt,
  tools-agnostic. On detect: strip tools upstream (forces text). Shadow
  classifier logs {has_marker, tool_count, sys_len} on EVERY request.
- P2/P3: named thresholds (mirror ≥20 / promote ≥40 / honesty <20) in one
  place; context-window map exact-match + explicit prefix rules; dead
  claude-* passthrough (:914-953) becomes an honest error; bind 127.0.0.1.

## External contracts (must NOT change)

State paths byte-identical (`~/.claude-codex/state/*`,
`~/.claude-codex/claudex-compact-stats.jsonl` — out-of-repo HUD reads them);
config dirs `~/.claude-codex` / `~/.claude-mythos` keep names; ports 3099/3098;
/health keeps version; discovery prefix `claude-codex--` + pinned-model
exclusion; effort precedence chain (v27); mirror wire format.

## Tests

Ported suites stay as integration tests booting the assembled servers
(scenario-mock pattern preserved). New units per module + the three Eli gaps:
1. SCENARIO:overflow_sse — SSE response.failed "exceeds the context window" →
   invalid_request_error/"prompt is too long".
2. Compaction fixture matching REAL v2.1.207 shape (verbatim prompt, tools
   present) → detected, tools stripped, summary text produced; canary on
   marker drift.
3. Client-abort mid-stream: slot freed once, upstream abort fired, no fake
   end_turn, cork drained before end.
Plus L1 include-guard, L2 both-paths-call-mirrorInto, mgmt API auth +
hot-config round-trip, webui vitest suite. CI runs all.

## Build order

1. ✅ Scaffold monorepo, git init, commit per phase. Walls first: ast-grep
   rules + single Python orchestrator + red/green tests (done 2026-07-13).
2. ✅ server/: decompose + fixes; node --test green (77 tests, f3fc1fb).
3. ✅ Management API + layered hot config (curl-verified PATCH round trip).
4. ✅ webui/: FSD pages over the typed client; singlefile build served at
   /dashboard; dist artifact committed (e572d6f).
5. ✅ Launchers: ensure-proxy + assemble-env + prepare-config + thin shims
   (080dd88). ~/.local/bin swap deferred to cutover (6).
6. Cutover: kill ALL old proxies by port+name (pgrep loop excluding own PID).
   Live verify: /health v30/v3; shadow-classifier lines appear; manual
   /compact detected with a real summary; /context shows autocompact enabled
   with a real threshold; mirror text visible; /model lists discovery models;
   claudithos arms healthy; /dashboard loads and PATCHing effort applies
   without restart.
7. Retire local-fork scripts only after (6); upstream npm files stay untouched.

## Out of scope (locked)

Reasoning replay stays unimplemented. No transcript shrinking, no fake
summaries. claudithos remains an experiment tool (disposable tasks only).
