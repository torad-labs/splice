# AGENTS.md — contracts for anyone (human or agent) touching mythos

## The locked invariants (L1–L4)

Structural walls enforce these at write time (`.rules/rules/`, orchestrated by
`.claude/hooks/orchestrator.py`) and permanent tests enforce the behavioral
half (`server/test/invariants.test.mjs`). Do not weaken either; walls are
grant-gated behind `MYTHOS_WALLS_OK=1`.

1. **L1 — no reasoning-item replay.** `codex/translate-request.mjs` never sets
   `include`. The per-turn amnesia + text mirror ARE the product (the mythos
   distillation loop); replay is a locked non-goal, revisited only as a
   measured A/B on disposable tasks.
2. **L2 — the mirror never regresses.** ONE `mirrorInto()` in
   `reasoning/mirror.mjs`, called by the stream path (`codex/stream.mjs`) and
   the non-stream path (`codex-proxy.mjs`). Thresholds are named there:
   mirror ≥20, promote ≥40, honesty <20 chars.
3. **L3 — honest failures.** `anthropic/sse.mjs` is the only module that can
   emit `message_stop`; clean stops only via `emitTerminal(...)` (which owns
   stop_reason derivation), failures only via `emitError(...)`. A failed or
   truncated stream can never masquerade as a clean `end_turn`.
4. **L4 — no fake summaries.** Empty in, empty out. Promote-to-text promotes
   MODEL content (the reasoning summary) only; the only fabricated literals
   anywhere are error strings and `[… omitted …]` markers.

## External contracts (must not change)

- State paths byte-identical: `~/.claude-codex/state/*` and
  `~/.claude-codex/claudex-compact-stats.jsonl` (an out-of-repo HUD reads them).
- Config dirs keep their names: `~/.claude-codex`, `~/.claude-mythos`.
- Ports: claudex 3099, claudithos 3098. `/health` keeps `version`.
- Discovery prefix `claude-codex--`; the pinned model is excluded from
  `/v1/models` (it rides `ANTHROPIC_CUSTOM_MODEL_OPTION`).
- Effort precedence (v27): explicit body effort field, then the Claude
  `/effort` picker (`thinking.budget_tokens`), then config/env fallback, then
  `high`. Compact turns always run `effort: low`, tools stripped.
- Mirror wire format: `\n[reasoning summary]\n<text>\n` (`mirrorWireText`).

## Management API

Bearer-guarded (`Authorization: Bearer $(cat ~/.claude-codex/state/mgmt-key)`),
loopback-only, both proxies:

| route | purpose |
|---|---|
| GET /mgmt/status | version, uptime, gate snapshot + live in-flight, mode/arm |
| GET /mgmt/config | effective + all four layers + restart-required keys |
| PATCH /mgmt/config | hot-apply runtime knobs; persists to state config.json |
| GET /mgmt/usage | 5h output-token window + persisted ratelimit headers |
| GET /mgmt/compact | compact outcomes + shadow-classifier tail |
| GET /mgmt/auth, POST /mgmt/auth/refresh | token introspection (masked), refresh |
| GET /mgmt/logs?tail=N | proxy log tail from ~/.claude-codex/logs/ |
| GET /mgmt/models | catalog, windows, pinned, discovery ids |

Config layering: defaults ← state/config.json ← env ← runtime PATCH. Env is
the boot authority (the launcher writes it); PATCH wins until restart and
persists to the file layer. `port`, `claudithosPort`, `upstreamTimeoutMs`
need a restart; everything else hot-applies on the next request.

## Gates

```
npm run gate:rules    # ast-grep scan (tree) + rule red/green tests
npm run test:hooks    # orchestrator routing tests
npm test -w server    # 77 node --test behavior/invariant tests
npm run lint -w webui # FSD boundaries — the architecture is lint-enforced
npm test -w webui     # vitest
npm run build -w webui# tsc strict + single-file dist (commit dist/index.html)
```

Green means all of them. A wall block means fix the code, not the wall.

## Compaction doctrine

Detection is a POSITIVE marker only: the verbatim v2.1.207 summarizer prompt
("tasked with summarizing conversations"), tools-agnostic — real compaction
requests carry tools; the builder strips them upstream. Never add size/content
heuristics (the v13/v24 misfire class). The shadow classifier logs
`{has_marker, tool_count, sys_len}` on every request; the marker canary test
pins the sentence. On drift: update `COMPACT_MARKER` + fixture together.

## Out of scope (locked)

No reasoning replay. No transcript shrinking. No fake summaries. claudithos
stays an experiment tool for disposable tasks only.
