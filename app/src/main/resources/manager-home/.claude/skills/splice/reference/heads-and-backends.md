Last read: 2026-10-15 against splice 0.4.0 (feat/v0.4.0)

# Heads and backends: add a head, and reach a backend splice has no profile for

Read this when the person asks to add a head, add a second head on a provider, or reach a vendor splice has
no profile for ("add Bedrock", "can splice talk to X?"). Every field named here is listed with its type and
default in `toml-reference.md`. How to edit `splice.toml` safely (backup, check, diff, restart) is in
`operations.md`, sections 3 to 5. Never write a key into `splice.toml`.

## 1. Provider, head, command

- A **provider** is one upstream: `dialect` (the wire splice speaks to it), `base_url`, `auth`, optional
  `quirks`, `extra_headers` and model rows. Table `[providers.<key>]`.
- A **head** is one loopback port that speaks the Anthropic Messages wire to Claude Code and translates each
  turn for one provider. Table `[heads.<key>]`, with `provider = "<key>"` and a port no other head and not
  the control plane uses. Many heads may share one provider.
- A **command** is the wrapper the person types to start Claude Code on a head, `[heads.<key>.claude]
  command`. `splice install <command>` links it into `~/.local/bin`.
- Claude Code sees each model row as `<discovery_prefix><id>`. A turn on an id that no declared or discovered
  row names is refused before it reaches the upstream.

Source: core/src/main/kotlin/splice/core/topology/Topology.kt (ProviderConfig, portCollisions);
core/src/main/kotlin/splice/core/topology/HeadConfig.kt; core/src/main/kotlin/splice/core/model/ModelCatalog.kt
(wrap, line 185); features/turns/src/main/kotlin/splice/head/turn/TurnPreparation.kt (line 152).

## 2. The three moves

Adding a head is always one of three moves.

| move | what changes | code? | examples in the example file |
|---|---|---|---|
| 1. Reuse a schema | An existing dialect and auth kind fit. The head is pure TOML: `base_url`, `auth`, models, port, prefix, command | no | openrouter, fireworks, deepseek, a local runtime |
| 2. Declare a new one | A new wire grammar (dialect) or a new token grammar (an auth flow: OAuth, signing, refresh) | yes: one module or one AuthProvider, plus one dispatch arm | chatgpt-oauth, grok-oauth, kimi-oauth, muse-oauth |
| 3. Reuse but reshape | Same schema, different knobs: `quirks` and `extra_headers` in TOML | no | fireworks `reasoning_effort = false`, deepseek `block_allowlist`, kimi's passthrough set |

The dialect is chosen in TOML from three: `openai-responses`, `openai-chat`, `anthropic-passthrough`. An
`auth.kind` other than the registered ones is accepted at parse and behaves as `api-key`, but always write
`api-key` for a key.

Source: docs/superpowers/specs/2026-08-15-claude-head-design.md (lines 8-14);
core/src/main/kotlin/splice/core/topology/TopologySchema.kt (Dialect); core/src/main/kotlin/splice/core/topology/AuthKind.kt
(lines 1-6, 145-167); app/src/main/kotlin/splice/app/provider/*Arm.kt (one arm per dialect or auth family).
The spec's line 14 says the passthrough dialect "today hardcodes Kimi's shape". That is stale: the passthrough
profile is neutral by default now and Kimi's deformations are opt-in quirks
(integrations/dialects/anthropic/src/main/kotlin/splice/dialect/anthropic/PassthroughQuirks.kt, lines 64-77).

## 3. The decision tree for any backend

Answer three questions from the vendor's own docs, in this order.

**A. Which wire does it speak?** Look at the request path and the streaming events in its docs.

| the vendor documents | dialect | splice posts to | splice lists models at |
|---|---|---|---|
| `POST .../v1/messages`, events `message_start`, `content_block_delta`, `message_stop` | `anthropic-passthrough` | `<base_url>/v1/messages` (so `base_url` has no `/v1`) | `<base_url>/v1/models` |
| `POST .../chat/completions`, `data:` chunks with `choices[].delta`, ending `data: [DONE]` | `openai-chat` | `<base_url>/chat/completions` (so `base_url` ends in `/v1`) | `<base_url>/models` |
| `POST .../responses`, events `response.created`, `response.output_text.delta`, `response.completed` | `openai-responses` | `<base_url>/responses` | `<base_url>/models` |
| anything else: the model in the URL path, a query string the path needs, a binary stream frame, a different body | none | needs code (move 2) | |

`models_url` overrides where the list is read. `base_url` must not end with `/`: splice appends the path as is.
All three dialects send `"stream": true` and read server-sent events. The model id goes in the body only.

**B. Which auth?**

| the vendor wants | TOML can do it? | how |
|---|---|---|
| One static key as `Authorization: Bearer <key>` | yes | `auth = { kind = "api-key", env = "NAME" }`; the person stores the key |
| Bearer, plus static non-secret headers (version, workspace, user agent, beta flag) | yes | add `extra_headers = { ... }` |
| The key only in another header (`x-api-key`, `api-key`, ...) and Bearer refused | no, not safely | the header name is fixed at `Authorization`. A static header would put the key in `splice.toml` in plain text. That needs code: an auth header option |
| A short-lived token minted from other credentials | partly | store it as an api-key; the person refreshes it with `splice key set` before it expires. Automatic refresh is code |
| Request signing (AWS SigV4, any per-request HMAC) | no | code: a new AuthProvider |
| A browser or device login | no, unless it is ChatGPT, Grok, Kimi or Muse | code: a new OAuth kind |
| Claude Code's own Anthropic login, forwarded | yes | `auth = { kind = "client" }`, Anthropic upstream only |

Which auth kind each dialect accepts. A pairing outside this table refuses boot.

| auth.kind | dialects | other limit |
|---|---|---|
| `api-key` (and any unregistered kind) | all three | none |
| `chatgpt-oauth` | `openai-responses` | none |
| `grok-oauth` | `openai-responses`, `openai-chat` | none |
| `kimi-oauth` | `anthropic-passthrough` | provider key must be `kimi` |
| `muse-oauth` | `openai-responses`, `anthropic-passthrough` | provider key must be `muse` |
| `client` | `anthropic-passthrough` | `extra_headers` may not set `Authorization` or `x-api-key` |

**C. Does it reject anything Claude Code sends?** Answer with a probe (section 4), then reshape with quirks.

| the vendor's 400 says | quirk (dialect) |
|---|---|
| unknown field `reasoning_effort` or `reasoning` | `reasoning_effort = false` (openai-chat) |
| unknown `stream_options` | `stream_usage = false` (openai-chat) |
| unknown content block type, such as `redacted_thinking` | `block_allowlist = [...]`, the types it accepts (passthrough) |
| `cache_control` rejected | `strip_cache_control = true` (passthrough; drops prompt caching) |
| `temperature`, `top_p` or `top_k` rejected | `strip_sampling_params = true` (passthrough) |
| tool name too long | `tool_name_cap = 64` (passthrough, muse) |
| JSON Schema keyword rejected in a tool schema | `mfjs = true` (passthrough; rewrites schemas, changes tool semantics) |
| a version or beta header missing | `extra_headers = { anthropic-version = "2023-06-01" }` (passthrough, openai-chat) |

**The outcome.** TOML only, when the wire is one of the three and the auth is one static key sent as Bearer
(plus static headers). Code, when it needs signing, a new login flow, a key in a header other than
`Authorization`, a new path or body, or a stream frame that is not server-sent events.

Source: integrations/dialects/anthropic/src/main/kotlin/splice/dialect/anthropic/PassthroughProvider.kt (line 48,
lines 99-103); integrations/providers/openai/src/main/kotlin/splice/provider/openai/OpenAiChatProvider.kt (line 40);
integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesProvider.kt (line 53);
core/src/main/kotlin/splice/core/topology/Topology.kt (rosterUrl, lines 183-193; client header rule, lines 204-213);
core/src/main/kotlin/splice/core/auth/Credentials.kt (lines 19-22, the Bearer default);
core/src/main/kotlin/splice/core/topology/AuthKind.kt (lines 184-193); core/src/main/kotlin/splice/core/topology/QuirksConfig.kt;
app/src/main/kotlin/splice/app/provider/QuirksOverlay.kt; app/src/main/kotlin/splice/app/provider/ChatArm.kt (lines 120-138).
`extra_headers` on a generic `openai-responses` provider: no reader found outside the Muse arm, so treat it as
not sent there (unverified).

## 4. Find out what a new backend needs

1. Read the vendor's API docs for: the base URL, the request path, the auth header, the model ids, a model list
   endpoint, and the streaming format. Use the web search tools for the current page; never trust memory.
2. Ask the person to send one request by hand with their own key. You never hold the key: give them the command,
   and they export the key in their own terminal and run it. Pick the block for the wire from step A.

   ```bash
   # Anthropic Messages (anthropic-passthrough). BASE has no /v1.
   curl -sS -N "$BASE/v1/messages" -H "Authorization: Bearer $VENDOR_KEY" \
     -H "anthropic-version: 2023-06-01" -H "content-type: application/json" \
     -d '{"model":"MODEL","max_tokens":64,"stream":true,"messages":[{"role":"user","content":"Say ok."}],
          "tools":[{"name":"echo","description":"Echo text","input_schema":{"type":"object","properties":{"text":{"type":"string"}}}}]}'
   ```

   ```bash
   # OpenAI Chat Completions (openai-chat). BASE ends in /v1.
   curl -sS -N "$BASE/chat/completions" -H "Authorization: Bearer $VENDOR_KEY" -H "content-type: application/json" \
     -d '{"model":"MODEL","stream":true,"messages":[{"role":"user","content":"Say ok."}]}'
   ```

   ```bash
   # OpenAI Responses (openai-responses).
   curl -sS -N "$BASE/responses" -H "Authorization: Bearer $VENDOR_KEY" -H "content-type: application/json" \
     -d '{"model":"MODEL","stream":true,"input":"Say ok."}'
   ```

3. Read the answer with the person. A 200 with the event names from step A means the wire fits. A 401 or 403
   means the key or the header is wrong: ask whether the docs name another header. A 404 means the path or the
   base URL is wrong. A 400 names a field: map it with the table in step C. A binary or non-`data:` stream means
   the wire does not fit.
4. Ask for the model list the same way (`GET $BASE/v1/models` or `GET $BASE/models`). No list is fine: you
   declare the rows yourself.
5. Claude Code sends tools on every turn, so a model that cannot call tools is no use as a head.

Source: README.md, "Local models" (lines 718-720, "Claude Code needs tools on every turn");
integrations/dialects/anthropic/src/main/kotlin/splice/dialect/anthropic/PassthroughEventRouter.kt (lines 31-49);
features/models/src/main/kotlin/splice/models/list/ModelsProbe.kt (lines 112-122).

## 5. How every recipe ends

Each recipe below ends with these checks. Steps 3 to 5 need the person's yes.

1. `splice check-config; echo "exit $?"`: no output and exit 0. Exit 3 lists every finding; fix them all.
2. `splice doctor`: the configuration section passes. `splice doctor --live` calls local runtimes only, never a
   cloud head.
3. The key: the person runs `splice key set <ENV name from auth.env>`, a masked prompt. A stored key is used by
   the next request, with no restart. A new head's command, `<command> login`, does the same.
4. `splice restart`. Say first that it cuts every turn in flight on the machine, yours included, and that
   sessions retry on their own. A new provider or head is read only at boot.
5. `splice install <command>` links the new command.
6. `splice status` shows the head and its port. `splice logs --tail 100` shows a line
   `[<head>] models: ...` saying what the endpoint listed, or why it listed nothing.
7. The smoke, one paid turn through the head's port, run by the person:

   ```bash
   <command> -p "Reply with the single word ok."
   ```

Source: app/src/main/kotlin/splice/app/cli/ConfigPreflight.kt; features/diagnostics/src/main/kotlin/splice/diagnostics/doctor/DoctorCommand.kt
(lines 64-67); app/src/main/kotlin/splice/app/cli/auth/KeyCommand.kt; README.md (lines 190-193, keys.toml read on
every request); app/src/main/kotlin/splice/app/provider/ModelRosters.kt (lines 102-140, the models log line).
Details of each verb: `cli.md`.

## 6. Recipes

### 6.1 A second head on a provider already in the file (move 1)

Use it for a second account or key, another default model, or other knobs on the same vendor.

1. Read `../splice.toml` (the person gets a prompt). Pick a head key, a port no head and not `[daemon] control_port` (3096 by default) uses, a
   new `discovery_prefix` and a new command.
2. Add the head. For a second key on an api-key vendor, give it its own provider with its own `env`, because the
   key belongs to the provider's `auth`.

   ```toml
   [heads.openrouter-cheap]
   provider = "openrouter"
   port = 3110
   discovery_prefix = "claude-orcheap--"
   pinned_model = "deepseek/deepseek-v4.1-flash"
   model_slots = { opus = "deepseek/deepseek-v4.1-flash" }
   [heads.openrouter-cheap.claude]
   command = "claude-orcheap"
   ```

3. `splice add <profile> --name <head> --key provider|new --command <cmd>` writes the same thing and checks it.
   It is the person's to run.
4. Verify with section 5.

Source: app/src/main/resources/splice.example.toml (lines 491-501, 571-632); README.md, "A second head on the
same provider"; features/configuration/src/main/kotlin/splice/configuration/add/AddArgs.kt (usage line).

### 6.2 An OpenAI-compatible vendor with an API key (move 1, maybe 3)

1. Confirm the wire with section 4: `POST <base>/chat/completions` streaming `choices[].delta`.
2. Fastest path, the person runs: `splice add api-key --name <vendor> --base-url <base> --model <id>:<window>`.
3. By hand, the example file's Fireworks block. A strict validator that 400s on unknown fields takes
   `reasoning_effort = false`.

   ```toml
   [providers.fireworks]
   dialect = "openai-chat"
   base_url = "https://api.fireworks.ai/inference/v1"
   auth = { kind = "api-key", env = "FIREWORKS_API_KEY" }
   quirks = { reasoning_effort = false }
   [[providers.fireworks.models]]
   id = "accounts/fireworks/models/llama-v3p1-70b-instruct"
   label = "Llama 3.1 70B (Fireworks)"
   context_window = 131072

   [heads.fireworks]
   provider = "fireworks"
   port = 3103
   discovery_prefix = "claude-fireworks--"
   pinned_model = "accounts/fireworks/models/llama-v3p1-70b-instruct"
   models = [{ id = "accounts/fireworks/models/llama-v3p1-70b-instruct", slot = "opus" }]
   context_window = 131072
   [heads.fireworks.claude]
   command = "claudefw"
   ```

4. Usage frames are off by default on this dialect for a hosted vendor. If the vendor accepts
   `stream_options.include_usage`, set `stream_usage = true`, or the head reports zero tokens and Claude Code
   never auto-compacts.
5. Verify with section 5.

Source: app/src/main/resources/splice.example.toml (lines 255-260, 369-381, 620-630); QuirksConfig.kt (lines 85-97).

### 6.3 An Anthropic-compatible vendor with an API key (move 1, maybe 3)

1. Confirm the wire with section 4: `POST <base>/v1/messages` streaming `message_start`. The key goes as
   `Authorization: Bearer`.
2. `splice add api-key` writes `openai-chat` only, so write this one by hand. The example file's DeepSeek block:

   ```toml
   [providers.deepseek]
   dialect = "anthropic-passthrough"
   base_url = "https://api.deepseek.com/anthropic"
   auth = { kind = "api-key", env = "DEEPSEEK_API_KEY" }
   models_url = "https://api.deepseek.com/models"
   [providers.deepseek.quirks]
   block_allowlist = ["text", "tool_reference", "image", "document", "server_tool_use", "tool_use", "tool_result", "web_search_tool_result", "thinking"]
   strip_cache_control = true
   [[providers.deepseek.models]]
   id = "deepseek-flash"
   label = "DeepSeek V4.1 Flash"
   context_window = 1000000

   [heads.claude-deepseek]
   provider = "deepseek"
   port = 3107
   discovery_prefix = "claude-deepseek--"
   pinned_model = "deepseek-flash"
   models = [{ id = "deepseek-flash", slot = "opus" }]
   [heads.claude-deepseek.claude]
   command = "claude-deepseek"
   ```

3. What a generic api-key passthrough head does by default: it forwards the body faithfully, keeps
   `cache_control`, does not rewrite schemas, drops no block type, and synthesizes no thinking signature. It
   does not forward Claude Code's own headers (`anthropic-version`, `anthropic-beta`, user agent), and sends no
   `anthropic-version` of its own. A vendor that needs one gets it in `extra_headers`.
4. Add quirks only for what a probe proved the vendor refuses (step C). `reanchor_prefill = true` only after a
   probe shows the vendor continues from a trailing assistant message.
5. Verify with section 5.

Source: app/src/main/resources/splice.example.toml (lines 674-730, 399-427);
app/src/main/kotlin/splice/app/provider/PassthroughArm.kt (lines 50-62, the neutral profile);
features/turns/src/main/kotlin/splice/head/turn/TurnPreparation.kt (lines 234-241, client headers forwarded only on
`client` heads); features/configuration/src/main/kotlin/splice/configuration/add/AddProfileCatalog.kt (lines 509-524).

### 6.4 A local runtime: Ollama, LM Studio, vLLM, llama-server

1. The person runs `splice add local`, or you copy the commented `[providers.ollama]` and `[heads.ollama]` blocks
   in the example file. The dialect is `openai-chat`, the base URL is on loopback, and any `env` name works.
2. Row ids are spelled exactly as the runtime lists them. A row the runtime does not list, a window above what it
   serves, or a model it says cannot call tools refuses boot while the runtime is up.
3. Verify with section 5. Here `splice doctor --live` does call the runtime: one streamed tool call per row.

Source: app/src/main/resources/splice.example.toml (lines 242-282); README.md (lines 718-733).

### 6.5 AWS Bedrock, Claude through the Messages endpoint (move 1 and 3)

(unverified against a live Bedrock endpoint)

What makes it TOML-only. Bedrock serves Claude on the Anthropic Messages wire at `/anthropic/v1/messages`, with
the same body as Anthropic's API and standard server-sent events. A Bedrock API key is a bearer token. The
passthrough dialect posts to `<base_url>/v1/messages`, sends an api-key as `Authorization: Bearer <key>`, puts
the model id in the body unchanged (dots and colons survive; only a trailing `[1m]`-style tag is stripped), and
keeps `cache_control`. Anthropic's own Bedrock Mantle client sends the API key exactly that way.

1. The person picks the endpoint and region, and creates a Bedrock API key in the AWS console. The principal
   needs `bedrock-mantle:CallWithBearerToken` (Mantle) or `bedrock:CallWithBearerToken` (Runtime). A short-term
   key lasts at most 12 hours. A long-term key lasts as long as the person chose.

   | endpoint | base_url | model ids | Bearer on this route |
   |---|---|---|---|
   | Mantle (recommended here) | `https://bedrock-mantle.<region>.api.aws/anthropic` | `anthropic.claude-opus-5-5`, `anthropic.claude-sonnet-5`, `anthropic.claude-haiku-4-5`, `anthropic.claude-fable-5-1` | sent by Anthropic's Mantle SDK client |
   | Runtime | `https://bedrock-runtime.<region>.amazonaws.com/anthropic` | an inference profile, such as `us.anthropic.claude-sonnet-5` or `global.anthropic.claude-sonnet-5` | AWS's examples send the key as `x-api-key`; Bearer here is unverified |

2. The person runs the section 4 Anthropic probe with `BASE=https://bedrock-mantle.us-east-1.api.aws/anthropic`,
   their key in `VENDOR_KEY` and `MODEL=anthropic.claude-sonnet-5`. A 200 with `message_start` proves the wire,
   the header and the model id. Stop here on a 401 or 403 and check the key's permissions.
3. Add the provider and the head. Pick a free port. Replace the region. Windows are Anthropic's published ones;
   confirm Bedrock serves the same before you rely on 1M.

   ```toml
   [providers.bedrock]
   dialect = "anthropic-passthrough"
   base_url = "https://bedrock-mantle.us-east-1.api.aws/anthropic"
   auth = { kind = "api-key", env = "BEDROCK_API_KEY" }
   # Mantle requires this header, and an api-key head forwards none of Claude Code's own headers.
   extra_headers = { anthropic-version = "2023-06-01" }
   # Bedrock serves no model list on this API: only the rows below join the picker.
   discovery = { exclude = ["*"] }
   [[providers.bedrock.models]]
   id = "anthropic.claude-opus-5-5"
   label = "Claude Opus 5.5 (Bedrock)"
   context_window = 1000000
   client_model = "claude-opus-5-5"
   [[providers.bedrock.models]]
   id = "anthropic.claude-sonnet-5"
   label = "Claude Sonnet 5 (Bedrock)"
   context_window = 1000000
   client_model = "claude-sonnet-5"
   [[providers.bedrock.models]]
   id = "anthropic.claude-haiku-4-5"
   label = "Claude Haiku 4.5 (Bedrock)"
   context_window = 200000
   client_model = "claude-haiku-4-5"

   [heads.claude-bedrock]
   provider = "bedrock"
   port = 3108
   discovery_prefix = "claude-bedrock--"
   pinned_model = "anthropic.claude-opus-5-5"
   models = [{ id = "anthropic.claude-opus-5-5", slot = "opus" }, { id = "anthropic.claude-sonnet-5", slot = "sonnet" }, { id = "anthropic.claude-haiku-4-5", slot = "haiku" }]
   [heads.claude-bedrock.claude]
   command = "claude-bedrock"
   ```

4. Why each line:
   - `client_model` makes Claude Code treat the row as the Claude model it knows (capabilities, window), through
     the head's `modelOverrides`, while the wire still sends the Bedrock id. It must start with `claude-`, one
     row per name.
   - `discovery = { exclude = ["*"] }` admits nothing from discovery, so a head model no row declares refuses
     instead of waiting for a list. The daemon still asks `<base_url>/v1/models` at start and hourly, and logs
     once that no list came.
   - No quirks: Bedrock is real Claude, so caching, full JSON Schema and native thinking blocks should ride
     untouched, which is the neutral profile.
   - A Workspace is a static header: add `anthropic-workspace-id = "<project id>"` to `extra_headers`.
   - Rates: splice ships no Bedrock price card, so turns read "no rate card" until the person adds
     `rates = { input = ..., cache_read = ..., output = ... }` to each row from AWS's price page. Never guess them.
5. The key: the person runs `splice key set BEDROCK_API_KEY`. When a short-term key expires, turns get 401
   until they run it again; no restart is needed. A script of their own may refresh it on a timer with
   `aws-bedrock-token-generator ... | splice key set BEDROCK_API_KEY --stdin` (unverified; the person writes it).
6. Do not put the key in `extra_headers` (`x-api-key = "..."`) or `Authorization` there. It would sit in
   `splice.toml` in plain text.
7. Verify with section 5. `splice models bedrock` reports no list; that is expected.

Known limits, each a hypothesis until a live session shows it:

- A session resumed onto this head from a non-Claude head can carry thinking blocks with an empty signature.
  Real Claude refuses a whole request for one ("Invalid signature in thinking block"). Only the `client` head
  drops them; no TOML quirk turns that on. Start Bedrock work in a fresh session.
- Claude Code's `anthropic-beta` header is not forwarded. If a turn 400s naming a beta, add that beta as a
  static `anthropic-beta` entry in `extra_headers`.
- Bedrock's Mantle Messages API refuses structured outputs (`output_config.format`) and server-side tools such
  as web search. A Claude Code feature that sends them fails on this head.
- `auth = { kind = "client" }` is not a shortcut: it forwards Claude Code's Anthropic login, not a Bedrock key.

What needs code, and why:

| Bedrock path | why TOML cannot | the move |
|---|---|---|
| IAM credentials, SSO, assumed roles (SigV4) | each request must be signed over its body and time; splice has no signer | 2: a SigV4 AuthProvider |
| Automatic short-term key refresh inside splice | api-key has no refresh flow | 2: a new auth kind |
| `InvokeModel`, `InvokeModelWithResponseStream`, `Converse` | the model is in the URL path, `anthropic_version` is in the body, and the stream is `application/vnd.amazon.eventstream` binary frames; the path is fixed at `/v1/messages` and the reader keeps only `data:` lines | 2: a new dialect |
| Dropping unverifiable thinking on an api-key passthrough head | the switch exists only for the `client` profile | a new quirk |

Source: app/src/main/kotlin/splice/app/provider/PassthroughArm.kt; PassthroughProvider.kt (lines 48, 99-103);
integrations/dialects/anthropic/src/main/kotlin/splice/dialect/anthropic/PassthroughQuirks.kt (lines 56-61);
PassthroughMessageScrubber.kt (lines 154-184); core/src/main/kotlin/splice/core/model/ModelCatalog.kt (lines 371-392,
client_model); features/models/src/main/kotlin/splice/models/discovery/ModelDiscovery.kt (line 46, only a local
provider is never asked); integrations/upstream/src/main/kotlin/splice/upstream/sse/SseReader.kt (lines 25-27).
AWS, read 2026-10-15: docs.aws.amazon.com/bedrock/latest/userguide/inference-messages-api.html (endpoints, base
URLs, `anthropic-version` header, model ids), .../api-keys.html and .../api-keys-use.html (Bearer, the
CallWithBearerToken permissions), .../api-keys-generate.html (12-hour short-term keys), .../workspaces.html
(`anthropic-workspace-id`), .../endpoints.html (Mantle refuses `output_config.format`).
Anthropic, read 2026-10-15: platform.claude.com/docs/en/build-with-claude/claude-in-amazon-bedrock (model ids,
standard SSE, no Models endpoint, no server-side tools); @anthropic-ai/bedrock-sdk 0.34.2 `src/mantle-client.ts`
(API-key mode sends `Authorization: Bearer`).
The OpenAI-compatible APIs on Bedrock (`https://bedrock-mantle.<region>.api.aws/v1`) would follow recipe 6.2
(unverified).

## 7. When the new head fails

| symptom | likely cause | fix |
|---|---|---|
| `check-config`: `port N is shared by A, B` | two heads, or a head and the control plane, on one port | give each its own port |
| `check-config`: `the provider this head names is not declared` | `provider =` typo | name a `[providers.<key>]` that exists |
| `check-config`: `incompatible auth kind` | auth kind and dialect outside the table in section 3 | change one of them |
| `check-config`: `is not a splice.toml setting` | a misspelled field | fix or remove that line |
| daemon log: `pinned_model is required` or `is not in the head model list` | a roster problem; boot goes on but that head does not start | add the pin to `models`, or fix the id |
| 401 or 403 on every turn | key missing, expired or wrong header | the person runs `splice key set`; recheck section 4 |
| 404 on every turn | `base_url` wrong: `/v1` twice, a trailing `/`, or the wrong host | compare with the probe URL |
| 400 naming a field or block | the vendor refuses part of Claude Code's request | step C quirks |
| the turn fails with no events | the stream is not server-sent events | the wire does not fit; needs code |
| zero tokens, never auto-compacts | no usage frames on openai-chat | `stream_usage = true` |

Retry and error behaviour, and the troubleshooting playbook: `reliability-mcp-install.md`.

Source: core/src/main/kotlin/splice/core/topology/TopologyWriter.kt (lines 345-366); TopologyLoader.kt (line 112);
core/src/main/kotlin/splice/core/topology/HeadModels.kt (lines 35-37, 66-69, 81-92);
integrations/topology/src/main/kotlin/splice/topology/ConfigFindings.kt (the boot pass runs with `rosters = false`).
