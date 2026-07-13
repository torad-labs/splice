# mythos

Productized claudex/claudithos proxy stack, named for its load-bearing
feature: the **mythos loop** — per-turn reasoning amnesia plus a visible
reasoning mirror, so conclusions persist in the transcript while reasoning is
re-derived from live evidence at every tool boundary.

Two heads, one stack:

- **claudex** (`:3099`) — Claude Code on a ChatGPT Codex subscription.
  Anthropic Messages ⇄ ChatGPT Responses translation, gateway model
  discovery, compaction detection + shadow instrument, OAuth refresh.
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

Both proxies serve a bearer-guarded management API under `/mgmt/*` and the
committed single-file dashboard at `/dashboard`
(key: `cat ~/.claude-codex/state/mgmt-key`).

## Run

```
bin/claudex            # ensure proxy v30, exec Claude Code against :3099
bin/claudithos         # ensure proxy v3 (arm from CLAUDITHOS_MODE), :3098
node server/src/codex-proxy.mjs        # proxy standalone
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
