# AGENTS.md — contracts for anyone (human or agent) touching splice

## The invariants (L1 retired; L2–L4 locked)

Structural walls enforce these at write time (`.rules/kotlin-splice/` for the
gateway, `.rules/rules/` for the webui, orchestrated by
`.claude/hooks/orchestrator.py`) and permanent tests enforce the behavioral
half (the `gateway/` module suites, plus the migration oracle's 11 byte-exact
fixtures — `npm run oracle:replay`). Do not weaken either.

1. **L1 — hard lock RETIRED (2026-07-14); replay DEFAULT-OFF (2026-07-15,
   measured).** Was "no reasoning-item replay." Replay is now a supported,
   config-gated behavior (`replayReasoning`): `codex/translate-request.mjs` can
   set `include: ['reasoning.encrypted_content']` and decode `redacted_thinking`
   blocks back into reasoning input items. But it ships **OFF**: measured, replay
   makes the model REUSE its prior thinking instead of re-deriving fresh, which
   suppressed the detailed-reasoning "wall" — output fell ~4× and the reasoning
   went thin. So the default IS the pure distillation loop — **mirror on (L2),
   replay off** — and `prompt_cache_key` (keyed on the first user message) warms
   the INPUT cache independently of replay (66% hit, up to 99% on continuation,
   with replay off). Opt into replay for extra cache warmth over reasoning depth
   with `CLAUDEX_REPLAY_REASONING=1`.
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
- Config dirs keep their names: `~/.claude-codex`, `~/.claude-splice`.
- Ports: claudex 3099, control 3096. `/health` keeps `version`.
- Discovery prefix `claude-codex--`; the pinned model is excluded from
  `/v1/models` (it rides `ANTHROPIC_CUSTOM_MODEL_OPTION`).
- Effort precedence (v27): explicit body effort field, then the Claude
  `/effort` picker (`thinking.budget_tokens`), then config/env fallback, then
  `high`. Compact turns inherit the session's own model AND effort (a mismatch on
  either invalidates the prompt cache and re-reads the whole transcript cold);
  tools stripped.
- Mirror wire format: `\n[reasoning summary]\n<text>\n` (`mirrorWireText`).
- Reasoning replay envelope (`reasoning/replay.mjs`): encrypted reasoning rides
  as a `redacted_thinking` block tagged `splice-reasoning` v1; encode/decode
  stay paired (a tag/version bump strands in-flight transcripts).
- `prompt_cache_key`: `splice-<sha256(first user message)[:32]>`, stable per
  conversation. Changing the derivation cold-starts every live session's cache.

## Management API

> **Wire contract, Kotlin implementation.** The behaviour below is the contract; the `server/`
> Node tree that first implemented it was **deleted on 2026-08-10** (P8-CUT). The live sources are
> `gateway/control/.../ControlServer.kt`, `gateway/gateway/.../wire/SseEmitter.kt` and
> `gateway/gateway/.../reasoning/Mirror.kt`. Where this section still reads as prose about a
> `.mjs` file, treat the contract as authoritative and the filename as history — the 11 byte-exact
> oracle fixtures pin the wire itself.

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

Config layering: defaults ← `[defaults]` TOML ← `[heads.<key>.overrides]` TOML ←
state/config.json ← env ← runtime PATCH. Env is the boot authority (the launcher
writes it); PATCH wins until restart and persists to the file layer. `port`,
`grokPort`, `controlPort`, `upstreamTimeoutMs` need a restart; everything else
hot-applies on the next request.

All heads share ONE `ConfigService` (one JVM — unlike the Node lineage's
process-per-head), so a knob read via `getConfig()` governs EVERY head. Anything
that belongs to a single upstream account — `maxInflight`, `maxQueued`, the
timeouts — must be read via `getConfig(headKey)` and set under
`[heads.<key>.overrides]`. NB `ConfigService(headOverrides = …)` is NOT that: it
is the flat GLOBAL layer sourced from the topology file. The per-head map is
`perHeadOverrides`.

## Control plane (spliced)

> **Wire contract, Kotlin implementation.** Same caveat as above: the control plane is now
> `gateway/control/src/main/kotlin/splice/control/ControlServer.kt`. The Node
> `src/control-server.mjs` it replaced was deleted on 2026-08-10.

The dashboard is centralized. `spliced` (`src/control-server.mjs`, loopback
`:3096`, `controlPort`) hosts the single webui at `/` and a bearer-guarded
`/api/*` that AGGREGATES every head (same mgmt-key). It reads file-based truth
(auth, usage, compact) directly so a DOWN head is still visible, and talks to
RUNNING heads over `/mgmt` for live status + config.

| route | purpose |
|---|---|
| GET /api/heads · POST /api/heads/:head/{start,stop,restart} | live status + full lifecycle |
| GET \| PATCH /api/config | effective + layers; PATCH fans out to running heads |
| GET /api/usage | per-head 5h usage + soft-warn (ok/warn/critical) |
| GET /api/auth · POST /api/auth/:head/{refresh,login} | per-head auth; codex browser login |
| GET /api/compact · GET /api/logs/:head | compact stats, per-head log tail |

- **Head lifecycle is shared** (`launcher/heads.mjs`): the CLI launcher and spliced
  call the SAME registry + spawn/kill primitives (never forked). Control-side
  spawns strip `CONFIG_ENV_NAMES` so `config.json` wins over inherited env.
- **Config PATCH fans out** to each running head's `/mgmt/config` (the runtime
  layer, which beats the launcher's env pin); with no head up it writes the file.
- **Soft-warn never blocks** (`usage/warn.mjs`, `usageWarnPct` / `usageWarnTokens5h`):
  drives the dashboard banner and the statusline `⚠`.
- Heads keep `/mgmt` but no longer serve `/dashboard`. `claudex dashboard` opens
  spliced; every head launch best-effort-starts it.

## Gates

```
npm run gate          # all Kotlin/Node/webui/release/OSS checks, ONE PASS/FAIL
npm run gate:rules    # ast-grep scan (tree) + rule red/green tests
npm run test:hooks    # orchestrator routing tests
npm test -w server    # 103 node --test behavior/invariant tests
npm run lint -w webui # FSD boundaries — the architecture is lint-enforced
npm test -w webui     # vitest
npm run build -w webui# tsc strict + single-file dist (commit dist/index.html)
```

The Kotlin gateway tier runs under `./gradlew check` (from `gateway/`, JDK 21): module-law
(config-time), detekt (`maxIssues:0`), the Konsist arch-tests, every unit test, and the
1000-stream load test. It runs in CI (`gateway-gradle` job) and inside `npm run gate`. Before this
existed it was authored but NEVER executed by automation — only the ast-grep walls ran.

Green means all of them. A wall block means fix the code, not the wall — never trust a filtered
`gradle | grep` exit; read the real `BUILD SUCCESSFUL` / `GATE: PASS`.

## PR titles are linted; branch commits are not

`.github/workflows/pr-title.yml` enforces Conventional Commits on the **PR title** via a REQUIRED
check. Allowed types, and this is the complete list:

    feat  fix  docs  test  build  ci  chore  perf  refactor  revert  release  codex

Scope optional (`fix(walls): ...`). Anything else fails `lint` and blocks the merge.

WHY THIS IS EASY TO GET WRONG THREE TIMES IN A ROW (it happened, 2026-07-28/29): branch commit
messages are NOT linted, so `harden(walls):` and `verify(x):` write and review fine locally, and the
title is checked only after the PR exists — late, well after the work reads finished.

AND THE CHECK IS NOT SUFFICIENT. Corrected 2026-07-29, same day the original claim was written: this
section used to say the PR title "replaces your branch commits in main". It does not always. PR #66
passed `lint` with the title `chore(reasoning-cache): ...` and landed on `main` as
`verify(reasoning-cache): ...` — its BRANCH COMMIT subject, a type the linter forbids. So a green
`lint` does not guarantee `main`'s history complies; the squash subject can come from either source
and which one is not reliably predictable from the PR (#66 had 3 commits and used the commit message,
#65 had 1 and used the title, so it is not a commit-count rule — mechanism undetermined).

PRACTICAL RULE, and it costs nothing: make the BRANCH COMMIT SUBJECT use an allowed type too. Then
whichever source the squash picks, `main` is compliant. Do not rely on the title check alone.

Pick the type from the list above BEFORE opening the PR. `gh pr create --body ...` bypasses
`.github/PULL_REQUEST_TEMPLATE.md`, so the template's reminder never reaches an agent — this section
is the one that does.

## Enforcement tiers (#924: make drift not compile)

Each failure class this codebase hit is now stopped by the STRONGEST tier that can express it —
CLAUDE.md/review are the last resort, not the first. Measure the generator (agents keep writing
this), not the clean snapshot.

| failure class | tier that now stops it |
|---|---|
| illegal module dependency | T1 config-time `check{}` (`splice.module-law.gradle.kts`) — build error |
| `stream_options` / request-body gzip (vendor 400s) | T2 ast-grep walls, now **default-deny** across all modules (`kt-no-stream-options-request`, `kt-no-request-body-gzip`) + T2 transport-shape test (`UpstreamClientTransportTest` pins body == `UTF-8(json)`, no `Content-Encoding` — the CLASS, not just the `GZIPOutputStream` name) |
| non-atomic 0600 credential write (kimi world-readable window) | T1 single primitive `core/util/SecureFile.writeAtomic0600` — the only writer |
| duplicated percent-encoder / form body | T1 single `core/util/FormEncoding` |
| JSON null read as the literal `"null"` (null auth token → live-looking bearer `"null"`) | T1 `core/util/JsonScalars.str`/`str(key)` (JsonNull-filtering) + T2 wall `kt-json-scalars-single-source` (provider-scoped: a new provider re-deriving `?.jsonPrimitive?.content` fails the build) |
| duplicated JSONL append + tail reader (perf/compact drift; `:perf` reached into `:compact`) | T1 `core/util/JsonlSink.appendLine`/`readTail` + wall `kt-jsonl-sink-single-source` |
| `runCatching` swallowing cancellation → leaked turn (600% CPU) | T2 wall `kt-no-runcatching-in-coroutine` on the turn/stream path |
| god class suppressed instead of split | T2 detekt `ForbiddenSuppress` + wall `kt-no-quality-suppress` |
| config weakened to hide findings | T2 `checks/config-guard.sh` (no baseline, maxIssues:0, walls stay `severity:error`) |
| the tiers never running | T0 `gateway-gradle` CI job + `bash checks/gate.sh` — everything above actually executes |

The gate reads **real** exit codes (`checks/gate.sh` → `GATE: PASS/FAIL`); a filtered `gradle|grep`
exit masked BUILD FAILED twice — never trust one.

## Compaction doctrine

Detection is a POSITIVE marker only: the verbatim v2.1.207 summarizer prompt
("tasked with summarizing conversations"), tools-agnostic — real compaction
requests carry tools; the builder strips them upstream. Never add size/content
heuristics (the v13/v24 misfire class). The shadow classifier logs
`{has_marker, tool_count, sys_len}` on every request; the marker canary test
pins the sentence. On drift: update `COMPACT_MARKER` + fixture together.

## Out of scope (locked)

No transcript shrinking. No fake summaries (L4). (Reasoning replay was formerly
out of scope; as of 2026-07-14 it is supported, and as of 2026-07-15 it is
default-off for the codex head — see "L1 — RETIRED" above.)
