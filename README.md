# mythos

Productized claudex/claudithos proxy stack, named for its load-bearing
feature: the **mythos loop** — reasoning surfaced back into the transcript as a
visible **mirror**, so conclusions persist and stay legible across the agent
loop. The codex head pairs the mirror with encrypted-reasoning **replay** and a
stable **`prompt_cache_key`** (Codex-parity prompt-cache warmth); the pure
per-turn-amnesia loop remains available as an A/B (`CLAUDEX_REPLAY_REASONING=0`).

Two heads, one stack:

- **claudex** (`:3099`) — Claude Code on a ChatGPT Codex subscription.
  Anthropic Messages ⇄ ChatGPT Responses translation, gateway model
  discovery, compaction detection + shadow instrument, OAuth refresh,
  reasoning replay + `prompt_cache_key` for Codex-parity cache warmth.
- **claudithos** (`:3098`) — Claude Code on Claude through the mythos
  memory-architecture transform (arms: native / amnesia / mirror).
  EXPERIMENT TOOL: disposable tasks only.

## Layout

```
server/   Node (no build step, dep: undici) — src modules + launcher + tests
webui/    React 19 + Vite + Zustand, FSD lint-enforced, single-file build
bin/      thin claudex/claudithos shims (exec env; logic lives in launcher/)
.rules/   ast-grep walls (write-time + gate, same rules twice)
```

Both proxies serve a bearer-guarded machine API under `/mgmt/*`. The **dashboard
is centralized**: a loopback control server (`mythosd`, `:3096`) hosts the single
committed webui at `/` over an aggregated `/api/*` spanning every head — status +
start/stop/restart, config, per-head usage soft-warn, and auth
(key: `cat ~/.claude-codex/state/mgmt-key`). Open it with `bin/claudex dashboard`.

## Run

```
bin/claudex login      # Sign in with ChatGPT (OAuth+PKCE) → ~/.codex/auth.json
bin/claudex            # ensure proxy v32 + mythosd, exec Claude Code against :3099
bin/claudex dashboard  # ensure mythosd (:3096) + open the control dashboard
bin/claudithos         # ensure proxy v3 (arm from CLAUDITHOS_MODE), :3098
node server/src/control-server.mjs     # control server standalone (:3096)
```

## Gates

```
npm run gate:rules     # ast-grep walls: tree scan + rule red/green
npm run test:hooks     # hook orchestrator routing tests
npm test -w server     # node --test behavior + invariant suites
npm run lint -w webui && npm test -w webui && npm run build -w webui
```

Contracts and invariants: `AGENTS.md`. History: `CHANGELOG.md`.
Wall doctrine: `.rules/README.md`.
