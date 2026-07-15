# mythos

**Run [Claude Code](https://docs.anthropic.com/en/docs/claude-code) against a ChatGPT Codex subscription — and keep the model's reasoning warm, legible, and cheap across the whole agent loop.**

mythos is a local, loopback-only proxy stack. It speaks Anthropic's Messages API on one side and OpenAI's ChatGPT **Responses** API on the other, so Claude Code's agent loop drives a ChatGPT Codex backend (or Claude, or Grok) without knowing the difference. Its load-bearing feature is the **mythos loop**: the backend's reasoning is surfaced back into the transcript as a visible **mirror**, so conclusions persist and stay legible turn after turn instead of evaporating.

> Status: personal project, run locally on loopback. Two heads are usable today (`claudex`, `claudithos`); a Grok head is scaffolded. No warranty — see [License](#license).

## Why it exists

Long coding-agent sessions bleed tokens and lose the thread. mythos goes after both:

- **Cache warmth is engineered, not hoped for.** A stable `prompt_cache_key`, encrypted-reasoning replay, and — critically — **compaction that runs on the session's own model and reasoning effort** keep the prompt cache warm across a long session. A mismatched compaction model *or* effort silently invalidates the cache and re-reads the entire transcript uncached every time it fires; getting this right is the difference between a session that sips your quota and one that drains it.
- **Reasoning is load-bearing.** The mirror writes the model's reasoning back into the transcript as readable text, so the agent — and you — can see *why* it did what it did, and that context survives compaction.
- **One instrument panel for the fleet.** A centralized control server (`mythosd`) serves a single dashboard over every head: live status, start/stop/restart, layered config with provenance, per-head 5-hour usage soft-warnings, auth, and logs.

## Two heads, one stack

- **claudex** (`:3099`) — Claude Code on a **ChatGPT Codex** subscription. Anthropic Messages ⇄ ChatGPT Responses translation, gateway model discovery, compaction detection with a shadow instrument, OAuth refresh, reasoning replay, and `prompt_cache_key` for Codex-parity cache warmth.
- **claudithos** (`:3098`) — Claude Code on **Claude** through the mythos memory-architecture transform (arms: `native` / `amnesia` / `mirror`). Experimental — for disposable tasks only.

## Quick start

```bash
bin/claudex login        # Sign in with ChatGPT (OAuth + PKCE) → ~/.codex/auth.json
bin/claudex              # launch Claude Code against the codex proxy (:3099) + mythosd
bin/claudex dashboard    # open the control dashboard (mythosd, :3096)
bin/claudithos           # Claude head, armed from CLAUDITHOS_MODE (:3098)
```

The dashboard and every `/mgmt/*` and `/api/*` endpoint are bearer-guarded and loopback-only. The unlock key lives at `~/.claude-codex/state/mgmt-key`.

## The cache-replay experiment

`experiments/cache-replay/` is a self-contained A/B that probes one question: **does replaying encrypted reasoning items back into a request bust the prompt cache?** It runs a fixed multi-turn conversation twice — once carrying the encrypted reasoning forward, once dropping it — and reports cached vs. uncached input tokens per turn.

- `real-ab.sh` — the honest version: two isolated real Claude-Code sessions on a side-port proxy, same turns, only the replay toggled.
- `run.mjs` — a dependency-free Node harness against the raw Responses endpoint.

Findings so far are **directional, not conclusive** — single runs are noisy and part of the token delta is simply that replay ships the reasoning blobs as extra input. Read `experiments/cache-replay/README.md` for the caveats and run it yourself.

## Layout

```
server/        Node, no build step (dep: undici) — proxy src, launcher, control server, tests
webui/         React 19 + Vite + Zustand, Feature-Sliced Design, single-file build
bin/           thin claudex / claudithos shims (exec env; all logic lives in launcher/)
experiments/   cache-replay A/B reproducer
.rules/        ast-grep "walls" enforced write-time AND at the commit gate (same rules twice)
```

## Development

```bash
npm test -w server                         # node --test behavior + invariant suites
npm run gate:rules                         # ast-grep walls: tree scan + rule red/green
npm run lint -w webui && npm test -w webui && npm run build -w webui
```

Contracts and invariants live in `AGENTS.md`; the change log in `CHANGELOG.md`; the wall doctrine in `.rules/README.md`.

## License

[MIT](LICENSE).
