Last read: 2026-10-15 against splice 0.4.0 (feat/v0.4.0)

# Reliability, MCP, code mode, install and troubleshooting

Read this file for: why a turn failed and what splice already retried, how MCP servers reach sessions, code
mode, how splice is built and where its state lives, install, upgrade and rollback, and the symptom table at
the end. Other files own the rest: `toml-reference.md` (TOML syntax of every field), `cli.md` (every verb),
`accounts-sessions-console.md` (logins, pages), `operations.md` (the safe way to edit and restart).

`(unverified)` marks a claim I could not confirm in code or a current doc. Where a doc and the code disagree,
the code wins and the line says so.

## 1. Retries and errors

### 1.1 The two rules

| rule | meaning |
|---|---|
| Retry is total | A known error gets its own plan. Every other error gets a bounded generic retry and an honest message. No failure ends a turn without one of the two. |
| Idle is a probe | Silence from the model is a question, never a verdict. splice acts only on a dead path, by re-anchoring (resuming the stream). It never reaps a model that is thinking in silence. |

Source: repo `CLAUDE.md` "Project laws"; `core/src/main/kotlin/splice/core/config/Knob.kt` lines 227-230 and
271-281; `core/.../turn/FailureCause.kt` lines 96-99; `integrations/upstream/.../retry/RetryPolicy.kt` lines 207-218.

Only a few verdicts are never retried, because the same bytes get the same answer: a credential rejected again
after a refresh, a context overflow (Claude Code compacts instead), a content-policy refusal, a refusal the
vendor module names, a spent plan window (the reset is the wait), an `encrypted_content` decrypt error.
Source: `RetryPolicy.kt` lines 118-123, 148-182.

### 1.2 The layers, in order

| layer | trigger | bound |
|---|---|---|
| Attempt backoff | any failed attempt (connection failure, any HTTP status) | RetryEngine decides from what failed and where: a server error before the provider's stream began is re-sent after 200, 400 and 800 ms. No knob sets it: `upstreamRetries` and `retryBackoff*` were retired in 0.4.0 |
| Credential refresh | a vendor auth failure | one refresh and one free re-send per turn. Rejected again: give up. A pooled head moves the turn to another login |
| Stream re-issue | stream torn after a 2xx, before the client saw a byte | 2 re-issues per turn |
| Body amendment | a 400 that a dialect knows how to repair | one amended re-send per turn |
| Re-anchor | stream cut or stalled after content began | splice re-sends from what it delivered. ChatGPT, Muse and other Responses heads: up to 5 times unless a tool call had started. Other heads only where measured to continue (Kimi, or `reanchor_prefill = true`) |
| Client retry | Claude Code receives `overloaded_error` | Claude Code re-sends. splice launches every client with `CLAUDE_CODE_RETRY_WATCHDOG=1` (persistent retry), so a rate-limit hold longer than the default retry budget does not end the session |

Source: `Knob.kt` 219-245; `RetryPolicy.kt` 125-146, 233, 248-274; `integrations/upstream/.../transport/UpstreamRetryLoop.kt`
100-110; `integrations/upstream/.../transport/TransportFailures.kt` 170-236; `features/launch/.../recipe/LaunchService.kt`
366-378; `CHANGELOG.md` 138-141.

### 1.3 Known errors and their plans

| error | plan |
|---|---|
| 429 with `Retry-After` | wait that long, at most 15 s, then retry while attempts remain. 15 s when the header is absent |
| 429 that names a spent plan window (5-hour or 7-day) | no retry. The account is held until the reset it named. A pooled head moves the turn to a free login before any answer reaches Claude Code |
| bare 429 after retries run out | the account is held 20 s without inventing a reset. The shared hold never exceeds 120 s |
| grok spending-limit 403, any 402 | treated as a 429 by every layer |
| every account of a head exhausted | refused at admission, zero upstream calls, with the earliest reset when known |
| DNS failure | its own schedule: 1 s, 2 s, 4 s |
| 5xx, 408, resets, timeouts, TLS | the generic curve. A `Retry-After` is obeyed up to 15 s |
| 4xx other than the fixed verdicts above | still retried on the generic curve (about 1.5 s), because a 403 has been seen as overload |

Source: `RetryPolicy.kt` 207-239, 290; `integrations/upstream/.../retry/RateLimitCooldown.kt` 392, 403;
`integrations/upstream/.../retry/PlanHold.kt`; `UpstreamTransport.kt` 335-336; `CHANGELOG.md` 560-574 and 1625.

### 1.4 The idle probe: four timers

| knob | default | what a breach does |
|---|---|---|
| `firstByteTimeoutMs` | 90000 | before Claude Code has seen any content: silence past it asks the path whether it is alive |
| `streamIdleMs` | 90000 | after content began: the same question |
| `stallReanchorMs` | 20000, armed only on heads measured to continue from a prefill | after content began: cancel the round and re-send from what was delivered. `0` turns it off for a head |
| `upstreamTimeoutMs` | 900000 (15 min), restart to change | the whole-turn wall: no model, tool or reasoning event for this long ends the turn. Keepalive pings do not renew it |

How the probe works: a round past a tier is asked whether its socket still answers. A path that answers is held and polled on. Only a path that has gone quiet too is reaped, and
the re-anchor machinery then resumes it. A turn ended by the wall reads `splice progress timeout expired after
Nms without upstream progress; retry`, and the console says "Given up after no progress".
Do not lower `streamIdleMs` to cure a slow model. It buys only how long the person waits before splice starts
healing. A head that needs a longer wait names it in its own overrides, with a reason.

Source: `Knob.kt` 253-305; `integrations/upstream/.../retry/Watchdog.kt` 1-9, 48-62, 140-160;
`app/src/main/resources/console/kit.js` 140-160.

### 1.5 Error classes and what the person sees

Claude Code sees one of seven Anthropic error types. splice chooses the type from the cause and from whether
any content had already reached the client. A failure that reaches the transcript reads
`[SPLICE-<TYPE>] <one sentence>`, for example `[SPLICE-OVERLOADED] ...`. The sentence is plain prose, never the
provider's raw JSON. The Requests page words (the "Requests word" column) come from the outcome tag.

| situation | wire type | Requests word | what happens next |
|---|---|---|---|
| provider overloaded, 5xx, reset, stall before content | `overloaded_error` | Overloaded, Network error, Given up after no progress | splice retries (1.2), then Claude Code re-sends |
| stream cut after content began | `overloaded_error` | Cut off | splice re-anchors (1.2). If a tool call had started the turn ends: the person re-sends |
| plan window spent | `rate_limit_error` | no word (the window shows its lock and reset time) | text: `Rate limit exceeded: the upstream reports this plan's <window> window is used up until <time>. The session waits and resumes after the reset.` Claude Code waits and resumes. Sessions page: At limit |
| burst 429 | `rate_limit_error` | Rate limited | 1.3. Persistent Claude Code sleeps until the reset |
| every login of a head spent | `rate_limit_error` | No quota | refused locally until a reset or a new login |
| splice holds no credential | `authentication_error` | Signed out | nothing to retry. The person signs in (6.3) |
| provider rejected the credential after a refresh | `authentication_error` | Credentials refused | the person re-logs in (6.3) |
| 403 from the provider | `permission_error` | No access | not retried |
| refusal, content filter, context overflow, bad request | `invalid_request_error` | Invalid request | not retried. An overflow before the answer makes Claude Code compact |
| over a daily budget set to block | HTTP 403, outcome `error:budget-blocked` | Over budget | answered 403 so Claude Code does not retry. Raise the budget or wait |
| head queue full | HTTP 529 `gateway at capacity` | Too many at once | `maxInflight` (12) turns run, `maxQueued` (512) wait. Claude Code retries |
| daemon draining for a restart | HTTP 529, outcome `error:restarted` | splice restarted | Claude Code retries (1.7) |
| cause splice cannot name | `overloaded_error` | Internal error | offered to Claude Code as retryable. Read `splice logs` |
| code mode failure | `invalid_request_error` | a line `splice [code] reason` | 3.3 |
| local 401, `invalid local gateway credentials` | HTTP 401 | n/a | the session's local key is not one this daemon accepts. Relaunch the session (cause unverified) |

Source: `core/.../turn/WireType.kt` 31-90; `core/.../turn/TurnOutcome.kt` 14-22; `features/turns/.../pipeline/FailureText.kt`
36-51; `core/.../usage/QuotaHeaders.kt` 113-116; `features/turns/.../admission/AdmissionGate.kt` 80-83 and
`AdmissionResponses.kt` 141; `core/.../perf/OutcomeTag.kt`; `app/src/main/resources/console/kit.js` 98-160;
`docs/specs/v0.4.0.md` line 584; `CHANGELOG.md` 1114-1116.

### 1.6 The knobs, and where they live in splice.toml

A knob sits in `[defaults]` (every head), in `[heads.<key>.overrides]` (one head), or in a `[daemon]` key spelled
with underscores. Values are quoted strings, for example `maxInflight = "4"`. One daemon serves every head and
shares one config service, so a limit that belongs to one upstream account (`maxInflight`, `maxQueued`, the timers)
belongs under that head's `[heads.<key>.overrides]`. The field syntax is in `toml-reference.md`.

| key | default | change takes effect |
|---|---|---|
| `firstByteTimeoutMs`, `streamIdleMs`, `stallReanchorMs` | 90000, 90000, 20000 | next turn (hot) |
| `upstreamTimeoutMs` | 900000 | restart |
| `maxInflight`, `maxQueued` | 12, 512 (0 = unlimited) | next admission (hot) |
| `progressLine` | true | next heartbeat (hot) |
| `maxRequestBytes`, `requestReadTimeoutMs`, `ingressReadIdleMs` | 33554432, 30000, 30000 | hot |

`upstreamRetries`, `retryBackoffBaseMs`, `retryBackoffCapMs`, `retryBackoffJitterPct` and the four `fold*` knobs
were retired in 0.4.0. splice refuses to start while splice.toml, an environment variable (`CLAUDEX_UPSTREAM_RETRIES`,
`SPLICE_RETRY_BACKOFF_*`, `CLAUDEX_FOLD_*`) or a saved setting still sets one, lists every place at once, and
`splice restart` on a terminal offers to remove the lines and clear the saved settings; a variable is unset where it is
set.

"Hot" means a `PATCH /api/config` (the console's Settings page) or the state `config.json` reaches the running
daemon. An edit of `splice.toml` is read only at boot, so any key changed there needs `splice restart`, whatever
its marking. The exceptions are `context_window` edits, `[daemon] mcp_share`, `update_channel`, `auto_update`
and the `[compaction]` tables, which the daemon re-reads from the file.

The example file's comment at `splice.example.toml` line 825 shows `maxInflight = "0"`. That is stale. The code
default is 12. Reasoning effort is restart-only on purpose: it is part of the prompt-cache key.

Source: `Knob.kt` 12-49, 192-200, 219-305; `app/src/main/resources/splice.example.toml` lines 3-7, 800-809, 841-892;
`AGENTS.md` lines 87-104; `CHANGELOG.md` 189-194.

### 1.7 What a restart cuts, and how sessions recover

- There is one daemon JVM. `splice restart` cuts the turns in flight on every head and every session on the
  machine, the manager's own included.
- `splice restart` first waits for running compactions, up to 900 s, unless `--now`. Then the daemon drains
  ordinary turns for up to 45 s. A turn still streaming after that is cut.
- While it drains, a head on its own port answers a new turn with 529 `error:restarted`, which Claude Code
  retries. Under socket activation the request waits in the kernel queue for the next daemon.
- Claude Code does not retry a turn that was cut after its text began, so the 45 s drain can cost the person that message: they re-send it. Whether Claude Code retries a turn cut before any text began: the CHANGELOG says only that splice can retry such a turn itself (unverified for the drain cut).
- Run `splice restart` in the background, never in the foreground. Never run `splice logs --follow`.
- A finished compaction whose client hung up is kept on disk for two hours, so the retry gets the same bytes.
- Code mode scripts in flight are lost and never rerun (3.3).
- MCP: with the hub, session tools survive a daemon restart. Hosted in the daemon (no hub), a restart is a gap of
  about 10 s, and Claude Code 2.1.296 gave up on an MCP server after about 15 to 17 s without it, so a session
  may need `/mcp` to reconnect. The 10 s figure is from `docs/specs/mcp-hub.md` proof (c); I did not re-measure.
- A restart run from the manager's pane starts the daemon with the manager's environment, and an API key exported
  elsewhere is not in it. Prefer the console's Restart button or the person's own terminal.
- A session that stays stuck after a restart is a splice defect to report with the `splice logs` lines. Do not
  work around it.

Source: `features/lifecycle/.../restart/RestartCommand.kt` header; `features/lifecycle/.../restart/RestartAfterCompactions.kt`
header; `features/lifecycle/.../start/DaemonLaunch.kt` line 152; `README.md` lines 334, 940-944; `CHANGELOG.md` 142-145,
1114-1116; `docs/specs/mcp-hub.md` lines 61-65, 142-147.

## 2. MCP: the host and the hub

### 2.1 What they are

| part | what it does |
|---|---|
| MCP host | In the daemon. Starts each shareable stdio MCP server once and serves it to every session over Streamable HTTP on loopback, at `/mcp/<name>` on the control port. Servers start on first use and stop when idle. |
| MCP hub | Linux x86-64 with `systemd --user`. A small native front (`splice-mcp-front`, holds `127.0.0.1:3095`) and one native holder (`splice-mcp-holder`) per server, in `app-mcp.slice`. The front outlives the daemon, so an upgrade, restart or crash of splice drops no session's tools. |
| launch file | Each launch hands Claude Code one owner-only `--mcp-config` file, `<state>/mcp-hub/launch-<pid>.json`, naming each server under its own name, so tool names do not change. |

The daemon decides once at boot whether the hub runs. Three checks, in order: Linux x86-64; both binaries
beside `splice.jar` and executable; `systemctl --user is-system-running` answers `running` or `degraded` within
2 s. The first failure is the logged reason, published on `/health` as `mcpHub` and `mcpHubOffReason`. Off means
the host runs in the daemon, the supported fallback. A launch offers hub addresses only after a front has run
here (`<state>/mcp-hub/front.json` exists) and while it answers on 3095. Otherwise the session prints `this
session starts its own MCP servers` and Claude Code runs its own.

`docs/specs/mcp-hub.md` is the approved design plus desk proofs on Claude Code 2.1.296. Read it for the why.
Where it says "will", trust the code and `CHANGELOG.md` 160-186. The hub port 3095 is a constant in the code.

Source: `README.md` 734-757; `CHANGELOG.md` 160-186; `integrations/mcp/.../hub/McpHubGate.kt` 23-52;
`app/.../McpHubWiring.kt` 92-103; `app/.../ControlLaunchWiring.kt` 42; `features/diagnostics/.../DoctorClientVersion.kt` 28-62;
`integrations/claude-code/.../mcp/McpHubLaunch.kt` 36.

### 2.2 How a tool server is attached, and how to add one

splice.toml has no table that declares an MCP server (the only MCP keys in the topology are in 2.3). Servers
come from Claude Code's own configuration, and splice hosts what it finds. So "add an MCP server" is a Claude
Code change, not a splice change.

| where a server is declared | what splice does with it |
|---|---|
| user scope, `mcpServers` in `~/.claude.json` | hosted. Each sharing head's own `.claude.json` copy is rewritten to the hosted URL. The person's file is never edited |
| project override inside `.claude.json`, repo `.mcp.json`, plugin `.mcp.json` or inline `plugin.json` | censused on `/api/mcp` (`sources`), not rewritten. With the hub, the launch file routes them through it. A repo server pending or rejected in Claude Code is not hosted |
| `http`, `sse`, `ws` servers and claude.ai connectors | left to Claude Code, untouched |

A server stays one copy per session (reason on `/api/mcp`) when its launch line names a working directory, a
relative path, a location flag such as `--root`, an existing absolute directory or `${VAR}`; when it asks its
client for roots; or when it keeps state per process (Desktop Commander). A hosted server runs in the home
directory, so one that reads its working directory without naming it belongs in `mcp_hosting_exclude`.

To add a server, the person runs Claude Code's own command (`claude mcp add`; its `--help` lists the scopes).
Scope `user` reaches every session. Then the server appears under Settings > MCP once a session starts.
Never read `~/.claude.json` into the conversation: server entries often carry API keys in `env`.

Source: `app/.../McpHostingWiring.kt` 51-84; `README.md` 740-757, 611; `core/.../topology/Topology.kt` 133-141;
`app/.../control/mount/McpMount.kt` 30-48; `docs/specs/mcp-hub.md` lines 22-48.

### 2.3 The keys that govern hosting

| key | default | note |
|---|---|---|
| `[daemon] mcp_hosting` | true (absent = on) | `false`: every session starts its own servers. Boot-read: needs `splice restart` (unverified: not listed among the live keys) |
| `[daemon] mcp_hosting_exclude = ["name"]` | none | keep these servers per session. Boot-read (unverified, as above) |
| `[daemon] mcp_share` | true (absent = on) | `true`: one shared copy per server. `false`: one per session. Read live, applies to new connections. `splice setup` asks once and writes it |
| `[defaults] mcpIdleTimeoutMs` | 1800000 | a shared server with no open stream stops after this. Hot |
| `[defaults] mcpMaxServers` | 32 | most servers hosted at once. Past it the longest-idle goes; if all are busy: "MCP host at capacity". Hot |
| `[defaults] mcpRequestTimeoutMs`, `mcpInitializeTimeoutMs` | 1800000, 60000 | hot |
| `[defaults] mcpSlice` | `app-mcp.slice` | the systemd slice servers spawn into. Restart |

Source: `core/.../topology/Topology.kt` 133-141; `Knob.kt` 364-388, 462-467; `app/.../McpHostingWiring.kt` 35-47;
`app/src/main/resources/splice.example.toml` lines 3-7, 28-41, 904-908; `README.md` 743-747, 772-777.

### 2.4 The hub's files

| file | what it is |
|---|---|
| `~/.local/share/splice/splice-mcp-front`, `splice-mcp-holder` | the two programs, beside `splice.jar`. `install.sh` and `splice upgrade` swap them in with the jar. A running front reloads (`systemctl --user reload splice-mcp-front.service`), never restarts |
| `~/.config/systemd/user/app-mcp.slice`, `splice-mcp-front.socket`, `splice-mcp-front.service` | the units. `splice install --all` writes each only where no unit of that name exists. It enables and starts nothing |
| `~/.config/splice/mcp-front.env` | where the hub keeps state and the control port. `splice init` writes it (Linux) |
| `<state>/mcp-hub/` | `front.json`, `access-key` (the MCP-only bearer, derived from the management key), `launch-<pid>.json`, `units-written` (SHA-256 of the units splice wrote), `plugin-flag.json`, one `<key>.json` and `.sock` per holder |
| `<state>/mcp-listings/`, `<state>/mcp-roots-bound` | the last tool list each server gave, so a session lists tools without starting the server; the servers that asked for roots |

`splice setup` with sharing on enables and starts `splice-mcp-front.socket`, but only over units splice itself
wrote. Otherwise setup prints the command. Until the socket is on, each session starts its own servers.

Source: `README.md` 599-601, 611-620, 668; `CHANGELOG.md` 132-134, 171-186; `app/.../InstallWiring.kt` 20-54;
`install.sh` 480-486.

### 2.5 See an MCP server's state

1. `splice doctor`. The `MCP hub` row reads one of: on; installed but its front never ran here (fix:
   `systemctl --user enable --now splice-mcp-front.socket`); front not answering on 127.0.0.1:3095 (fix:
   `systemctl --user status splice-mcp-front.socket splice-mcp-front.service`); off with the reason.
2. Settings > MCP in the console: the sharing choice, what sharing saves in memory, and each server's copies,
   reason and sessions using it. These are the keyed routes `GET /api/mcp` and `GET /api/mcp/memory`. You have no
   key, but you can open and read that page on the person's console: `splice console goto settings`, then
   `snapshot`, tap "MCP servers" in the rail, and `read`.
3. `splice logs --tail 300` and read the `[mcp-hub]` and `[mcp-host]` lines.

### 2.6 Recipes

Keep one server per session:

1. Copy `splice.toml` to a dated backup beside it.
2. Under `[daemon]`, add or extend `mcp_hosting_exclude = ["<server name>"]`.
3. `splice check-config`. Show the diff.
4. With a yes, the person runs `splice restart`. New sessions start their own copy.
5. Verify: Settings > MCP shows the server with its own copy for each new session, and `splice doctor` has no new finding.

## 3. Code mode

### 3.1 What it is

For a ChatGPT-shaped head, the model writes a short JavaScript program that batches Claude Code tool calls,
instead of one call per round trip. The script runs inside splice, in a bundled GraalJS runner (no Node, no
Codex install). Claude Code's permission-checked handlers still run every real operation. The session shows the
model's reasoning summaries and each tool call, never the program. An eligible turn declares one tool, `exec`.

### 3.2 How it is turned on

On by default for providers with `auth.kind = "chatgpt-oauth"` and `dialect = "openai-responses"`, including
custom head names. Nothing else can enable it: `code_mode = true` on any other shape is a config finding and
boot refuses with `code_mode is only supported with ...`. Eligible models: every model the Codex backend marks
`tool_mode = "code_mode_only"`, plus the ids in `code_mode_models`.

```toml
[providers.codex.quirks]
code_mode = false          # off for every head on this provider
# code_mode_models = ["codex-auto-review"]
```

The setting is read only at boot and is shared by every head on the provider. Finish code-mode work, edit,
`splice check-config`, then the person runs `splice restart`. A head restart alone does not reload TOML.

### 3.3 Limits

| limit | value |
|---|---|
| host JVMs per head `code_mode_workers` | 6, four session engines per host |
| host heap `code_mode_heap` | 512 MiB (a session's guest heap 384 MiB) |
| head-wide admission reservation `code_mode_memory_mb` | 24576 MiB; not an OS memory limit. The default admits 21 sessions |
| tool calls per script cell | at most 32, sent in batches of at most 8 |
| one tool result | 64 KiB, cut with `[truncated N chars]` |
| output ceiling | 1,048,576 characters |
| guest access | no host or file access |
| idle engine | evicted after 1 minute. A conversation's journal expires 24 h after last use |

A script that fails shows the model its evidence (results so far, unresolved calls, the reason). Over capacity, admission returns an `exec` result asking the model to retry after a running script finishes. A restart or a dead host fails only its own cells and splice
never reruns lost source. `keepCodeModeWork = "false"` keeps the journal in memory only, so a restart ends running
scripts. A script splice cannot match to the conversation falls back to the client's own history, with one
`[code-mode]` log line.

Source: `README.md` 836-882; `core/.../topology/QuirksConfig.kt` 60-79; `core/.../topology/Topology.kt` 215-240;
`app/src/main/resources/splice.example.toml` 126-150; `Knob.kt` 217-218.

## 4. Architecture brief

### 4.1 One turn, in plain words

1. The person starts a head's command, for example `claudex`. It is a symlink to the launch shim `splice-launch`.
2. The shim checks the daemon's version, cold-starts the daemon if down, asks it for a launch recipe over
   loopback, and execs the real `claude` with the recipe's environment. That environment carries
   `ANTHROPIC_BASE_URL` (the head's port), a derived turn key, `CLAUDE_CONFIG_DIR=~/.claude-<head>`,
   `API_TIMEOUT_MS`, `CLAUDE_CODE_RETRY_WATCHDOG=1` and the model window variables.
3. Claude Code posts `POST /v1/messages` to the head's loopback port. The head checks the key, admits the turn
   (`maxInflight`, `maxQueued`) and applies the standing prompt layers.
4. The provider module builds the request in the provider's dialect: `anthropic-passthrough`,
   `openai-chat` or `openai-responses`, with the auth kind's credential.
5. The upstream client sends it under the retry rules of section 1.
6. The stream is translated back into Anthropic SSE events. Only one module may end a stream cleanly, and a
   failed or cut stream is never shown as a clean answer.
7. Perf rows, the trace and usage are written to the state dir.

Source: `app/src/main/dist/bin/splice-launch` header; `README.md` 920-938; `features/launch/.../recipe/LaunchService.kt`
350-378; `features/turns/.../head/HeadServer.kt` 1-18; `AGENTS.md` lines 29-35 (honest failures).

### 4.2 The processes

| process | what it is |
|---|---|
| daemon (`spliced`, `java -jar splice.jar`) | one JVM. Every head runs inside it, each on its own loopback port. It also serves the control plane and the console |
| control plane | one Ktor server on `127.0.0.1:<control_port>`, default 3096. `GET /health` is open. Every `/api/*` route needs the management key. It refuses a `Host` other than `127.0.0.1`, `localhost`, `[::1]`. The console pages are served at `/` |
| head | a loopback listener per `[heads.<key>]`, serving `/v1/messages`, `/v1/models`, `/health` |
| launch shim `splice-launch` | POSIX `sh` and `curl`, no Node. Version-locked to the jar |
| head wrappers | `~/.local/bin/<command>` symlinks to the shim. The command's name selects the head. `splice` itself is one |
| hub front, holders | section 2 |

AGENTS.md lines 118-126 contradict themselves: line 118 says the control plane is `ControlServer.kt`, line 120
still names the deleted Node file `src/control-server.mjs`. The code is right: `app/src/main/kotlin/splice/app/control/ControlServer.kt`.
AGENTS.md's `/mgmt/*` table is also stale: no Kotlin route serves `/mgmt`, and the live surface is `/api/*` on the
control plane. Source: `ControlServer.kt` 1-8, 171-187, 254-276; `grep '"/mgmt'` finds no route.

### 4.3 The modules

- `core/`: the shared kernel (config, topology, auth, turn, model, usage). No framework.
- `integrations/`: adapters. `upstream` (transport and retry), `dialects/*`, `providers/*`, `oauth`, `topology`
  (the loader), `mcp` (host), `mcp-hub` (native front and holder), `codemode`, `claude-code`, `tmux`, `terminal`.
- `features/`: capabilities (`turns`, `sessions`, `models`, `heads`, `accounts`, `usage`, `lifecycle`,
  `diagnostics`, `launch`, `configuration`). A slice reaches another only through its public ports.
- `app/`: composition. The daemon, the control plane, the `splice` CLI, the fat jar, the launch shim and
  `splice.example.toml`.
- `quality/`: architecture laws. The module graph is law (`gradle/module-law.txt`).

Source: `README.md` 976-1003; `gradle/module-law.txt`; `settings.gradle.kts`.

### 4.4 Where state lives

| what | where |
|---|---|
| config dir (the manager's `manager/` is inside it) | the folder of `splice.toml`: `SPLICE_CONFIG`, else `$XDG_CONFIG_HOME/splice`, else `~/.config/splice`. Holds `splice.toml`, `keys.toml`, `key-ledger.json`, `auth/`, `prompts/`, `mcp-front.env`, `manager/` |
| state dir | `SPLICE_STATE_DIR` (or the older `CLAUDEX_STATE_DIR`), else `[daemon] state_dir`, else `~/.splice/state`. An install older than 0.4 keeps `~/.claude-codex/state` and splice adopts it in place. Holds `mgmt-key`, `config.json`, `trace/`, perf and usage files, `teams.json`, `mcp-hub/`, `config-backups/` |
| logs dir | the sibling `logs` of the state dir: `~/.splice/logs`, or `~/.claude-codex/logs` when adopted. `daemon.log`, `daemon-boot.log` |
| install dir | `~/.local/share/splice` (`SPLICE_SHARE_DIR`): `splice.jar`, `splice-launch`, `releases/`, `managed-install`, hub programs |
| bin dir | `~/.local/bin` (`SPLICE_BIN_DIR`): `splice` and one symlink per head command |
| Claude Code config per head | `~/.claude-<head>/` |

The manager reads nothing in the state dir by hand: it holds `mgmt-key` and `console-open.html`, which carries the management key. Use `splice logs`, `splice status` and `splice doctor` instead. The paths here are for explanation.

Stale docs on this: `splice.example.toml` line 15 says the default `state_dir` is `~/.splice`; the code default is `~/.splice/state` (`StatePaths.kt` 193), and logs sit in the folder beside it. `README.md` says other keys are read only at boot, but the code re-reads `mcp_share`, `update_channel`, `auto_update` and the `[compaction]` tables live.

On this machine the state dir is `~/.claude-codex/state`, so the logs are in `~/.claude-codex/logs`. Run
`splice doctor`: it reports which root is live and warns about a root it could not read.

Source: `core/.../config/StatePaths.kt` 45-55, 144-235; `core/.../topology/Topology.kt` 117;
`app/src/main/resources/splice.example.toml` 13-16, 981-985; `README.md` 523-535, 657-665.

## 5. Install, upgrade, rollback

### 5.1 What `install.sh` does, in order

1. Checks the platform (Linux, macOS, WSL2) and dependencies. Java 21+ is the one hard requirement. Everything
   else is reported with its fix and never aborts the install.
2. Gets the jar: `SPLICE_JAR=/path`, else builds from a checkout, else downloads the release (`SPLICE_VERSION=vX.Y.Z`
   pins one; `SPLICE_RELEASE_BASE_URL` points elsewhere). Checks it against `sha256sums.txt`, and the build
   attestation when `gh` is signed in. On Linux x86-64 it also gets the hub pair the same way.
3. Refuses a launcher whose version does not match the jar.
4. Copies the old jar and shim to temporary backups, then replaces them atomically.
5. Runs `java -jar splice.jar init`, then `install --all`. If either fails, it rolls back to the old pair.
6. Reloads a running hub front.
7. Checks that `~/.local/bin/splice` exists, writes `managed-install`, keeps `releases/<version>/`, sets
   `current` and `previous`, and deletes its temporary backups.
8. Runs `splice doctor`. Findings are next steps, not failures. Then: `splice setup`.

A daemon that is already running keeps the old build until `splice restart`. The installer does not restart it.
Env: `SPLICE_SHARE_DIR`, `SPLICE_BIN_DIR`, `SPLICE_JAR`, `SPLICE_SHIM`, `SPLICE_VERSION`, `SPLICE_RELEASE_BASE_URL`.

Source: `install.sh` lines 7-15, 92-146, 257-331, 373-393, 464-497, 543-566.

### 5.2 `init`, `install --all`, `setup`, `doctor`

| verb | does |
|---|---|
| `splice init` | writes the starter `splice.toml` only if absent, on Linux writes `mcp-front.env`, and places the manager's folder (`CLAUDE.md`, `.claude/settings.json`, the skill) in `manager/` beside `splice.toml`. A file the person edited is kept as they left it. The daemon does the same at every start, because auto-update swaps the jar and never reruns init |
| `splice install [<head>\|--all]` | links the wrapper symlinks in `~/.local/bin`, and on Linux writes the hub units where none exist. Never prunes a link |
| `splice setup` | guided, needs a terminal: first provider, which commands, plain `claude` or `claude-splice`, MCP sharing. Writes nothing until confirmed |
| `splice doctor [--live] [--json [--with-logs] [--out FILE]]` | seven sections: prerequisites, installation, configuration, daemon, auth, accounts, runtime. Every failing check prints its fix. `--live` sends one tiny tool call per local-runtime row and nothing else. `--json` is a redacted, shareable report |
| `splice check-config` | the boot findings pass, no daemon. Exit 0 clean, exit 3 on any finding, all listed |

Source: `app/.../cli/CommandParser.kt` 72-170; `app/.../InstallWiring.kt` 40-54; `app/.../manager/ManagerHome.kt` 1-10; `features/diagnostics/.../doctor/DoctorCommand.kt`
64-75, 239-251; `app/.../cli/ConfigPreflight.kt` 20-50; `README.md` 160, 314-320.

### 5.3 Upgrade, auto-update and the beta channel

| command | does |
|---|---|
| `splice upgrade` | fetch the latest release, check it against its sums, stage it under `releases/`, wait up to 30 min for in-flight turns, repoint the live jar, restart, run doctor |
| `splice upgrade --to vX.Y.Z` | one named version |
| `splice upgrade --now` | skip the waits |
| `splice upgrade --rollback` | put the previous release back. Refuses if that release would refuse your config |
| `splice upgrade --check` | read the newest release on your channel and say so (found in the code; the parser's help line omits it) |

Before anything stops, the new release's own `doctor` must answer and its `check-config` must accept your
config. A release that comes up unhealthy or red is put back and listed in `<state>/upgrade-rejected`. Config and
credentials are never touched. An upgrade does not relaunch sessions.

Auto-update is two keys under `[daemon]`, read live at each check:

```toml
[daemon]
update_channel = "beta"   # "stable" (default) follows releases; "beta" follows prereleases too
auto_update = true        # absent reads as off
```

The first check is 10 min after daemon start, then hourly. It runs the same upgrade with `--auto`: assets are
checked against `sha256sums.txt` only (a beta built while CI is locked has no attestation), and it restarts at
the first moment no turn is in flight, or after the bounded wait (30 min). It acts only where `managed-install`
exists. One try per version per daemon. Any other value of `update_channel` is a config finding.

Source: `features/lifecycle/.../upgrade/UpgradeArgs.kt` 10-60; `UpgradeAuto.kt` 1-70; `UpgradeLayout.kt` 28-62;
`UpgradeCommand.kt` 44-48; `core/.../topology/Topology.kt` 139-141; `TopologyWriter.kt` 282-287; `README.md` 104-120,
663-665; `app/src/main/resources/splice.example.toml` 36-41.

### 5.4 Backup and rollback

- Code-supported rollback: `splice upgrade --rollback` (needs `releases/previous`; `install.sh` sets it).
  Or re-run the installer pinned to the older tag: `curl -fsSL <release>/install.sh | env SPLICE_VERSION=vX.Y.Z bash`.
- A failed `install.sh` restores the old jar and shim by itself. If it cannot, it keeps the backups and prints the
  commands that finish the restore.
- Operator convention, not in code or the README: copy the jar to a dated name before replacing it by hand,
  `cp -p ~/.local/share/splice/splice.jar ~/.local/share/splice/splice.jar.bak-<date>`. The person's own
  `splice.jar.bak-<stamp>-pre-<sha>` files on this machine follow it. Offer it; do not assume it exists.
- `splice.toml` backups: console edits keep the ten newest in `<state>/config-backups/`. `splice add` keeps none.
  A hand copy beside the file is the manager's convention (`operations.md`).

Source: `install.sh` 398-446, 505-543; `README.md` 114, 602-603; `UpgradeActivation.kt` 1-45.

### 5.5 Uninstall

`splice uninstall [<head>|--all]` removes the wrapper symlinks only. `--all` includes `splice` itself. It refuses
a name that is not a bare link under `~/.local/bin`. It never touches the jar, config, keys or state. Removing
those is the person's own decision and needs their explicit yes, with a backup, per `operations.md`.
Source: `features/launch/.../install/UninstallCommand.kt` 17-50.

## 6. Logs and the troubleshooting playbook

### 6.1 Where to look

| source | how |
|---|---|
| daemon log | `splice logs [--head <key>] [--tail N]`. Default 50 lines. Works with the daemon stopped, needs no key. Reads `<logs>/daemon.log` (rotates at 64 MB to `daemon.log.1`) |
| boot crashes | `<logs>/daemon-boot.log`: JVM output when splice started the daemon. Under a `splice.service` unit it is in the systemd journal instead |
| status | `splice status`: one row per head, daemon line first |
| sessions | `splice sessions`: each session, its head, live / stale / gone |
| findings | `splice doctor`, `splice check-config` |
| requests | the console's Requests page, outcome words from 1.5 |
| rates and failures | `splice perf --window 1h\|24h\|7d` |

Never run `splice logs --follow` (it never returns). `splice trace` and `splice wire` print conversation
content: ask first. A log line can quote up to 200 characters of an upstream error body. Never paste a key you
find in output.

Source: `features/diagnostics/.../logs/LogsCommand.kt` 1-45, 163-169; `README.md` 553-554, 322-325;
`app/.../cli/status/StatusCommand.kt` 41-70.

### 6.2 What `splice status` says

First line: `daemon running on <port>`, or `daemon stopped (starts on first launch)`, which is normal and not an error.
Per head, the last column reads `ready` (or `ready (your login)`); `splice install` (wrapper link missing);
`<command> login` (no credential); `out of quota until <time>`; `runtime not answering on <endpoint>` (a local
model server is down); or `not running: <reason>` (the daemon could not build or bind the head).
Source: `StatusCommand.kt` 56-70; `StatusTable.kt` 109-156.

### 6.3 Playbook

"Ask" means the manager asks the person first, per `operations.md`. "Person types" means the person runs it in
their own terminal; you type nothing secret.

| symptom | check | fix |
|---|---|---|
| A head will not boot, or the whole daemon refuses to start | `splice check-config`: exit 3 lists every finding. Then `splice status` for `not running: <reason>`, then `splice logs --tail 100` for `[<key>][boot] SKIPPED` | Whole-file findings refuse boot; a single head that fails to build is skipped with its reason. Ask, back up, edit `splice.toml`, rerun `splice check-config` until exit 0, then the person restarts. In a terminal, a `splice` verb that hits a refusal offers an interactive fix session. Outside a terminal it only prints the list |
| Port in use | `splice status` reasons: `port N is claimed by A and B; give each head its own port`, or `head 'k' has an invalid port N`. Another program: `ss -ltnp` (Linux) or `lsof -iTCP:<port> -sTCP:LISTEN` shows the holder. `splice listeners` prints the ports splice wants | Give each head a unique `port` in `[heads.<key>]`. For the control port set `[daemon] control_port` (or `SPLICE_CONTROL_PORT`). Port 3095 is the hub's and is fixed. Ask, edit, `splice check-config`, restart |
| 401 from a provider | Requests word: Signed out (splice holds nothing) or Credentials refused (provider rejected it). `splice doctor` auth section | OAuth head: person types `splice login <head>` (add `--label <name>` for another account). API-key head: person types `splice key set <ENV_NAME>` or `<command> login`; a saved key applies on the next request, no restart. `splice key list` shows names only. If the text is `invalid local gateway credentials`, relaunch that Claude Code session |
| Quota exhausted on an account | Status row `out of quota until <time>`; Requests shows the window's lock and reset; Sessions card says At limit; `splice doctor` accounts section | It clears at the reset by itself. A head with an account pool moves to a free login. Options for the person: add an account (`splice login <head> --label <name>`), use Continue on to move the session to another head on the Sessions page, or wait |
| Over budget | Requests word Over budget (`error:budget-blocked`) | Budgets are set in the console. The person raises or clears it |
| A session does not come back after a restart | `splice sessions` (live or stale), `splice logs --head <key>`, the session card on Sessions (Stalled, Resumes in, Won't resume, At limit, Signed out) | Claude Code retries by itself. If the turn was cut after text began, the person re-sends. `claude-<head> -r <id>` resumes the transcript. A session that never recovers is a bug: report the log lines |
| Console says Locked or unauthorized | the page shows `Locked` and `splice console` with a Copy button: the browser holds no key splice accepts | Person types `splice console`. It starts the daemon if needed and opens the console with the key in the address fragment. Do it again after the management key is rotated or deleted |
| `splice console` cannot start the daemon | message `the daemon isn't running and couldn't be started`, or `the daemon has no management key yet; run splice restart` | read `<logs>/daemon-boot.log`, run `splice check-config`, then 6.3 first row |
| Usage chart slow or half drawn | first open after idle reads each head's totals: chips read Not counted yet or Not read while a head is still being read | wait and reopen. A long `historyRetentionDays` (default 35 on a fresh install) makes the week read bigger; shorten it only on the person's yes, in Settings > Your data, because it asks before deleting. A head that errors keeps its chip: check that head |
| A model is missing from the picker | `splice models [provider]` lists what the endpoint serves beside what is declared. Check the head's `models` allowlist, the provider's `discovery = { include/exclude }`, and `model_slots` | A `models` list is an allowlist: add the id, or use `model_slots` to map tiers without restricting. A discovery `exclude` hides it. The endpoint's list is read at start and hourly. A provider or head edit needs `splice check-config` and a restart; a pure `context_window` edit does not |
| Claude Code shows the wrong context window or compacts early or late | the row's `context_window` in `splice.toml` (Models page shows it); the id spelling in the picker | Claude Code fixes one window per process (`CLAUDE_CODE_MAX_CONTEXT_TOKENS`, set from the pinned row) and compacts on counts splice reports, scaled by client window over declared window. So correct the row's `context_window` to the real ceiling: it is live, no restart. The bracket in an id, `[1m]` or `[500k]`, is a selector Claude Code reads: `[1m]` is always 1,000,000 to the client, so never put `[1m]` on a row whose real ceiling is lower; declare `1000000` for a `[1m]` row, not 1048576 |
| `splice status` says `daemon stopped` | it starts on the first launch of any head command, `splice console` or `splice restart`. If it will not start: `<logs>/daemon-boot.log`, `splice check-config`, `splice doctor`. A daemon of another version is replaced by the next launch | Ask the person to run `splice restart` in their own terminal (a restart from the manager's pane carries the manager's environment). If the control port is held, see Port in use |
| Tools vanish from a session | `splice doctor` MCP hub row, `systemctl --user status splice-mcp-front.socket` | 2.5. Without the hub, a daemon restart can need `/mcp` in the session |

Source: `app/.../head/HeadBoot.kt` 28-60; `core/.../topology/TopologyMessages.kt` 32-38; `app/.../cli/ConfigFixOffer.kt` 1-3;
`app/.../cli/auth/KeyCommand.kt` 1-6; `app/.../cli/status/ConsoleCommand.kt` 28-46; `app/src/main/resources/console/api.js` 12-29;
`CHANGELOG.md` 152-153, 202-208, 1117-1118, 1807-1810; `app/.../daemon/TopologyWindows.kt` 1-22;
`core/.../model/ModelCatalog.kt` 6-30, 218-290; `app/src/main/resources/splice.example.toml` 436-445, 488-499;
`README.md` 172-185, 332-336, 946; `features/lifecycle/.../restart/RestartCommand.kt` 1-6.

The example file's comment near `splice.example.toml` line 500 says a window edit needs `splice restart`. The
header at lines 5-6, the README and `TopologyWindows.kt` say it does not. The code wins: no restart.

### 6.4 When the table does not answer

1. Run `splice doctor` and read the failing row's fix.
2. Run `splice logs --tail 200` and `splice logs --head <key> --tail 200`.
3. Search this skill: `grep -rn "<words>" .claude/skills/splice/`.
4. Say "I don't know", name what you checked, and offer `splice doctor --json --with-logs --out <file>` for the
   person to share. It is redacted, and nothing is uploaded.
