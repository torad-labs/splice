Last read: 2026-10-15 against splice 0.4.0 (feat/v0.4.0)

# splice.toml reference

Every table and field a manager needs, with its type, default, effect and when it applies. Built from the
schema classes; where `splice.example.toml` or another doc disagrees, the code wins and the row says so.
How to edit the file safely is `operations.md`, section 3. Recipes that use these fields are in
`heads-and-backends.md`. The annotated example ships in the repo at `app/src/main/resources/splice.example.toml`.

Source: core/src/main/kotlin/splice/core/topology/Topology.kt, TopologySchema.kt, HeadConfig.kt, QuirksConfig.kt,
ModelDiscoveryConfig.kt, ChatEffortVocabularyConfig.kt; core/src/main/kotlin/splice/core/model/ModelCatalog.kt
(ModelEntry, ExtraWindow, WindowRule) and TokenCost.kt (TomlRates); core/src/main/kotlin/splice/core/config/Knob.kt.

## 1. How the file is read

- **Where.** `SPLICE_CONFIG` if set, else `$XDG_CONFIG_HOME/splice/splice.toml`, else
  `~/.config/splice/splice.toml`. An absent file is written as a starter at first boot, mode 0600.
- **When.** Once, at boot. Any provider, head or knob edit applies at the next `splice restart`.
- **Live keys.** The running daemon re-reads these when the file changes, with no restart: every context
  window (a model row's `context_window`, `extra_windows`, `window_rules`, `default_context_window`, a head's
  `context_window`), the `[compaction]` tables, and `[daemon]` `mcp_share`, `update_channel`, `auto_update`.
- **Strings, numbers, booleans.** Strings are quoted, integers bare, booleans `true`/`false` unquoted. Values
  under `[defaults]` and `[heads.<key>.overrides]` are always quoted strings, even numbers.
- **Code Mode controls** belong only in `[heads.<key>.overrides]` and require a restart:
  `codeModeDefaultExecYieldTimeMs = "30000"`, `codeModeToolDescriptionFirst = "false"`,
  `codeModeShowCellOverhead = "false"`, and `codeModeUnifiedImageBudget = "false"` are the defaults.
  Optional `codeModeToolInputSchemaMaxBytes = "16000"` raises the declaration budget above the bundled minimum.
  Exec pragmas override the ordinary observation budget; yielding never cancels the script.
- **Inline or table form.** `quirks = { a = true }` and `[providers.x.quirks]` are the same table. TOML forbids
  adding a sub-table (such as `tool_surface`) to an inline table, so use the table form then.

A live edit the daemon cannot apply (the file no longer parses, a head's new declaration fails, a local runtime
refuses the window) keeps the windows already in force and says why in the daemon log.

Source: integrations/topology/src/main/kotlin/splice/topology/TopologyLoader.kt (lines 226-290);
Topology.kt (withoutLiveKeys, lines 99-108); app/src/main/kotlin/splice/app/daemon/TopologyWindows.kt (header,
lines 1-23); splice.example.toml (lines 3-7, 74-75).
README "Long-session reliability" says every other key is read only at boot; the code also reads `mcp_share`,
`update_channel`, `auto_update` and `[compaction]` live. web-console.md section 2.3 (line 96) names only a
model's `context_window` as live. The example's lines 500-501 say a window edit reaches running sessions after
`splice restart`, against its own lines 5-6. The list above is the code's.

### Boot is fail-closed

Boot and `splice check-config` run one findings pass. Any finding refuses boot, and every finding is listed at
once. `check-config` prints nothing and exits 0 on a clean or absent file. On findings it prints
`splice: splice.toml has N problem(s); splice will not start until all are fixed:` and one `  - <key> (line N): <why>`
per finding, and exits 3. It never starts or contacts the daemon. It does ask a local runtime that is up what
it serves. `SPLICE_CONFIG=<path> splice check-config` checks a candidate file at another path.

| finding | message (start) |
|---|---|
| a field the schema does not declare | `is not a splice.toml setting; remove the line` |
| a value of the wrong type | `expects <type>: <fix>` |
| a line the parser cannot read | `the parser cannot read this line; ...` |
| a table header written twice | `table X (line N) is defined twice ...` |
| `models =` twice in one table, or `models` as plain strings | `duplicate models key in X` / `must be an array of inline tables` |
| a head's `provider` not declared | `the provider this head names is not declared under [providers]` |
| an auth kind its dialect cannot carry | `head 'K' has incompatible auth kind ...` |
| a head port outside 1-65535 | `port P is outside 1-65535; use a port in that range` |
| two heads, or a head and the control plane, on one port | `port P is shared by A, B; give each its own port` |
| head `context_window` 0 or less | `head context_window must be positive` |
| empty `discovery_prefix` | `discovery prefix is the picker namespace and is never empty` |
| a tier written twice in `model_slots` | `model_slots in X (line N) model_slots contains duplicate tiers ...` |
| a knob name the daemon does not know, a value it cannot coerce, or a per-head-only knob in `[defaults]` | under `defaults.<k>` or `heads.<h>.overrides.<k>` |
| a standing prompt with both text and file, an unreadable file, a relative project root, a bad strip pattern | under `heads.<k>.system_prompt*` or `projects.<root>` |
| `[compaction]` with both keys, a blank or repeated model, a repeated project, an unreadable file | under `compaction` |
| `update_channel` other than `stable` or `beta` | `must be "stable" or "beta"` |
| `client` auth with `Authorization` or `x-api-key` in `extra_headers`; `code_mode` on the wrong shape; empty `code_mode_models`; `code_mode_memory_mb` not positive; the retired `compact_effort`; a `model_slots` unknown tier, blank id, one id on two tiers, or slots in both styles | these refuse inside the decoder, so unless a type or unknown-key finding shows, the file reports only `the file cannot be decoded and the parser's text is withheld ...`. Look at the last edit |
| a local runtime that is up refusing a row (unlisted, window too large, no tools) | the runtime's own words |

Not boot findings: a head's roster (`pinned_model` missing, a pin outside `models`, a `models` id the provider
does not declare and cannot list). Boot goes on and that head alone does not start; the daemon log says why.
Unknown keys are not checked inside map tables (`[defaults]`, `overrides`, `extra_headers`, `rates`,
`model_slots`), but knob names in `[defaults]` and `overrides` are, by the knob row above.

Source: integrations/topology/src/main/kotlin/splice/topology/ConfigFindings.kt; TomlStructurePreflight.kt;
TopologyLoader.kt (lines 85-114); core/src/main/kotlin/splice/core/topology/TopologyWriter.kt (lines 268-407);
core/src/main/kotlin/splice/core/util/SafeFailureText.kt (lines 48-77); app/src/main/kotlin/splice/app/cli/ConfigPreflight.kt;
core/src/main/kotlin/splice/core/config/ConfigService.kt (coerceRejects, lines 220-247); HeadModels.kt.

## 2. Top-level tables

| table | type | what |
|---|---|---|
| `[daemon]` | table | the daemon itself: control port, state dir, reasoning display, MCP, updates |
| `[defaults]` | map of knob name to string | knobs for every head |
| `[claude]` | table | which Claude Code config items every head shares |
| `[compaction]` | table | your text after Claude Code's compaction prompt (live) |
| `[providers.<key>]` | map of provider tables | one upstream each |
| `[heads.<key>]` | map of head tables | one loopback port each |
| `[projects."<root>"]` | map of project tables | standing prompts by working directory |

## 3. [daemon]

| field | type | default | effect | applies |
|---|---|---|---|---|
| `control_port` | int | absent: the `controlPort` knob, 3096 | the loopback control plane and console port; counted in the port-collision check | restart |
| `state_dir` | string | `~/.splice/state` (or `SPLICE_STATE_DIR`, or an adopted pre-0.4 `~/.claude-codex/state`) | the state folder itself: lock, per-head state, traces, mgmt key. `~/` is home. An unusable value keeps the default and doctor names it | restart |
| `show_reasoning` | string | `"text"` | `"text"`, `"thinking"` or `"off"`: how reasoning streams into Claude Code | restart from TOML; HOT by PATCH |
| `summary` | string | `"detailed"` | `"detailed"`, `"concise"`, `"auto"`, `"none"` | restart; HOT by PATCH |
| `effort` | string | absent (reads as high) | default reasoning effort | restart |
| `replay_reasoning` | bool | true | send earlier turns' reasoning back to the model, as Codex does | restart; HOT by PATCH |
| `mirror_reasoning` | bool | false | locked off; nothing can enable it | n/a |
| `fold_reasoning_models` | array of strings | `gpt-5.6-luna`, `gpt-5.6-terra`, `gpt-5.5` | models whose cut reasoning is continued | restart; HOT by PATCH |
| `fold_max_continue` | int | 3 | hidden continuation rounds per fold | restart; HOT by PATCH |
| `fold_marker_text` | string | `"Continue thinking..."` | the continuation instruction | restart; HOT by PATCH |
| `fold_max_tier` | int | 6 | deepest truncation tier that still folds | restart; HOT by PATCH |
| `mcp_hosting` | bool | absent reads as true | host eligible stdio MCP servers once in the daemon | restart |
| `mcp_hosting_exclude` | array of strings | empty | servers kept per session | restart |
| `mcp_share` | bool | absent reads as true | one shared copy of each server, or one per session | live |
| `update_channel` | string | absent reads as `"stable"` | `"stable"` or `"beta"` (prereleases too) | live |
| `auto_update` | bool | absent reads as false | install newer releases on the channel by itself | live |

The `[daemon]` fields are snake_case. The same knobs elsewhere (`[defaults]`, overrides) use the camelCase
knob name: `show_reasoning` here is `showReasoning` there.

Source: Topology.kt (DaemonConfig, lines 111-142); TopologyKnobLayer.kt (lines 27-51);
integrations/topology/src/main/kotlin/splice/topology/TopologyStatePaths.kt (lines 22-44);
core/src/main/kotlin/splice/core/config/StatePaths.kt (lines 144-191); splice.example.toml (lines 13-41).
The example's line 16 says the `state_dir` default is `~/.splice`. In code the value names the state folder
itself, and the default is `~/.splice/state`. MCP behaviour: `reliability-mcp-install.md`. Updates and their
install path: `reliability-mcp-install.md`.

## 4. Knobs: [defaults] and [heads.<key>.overrides]

A knob is a named runtime setting. Its value is always a quoted string. Precedence, lowest first: the knob's
default, `[defaults]`, `[heads.<key>.overrides]`, the state dir's `config.json`, the environment, a runtime
PATCH. A knob in splice.toml applies at the next restart whatever its marking. HOT means a console change or
PATCH reaches the running daemon at its next use. Env aliases: the example file, lines 934-978.

| knob | default | HOT | what |
|---|---|---|---|
| `maxInflight` | `12` | yes | concurrent turns per head; the rest queue. 0 = unlimited. Set it to what the upstream account tolerates |
| `maxQueued` | `512` | yes | admission queue cap; 0 = unlimited |
| `upstreamTimeoutMs` | `900000` | no | the longest one upstream round may take |
| `firstByteTimeoutMs` | `90000` | yes | silence before the first frame before the watchdog probes |
| `streamIdleMs` | `90000` | yes | mid-stream silence before the watchdog probes; a live path is held |
| `stallReanchorMs` | `20000` | yes | mid-output silence before a re-anchor; only for a `reanchor_prefill` head; 0 off |
| `upstreamRetries` | `4` | yes | re-POSTs per round before failing |
| `retryBackoffBaseMs` / `retryBackoffCapMs` / `retryBackoffJitterPct` | `200` / `10000` / `10` | yes | the generic retry curve |
| `progressLine` | `true` | yes | splice's status line on a quiet wire |
| `showReasoning` / `summary` / `replayReasoning` | as `[daemon]` | yes | reasoning display |
| `effort` | none | no | default effort |
| `toolSurface` | `auto` | no | `auto` lets splice arm tool_search per backend |
| `quotaPoll` | `auto` | no | `off` stops every plan-usage poller |
| `quotaPollIntervalMs` | `300000` | yes | plan-usage poll interval |
| `usageWarnPct` | `80` | yes | plan-window percent that warns |
| `usageWarnTokens5h` | `0` | yes | absolute 5-hour token warning; 0 off |
| `budgetDefaultAction` | `warn` | yes | `warn` or `block` for a bare budget row |
| `historyRetentionDays` | none (the starter writes `35`) | yes | days of hourly totals and request records, or `forever` |
| `activityRetentionDays` | `90` | yes | days of message edges kept, at least 2 |
| `messageEdges` | `true` | yes | keep SendMessage edges |
| `activityStoreHeads` | `*` | no | heads that store activity labels: `*`, empty, or `a,b` |
| `transcriptView` | `true` | yes | read Claude Code's written conversation for the console |
| `keepTranscriptCopies` / `transcriptCopyDays` | `true` / `30` | yes | copy a transcript before a move rewrites it, and how long |
| `keepCompactionSummaries` | `true` | yes | park a finished compaction answer for a retry |
| `keepCodeModeWork` | `true` | yes | write code mode's journal to disk |
| `projectsFolders` | empty | yes | colon-separated folders that define a session's project |
| `statuslineGitRoots` | empty | yes | extra git roots the status line reads |
| `mcpIdleTimeoutMs` / `mcpMaxServers` | `1800000` / `32` | yes | hosted MCP idle close; most hosted servers |
| `mcpRequestTimeoutMs` / `mcpInitializeTimeoutMs` | `1800000` / `60000` | yes | hosted MCP request and handshake budgets |
| `maxRequestBytes` | `33554432` | yes | largest client request body |
| `requestReadTimeoutMs` / `ingressReadIdleMs` | `30000` / `30000` | yes | client body read limits |
| `materializationHeapBytes` | `0` (JVM max heap minus 384 MiB) | yes | shared decode budget |
| `trace` | `true` | no | per head only: the full request/response trace under `<state>/trace/` |
| `wireTap` | `0` | no | per head only: keep the last N upstream request bodies in memory for `splice wire` |
| `traceRetentionDays` / `traceMaxBodyChars` | `7` / `16777216` | no | trace retention and body cap |
| `authCacheMs` | `60000` | no | how long an OAuth snapshot is reused |
| `debug` | `false` | no | verbose logging |
| `supervisorUnit` / `mcpSlice` | `splice.service` / `app-mcp.slice` | no | optional systemd names |
| `controlPort` | `3096` | no | same as `[daemon] control_port` |
| `contextWindowOverride` | none | no | pins one client window |

Legacy single-head knobs (`port`, `pinnedModel`, `chatgptApiBase`, `codexAuthPath`, `grokPort`, `grokModel`,
`xaiApiBase`, `grokAuthPath`) are seeded from the TOML only when exactly one ChatGPT or Grok head exists. Set
the head's own fields instead.

Source: core/src/main/kotlin/splice/core/config/Knob.kt (defaults and restartRequired, for example lines 191-200,
246-251); TopologyKnobLayer.kt (lines 53-96); splice.example.toml (lines 644-662, 799-932).
The example's line 825 says `maxInflight` defaults to `"0"` (unlimited). The code's default is 12 (Knob.kt:199).
`upstreamTimeoutMs` is missing from the example's knob reference; its default is 900000 and it needs a restart.

## 5. [providers.<key>]

| field | type | default | effect |
|---|---|---|---|
| `dialect` | string, required | none | `openai-responses`, `openai-chat` or `anthropic-passthrough` |
| `base_url` | string, required | none | the upstream root; no trailing `/`. See the path table in `heads-and-backends.md`, section 3 |
| `auth` | table, required | none | section 5.1 |
| `extra_headers` | map of string to string | empty | static headers on every upstream request and the model-list probe. Literal strings; no env interpolation. Never a secret |
| `quirks` | table | all absent | section 5.5 |
| `models` | array of tables | empty | the declared rows, section 5.2 |
| `extra_windows` | array of tables | empty | windows for ids without a row, section 5.3 |
| `window_rules` | array of tables | empty | windows by id prefix |
| `default_context_window` | int | 0 (unset) | the provider's fallback window |
| `local` | bool | absent: true for `openai-chat` on a loopback host | a user-managed runtime: probed at boot, never asked for a model list |
| `models_url` | string | the dialect's place | where the model list is read: `<base_url>/models` (openai), `<base_url>/v1/models` (passthrough) |
| `discovery` | table | admits every usable published model | section 5.4 |

Everything here applies at restart, except the window fields, which are live.

`extra_headers` by dialect: on `anthropic-passthrough` they apply last and replace any header of the same
name, `Authorization` included. On `openai-chat` they ride but cannot replace the auth header. On a generic
`openai-responses` provider no reader was found outside the Muse arm (unverified). A quoted header name
(`"anthropic-version" = ...`) and a bare one behave the same.

Source: Topology.kt (ProviderConfig, lines 144-244); LocalProviderRule.kt; PassthroughProvider.kt (lines 99-103);
app/src/main/kotlin/splice/app/provider/ChatArm.kt (lines 120-138); app/src/main/kotlin/splice/app/provider/MuseResponsesArm.kt (line 46).

### 5.1 auth

| field | type | effect |
|---|---|---|
| `kind` | string | `api-key`, `chatgpt-oauth`, `grok-oauth`, `kimi-oauth`, `muse-oauth` or `client`. An unregistered kind behaves as `api-key` |
| `env` | string | api-key: the variable name the key is read under. Default `<HEAD KEY>_API_KEY` uppercased, which is not a valid shell name when the head key has a dash, so always set it |
| `file` | string | api-key: a key file (a bare line or `{"api_key": ...}`). OAuth: the credential file; default is splice's own under `~/.config/splice/auth/`. Point it at a vendor app's file only to share that app's login, which then rotates against splice's |

An api-key is looked up per request in this order: the daemon's environment variable `env`, then `file`, then
`keys.toml` under the same name. It is sent as `Authorization: Bearer <key>`. The person stores it with
`splice key set <env>`; no restart is needed. Which kinds fit which dialect: `heads-and-backends.md`, section 3.
Accounts, a second login and pools: `accounts-sessions-console.md`.

Source: TopologySchema.kt (AuthConfig, lines 49-62); AuthKind.kt; integrations/providers/openai/src/main/kotlin/splice/provider/openai/ApiKeyAuthProvider.kt
(lines 106-111); core/src/main/kotlin/splice/core/auth/Credentials.kt (lines 19-22);
app/src/main/kotlin/splice/app/cli/auth/CliSignIn.kt (lines 44-49, the env name derives from the head key).

### 5.2 Model rows: [[providers.<key>.models]]

| field | type | default | effect |
|---|---|---|---|
| `id` | string, required | none | the upstream id, sent as written. A trailing `[1m]`-style tag is stripped on the wire and kept as a picker hint |
| `context_window` | int, required | none | the window Claude Code compacts at for this row (live). A `[1m]` row declares exactly 1000000 |
| `label` | string | `""` (shows the id) | picker name |
| `description` | string | `""` | picker text |
| `client_model` | string | none | the Claude model Claude Code resolves this row as, through the head's `modelOverrides`. Must start with `claude-`; one row per name |
| `compaction_reserve_tokens` | int, 0 or more | the bundled calibration | tokens held back before compaction |
| `rates` | inline table | none: the provider's listed price, then splice's shipped card for a known vendor | USD per million tokens, section 5.6 |

A declared row always wins its model over a discovered one: its window, label, rates and place.

Source: core/src/main/kotlin/splice/core/model/ModelCatalog.kt (lines 41-81, 371-392); Topology.kt (lines 360-394);
splice.example.toml (lines 428-454).

### 5.3 Windows without a row

```toml
[[providers.kimi.extra_windows]]
id = "k3"                 # an exact id; max_context_window = N optionally names the serve ceiling
context_window = 1000000
[[providers.x.window_rules]]
prefix = "vendor/big-"    # every id starting with this
context_window = 500000
```

A row's window is chosen in this order: the head's `context_window`, the row, a row of the same bare id,
`extra_windows`, `window_rules`, `default_context_window`, then 200000. All live.

Source: Topology.kt (declaredWindowFor, lines 256-265; windowFor, lines 405-413); ModelCatalog.kt (lines 82-102).

### 5.4 discovery

At start and hourly the daemon asks the provider's list endpoint what it serves. Undeclared usable models join
the picker of a head with no `models` allowlist. A local provider is never asked.

| field | type | default | effect |
|---|---|---|---|
| `include` | array of globs | empty: all | only matching ids join (`*` matches any run) |
| `exclude` | array of globs | empty | matching ids never join. `["*"]` admits nothing, and a head model no row declares is then refused as a misspelling. The endpoint is still asked |
| `family` | string | derived from auth, key, address | whose machine runs it, for the console (for example `"vast"`) |

Source: ModelDiscoveryConfig.kt; Topology.kt (listsModels, lines 420-424); features/models/src/main/kotlin/splice/models/discovery/ModelDiscovery.kt
(line 46); app/src/main/kotlin/splice/app/provider/ModelRosters.kt (lines 97-140); ProviderFamilyRule.kt.
ModelDiscoveryConfig's comment and the example's line 225 say `exclude = ["*"]` turns discovery off. The
request still goes out; only its result is dropped.

### 5.5 quirks, by dialect

Absent means the provider's base profile. Only the listed dialect reads a quirk.

| quirk | type | default | dialect | effect |
|---|---|---|---|---|
| `store` | bool | false | responses | the Responses `store` field |
| `cache_key` | string | `"first-message-hash"` | responses | prompt-cache key strategy; the example also uses `"session-id"` |
| `summary_field` | bool | true | responses | send `reasoning.summary` |
| `tool_choice` | bool | false | responses | send `tool_choice` |
| `reasoning_cache` | bool | codex on, others off | responses | hold encrypted reasoning across tool round-trips |
| `parallel_tool_calls` | bool | false | responses | the value sent; ChatGPT lite 400s on true |
| `websocket` / `websocket_prefill` | bool | off | responses | Responses WebSocket overlay; ships off |
| `summary_delivery` | string | codex `"sequential_cutoff"` | responses | `"off"` omits the field |
| `account_id_header` | bool | false | responses, ChatGPT | send the account id header |
| `effort_ceiling` | string | `"max"` | none | parsed, but no arm reads it today |
| `zstd_request_body` | bool | false | any | compress request bodies; proven for ChatGPT only, xAI 400s |
| `tool_surface` | table | absent: off | responses | deferred tools: `enabled` (true), `defer_prefixes` (`["mcp__"]`), `defer`, `eager`, `min_deferred` (8), `search_limit` (8, 1-50), `search_rounds` (3, 1-5) |
| `code_mode` and `code_mode_workers` (6), `code_mode_heap` (512), `code_mode_memory_mb` (24576), `code_mode_models` | bool, ints, array | on for `chatgpt-oauth` + responses only | responses | the native JavaScript runner; `code_mode_timeout_ms` is retired and must be removed; use exec/wait `yield_time_ms` instead. See `reliability-mcp-install.md` |
| `reasoning_effort` | bool | true | chat | false omits `reasoning_effort` and `reasoning` (strict vendors) |
| `chat_effort_vocabulary` | table | absent | chat | `default`, `levels` map, optional `model_pattern` regex |
| `stream_usage` | bool | on for local and grok-oauth, else off | chat | ask for a usage frame |
| `slot_affinity` | bool | off | chat, llama-server | keep each conversation on one slot |
| `mfjs` | bool | off | passthrough | rewrite tool schemas to Moonshot's dialect |
| `block_allowlist` | array | absent: all ride | passthrough | content blocks kept; others dropped. `[]` clears a base list |
| `strip_cache_control` | bool | off | passthrough | drop every `cache_control` |
| `synthesize_signatures` | bool | off | passthrough | invent a thinking signature; wrong for a verifying upstream |
| `map_thinking_adaptive` | bool | off | passthrough | map thinking to Kimi's adaptive form |
| `strip_sampling_params` | bool | off | passthrough | drop temperature, top_p, top_k |
| `reanchor_prefill` | bool | off | passthrough | resume a cut stream by prefilling; only after a probe |
| `tool_name_cap` | int | muse 64, else none | passthrough, muse | cap tool name length |
| `compact_effort` | retired | | | refuses boot; remove it |

Source: QuirksConfig.kt; app/src/main/kotlin/splice/app/provider/QuirksOverlay.kt (lines 37-188);
CodexResponsesArm.kt (line 97); app/src/main/kotlin/splice/app/head/UpstreamFactory.kt (line 31);
splice.example.toml (lines 76-176, 284-309, 399-422).

### 5.6 rates

USD per million tokens, on a row (`rates = { ... }`) or on a head (`[heads.<key>.rates]`, keyed by row id, which
wins for the ids it names). Required: `input`, `cache_read`, `output`. Optional: `cache_write`, `cache_write_1h`.
A long-context tier comes as a set: `long_context_over_input_tokens`, `long_context_input`,
`long_context_cache_read`, `long_context_output`, optional `long_context_cache_write` and
`long_context_cache_write_1h`. No card means "no rate card", never zero. Budgets live in `budgets.json`, not here.

Source: TokenCost.kt (TomlRates, lines 142-175); HeadConfig.kt (lines 44-54); splice.example.toml (lines 709-713, 731-732).

## 6. [heads.<key>]

| field | type | default | effect |
|---|---|---|---|
| `provider` | string, required | none | a `[providers.<key>]` key |
| `port` | int, required | none | the loopback port; unique across heads and the control plane |
| `discovery_prefix` | string, required, non-empty | none | the picker namespace: Claude Code sees `<prefix><id>` |
| `pinned_model` | string | `""` | the default model; required except on a `client` head |
| `models` | array of `{ id, slot }` | absent: the provider's whole surface | an ordered allowlist. `slot` is `opus`, `sonnet`, `haiku` or `fable`, one each, one id each |
| `model_slots` | map of tier to id | empty | tiers without restricting the picker. Never with slots inside `models` |
| `context_window` | int, positive | absent | forces one window onto every selected row (live) |
| `overrides` | map of knob to string | empty | knobs for this head only, section 4 |
| `system_prompt` / `system_prompt_file` | string | absent | standing text on every turn; never both. A relative file is beside splice.toml |
| `system_prompt_mode` | string | `"append"` | `"append"`, `"replace"` or `"strip"` (a list of regex, one paragraph each) |
| `rates` | map of id to rates | absent | this head's own cards, section 5.6 |

A tier no row declares is not offered at launch, so Claude Code's built-in requests on it (titling,
`--model haiku`) get a clean 400 before the upstream. Declare a row per tier you want served.

`[heads.<key>.claude]`, the wrapper:

| field | type | default | effect |
|---|---|---|---|
| `command` | string | the head key | the wrapper name `splice install` links |
| `config_dir` | string | `~/.claude-<head key>` | this head's Claude Code config folder |
| `isolate` | array of item names | empty | items this head keeps private, overriding `[claude] share` |

Source: HeadConfig.kt; HeadCatalogStatics.kt; HeadModels.kt; TopologySchema.kt (ClaudeWrapperConfig);
Topology.kt (resolveHeadKeys, line 56); splice.example.toml (lines 491-501, 517-545, 571-592).

## 7. [claude]

| field | type | default | effect |
|---|---|---|---|
| `share` | array of strings | `settings`, `mcps`, `skills`, `hooks`, `agents`, `commands`, `plugins`, `claude_md`, `sessions`, `projects` | items every head shares with `~/.claude`. `CLAUDE.md` and `claude_md` are the same item, so are `mcps` and `mcp` |

`sessions` shares the messaging registry, so sessions on any head can see each other. `projects` shares the
transcript tree, so `-r` on any head can resume a session from another. Sessions and Teams have no other TOML
table; Teams lives in daemon state (`teams.json`). See `accounts-sessions-console.md`.

Source: TopologySchema.kt (lines 72-86); integrations/claude-code/src/main/kotlin/splice/client/ClaudeMaterializeTypes.kt
(lines 17-30); splice.example.toml (lines 502-515).

## 8. [projects."<root>"]

The key is an absolute path or `~/...`; the deepest root holding the session's working directory wins. Fields:
`system_prompt`, `system_prompt_file` (relative to the root), `system_prompt_mode`, and
`[projects."<root>".heads.<key>]` with the same three for one head. Layers apply head, then project, then
project-head. Restart.

```toml
[projects."~/work/api"]
system_prompt_file = ".splice/prompt.md"
[projects."~/work/api".heads.claudex]
system_prompt = "Run the api test suite before you say done."
```

Source: TopologySchema.kt (ProjectConfig, lines 10-29); splice.example.toml (lines 546-569).

## 9. [compaction] (live)

`instructions` or `file`, never both, at the top, then `[[compaction.model]]` (`model`, plus `instructions` or
`file`) and `[[compaction.project]]` (`path`, optional `model`, plus one of the two). The most specific scope
wins and replaces the others; `instructions = ""` opts a scope out. An unreadable file refuses boot.

Source: core/src/main/kotlin/splice/core/compaction/CompactionScope.kt (lines 8-28); TopologyWriter.kt (lines 314-329);
splice.example.toml (lines 43-55).

## 10. Where the other topics live

| topic | in splice.toml | the rest |
|---|---|---|
| accounts and logins | `auth.file`; extra accounts are files beside it, with no TOML key | `accounts-sessions-console.md` |
| models and routing | rows, `models`, `model_slots`, `pinned_model`, `client_model`, discovery | `accounts-sessions-console.md` |
| console | no table; the Settings and Models pages write this file | `accounts-sessions-console.md` |
| MCP | `[daemon] mcp_hosting`, `mcp_hosting_exclude`, `mcp_share`; `mcp*` knobs | `reliability-mcp-install.md` |
| code mode | the `code_mode*` quirks | `reliability-mcp-install.md` |
| usage and cost | `rates`, `usageWarn*`, `quotaPoll*`, `budgetDefaultAction`, `historyRetentionDays` | `accounts-sessions-console.md` |
| retries and timeouts | the retry and timeout knobs, section 4 | `reliability-mcp-install.md` |
| updates | `[daemon] update_channel`, `auto_update` | `reliability-mcp-install.md` |

Source: README.md (lines 274-291, budgets; lines 342-437, accounts); docs/specs/web-console.md (line 521, Teams in state).
