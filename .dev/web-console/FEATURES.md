# splice web console: feature inventory

2026-09-17. Input to the impeccable pipeline (`init`, then `shape`, then new-work). Not a ledger
and not a design: it says WHAT the console must cover and WHERE each fact comes from. Ledger rows
come after `shape` confirms the brief, per the operator's instruction of 2026-09-17.

Branch: `feat/v0.4.0`, same worktree as every other seat. No side branch.

## 0. How this list was built

Three inputs, in order of authority:

1. **The daemon, read from source on `feat/v0.4.0`.** Every knob, table, route and telemetry field
   below is enumerated from a named file, so the denominator is the code, not memory (§2).
2. **The v0.4.0 feature list, section 9** (`.dev/campaigns/v0.4.0/FEATURES.md`), which already
   makes a redesigned, complete console a user-required item and records decisions from
   2026-09-13: left rail navigation, per-knob source and live-versus-restart disposition, honest
   subscription data, masked secrets, a coverage gate. Those decisions are carried here unchanged,
   with one exception the operator made on 2026-09-17: the visual world may be replaced; it no
   longer has to be the Torad plate system.
3. **What competing consoles offer today** (§3): Portkey, LiteLLM, Helicone, Bifrost, Kong AI
   Gateway, Cloudflare AI Gateway, Vercel AI Gateway, OpenRouter, TrueFoundry, TensorZero,
   Langfuse, Requesty, researched 2026-09-17.

## 1. Who uses it and for what

One operator, one machine, several Claude Code sessions running at once on several heads
(claudex, claude-grok, claude-kimi, claudeor, claude-splice, claude-muse, claude-deepseek on this
machine today). The operator is mid-session, usually with live turns in flight, and opens the
console to answer one question fast: which head needs me, why is this turn slow, how much plan is
left, what is this knob and where does its value come from, which account is this session on. The
console is loopback-only, bearer-guarded, served as one HTML file from the daemon jar.

There are no teams, no tenants, no end users of the console other than the operator. That rules
whole categories of competitor features out (§5) and makes others uniquely possible (§4, marked
"only here").

## 2. The daemon surface the console must cover

### 2.1 Control routes that exist today

Source: `gateway/control/src/main/kotlin/splice/control/ControlServer.kt` on `feat/v0.4.0`.

| Route | Serves | Console coverage today |
|---|---|---|
| `GET /health` | ok, version, heads ready/failed, topology digest and stale flag | banner only |
| `GET /api/status` | version, head registry (key, label, authKind) | header |
| `GET /api/heads` | per head: running, healthy, version match, gate snapshot with live turns, error counters, pids | fleet plates |
| `POST /api/heads/{head}/{start,stop,restart}` | lifecycle | fleet plates |
| `POST /api/daemon/shutdown` | stop the daemon | none |
| `GET /api/config?head=` | effective config plus every layer, restart-required keys | config page |
| `PATCH /api/config` | hot-apply runtime knobs, persist to state file | config page |
| `GET /api/usage` | per head: 5h output tokens, ratelimit headers, quota windows (plan, 5h, 7d), warn level | fleet meter |
| `GET /api/perf?tail=` | per head: p50, p95, max per perf field | none |
| `GET /api/perf/summary?window=1h,24h,7d` | per head: windowed summary (v0.4.0) | none |
| `GET /api/economics` | per head: hourly buckets of tokens, bytes, tool partition, rate limits (v0.4.0) | burn page |
| `GET /api/auth` | per head: kind, present, masked account, last refresh, plus the account pool (v0.4.0) | auth cards, pool not shown |
| `POST /api/auth/{head}/{refresh,login}` | refresh a token; login is "manual" for OAuth kinds | refresh button |
| `GET /api/compact` | outcome totals and event tail | compaction page |
| `GET /api/sessions` | Claude Code session registry joined to heads (v0.4.0) | none |
| `GET /api/logs/{head}?tail=` | log tail | logs page |
| `GET /api/mcp` | hosted MCP servers: eligible, hosted, pid, sessions, streams, restarts, last error (v0.4.0) | none |
| `POST /launch/{head}`, `/statusline/{head}` | used by the shim and the status line, not by the console | n/a |
| `GET /mcp/{name}`, `POST /mcp/{name}`, `DELETE /mcp/{name}` | the hosted MCP servers' streamable-HTTP transport for clients (open, post, close a stream), not an operator surface | n/a (excluded: client transport) |
| `GET /`, `GET /dashboard` | the console itself (dist/index.html) | n/a (excluded: it is the console) |

### 2.2 Runtime knobs

Source: `gateway/core/src/main/kotlin/splice/core/config/Knob.kt`. 33 keys. Precedence: enum
default, then `[defaults]` in TOML, then `[heads.<key>.overrides]`, then the state file, then env,
then a runtime PATCH. Three hot-apply without a restart: `maxInflight`, `maxQueued`,
`statuslineGitRoots`. The other 30 apply on restart. The console reads the disposition from
`restart_required_keys` on `/api/config`, never from a hand list.

Keys: `port`, `chatgptApiBase`, `codexAuthPath`, `pinnedModel`, `effort`, `summary`,
`showReasoning`, `replayReasoning`, `mirrorReasoning`, `progressLine`, `foldReasoningModels`,
`foldMaxContinue`, `foldMarkerText`, `foldMaxTier`, `toolSurface`, `quotaPoll`, `maxInflight`,
`maxQueued`, `upstreamRetries`, `upstreamTimeoutMs`, `firstByteTimeoutMs`, `streamIdleMs`,
`authCacheMs`, `debug`, `contextWindowOverride`, `grokPort`, `grokModel`, `xaiApiBase`,
`grokAuthPath`, `controlPort`, `usageWarnPct`, `usageWarnTokens5h`, `statuslineGitRoots`.

Some are legacy single-head knobs (`grokPort`, `grokModel`, `xaiApiBase`, `chatgptApiBase`); the
console shows them with their provenance like any other and lets the coverage gate decide whether
they are "editable" or "read-only with a reason".

### 2.3 Topology (`~/.config/splice/splice.toml`, boot-only)

Source: `gateway/core/src/main/kotlin/splice/core/topology/Topology.kt`, `QuirksConfig.kt`,
`TopologySchema.kt`, `core/compaction/CompactionScope.kt`, `core/prompt/HeadSystemPrompt.kt`,
`core/model/TokenCost.kt`. Every edit here is boot-only: `/health` reports `topologyStale` when
the file on disk no longer matches what the daemon booted with, and `splice restart` drains
in-flight turns before restarting (V4-74). One exception is live already: a model's
`context_window` reaches running sessions (0.3.1).

- `[daemon]`: `control_port`, `state_dir`, `show_reasoning`, `summary`, `effort`,
  `replay_reasoning`, `mirror_reasoning`, `fold_reasoning_models`, `fold_max_continue`,
  `fold_marker_text`, `fold_max_tier`, `mcp_hosting`, `mcp_hosting_exclude`.
- `[claude]`: `share` and `isolate` over `settings`, `mcps`, `skills`, `hooks`, `agents`,
  `commands`, `plugins`, `claude_md`, `sessions`, `projects`; `config_dir`.
- `[compaction]`: `instructions` or `file` (global); `[[compaction.model]]` with `model`,
  `instructions` or `file`; `[[compaction.project]]` with `path`, optional `model`,
  `instructions` or `file`. Inline text and file together is a load error, never silent precedence.
- `[defaults]`: any runtime knob, applied to every head.
- `[providers.<name>]`: `dialect` (openai-responses, openai-chat, anthropic-passthrough),
  `base_url`, `auth` (`kind`: chatgpt-oauth, grok-oauth, kimi-oauth, muse-oauth, api-key with
  `env`, client; optional `file`), `quirks`, `extra_headers`, `models` (`id`, `label`,
  `description`, `context_window`), `extra_windows`, `window_rules`, `default_context_window`,
  `local` (inferred for openai-chat on a loopback base URL), `rates` per model id.
- `[providers.<name>.quirks]`: `store`, `account_id_header`, `cache_key`, `effort_ceiling`,
  `summary_field`, `compact_effort`, `tool_choice`, `reasoning_cache`, `parallel_tool_calls`,
  `websocket`, `code_mode`, `zstd_request_body`, `reasoning_effort`, `mfjs`, `block_allowlist`,
  `strip_cache_control`, `synthesize_signatures`, `map_thinking_adaptive`,
  `strip_sampling_params`, `reanchor_prefill`; sub-table `tool_surface` with `enabled`,
  `defer_prefixes`, `defer`, `eager`, `min_deferred`, `search_limit`, `search_rounds`.
  Which quirks apply depends on the dialect; the console must show only the ones the provider's
  dialect reads and say why the others are absent.
- `[heads.<key>]`: `provider`, `port`, `discovery_prefix`, `pinned_model`, `models` as
  `{id, slot}` rows where slot is opus, sonnet, haiku or fable, `context_window`, `overrides`
  (runtime knobs as strings), `claude` (`command`, `share`, `isolate`), `system_prompt` or
  `system_prompt_file` with `system_prompt_mode` append or replace, `rates`.
- Model rates: `input`, `cache_read`, `output`, optional `cache_write`, per million tokens.
  Absent rates mean "no dollar figure", never zero.

### 2.4 Per-turn telemetry

Source: `gateway/core/src/main/kotlin/splice/core/perf/PerfKeys.kt` and
`gateway/gateway/src/main/kotlin/splice/gateway/perf/PerfStats.kt`. One JSONL row per turn per
head, 37 field names plus `ts`, `model`, `outcome`, `compact`, `session` (first 8 of the Claude
Code session id) and, after an account switch, the account label and a cache-cold tag.

- Stage marks, ms since arrival, in pipeline order: `recv`, `parse`, `build`, `gate`,
  `headers`, `first_byte`, `first_frame`, `first_delta`, `stream_end`, `finish`, `total`.
- Counters: `auth_ms`, `backoff_ms`, `refresh_ms`, `write_ms`, `usage_ms`, `attempts`,
  `retries`, `refreshes`, `post_send_retries`, `req_bytes`, `upstream_req_bytes`,
  `sse_bytes_in`, `events_in`, `frames_out`, `content_frames_out`, `frames_skipped`,
  `bytes_out`, `in_tokens`, `cached_tokens`, `cache_write_tokens`, `out_tokens`, `inflight`,
  `async_io_drops`, `tools_eager`, `tools_deferred`, `search_rounds`.
- Retention: the perf lane rotates at 64 MiB with one previous file, about 20 days on this
  machine. A 30-day view needs a daily roll-up, which the economics rollup already is.
- The control plane exposes aggregates only (`/api/perf`, `/api/perf/summary`). The per-row tail
  reader exists server-side (`PerfStats.tailNumeric`) and is not routed yet.
- Live turns: `gate.live` on `/api/heads` carries label, compact flag, phase (connect or
  streaming), age and idle time per in-flight turn, plus the head's `stream_idle_ms` threshold.

### 2.5 Usage, quota and economics

Sources: `control/api/UsagePayloads.kt`, `app/quota/QuotaProbes.kt`, `control/api/EconomicsPayloads.kt`.

- Per head: output tokens in the trailing 5 hours, entry count, the provider's
  `x-ratelimit-*` family when it sends one, the warn policy (level, pct, source, reset).
- Subscription windows by provider: ChatGPT reports plan plus five-hour and seven-day windows;
  Kimi a weekly quota plus a five-hour rate window; Grok a weekly credit period; Muse a weekly
  window inferred from headers. API-key providers and the Claude passthrough report nothing and
  must read "not reported by provider", never zero.
- Economics: hourly buckets per head over a retention window with turns, `in_tokens`,
  `cached_tokens`, `cache_write_tokens`, `out_tokens`, request bytes, upstream bytes, eager and
  deferred tool counts, deferral turns, rate-limited turns, and the provider ceiling when known.
  Sums only; every rate is derived in the console so it stays recomputable per window.
- Session cost: `control/SessionCost.kt` prices a session's buckets with the head's declared
  rates and falls back to the client's own `total_cost_usd` when no rates are declared.

### 2.6 Auth and account pools

Source: `control/api/AuthRoutes.kt` on `feat/v0.4.0`.

- Per head: auth kind, whether login is automated (OAuth) or manual (key), credential present,
  masked account id, last refresh, credential path, refresh latch reason.
- Pool per OAuth kind: `selected_label` plus accounts with `label`, `primary`, `selected`,
  `available`, `credential_present`, `auth_excluded_until_epoch_millis`,
  `auth_exclusion_reason`, `plan`, `five_hour_used_percent`, `five_hour_reset_epoch_seconds`,
  `seven_day_used_percent`, `seven_day_reset_epoch_seconds`; the last switch as `from`, `to`,
  `reason`, `at_epoch_millis`.
- Selection is per turn and sticky per session; never inside a turn. All accounts exhausted
  fails the turn in words naming the earliest reset.
- Every pooled account has its own quota poller (`ManagedHeadFactory.kt:169`, every 5 minutes),
  so windows are known for idle accounts too, not only the selected one. Pools exist for
  `chatgpt-oauth`, `grok-oauth`, `kimi-oauth` and `muse-oauth`. A pool is keyed by the head's
  primary credential FILE (`OAuthAccountFiles.kt:178-186`), and its runtime state (selection,
  stickiness, cooldowns, last switch) is per head; two heads share a pool only when they share the
  credential file. A head with one login has no pool object at all (`HeadAccountPools.kt:54`).
  Selection order is primary if available, else the session's sticky account, else lowest
  seven-day used (`AccountPool.kt:101-112`); an account with no snapshot sorts as zero used. The Claude head runs
  `auth = { kind = "client" }`: the client's own login passes through, the daemon holds no Claude
  account and tracks no Claude window today (the statusline route reads `context_window` only,
  `StatuslineRenderer.kt:141`).
- Windows per provider: ChatGPT primary and secondary windows (5h and weekly) plus `plan_type`
  (`CodexQuotaProbe.kt:39-45`); no provider probe reports a per-model window today.
- Actions today: refresh. Login (`splice login <head> --label <name>`, device or browser flow)
  and `/login` inside a head run in the terminal. No manual switch endpoint exists.

### 2.7 Sessions

Source: `control/api/SessionsRoutes.kt`, `core/sessions/SessionRegistry`. Rows from
`~/.claude/sessions/<pid>.json` joined to the head that launched them: `pid`, `session_id`,
`name`, `kind`, `version`, `cwd`, `status`, `status_updated_at`, `started_at`, `updated_at`,
`address`, `head` or "unknown head", `availability` (live, stale, gone). Headless `claude -p`
runs never register and the payload says so. Cross-head resume (`<head> -r <id>`) is a v0.4.0
feature the console can explain but must not perform (§12 of the v0.4.0 list).

### 2.8 Shared MCP hosting

Source: `control/mcp/McpHost.kt`, `McpStatus.kt`, `McpHostConfig.kt`. Per configured stdio
server: eligible or the reason it is not, hosted, pid, session count, open streams, started at,
last activity, restarts, last error. Host knobs: idle timeout 30 min, max servers 32, request
timeout 30 min, initialize timeout 1 min. Toggle and exclude list live in `[daemon]`.

### 2.9 Compaction

Sources: `gateway/compact`, `core/compaction`. Outcome totals and a tail of events with head,
timestamp, outcome, chars, ms, status, error. Effective instructions per scope with their source
(global, model, project), resolved at compaction time. Compaction runs on the session's own model
and effort by law; the console must never offer a compaction model knob.

### 2.10 Code mode

Sources: `app/codemode/*`. Worker pool with cells that can be parked, evicted or lost; bounded by
workers, time, heap and wire. State reaches the operator only through `[<head>][code-mode]` log
lines today. No endpoint exists. The console can show the quirk (`code_mode`) and surface the log
lines by tag until an endpoint lands.

### 2.11 CLI verbs with no console equivalent

From `CHANGELOG.md` Unreleased on `feat/v0.4.0` and `app/cli/Command.kt`: `doctor [--json]`,
`status`, `sessions`, `perf [--window]`, `add <profile>` (guided provider setup, five profiles),
`add-model` (edits the roster through the TOML structure, never raw text), `upgrade [--to] [--now]
[--rollback]`, `login <head> [--label]`, `key set`, `logs`, `restart`, `setup`, `init`, `install`,
`uninstall`, `dashboard`, `version`.

### 2.12 What the daemon does not have

- Request or response bodies. Only sizes and token counts are recorded. Keep it that way by
  default; body capture, if ever wanted, is a per-head opt-in with its own row.
- A cause for a slow turn. Marks separate queue wait, upstream wait and streaming time; nothing
  says why the upstream was slow. The console labels the phases and stops there.
- Dollar cost without declared rates. Rates are optional per model.
- Per-turn rows over HTTP, a model catalog route, a doctor route, a manual account switch, a
  topology read or write route, a draining restart route. See §6.

## 3. What competing consoles offer

Researched 2026-09-17. Market context: TensorZero archived (June 2026); Helicone in maintenance
after the Mintlify acquisition; Portkey acquired by Palo Alto Networks; Langfuse by ClickHouse;
Bifrost is the 2026 newcomer that reset the performance bar.

Table stakes, present in essentially every console:

1. Request logs with per-request model, status, latency, tokens and cost, filterable, exportable,
   with a drawer showing the full request. Pattern: table plus side drawer, optional follow-live.
2. Cost analytics by model, key, team, day, with stat tiles, a stacked time chart and a breakdown
   table, plus an explore view where the user picks metric, group-by and granularity.
3. Routing: fallback chains, load balancing weights, conditional rules, retries. Either a code
   view (YAML or TOML) or a reorderable list; no product unifies both views of the same config.
4. Model catalog with pricing and context windows.
5. Keys with budgets and rate limits; hierarchical budgets (org, team, key).
6. Alerts on spend or error rate; audit log; teams, RBAC, SSO.
7. Semantic cache configuration and hit rate; guardrails; prompt playground with versions.

What users praise: one-line drop-in integration; rotating provider keys without touching code;
per-user and per-property breakdowns with a query language (Helicone); click-through from any chart
into the underlying logs (OpenRouter Activity, August 2026); session replay of a whole agent run
(Requesty); one consistent interface for providers, policies and telemetry (TrueFoundry).

What users complain about: LiteLLM's admin UI is unstable at scale (key list pages that take a
database down, hydration failures leaving a spinner, a "UI DDoSing itself" thread); Portkey gates
budget enforcement behind enterprise pricing; Cloudflare's gateway returns unexplained 500s with no
support answer; Vercel's gateway does not retry a retryable 503 unless fallbacks are configured;
TensorZero's product disappeared.

Gaps none of them fills, because none of them sits next to one human's live coding session:
explaining why a request routed where it did, showing an in-flight stream, tying cache hits to
the conversation on screen, any concept of compaction, config diffing with provenance, per-session
views for one person, subscription OAuth lifecycle, rolling subscription windows.

## 4. The feature list

Status: **have** means the data and the route exist; **route** means the data exists server-side
but needs a route (§6); **new** means daemon work beyond a route. Origin: **daemon** means it
exposes splice's own machinery; **table stakes** means competitors have it and an operator will
expect it; **only here** means no competing console can offer it.

### 4.1 Shell (cross-cutting)

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Views | Every list page is one data source shown through saved views, the way Notion databases work (notion.com/help/views-filters-and-sorts, read 2026-09-17): a view is a layout (rack, board, timeline, table) plus its own filter, sort, group and sub-group, visible fields and name; settings belong to the view, never to the page; views sit as tabs beside the page title with a default view first; add, rename, duplicate, reorder and delete views; copy a link to a view; search inside a view. Teams ships three: board by head, board by role, timeline of the day. Sessions ships by head, by project, by team and a timeline. Turns ships table and timeline. Accounts ships by provider and by headroom. View definitions live in the console's local storage first, with export as JSON; no daemon route. Chosen by the operator in the comp round ("make each a view of the page, switch based on need, like Notion"). | new | operator 2026-09-17 |
| Left rail | Fleet, Turns, Sessions, Teams, Projects, Accounts, Usage, Settings, Models, Logs, Compaction, MCP, Doctor. Each page names the decision it closes. | have | v0.4.0 §9 |
| Deep links | Every head, turn, session, knob and account has a URL. | new (router) | table stakes |
| Command palette | Jump to a head, knob, session or account; run start, stop, restart, refresh, switch. Keyboard first. | new | table stakes |
| Honest states | Every number is measured, estimated, unavailable or stale, and says which. Loading is a skeleton in the final shape. Empty states teach. Missing subscription data is "not reported by provider", never zero. | have (policy) | v0.4.0 §9 |
| Staleness | Every polled surface shows age past 15 s; the header shows daemon version, heads ready and failed, topology stale. Fixes the header stuck on "connecting" after unlock. | have | daemon |
| Key gate | Paste the management key once; poll only after unlock; re-arm on unlock. | have | daemon |
| Theme | Light and dark, both AA for text, controls, focus and data inks. Dark by default regardless of the OS, light available, a manual switch remembered (operator 2026-09-17). | have (policy) | v0.4.0 §9 |
| Mutations confirm | Stop, restart, shutdown, account switch, topology write and any restart-required change show what will happen and to which live sessions before firing. | have (pattern) | daemon |
| Single file | One inlined HTML served from the jar; loopback only; no external requests. | have | daemon |

### 4.2 Fleet

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Head table | One row per head: label, provider, dialect, auth kind, port, version and match, healthy, gate inflight and queued against max, local and provider error counters, pids, selected account, plan windows. Sortable, dense, the whole fleet above the fold. | have | daemon |
| Live turns | The gate's live list per head: session, phase, age, idle, compact flag, drawn against `stream_idle_ms` and `firstByteTimeoutMs` so a hung turn looks different from a reasoning one. | have | only here |
| Attention banner | Heads at warn or critical usage, stalled turn path, failed heads, topology stale, version mismatch. Absent when nothing is wrong. | have | daemon |
| Lifecycle | Start, stop, restart per head with confirmation naming the live sessions a stop or restart would interrupt. Daemon restart drains turns first. | have, restart route new | daemon |
| Shutdown | Stop the daemon, with the same confirmation. | have | daemon |

### 4.3 Turns (performance)

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Turn list | One row per turn, newest first, filter by head, model, outcome, session, compact, account, window (1h, 24h, 7d). Columns: time, head, model, outcome, total, first byte, tokens in, cached, cache write, out, retries, attempts, inflight. | route | table stakes |
| Turn waterfall | Per turn: a stage bar from recv to finish, so queue wait (gate), upstream wait (headers, first byte) and streaming (first delta to stream end) are visibly separate. Counters beside it: retries, refreshes, backoff, post-send retries, bytes each way, frames, tool partition, search rounds, async io drops. | route | only here |
| Summary per head | The v0.4.0 summary: p50 and p95 of first byte and total, failure share by outcome, retries and refreshes totals, cache hit ratio, peak inflight, rows with dropped telemetry. Selectable window. | have | daemon |
| Distribution charts | Latency percentiles over time per head; outcome mix over time. | have (from summary and economics) | table stakes |
| Cross-links | A turn links to its session, its head, its account and its log lines by session tag. | route | only here |
| Honest gaps | Rows with `async_io_drops` > 0 read "telemetry dropped". A window with no rows reads "no turns in window", never zero latency. | have (policy) | v0.4.0 §3 |

### 4.4 Sessions

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Session list | Live, stale and gone sessions with pid, name, cwd, head, version, started, last update, status. Headless runs noted as not registrable. | have | v0.4.0 §4 |
| Session detail | Turns for this session joined by the perf `session` tag, tokens by bucket, estimated cost when rates exist, the account each turn used, compactions. | route | only here |
| Coordination | The copyable `SendMessage` instruction per live session. No socket writes from splice. | have | v0.4.0 §4 |
| Head ownership | Which head owns the transcript, and the `<head> -r <id>` command to pull it elsewhere. Explained, not performed. | have | v0.4.0 §12 |
| Group by head | The session list grouped by the head (provider and model) that launched each session. | have | daemon |
| Group by repo | The same list grouped by project: the git root of each session's `cwd`, resolved only inside the trusted root set the statusline already uses (`$HOME`, `/tmp`, `statuslineGitRoots`; `StatuslineRenderer.kt:213`), through a cached resolver with the branch cache's TTL and bound (nothing derives a root today; `rev-parse` is new). Worktrees group under their shared repo (`git rev-parse --git-common-dir`) with the worktree shown as a sub-label, so v0.4.0 sessions and main sessions land in one project. A cwd outside the trusted set groups under its cwd with the reason shown. Entry point to the Project page (§4.14). | route | operator 2026-09-17 |
| Group by team | The same list grouped by the team the operator assigned the session to (§4.13). | new | operator 2026-09-17 |
| Transcript | Read the session's conversation: user turns, assistant output, tool calls and results, system prompt, paginated and searchable. Source: the client's own local transcript file, joined by `session_id`, under the head's own config dir (`CLAUDE_CONFIG_DIR/projects/<slug>/<session_id>.jsonl`, real directories per head since V4-115, `ProjectsLink.kt`); the vanilla `~/.claude/projects` tree for sessions splice did not launch and for history written before the un-link (the reader falls back per id and shows which path it read). Read as a conversation between participants, not a log tail. For clients with no local transcript, the opt-in body capture in §4.9. Never sent anywhere; read from disk on demand. | route | operator 2026-09-17 |

### 4.5 Accounts

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Pool per provider | Every account with label, primary, selected, available, plan, five-hour and seven-day windows with resets, exclusion reason and expiry, credential present. Secrets never shown. | have | daemon |
| Which head is where | For each head: selected account, last switch with reason and time, and which sessions ride it. | have | only here |
| Refresh | Per account. | have | daemon |
| Manual switch | Pin the account a head uses from the next turn. Needs a real pin in `AccountPool`: `select` is policy-only and the next turn's primary preference undoes a nudge (`AccountPool.kt:101-112`). | new | v0.4.0 §9 |
| Add account | Start `login --label` from the console: show the device code or open the browser flow, confirm when the credential lands. Until a route exists, show the exact command. | new | v0.4.0 §9 |
| Remove and relabel | Manage the pool without touching files. | new | v0.4.0 §9 |
| Key providers | API-key heads: key present or not, which store it came from (env or `keys.toml`), rotate via `key set`. | have (partial) | table stakes |
| All accounts, one screen | Every account of every provider on one page: provider, label, plan, 5h used and reset, weekly used and reset, per-model windows where the provider reports them (otherwise "not reported by provider"), available, excluded with reason, selected, which heads and sessions ride it. Windows are labeled by their reported length (Grok reports a 30-day period, `GrokQuotaProbe.kt:41-52`; Grok and Muse report no plan), never assumed weekly; the control projection must carry the window length it drops today (`HeadAccountPools.kt:57-72`). Sorted by headroom with unknown shown as unknown, never sorted first; the account the selector takes next is marked using the real order (primary if available, else sticky, else lowest seven-day used); the earliest reset named. Single-login heads appear from the auth view, since they have no pool. This replaces the operator's manual hunt through browser logins for an account with room (operator 2026-09-17: "one of the most manual work I do"). | have (data for pooled kinds), route (Claude, single-login heads, window length) | operator 2026-09-17 |
| Add accounts without limit | Start a login from the console for any OAuth head, as many accounts as wanted: the device code and verification link shown on the new account strip, or the browser flow opened by the console, the credential confirmed when it lands. Needs a new observation seam: today the device flow prints its code to stdout only (`DeviceLoginFlow.kt:114-124`), the browser flow parks a thread and opens the daemon's own browser (`OAuthLoginFlow.kt:64-105`), and login is keyed by head, not kind (`LoginCommand.kt:34-46`). A new account joins the pool only at head assembly (`OAuthAccountFiles.discover`, `ManagedHeadFactory.kt:143`), so the strip stays cocked with "signed in, live after restart" until the route restarts that head. Heads that share a credential file share the login; heads with their own file need their own. | new | operator 2026-09-17 |
| Claude windows | The windows of the Claude account each session runs on, recorded from the `rate_limits` object in Claude Code's statusline payload, which the daemon already receives. The installed client (2.1.257) sends `five_hour`, `seven_day`, `seven_day_opus`, `seven_day_sonnet`, `seven_day_oauth_apps`, a `model_scoped` list of per-model weekly windows, a `rate_limits_available` flag (false for API-key, Bedrock and Vertex sessions) and `spend_limit`. Gate on the flag, never on a version number. Record only the window fields; the payload also carries session cost and per-model usage, which stay unrecorded under PRODUCT.md line 78. Official channel, no extra calls, no token handling. Dependency: the statusline route is bearer-guarded in this tree while the launcher's curl sends no bearer (`ControlServer.kt:158-159`, `LaunchSpecFactory.kt:84`); a ledger item already names it. | new | operator 2026-09-17 |
| Claude logins | Several Claude logins on the splice-owned Claude head (`claude-splice`, its own config dir), one chosen at session launch by materializing its credential file into that config dir; traffic still flows through Claude Code's own login, splice never calls Anthropic with a consumer token. This is not the account pool: `auth = { kind = "client" }` builds no pool, no poller, no availability, no exclusion and no per-turn selection (`HeadAccountPools.kt:27,54`), and the credential file is per head, so every live session on the head shares one login and a swap while a session runs collides with that session's own token refresh. The console renders Claude as launch-time selected, no mid-session switch, no next target; the constraint (one login per Claude head at a time, or a config dir per session, which is a later design) is printed on the strip. Never on a wrapped default head. Ruled in by the operator on 2026-09-17; Anthropic's consumer terms reserve OAuth for Claude Code and claude.ai, and the operator owns that reading. | new | operator 2026-09-17 |
| Per-model windows | Shown for Claude from the statusline payload (`seven_day_opus`, `seven_day_sonnet`, `model_scoped`). For probed kinds the daemon's snapshot holds exactly two windows filed by length (`Quota.kt:17-34`), so the empty says "splice keeps two windows per account" rather than blaming the provider, until the snapshot grows a scoped slot. | new | operator 2026-09-17 |

### 4.6 Usage (burn)

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Plan windows | Per head and per account: five-hour and seven-day used percent with resets, or "not reported by provider". Warn and critical thresholds from config. | have | only here |
| Burn | The v0.4.0 burn page: input tokens against the ceiling, burn rate, projected exhaustion, cache hit rate demoted to the ledger on purpose. Folded into this page, not a separate tab. | have | daemon |
| Tokens over time | Hourly buckets per head: in, cached, cache write, out, bytes each way, deferral rate, rate-limited turns. | have | table stakes |
| Cost | Dollars from declared rates per model, labelled estimated; the client's own figure as fallback; "no rates declared" otherwise. | have | table stakes |
| Explore | Pick metric, group by head or model, window and granularity; every chart clicks through to the turns behind it. | route | table stakes |
| Ratelimit headers | The provider's live limit, remaining and reset when it sends them. | have | daemon |

### 4.7 Settings

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Every knob | All 33 runtime knobs, grouped by concern, each with default, TOML, per-head override, state file, env, runtime and effective value, plus hot or restart. Advanced groups behind a disclosure. | have | v0.4.0 §9 |
| Validated editing | Typed inputs from `KnobKind` and the known enums; review as a diff; apply with per-target results; restart-required keys say so before apply. | have | daemon |
| Per-head view | The effective config for one head folds its overrides in. | have | daemon |
| Topology editor | `splice.toml` in a code editor with syntax, validation and a side-by-side diff before write. Writes go through the structured TOML writer `add-model` uses, never raw text. Backs the file up first. Marks the daemon stale and offers the draining restart. | route, new | only here |
| Provider forms | Add a provider from the five `add` profiles; edit base URL, auth kind and env, quirks filtered by dialect, extra headers, models with windows and rates, local flag. | route | table stakes |
| Head forms | Add or edit a head: provider, port, prefix, pinned model, slots per tier, context window, overrides, sharing and isolation, system prompt inline or file with mode, rates. | route | daemon |
| Compaction instructions | Global, per model and per project scopes; inline or file, never both; effective text preview. | route | only here |
| MCP hosting | Toggle and exclude list. | route | daemon |
| Secrets | Masked, replace-only, never read back. | have (policy) | v0.4.0 §9 |
| Coverage | A knob without a console disposition fails the build. See §7. | new (gate) | v0.4.0 §9 |

### 4.8 Models

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Catalog per head | Rows with id, label, description, slot, context window and its source, rates, pinned. The tiers Claude Code will and will not get on this head, and what degrades when a tier is undeclared. | route | table stakes |
| Windows | Extra windows and window rules, the forced head-wide window, the live window each session reports. | route | daemon |
| Add model | The `add-model` flow as a form. | route | daemon |

### 4.9 Logs

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Virtualized viewer | Tail of the daemon log with follow mode, pause, search, filter by head tag and by subsystem tag such as `[code-mode]`, larger tails on demand. | have | table stakes |
| Cross-links | A `perf outcome=` line opens the turn; a session tag opens the session. | route | only here |
| Path and rotation | Shown, with the reason when a tail is empty. | have | daemon |
| Request drawer | For heads with body capture on (§5): the full request and response of a turn beside its perf row, redacted, exportable. Off by default and says so. | new | table stakes, operator 2026-09-17 |

### 4.10 Compaction

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Outcomes | Totals by outcome and the event tail with head, chars, ms, status, error. | have | daemon |
| Effective instructions | Which text applied to a compaction and from which scope. | route | only here |
| Law | States that compaction runs on the session's own model and effort, and why. | have | daemon |

### 4.11 MCP

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Hosted servers | Per server: eligible or why not, hosted, pid, sessions, streams, started, last activity, restarts, last error. | have | daemon |
| Host limits | The four host knobs, read-only with the reason. | have | daemon |

### 4.12 Doctor

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Checks | The `doctor --json` report: every check with status and the exact fix command, copyable. Redacted like the CLI. | route | daemon |
| Claude head mode | Two ways to run Claude through splice, chosen here and in Settings. Separate (default): `claude-splice` is a splice-owned head with its own config dir, logins, projects and transcripts, and the operator's plain `claude` stays vanilla and untouched. Wrap: the operator's default `claude` command becomes a splice launcher over the vanilla config dir (`~/.claude`), so their history, login and memory stay where they are and every turn gains splice's routing, perf and compaction; no per-head isolation and no account pool on a wrapped head. Wrap is not free of side effects and the page says so: materialization writes a merged `settings.json` (model allowlist, `enforceAvailableModels`, the statusline block, six hook groups) and `.claude.json` (`ClaudeConfigMaterializer.kt:88,108,295`), so wrapping edits those two files in `~/.claude`, backed up on wrap and restored on unwrap; the materializer's DR-102 guard refuses `~/.claude` by design (`:121-131`), so wrap needs its own deliberately narrowed path, never a bypass of that guard; the shim takes its head from its own basename and execs `claude` from PATH (`splice-launch:8`, `LaunchService.kt:32,78-79`), so a shim named `claude` needs the real binary's absolute path threaded in, and "backing up the shadowed binary" means preserving the symlink target (`~/.local/bin/claude` is a symlink to the versioned binary). The page shows what `claude` on PATH resolves to today, and wrap and unwrap are one action each with the exact shim path and the two backed-up files shown. | new | operator 2026-09-17 |
| Version drift | Client version against the tested version. | route | daemon |
| Upgrade | The `upgrade` status: installed, latest, rollback available. | route | daemon |

### 4.13 Teams

The operator's own multi-session setups: a frontier model orchestrating or reviewing, cheaper or
faster models building, sometimes two leads. Composition changes per project and per feature, so
the console surfaces it and never enforces it. The sessions talk to each other through the
client's own messaging (Claude Code `SendMessage` between local sessions); splice launched them on
different heads and knows which head each one is on.

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Team list | Every team with name, goal, repo, member count, live members, turns and cost so far. | new | operator 2026-09-17 |
| Team composer | Create or edit a team: name, goal, feature list, repo (git root), and role slots (role, head, optional model and account). Roles are free text with suggestions (orchestrator, lead, builder, reviewer, researcher). A team is slots, never pids: sessions bind to a slot and unbind when they end, and the team survives every terminal restart. Stored in daemon state, not in `splice.toml`. | new | operator 2026-09-17 |
| Bind sessions | Bind live sessions to slots by picking them, or by rule (repo plus head maps to a role). No rule about how many teams a session may serve; the operator said composition is never strict. | new | operator 2026-09-17 |
| Team board | Three saved views of one team, switchable as tabs: board by head (sessions racked under the head they run on, a hand-off strip sliding between bays), board by role (lead, builder, reviewer bays with honest empties and a time rule of hand-offs), timeline (time runs down the page, one column per member, messages crossing columns at their time, cost per role beside it). Chat and the activity feed stay as panels in every view. The room, not a cost center: the team's sessions on their heads with roles printed, which lead is driving now (the slot flagged lead, a flag independent of the free-text role name, whose session most recently sent a message), hand-offs drawn from message edges (a lead's message to a builder, the builder's reply), each slot's state, and cost per role beside what it landed (turns, errors). | new | operator 2026-09-17 |
| Message edges | From session, to address, time and direction, derived on the wire from the `SendMessage` tool call's input (recipient and time) in the request the gateway already sees; new wire observation, because today's activity label only records that a message went by (`ActivityLabel.kt:43,105` never reads the recipient). Addresses resolve through the registry (`SessionRegistry.kt`). No message text is recorded; the text is read on demand from the transcript. Ruled metadata, kept by default (operator 2026-09-17, recorded in PRODUCT.md). | new | operator 2026-09-17 |
| Team chat | The team's messages as one group chat in time order, like a chat room: every message any member sent or received, with sender role and head, read from the members' transcript files on demand and indexed by the recorded edges. Readable for any day the transcripts still exist; a missing transcript leaves the edge with "text not on disk". Transcripts written before the per-head un-link (V4-115) stay in the vanilla tree; the reader falls back there for an id missing from the head's tree and shows which path it read. | new | operator 2026-09-17 |
| Team tracking | Per team and per role: turns, tokens by bucket, estimated cost, plan window use, over the team's lifetime and per day, joined on the perf `session` tag, never on the live registry (a gone pid loses its head, `SessionRegistry.kt:126`). The tag is an 8-character prefix of the session id, per head file (`TurnDrive.kt:100-103`); turns with no client session id are counted and shown as unattributed, never dropped from a total. History reaches back only as far as the perf files' 64 MB rotation keeps (§6 storage); the page names the oldest turn it can see. | route | operator 2026-09-17 |
| Launch into a team | Start a new session on a head with the team's role preset, using the existing launch route. | have | daemon |
| Role instructions | Standing instructions per slot, authored by the operator and added by splice to the system prompt of every session bound to that slot: the role, who the lead is, the address to message. Rides the existing per-turn seam (`TurnPreparation.kt:149-159`, `withSystemPrompt`) with a new per-session, hot-reloadable resolver in daemon state, because the per-head resolver is boot-only and immutable by design (`HeadServerFactory.kt:73-77`). Two rules: slot text is appended after whatever the head produces, never replacing, even on a head in `system_prompt_mode = replace`; editing it mid-session changes the request prefix and costs that session one cold-cache turn, which the strip shows. Works for any client. Adopted from Claude Projects' project instructions (operator 2026-09-17). | new | operator 2026-09-17 |
| Activity feed | What each member did today, in time order, beside the chat, from the gateway's activity labels (editing a file, running a command, messaging a peer, reading), kept as a store, no file contents. Honest about its nature: the client asks for a label about every 30 seconds and the label names the last tool call at that moment (`ActivityLabel.kt:24-33`), so the feed is a sample, and says so. The daemon counts label queries sent upstream so an empty feed reads "this client version no longer matches the label prompt" rather than "nothing happened". Adopted from Projects' activity feed; the operator's favorite. On by default, switchable off per head, retention-bounded (PRODUCT.md, 2026-09-17). | new | operator 2026-09-17 |
| Archive | Teams are archived, never deleted: chat, edges, activity and tracking stay readable; an archived team leaves the rail. | new | operator 2026-09-17 |

### 4.14 Project

Project is already a daemon scope: `[[compaction.project]]` is keyed by path with its own model and
instructions, `SessionProject.kt` resolves a session to its cwd for it, and `statuslineGitRoots` is
one of the three hot knobs. The operator asked for a view per git repo; it is a page, not a filter.

| Feature | Detail | Status | Origin |
|---|---|---|---|
| Project list | Every repo seen in the trusted root set: live sessions, teams, turns and cost today. | route | operator 2026-09-17 |
| Project page | The repo's sessions, its teams, its turns and cost over time, its compaction scope and the effective instructions, the statusline roots entry. | route | operator 2026-09-17 |
| Instruction files | The repo's `CLAUDE.md` and `AGENTS.md`, read-only, path shown. Always present when the repo has them. | route | operator 2026-09-17 |
| Client memory | The client's per-project memory (`<config dir>/projects/<slug>/memory/MEMORY.md` plus topic files), read per head from each head's own config dir, read-only, path shown. Empty by default on most machines: the empty names `autoMemoryEnabled` and the exact directory it looked in. Adopted from Claude Projects' project memory (operator 2026-09-17). | route | operator 2026-09-17 |

## 5. Explicitly out of scope, with the reason

- RBAC, SSO, tenants and audit log for multiple humans: one operator on loopback; the management
  key is the only principal. (Teams of model sessions are in scope, §4.13; teams of people are not.)
- Kept in, needing daemon work (operator 2026-09-17, "every feature another proxy has in its UI"):
  spend budgets per head and per day with warn and block; alerts as desktop notifications and an
  optional webhook; a playground that sends one prompt through a chosen head and shows the raw
  request and response. Listed under §6 as new routes.
- Guardrails, PII redaction, prompt injection scanning: splice forwards a coding agent's own
  traffic to accounts the operator owns; nothing here is a policy boundary.
- Semantic cache: the prompt cache is the provider's; the console shows its hit rate and never
  pretends to own it.
- Prompt versioning and evals: not what the daemon does. (The playground itself is in, above.)
- Request and response body capture: in, opt-in per head, default off, local disk only, size
  capped, keys redacted. It feeds the Logs drawer and the transcript view for clients that keep no
  local transcript (operator 2026-09-17).
- Alerts to chat channels (Slack, PagerDuty): out; desktop notification and one webhook are in.
- A second control plane, a new orchestration engine, automatic transcript migration: ruled out by
  the v0.4.0 list.

## 6. Daemon work the console needs

Small, mostly read-only, all under the existing bearer guard.

| Route | Purpose | Server-side today |
|---|---|---|
| `GET /api/perf/turns?head=&n=&since=` | per-turn rows for the turn list, waterfall, session detail, chart click-through; every row carries `head` (the per-head perf files do not record it; the aggregating route adds it from the file it read); an unknown head answers 400 with `error.message` naming the head, never 404 (the console reads 404 on this path as "route not built yet") | `PerfStats.tailNumeric` exists; needs the non-numeric fields (model, outcome, session, account) |
| `GET /api/models` | catalog per head with slots, windows, sources, rates, pinned; every catalog row carries `provider: string`, the registry's provider key for the head (decided 2026-09-18 with the daemon lead, V4-127: the models page groups by provider) | `ModelCatalog`, `Topology` |
| `GET /api/doctor` | the `doctor --json` report | `DoctorCommand` |
| `GET /api/topology`, `PUT /api/topology` | read and validated write of `splice.toml`, backup first, structure-preserving | the `add-model` TOML writer, `TopologySchema` |
| `POST /api/auth/{head}/switch` | manual account switch, next turn | account pool selection |
| `POST /api/auth/{head}/login`, `GET /api/auth/{head}/login/{id}` | start a device or browser login for a head with a label, returning a login id, the user code and verification link, or the browser URL for the console to open; poll its state; on landing, restart the head so the account joins its pool | new observation seam over `DeviceLoginFlow` and `OAuthLoginFlow` (today stdout-only, thread-parking, stdin fallback dead in a daemon), plus a head restart |
| `DELETE /api/auth/{kind}/accounts/{label}`, `PATCH /api/auth/{kind}/accounts/{label}` | remove and relabel pooled accounts | pool store |
| `GET /api/accounts` | every account in one payload: windows with their reported length, resets, plan when reported, exclusion, selection, the next target by the real selector order, and the heads riding it, joined on the credential path so one login under several heads is one row; single-login heads from the auth view | `HeadAccountPool` views plus `windowSeconds` (dropped by the projection today), `HeadAccountAuthSource` |
| statusline `rate_limits` capture | record Claude Code's 5h and weekly windows per session and account from the payload the daemon already receives | `StatuslineRoute`, `StatuslineRenderer` (reads `context_window` today) |
| `POST /api/daemon/restart` | draining restart | V4-74 |
| `GET /api/upgrade` | installed, latest, rollback available | `upgrade` command |
| `GET /api/sessions/{id}/transcript` | the session's local transcript from its head's `CLAUDE_CONFIG_DIR/projects`, paginated, redacted | `SessionRegistry`, `ProjectsLink` (note: `compaction/SessionProject.kt:24` still hardcodes the vanilla path for its headless fallback; fix in the same campaign) |
| `GET /api/sessions/{id}/edges`, `GET /api/teams/{id}/edges` | message edges (from, to address, time, direction) derived on the wire, no text, kept by default | new wire observation of the `SendMessage` tool_use input at the point `ActivityLabel` already extracts the block; `SessionRegistry` for addresses |
| `GET /api/teams/{id}/chat?day=` | the team's group chat: edges joined to the message text read from each member's transcript on demand | edges plus the transcript reader |
| `GET /api/teams/{id}/activity?day=` | per member activity labels in time order, metadata only | `ActivityLabel` history, new store |
| `PUT /api/teams/{id}/slots/{slot}/instructions` | standing role instructions per slot, appended at the per-turn system prompt seam | new per-session resolver at `TurnPreparation`; `HeadSystemPrompt` stays per head |
| `POST /api/teams/{id}/archive` | archive, keeping everything readable | team state |
| `GET /api/projects/{id}/files` | the project's instruction and memory files, per head, read-only | per-head config dirs |
| `GET /api/events` | server-sent stream of head, turn, session and edge events, so gestures fire when things happen and not on the next poll; polling stays as the fallback and prints its age. Frame contract (decided 2026-09-18, V4-126): SSE frames with `id:` a monotonic integer per daemon lifetime, `event:` the kind (`head.state`, `turn.start`, `turn.end`, `session.change`, `message.edge`, `account.switch`), `data:` one JSON object; a `: heartbeat` comment every 15 s; the bearer arrives as the `Authorization` header (the console streams with `fetch`, not the `EventSource` API, which cannot send headers); a `Last-Event-ID` request header replays the frames after that id from a bounded ring (oldest dropped; an id older than the ring replays the whole ring) | new |
| `repo` and `team` FIELDS on every row of `GET /api/sessions` (decided 2026-09-18; the per-session detail route may repeat them) | git root of `cwd`, cached, bounded to the trusted roots, worktrees folded into their shared repo; `team` is the bound team id or null; the Sessions page groups the whole registry by repo and by team in one read | new resolver beside `StatuslineRenderer.safeGitCwd` |
| `edges` SUMMARY on every row of `GET /api/sessions`, and `GET /api/sessions/edges` (decided 2026-09-18, console request from M2-02: the board's peer column needs every session's edges in one read) | every registry row carries `edges: { sent: number, received: number, last_at: number \| null }` counted from the same wire observation as the per-session route; `GET /api/sessions/edges` returns `{ sessions: Record<session_id, SessionEdge[]> }` with exactly the `/api/sessions/{id}/edges` edge shape (`from`, `to`, `at`, `direction`) for every session the registry holds, empty arrays included | the V4-130 wire observation; `SessionRegistry` |
| `GET /api/compaction/instructions?head=<key>` (decided 2026-09-18 with the daemon lead, V4-136; corrected the same day by the daemon builder reading core: there is no session scope, `source` is a composed label, `chars` is live) | under the bearer; `{ scopes: [{ scope: 'client' \| 'global' \| 'model' \| 'project' \| 'project-model', source: string, chars: number \| null }] }`, ONE ENTRY PER CONFIGURED RULE (not one resolution): global, every project rule, and the model rules for models in that head's roster. Precedence project-model, project, model, global; `client` means no rule matched and the client's own instructions stand. `source` is core's composed label, never a bare path and never `none`: `global`, `model:<id>`, `project:/abs/path`, `project:/abs/path model:<id>`, each optionally suffixed ` file:/abs/path` or ` file:/abs/path unreadable`; the console prints the label and never parses it. `chars` is the live length of the text that rule would produce now (a file edit shows without a restart), `0` for an explicit opt-out (empty text, the client's own instructions preserved), `null` when the text is unavailable because the file is unreadable, which the label also says. No instruction text over the wire; unknown head answers 400 naming it | the compaction instruction resolver (`CompactionScope`) |
| `GET/PUT /api/teams`, `PUT /api/teams/{id}/sessions` | operator-defined teams and session assignment, in daemon state | new state file |
| `GET /api/teams/{id}/economics` | turns, tokens, cost per team and per role | `PerfPayloads` join on `session` |
| `GET/PUT /api/heads/{head}/capture` | opt-in body capture toggle and its store | new |
| `GET/PUT /api/budgets` | spend budgets per head and per day, warn and block | new |
| `POST /api/playground` | one prompt through one head, raw request and response back | head proxy |
| `GET/PUT /api/alerts` | desktop notification and webhook settings, test send | new |
| `GET /api/claude-head`, `POST /api/claude-head/{wrap,unwrap}` | which mode the Claude head runs in, what `claude` on PATH resolves to, install or remove the default-command shim with the symlink target and the two rewritten files backed up and restored | `InstallCommand` shim machinery; `LaunchService` with an explicit real-binary path; a narrowed materialization path for `~/.claude` that never bypasses the DR-102 guard |

Storage for the new stores (activity labels, message edges, teams), decided 2026-09-17 after
weighing SQLite (sqlite-jdbc) against the daemon's existing pattern:

- Plain append-only JSONL, one file per day per store (`activity-YYYY-MM-DD.jsonl`,
  `edges-YYYY-MM-DD.jsonl`) under the state dir, appended through the existing `JsonlSink` (per-row
  fsync from V4-45, torn-append heal from the 2026-08-25 ENOSPC fix, the shared file lane, a one
  generation rotate at 64 MB by default, `JsonlSink.kt:110-175`), and `teams.json` for team
  definitions. The sink has no whole-file reader (only tail windows, `:192-222`), so the day-scan
  reader is new code, and per-day files pass a `maxBytes` chosen against the rotate so a day is
  never rolled away. The per-row fsync is the write-rate budget: labels arrive at most every 30 s
  per session, well inside it. No new
  dependency, no native library in the jar, files the operator can read and delete by hand, which
  is the trust story. Reads by team or day are a scan of a few files; at the expected volume
  (about one label per session per 30 s, tens of edges a day) a day is a few thousand rows and a
  year is under a hundred megabytes before retention.
- Retention: a knob (`activityRetentionDays`, default 90) deletes whole day files; the same knob
  bounds edges. Doctor reports the state dir size and the oldest file. Related existing gap: the
  per-head `*-perf.jsonl` files are not unbounded, they rotate at 64 MB into one `.1` generation
  and discard what falls off (`claudex-perf.jsonl.1` is already a 67 MB rolled generation on the
  operator's machine), so team and project tracking "over a lifetime" is bounded by that window
  until a perf archive exists; a perf retention design belongs in the same change.
- SQLite was ruled out for now: sqlite-jdbc adds 10 to 20 MB of native binaries to a single-file
  jar (or platform classifiers to manage), extracts a library into the temp dir at load, brings
  WAL files that a plain `cp` backup gets wrong, and buys indexed queries the volume does not
  need. Revisit only if a query cannot be served by scanning a day's files.

Optional, later: `GET /api/codemode` for the worker pool; remote provider catalog listing for the Models page (today the catalog is declared models only).

## 7. How "complete" is proven

The coverage gate from v0.4.0 §9, made concrete:

1. Enumerate the configuration surface from the source at build time: the `Knob` enum, the
   `@SerialName` fields of `Topology.kt`, `QuirksConfig.kt`, `CompactionScope.kt`,
   `HeadSystemPrompt.kt`, `TokenCost.kt` and `TopologySchema.kt`.
2. The console ships a manifest that gives every enumerated key one disposition: editable
   (with scope and live-or-restart), read-only with a written reason, or computed.
3. A key with no disposition fails `npm run build -w webui`. The gate is mutation-tested with a
   synthetic knob added to the enumeration and must go red.
4. Every route in §2.1 and §6 has at least one page that reads it, checked the same way.
5. Playwright runs against a live daemon on loopback and asserts the honest states: a head with no
   quota reads "not reported by provider"; a window with no rows reads "no turns in window"; a
   stale poll shows its age.

5. Copy: every UI label lives in one string table, and a webui test fails on any label longer than three words (the brief's copy rule, operator 2026-09-17).

## 8. Open questions for `shape`

1. Turns page default window and default columns for a 13-inch screen.
2. Whether Usage and Turns share one explore view or stay separate.
3. Whether the topology editor is the primary Settings surface with forms as helpers, or the
   reverse.
4. Whether account add and remove ship in the first cut or the console shows the CLI command
   until the login route lands.
5. The replacement visual world: chosen in new-work, after this document and `init`.
