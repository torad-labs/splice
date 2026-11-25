# Server mode: one splice is a team's front door to a shared model

- Status: planned 2026-11-15 from issue #604. P1 is being built feature by feature, each against frozen acceptance
  checks in `.campaign/checks/server/`. P2 and P3 get their checks when P1 has landed, because they build on its
  shapes.
- Overturns: the single-user boundary in `docs/specs/v0.4.0.md` (the "Preserve the single-user, loopback product
  boundary" item), which `docs/specs/lan-share.md` already moved for one person's own machines. Server mode serves
  many people, each known by a key. Share mode stays what it is: one person, a private network, no identity.
  The README's "Single-user by design" note changes when the first server feature ships.

## The goal

A small team shares one inference server (for example `llama-server` on a GPU box) through one splice. Each
laptop runs splice, links the team's commands with `splice remote add`, and signs in once. The team's own gateway,
reverse-proxy config and laptop CLI go away: splice is the front door.

The people it serves, and how each can tell it works:

- **A team member** runs `splice login https://ai.team.example`, signs in with the company's Google account in the
  browser, and their `claude-team` command works. Nothing on the laptop holds a long-lived secret in plain
  environment variables, and a second laptop signing in does not log the first one out.
- **The operator** issues keys for scripts, revokes a person on their last day, caps how much of the box one person
  can hold, sets budgets per person and team, and answers "who used what and what did it cost" by person, team,
  day, model or device, as JSON, CSV or a table, and in their warehouse.
- **Whoever runs the box** sees every person's traffic live in Prometheus or their OTLP collector: requests by
  status class, tokens by kind, time to first token, prefill and decode speed, stream stalls, and what each person
  has in flight against their cap.
- **Everyone** gets honest outcomes: a turn the upstream broke is a 502 in the ledger, never a 200; a person who
  closed the laptop is a 499, apart from errors; a long prefill keeps its connection alive instead of timing out
  behind a proxy.

## What the market does, and where splice goes past it

The gateways teams reach for today (LiteLLM's proxy, Portkey, Bifrost, Kong's AI gateway) share a core: virtual
keys per person or team, budgets and rate limits, load balancing and fallbacks across upstreams, spend logs and
Prometheus metrics, and SSO on their admin side. splice takes that core and adds what only a gateway that is also
the laptop's client can do:

- **Sign-in that mints the key**, per device, rotated without breaking a running session, with the laptop getting
  everything it needs from the server (no client configuration to hand out).
- **A content-free ledger by construction**: no prompt or reply text is ever written, and a check proves it with a
  canary.
- **Self-hosted servers as first-class upstreams**: llama-server's `timings` give prefill and decode speed per
  request, keepalives cover minute-long prefills, and the pool is health-checked with per-person preference.
- **Claude Code on the laptop configured for the server**: windows and effort from the server's profile, a key
  helper instead of a key in the environment, and (P2) Claude Code's own telemetry relayed with the person stamped
  by the server, so no client can report as someone else.

## Decisions

1. **Server and share are exclusive.** `check-config` refuses a config with `[server]` and an enabled `[share]`.
   Share mode answers anyone on the LAN; server mode answers only keys. One daemon does one or the other.
2. **Only the commands listed in `[server] commands` are served**, and `check-config` refuses an unknown one.
3. **A head that signs with a consumer subscription login (a Claude or ChatGPT plan) is not served to member keys.**
   Those plans' terms are for one person. `check-config` refuses such a command in `[server] commands`, naming the
   reason. API-key and self-hosted providers are served. Whether to ever offer an opt-in is the founder's call and is
   not built.
4. **Keys are `<key_prefix><8 lowercase alphanumerics>_<32 to 128 alphanumerics>`**, with `key_prefix` `spk_` unless
   `[server] key_prefix` sets another. The server mints 40-character secrets and admits any of 32 to 128 characters, so
   an imported key verifies by the same rule its import was checked by. The first two parts are the visible prefix
   that names the key everywhere; only a hash of the whole key is stored. No file in the server's home, its log
   included, ever holds a key or its secret.
5. **A person is a lowercased email.** Teams come from the email's domain (`[server.teams] by_domain`) unless the
   operator sets one with `splice server person set <email> --team <team>`. Each ledger row keeps the team the person
   had when it ran.
6. **Limits refuse with 429 and `Retry-After`**, in Anthropic's error shape on the Messages route and OpenAI's on the
   OpenAI route. Claude Code honours `Retry-After`.
7. **The ledger lives in the state directory and survives restarts.** Durable storage beyond local files (SQLite,
   snapshots to object storage) is P3.

## P1: what it takes to retire a team's own gateway

Each feature is built against the named check file. The check is the contract: its header says what the feature
is for, and its assertions are the behaviour.

### F1 server-listener and server-keys (`listener.test.ts`, `keys.test.ts`)

```toml
[server]
listen = "0.0.0.0:3443"
commands = ["claude-team"]
tls_cert = "/etc/splice/tls/fullchain.pem"   # or terminate TLS at a proxy:
tls_key = "/etc/splice/tls/privkey.pem"
trusted_proxies = ["10.0.0.0/8"]            # X-Forwarded-For / -Proto honoured only from these
admins = ["ops@team.example"]
admin_cidrs = ["192.0.2.0/24"]              # optional: empty preserves unrestricted admin addresses
key_prefix = "spk_"                         # optional: only new keys use this mark
```

- Routes on the listener: `GET /health` (open), `GET /` and `GET /<command>/launch` (as share mode serves them),
  `ANY /<command>/v1/...` for turns, `/server/...` for the server's own API, and `/server/mod/<name>/...` for the
  modes splice.toml lets serve here ([Mode routes](#mode-routes)).
- A key arrives as `x-api-key` or `Authorization: Bearer`. A missing, unknown, revoked or expired key is a 401
  `authentication_error` that never echoes the key, and nothing reaches the upstream.
- CLI: `splice server key create --person <email> [--name <n>] [--expires <duration>] --json`,
  `server key list [--person <email>] --json`, `server key revoke <prefix>`,
  `server key revoke-person <email>` (offboarding; prints how many keys it revoked).
- Control plane (management key, loopback): `POST /api/server/keys`, `GET /api/server/keys`,
  `DELETE /api/server/keys/<prefix>`, `DELETE /api/server/people/<email>/keys`.
- Admin over the listener: `GET/POST /server/api/keys`, `DELETE /server/api/keys/<prefix>` and other
  admin routes, including metrics, require an admin key and, when `admin_cidrs` is set, a matching client IP.
  A trusted proxy must identify the client via `X-Forwarded-For`; a missing or invalid client address is denied.
  The loopback control plane continues to use the management key.
- To migrate a Splice-format key without sending the secret, an admin can
  `POST /server/api/keys/import` with `person`, the existing public `prefix`, and the complete key's
  SHA-256 hex `hash`. Copy any existing expiry or revocation into `expires_at` and `revoked_at` as
  ISO-8601 timestamps. The response never includes the hash or the key. Existing hashes or prefixes return
  409, including revoked ones. No external verifier is contacted. A `key_prefix` starts with a letter,
  ends in `_`, and is up to 24 ASCII letters, digits, hyphens or underscores. Changing it leaves
  existing keys usable until they expire or are revoked.

### F2 signin-server and signin-client (`signin.test.ts`, `signin-client.test.ts`)

```toml
[server.signin]
issuer = "https://accounts.google.com"
client_id = "…apps.googleusercontent.com"
client_secret_env = "SPLICE_SIGNIN_SECRET"
domains = ["team.example"]
hosted_domain = "team.example"   # optional: also require the verified ID token's hd claim
key_days = 30
rate_per_minute = 20
```

- `GET /server/signin` (open) gives the laptop everything it needs: issuer, client id, authorization endpoint,
  scopes, domains. Never the secret.
- `POST /server/signin/token {code, code_verifier, redirect_uri, nonce, device}` trades the code, verifies the ID
  token (JWKS signature, issuer, audience, expiry, nonce, a verified email in an allowed domain) and mints a key
  with source `signin`, the device, and an expiry of `key_days`. The redirect must be the client's HTTP
  loopback callback. If `hosted_domain` is set, the signed `hd` claim must also match; a matching email
  suffix by itself is not enough. Failed token trades mint nothing.
- Keys are per device. On the same device the old key works until the new key's first request, then stops; other
  devices are untouched. Sign-in is rate-limited per client address (429 with `Retry-After`).
- On the laptop: `splice login <server>` runs authorization code with PKCE through a loopback redirect and prints
  the URL it opened; `splice remote add <server>/<command>` links the command; the launch recipe gives Claude Code
  an `apiKeyHelper` that prints the current key, so no key sits in the session's environment. `splice logout
  <server>` forgets it.

### F3 person-limits and person-budgets (`limits.test.ts`, `budgets.test.ts`)

```toml
[server.limits]
inflight_per_person = 12
rpm_per_person = 0          # 0: no cap

[server.budgets]
person_daily_usd = 50
action = "block"            # or "warn"
```

- Over the in-flight cap a turn is refused at once with 429, `Retry-After` and a message naming the cap; a client
  that disconnects frees its slot. `splice server person set <email> --inflight N --rpm N --team T --upstream U`
  overrides per person.
- `splice server budget set --person|--team <name> --daily-usd N --monthly-usd N --action block|warn`, and
  `server budget list --json` with what each has spent today and this month and whether it is over. A blocked
  budget refuses before the upstream with a message naming the budget; warn serves and reads over. Spend survives
  a restart. A team budget holds every member. A turn's spend counts from the moment the last part of its answer is
  handed to its client's connection, so a client that asks again once it has the whole answer is held to what that
  answer cost.

### F4 upstream-pool, prefill-keepalive and openai-route (`pool.test.ts`, `keepalive.test.ts`, `openai-route.test.ts`)

```toml
[providers.box]
dialect = "openai-chat"
auth = { kind = "api-key", env = "BOX_KEY" }
health_seconds = 5

[[providers.box.upstreams]]
name = "gpu-a"
url = "http://10.0.0.21:8080/v1"

[[providers.box.upstreams]]
name = "gpu-b"
url = "http://10.0.0.22:8080/v1"

[server]
keepalive_seconds = 10
```

- Upstreams are health-checked on `/health`; turns go to the healthy upstream with the least in flight, or to the
  person's preferred one while it is healthy. A refused connection fails over at once, before the next health
  check. With none up, a turn is refused within seconds naming the upstreams. `GET /api/server/upstreams` lists
  each with its health and what it has in flight.
- A streamed turn gets its headers at once and an SSE keepalive at least every `keepalive_seconds` while the
  upstream is still prefilling; tokens are relayed as they come. A non-streamed turn answers one JSON body.
- `/<command>/v1/chat/completions` and `/<command>/v1/models` serve OpenAI-style clients with the same keys and
  limits. `stream_options.include_usage` is always asked of the upstream; the usage chunk reaches the client only
  when it asked.

What F4's reviewers found outside its items' words was built next as the feature `server-hardening`
(`messages-key.test.ts`, `openai-cut.test.ts`, `openai-reply.test.ts`, `openai-model.test.ts`,
`openai-keepalive.test.ts`):

- A header the provider's `forward_headers` names never carries one of the server's keys to the provider, on the
  Messages route as on the OpenAI route. The header stays behind, the turn is still answered, and the log names
  the header, never the key.
- On the OpenAI route, an answer the upstream cuts short is an error the client sees: a stream ends with one error
  event in OpenAI's shape and no `[DONE]`, and a JSON answer is a 502 with OpenAI's error body.
- The client gets the upstream's status, body, content type and, on a refusal, its `Retry-After`, and none of the
  provider's own response headers. Usage on a finish chunk reaches only a client that asked for it.
- `GET /<command>/v1/models/<id>` answers the provider's own object, or a 404 in OpenAI's error shape.
- A streamed chat completion is kept alive through a long prefill as a streamed turn is, however many wait at once.

### F5 ledger-rows, ledger-reports and ledger-export (`ledger.test.ts`, `reports.test.ts`, `export.test.ts`)

```toml
[server.reference]            # what the same tokens would cost on a hosted model
input_usd_per_mtok = 3.0
cached_usd_per_mtok = 0.3
output_usd_per_mtok = 15.0

[server.teams]
by_domain = { "team.example" = "core", "partner.example" = "partners" }

[server.export]
to = "s3://usage-bucket/gateway"    # or file:///var/lib/splice-usage
endpoint = "https://<account>.r2.cloudflarestorage.com"
region = "auto"
path_style = true
access_key_env = "USAGE_S3_KEY"
secret_key_env = "USAGE_S3_SECRET"
every = "1h"
```

- One row per request: when, person, team then, device (key prefix), command, model, route, upstream, status,
  outcome (`ok`, `upstream_error` 502, `client_gone` 499, `refused`, `cut_off`, `splice_error` 500), prompt,
  cached and completion tokens, time to first token, duration, prefill and decode rates (from llama-server's
  `timings`), cost from the model's rate card. Never prompt or reply text.
- `splice server usage rows [--since 1h | --from T --to T] [--person E] --json`.
- `splice server usage report --by person|team|current-team|day|model|device --since|--from/--to
  --format json|csv|md`, the same from `GET /api/server/usage` and, for admins, `GET /server/api/usage`. Each
  group carries requests, errors, client_gone, tokens, cost and reference cost.
- Export writes `<prefix>/splice-usage/YYYY/MM/DD/HH.jsonl` (UTC hour of each row) on `export --now` and every
  `every`, rewriting an hour's file whole so each row is in it once. S3 requests are SigV4-signed.
- splice's own failures (a full disk, a ledger it cannot write) are logged and answered 500 `splice_error`, never
  blamed on the upstream.

### F6 server-metrics (`metrics.test.ts`)

- `GET /server/metrics` for admins, Prometheus text: `splice_server_requests_total{person,team,command,
  status_class}`, `splice_server_tokens_total{person,team,kind}`, histograms `splice_server_ttft_seconds`,
  `_duration_seconds`, `_prefill_tokens_per_second`, `_decode_tokens_per_second`, `_stream_stall_seconds`
  (the longest wait for the upstream's next chunk after the first token), gauges `splice_server_inflight{person}`
  and `splice_server_inflight_limit{person}`.
- `[server] metrics_prefix = "team_gateway_"` replaces the literal `splice_server_` prefix on this scrape,
  including HELP, TYPE and histogram series. Include your own separator; a prefix starting with `__`, which
  Prometheus reserves, is refused. It does not rename the operator OTLP
  export or the gateway's identity-free metrics.
- Every person with a live key has their series at 0 from startup; a person with none has no series.
- The same series go through the operator's OTLP export (`[telemetry.operator]`). These carry person and team by
  design; the operator families described in `docs/observability/exporters.md` stay identity-free.

### Load, streaming and drain (#791, `ServerLoadTest`, `ServerStreamIdleTest`)

A team in front of a small fixed pool of model servers needs the pool's limits held for everyone together, a stream
that dies mid-answer to end, and a stop that lets turns finish. Each is a setting, off by default:

```toml
[server]
stream_idle_seconds = 0      # 0: a silent answer is waited on however long
drain_seconds = 0            # 0: the listener's one second of grace at stop; at most 45

[server.limits]
inflight_total = 0           # the whole server's turns in flight at once; 0: no cap
inflight_per_upstream = 0    # each server of a provider with upstreams; 0: no cap
busy_status = 429            # or 503
busy_retry_after = 5         # seconds
busy_message = ""            # blank: splice's own sentence naming the cap
```

- A turn past `inflight_total`, or on a provider whose healthy upstreams each hold `inflight_per_upstream`, is
  refused at once with `busy_status`, `Retry-After: busy_retry_after` and `busy_message` or a sentence naming the
  cap. These caps are asked after the person's token cap and before their turns in flight and turns a minute, so a
  turn they refuse spends none of its person's `rpm_per_person`. One slot holds both places, so a turn's end and its
  client's hang-up each give both back, once. A turn that runs to its end gives them back as the last part of its
  answer is handed to the client's connection, after its upstream has answered in full and before the client can have
  the whole answer, so a client that asks again only once it has its answer is never refused for the turn before. With `inflight_per_upstream` each admitted turn reserves a place in the pool at
  admission, counted against the servers' room together with every lease, so turns admitted before any of them leased
  never add up past the cap; the turn's first lease takes its place. No lease is ever given on a server that is
  full, and a lease that holds no reserved place never takes one that is reserved: a turn that finds no place (every
  server full, or the one with room gone down after admission) is sent nowhere and told every upstream holds its cap,
  as a retryable overload on a command's turn and a 503 on the OpenAI route. The pool places every turn on a server with room:
  a session whose own server is full goes to the least busy healthy one with room and keeps its home, and a person's
  preferred server that is full gives a new session's turn to the least busy with room.
- `drain_seconds`: from a stop's first moment every keyed request, a turn on any route (a command's, the OpenAI route,
  the team's door) or anything else a key asks, `/server/forward-auth` included, is a 503 with `Retry-After` in its
  client's shape, and `GET /health`
  answers 503 `{"status":"draining"}`; the stop waits up to that long (and at least the one second of grace) for every
  answer the listener has begun to be written to its client's connection, then closes the listener. That is every
  answer, not only a turn's: a slow download, a mode's long poll on `/server/mod/` and the drain's own 503s hold the
  stop too, until their last bytes are on the socket or their connection closes, and whatever is still unwritten when
  the time is up is cut. The drain is spent before the heads' own stop budget, which shrinks by what it took, so the daemon's whole
  stop stays inside its cooperative cap; hence the 45-second ceiling, a boot finding past it.
- `stream_idle_seconds`: a streamed answer silent that long after its last word is hung up on at the upstream, and the
  stream ends with an error event (504 in the ledger), on the Messages route and the OpenAI route alike, never with an
  ending. The relay passes a stream on event by event, so that error always follows a whole event; and a stream whose
  connection closes before its last event's blank line ends with the relay's error event too, never half an event. On the team's door
  the turn runs on this machine with no relay in front of it, and its own watchdog keeps the same deadline as the one
  tier no live path holds off, counted from the model's last progress so a keepalive never renews it (504 in the
  ledger when it ends the turn): the silent round is hung up at the upstream, resumed where the provider can continue and
  otherwise ended with the turn's error event. The wait for the first word is a prefill and is not timed
  (`keepalive_seconds` covers it); a keepalive comment, a `ping` event, splice's own wait notice and an OpenAI-style
  chunk with no text, reasoning or tool call in it (the role-only first chunk) are no word. Off by default: idle is a probe, never an error,
  and only the operator knows how long their models think in silence.

### External hourly costs

Provider `hourly_usd` still follows the splice host's uptime. Add a named `[server.cost_meters.<name>]` table only
for a separate resource, named apart from every provider key (the report rows both by name). Each table
requires a nonnegative USD hourly rate and exactly one of these signals:

```toml
[server.cost_meters.gpu]
hourly_usd = 3.25
file = "/run/example/gpu-running" # trimmed text "true" (at most 64 bytes)

[server.cost_meters.storage]
hourly_usd = 0.6
url = "http://127.0.0.1:8081/running" # GET returning 2xx; body ignored

[server.cost_meters.worker]
hourly_usd = 1.25
argv = ["/usr/local/libexec/worker-running", "worker-1"] # exit 0
poll_seconds = 30 # default: 60
```

Relative files resolve beside `splice.toml`; replace signal files atomically. URLs must be HTTP(S) with no embedded
credentials and are not followed through redirects. The daemon starts argv directly, with no implicit shell,
in the config directory with an empty environment and closed stdin. Use trusted, foreground probes only. A URL has
five seconds to send its response headers; a command has five seconds to exit. Settings take effect on daemon restart.

A report bills only the elapsed time between consecutive affirmative polls no more than one interval plus five
seconds apart. A missing, malformed, negative, or failed probe ends that run at the last affirmative sample.
There is no backfill before the first sample, during daemon downtime, or after the last confirmed sample. Each
confirmed interval retains its original rate in `<state>/server/cost-meters.jsonl`, including across restarts,
rate changes or removal of the meter. No probes run with the table absent.

Read separate meter lines with `splice server usage report --format json`: each is in `fixed` with the meter name
in `provider` and `source: "external"`. Their costs contribute to `fixed_usd` and `actual_usd`, not the token
charges or per-person budgets. The history is in `[server.backup]` snapshots, not the per-turn usage export.

### External upstream state (`[server.state]`)

The box behind the server often knows before splice does whether it can take a turn: a model still loading, a GPU
rented only for set hours, a maintenance window. `[server.state]` lets the operator's own automation say so, and
the server believes it over its own health checks: a member can answer `/health` while the state says off, and it
takes no turns.

```toml
[server.state]
file = "/run/box/state"        # exactly one source: file, url or command
# url = "http://box.lan:8080/state"
# command = ["/usr/local/bin/box-state"]
every_seconds = 5              # how often the source is read again
message_starting = "Upstream is starting; retry shortly."
message_off = "Upstream is off until {until}."
retry_after_starting = 30
```

- The source is read every `every_seconds`: a file's contents, a `GET` of `url`, or the standard output of
  `command`, run as its argv stands with no shell. A relative `file` is read beside `splice.toml`. A table that
  names no source, or more than one, refuses boot beside every other finding; no table reads no signal.
- What the source says is one of two forms. Plain text is a single line: `up`, `starting`, `off`, or
  `off until <RFC3339>`, as in `off until 2026-11-24T17:00:00Z`. JSON names the same states with room for more:
  `{"state": "up" | "starting" | "off", "until": "<RFC3339>", "message": "<words for the refused turn>"}` where
  `until` and `message` are each optional and `message`, when present, is the refusal's words in place of the
  configured ones.
- `up` serves as before. `starting` and `off` refuse every turn at the door with a 503 before anything reaches the
  upstream, in the route's own error shape, carrying `message_starting` or `message_off` (`{until}` is replaced by
  the `until` the source named). The refusal's `Retry-After` is `retry_after_starting` while starting; while off
  until a future `until` it is the seconds remaining until then, never less than one; while off with an `until`
  absent or already past it falls back to `retry_after_starting`. Only a new `up` signal resumes service; an
  elapsed `until` is not an `up` signal.
- Before the first valid reading the signal is assumed up. If a read fails or returns invalid output, the server
  keeps the last valid signal and tries again at the next poll. The API reports the latest check time and error.
- The signal is the server's, not a pool's: it holds for a pooled provider and for a provider on a single
  `base_url` alike, on the Messages, door and OpenAI routes. When configured, `GET /api/server/upstreams` has a
  top-level `state` object with `state`, `until`, `message`, `last_error`, and `last_checked`, including on a
  server with no pooled provider at all.

### The readiness probe (`[server.probe]`)

`/health` proves a model server's process answers; it does not prove the model behind it can still complete a
request, which is what a pool member is believed for. `[server.probe]` chooses the question every pooled upstream
is asked, and each provider on one `base_url` that its `single_url` names:

```toml
[server.probe]
kind = "completion"            # "health" (the default) or "completion"
model = "team-model"           # required for a completion probe
timeout_seconds = 30
every_seconds = 60
at_start = true                # hold each member out until its first probe passes
single_url = ["solo"]          # providers on one base_url the probe asks too (#791)
```

- `kind = "health"` keeps today's check: `GET /health` beside the member's API root, a 2xx is up, asked at its
  provider's `health_seconds` as before.
- `kind = "completion"` asks the member to really complete a minimal request for `model` every `every_seconds`,
  and counts it up only on a well-formed completion answer within `timeout_seconds`. It covers the
  chat-completions dialect, the only one pools and `single_url` allow, so a member whose model is wedged behind a
  healthy process stops taking turns until a completion succeeds again.
- `at_start` gates a completion probe's members at start. It defaults to `true`: every member takes no turn until
  its first completion passes, so a just-booted daemon refuses turns on a pool none of whose members has proved its
  model yet. Set it `false` to serve each member at once, as the health probe always does, and take it out only when
  a probe fails. A member held out says why in the log at its first failed probe. The health probe ignores it.
- A completion probe carries what a turn carries: the provider's configured headers and its login (an API key, a
  credential command's token or an OAuth bearer, read as a turn reads it). Its body is the dialect's minimal
  non-streamed request: one user message and one output token, in the field the provider's quirks name. A member is
  up only on a 2xx whose body is a completion's envelope (for chat completions, a JSON object with a `choices`
  array), never on the text inside it: one output token can vanish into a reasoning model's thinking. Any other
  answer, or none within `timeout_seconds`, keeps it down, and the log line names the status and shape it saw.
- `single_url` gives the probe to providers that are one server (#791). Each entry names an `openai-chat` provider
  on one `base_url`; boot refuses an entry that names no provider, a provider that lists upstreams (the probe asks
  those already), another dialect, or the same provider twice. A named provider becomes a pool of that one server,
  listed in `GET /api/server/upstreams` under its provider's key, and is asked exactly what a pool member is asked,
  `kind` and `at_start` included (under the health probe that is `/health` beside its `base_url`, so name there only
  a provider whose server answers it). A turn for it is never sent before its probe passes: while it is down, every turn
  route answers the 503 a pool none of whose members is up answers, naming the provider. It is then one server of
  a pool in every other way too: `inflight_per_upstream` caps it, and a connection it refuses takes it out until a
  check passes. A provider `single_url` leaves out serves its turns from its `base_url` as before.
- A turn refused because no server of its provider is up carries `Retry-After`: the seconds between two checks of
  that provider's servers, `every_seconds` for a completion probe and the provider's `health_seconds` for the
  health probe, the soonest a check could bring one back (#791). Pools took the same 503 without it before.

### External mode JARs

Declare each JAR at the top level of `splice.toml`. This works whether or not the team listener is configured.

```toml
[mods]
dir = "mods"                         # optional; default: mods/ beside splice.toml

[[mods.jar]]
name = "inventory-release"
path = "inventory.jar"
sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

[[mods.jar]]
name = "reports-release"
url = "https://downloads.example.org/splice/reports-1.2.jar"
sha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
```

Use a unique `name`, exactly one of `path` or `url`, and the trusted SHA-256 of the exact JAR (64 hex
digits, either case). Relative paths are confined to `dir`, which is itself relative to the config directory
unless absolute. An absolute JAR path outside `dir` uses its own parent as the directory boundary.
Directories cannot be world-writable, a mod directory cannot itself be a symlink, and a JAR symlink cannot
leave its boundary. Only archives listed explicitly are loaded.

Use the [mode packaging contract](../../modes/README.md#how-splice-finds-a-mode) for the service descriptor,
mode index, and manifest. The configured `name` identifies the archive, not the modes it contributes.
Use each manifest's mode name in `[modes.<name>]`. No discovered mode, server, or offered tool name can
overlap another mode's claim. Compile against splice's mode API and the libraries its types use, such as
Kotlin serialization JSON. The daemon's classpath always wins; bundle only dependencies it doesn't already
provide, or relocate private dependencies that need a different version. Don't bundle entries under
`splice/core/` or `splice/modes/api/`, or reference other JARs in a manifest `Class-Path`.
A JAR can be at most 32 MiB on disk and 64 MiB expanded.

URL entries use a direct HTTP(S) URL without embedded credentials or redirects. The entire download, including
the response body, has a ten-second deadline. The daemon downloads into memory,
checks the pin and the catalog, then writes `<state>/mod-<sha256>.jar` atomically with mode 0600. A matching
cache works without network access. A bad cache is a finding, not permission to replace it; remove the bad cache
to fetch the same pin again. To upgrade, set the new trusted pin (and URL, if it changed), then restart.
Remove the entry and restart to stop loading it; unneeded cached versions can be removed separately.

`splice check-config` verifies pins and discovers modes, even for a cold URL, without writing a cache.
`splice restart` runs the same checks before stopping the existing daemon. On successful boot splice logs the
loaded paths and verified pins. Management route ownership is checked when the daemon mounts its control router.
Only configure JARs you trust with everything the daemon can access. Pinning verifies the chosen bytes;
the class loader is not a sandbox, and configuration verification instantiates the registered providers too.

## P2: the laptop side

Drafted checks for these exist and are committed with the P2 plan once P1 has landed.

- **F7 server profile.** `GET /server/profile` for a key holder: each command with its models and their context
  windows, the sign-in configuration, the telemetry relay and the minimum client. `splice login <server>
  --key-stdin` keeps an operator-issued key the way a sign-in keeps its own (never in a config file).
  `splice remote add <server>` links every command the server offers, and `splice remote refresh <server>` links
  the ones it starts offering and unlinks the ones it drops. Each launch already takes the server's window from its
  recipe; `splice remote set <command> --window N|server` pins one on the laptop or gives it back.
- **F8 telemetry relay.** `[server.telemetry] forward_to` names the operator's collector.
  `POST /server/otlp/v1/{metrics,logs,traces}` takes Claude Code's own OTLP (JSON or protobuf) with the person's
  key, sets `splice.person`, `splice.team` and `user.email` on every resource whatever the client claimed, and
  forwards it in the same encoding. Override the three names independently with `person`, `team` and `email`
  under `[server.telemetry.identity_attributes]`. Keys must be distinct and nonblank. Client claims under either
  the configured names or the defaults are removed; a caller without a team gets no team attribute.
  The laptop recipe sets `CLAUDE_CODE_ENABLE_TELEMETRY=1` and
  `OTEL_EXPORTER_OTLP_ENDPOINT=<server>/server/otlp` with an `otelHeadersHelper` that sends the key, and never sets
  `OTEL_LOG_USER_PROMPTS`. Where any of the organisation's managed settings sets a telemetry key, splice adds no
  telemetry of its own; managed settings that set none leave the relay on. splice reads the file sources (#791): the
  platform's `managed-settings.json` (`/etc/claude-code`, `/Library/Application Support/ClaudeCode`,
  `C:\Program Files\ClaudeCode`) with each `managed-settings.d/*.json` beside it merged over it in filename order, as
  Claude Code merges them (a later file's value replaces an earlier one's, `env` merges per variable, and a key a later
  file sets to `null` is cleared), and the command's `remote-settings.json`. A malformed or unreadable file sets
  nothing. A macOS configuration profile (the `com.anthropic.claudecode` managed preferences domain) and a Windows
  registry policy (`HKLM` or `HKCU\SOFTWARE\Policies\ClaudeCode`) are not read: an organisation that sets its
  telemetry only there gets splice's relay beside it unless the same keys are also in a managed settings file.
- **F9 client version policy.** `[server.client] minimum`. Each launch sends `x-splice-client: <version>` in
  `ANTHROPIC_CUSTOM_HEADERS`. An older client's turn is answered 200 with an assistant message naming the version
  to upgrade to, streamed or not, and nothing reaches the upstream (Claude Code retries a refusal about six times
  and shows a 200 once). A request with no version (a script) is served. The minimum holds on a Messages turn, a
  door turn and a chat completion alike. From an outside source (#791): `version_header` names the header a team's
  own launcher sends its version in (`name/1.2.3` reads as `1.2.3`); `checkin = true` mounts
  `POST /server/client/checkin` `{"version"}` (at most 4 KiB, else 413) under a person's key, whose latest report,
  kept in memory, stands for their turns that name none; `outdated_status` is 200 (in the conversation, an OpenAI
  answer on the chat route), 400 or 426; `outdated_message` words the notice with `{version}` and `{minimum}`, in any
  language: the listener hands its head the notice as RFC 8187 `UTF-8''` percent-encoded text, which the head
  decodes. A version is read as at most 128 characters of dotted numbers, each of at most 18 digits.
- **Path aliases (#791).** `[server.aliases]` maps one first path segment to a served command or `door`, as
  `"/v1" = "claude-team"`. The listener rewrites the decoded segments once, before any route reads them, so
  `/v1/chat/completions`, `/v1/models`, `/v1/messages` and `/v1/messages/count_tokens` are the command's own routes
  with its key, caps, allowlist and minimum client. Boot refuses an alias that is not `/<segment>`, that is written
  twice (`"/v1"` and `" /v1"`), that is `server`, `health`, `door`, a served command or the head key or command of a
  served head, or that stands for anything but `door` or a served head. A provider `[server] commands` serves with no
  head is served at the door alone, so an alias reaches it as `door`. The upgrade itself goes through
  `splice upgrade` and, with F15, the build the server points at: verified download, the new executable must run
  and name its version before it replaces the old, the old kept for rollback, one check at a time.
- **F10 fixed-cost providers.** `hourly_usd` on a provider, metered from the host's live boot time read at report
  time (never a boot time stored earlier). Reports carry `fixed: [{provider, hours, cost_usd}]` and
  `totals: {cost_usd, fixed_usd, actual_usd, reference_cost_usd}`.
- **F11 session affinity.** A session's turns (by `X-Claude-Code-Session-Id`) go to the upstream that served its
  last turn while it is healthy, so the server's prefix cache stays warm. New sessions are still balanced; a
  session whose upstream goes down moves once and stays where it moved.
- **F12 access and token caps.** `splice server team set <team> --commands a,b` and `server person set <email>
  --commands a,b|all`; the person's setting wins over the team's. A command a person may not use is a 403
  `permission_error` naming it. `[server.limits] tpm_per_person` refuses a person's next turn with 429 once their
  last minute's tokens reach the cap, the turn whose answer they have just had among them, as for budgets (F3).

## The team gateway (gateway-no-heads)

A team reaches the gateway through the same keyed listener: `/door/v1/messages`, `/door/v1/messages/count_tokens` and
`/door/v1/models`, with a person's key. The gateway reads the request, resolves the model it names (the provider's own
`claude-<provider>--<id>`, a selector under an old head prefix, a `[door.selector_aliases]` entry, or a readable name) to
the provider that serves it, and serves the turn on that provider's own serving: no head is read and none is relayed to.
What is the server's holds on that *selected provider*, never on the selector the client wrote:

- the key, per-person caps (in flight, requests and tokens a minute), budgets and the ledger row, as for a head's turn,
  the row naming the provider as its command;
- the allowlist (`--commands`): a provider is allowed by its own key or by the command or key of a head that runs it, so
  a person allowed `alpha` is refused `beta` with a 403 naming `beta` whether they asked for `claude-beta--m` or for an
  old prefix of beta's, and `GET /door/v1/models` lists only what they may use. Each name means one provider: a name that
  is a head's command and also another provider's key keeps the head's meaning, and boot reports the clash;
- the minimum client: an older laptop's turn is answered in the conversation and takes no slot;
- a streamed turn is kept alive by the gateway's own client channel, the one every provider turn writes through, which
  pings its client every two seconds while the answer is silent to learn whether it left. `[server] keepalive_seconds`
  shortens that tick when it is below two (`1` pings every second), the listener passing the cadence as typed turn
  metadata, not a header; the heartbeat and progress line keep their own timing. `0`, or any value from two up, adds
  nothing: the two-second tick is the floor. A response that is not streamed is not pinged;
- the upstream the person prefers and the id the ledger names the turn by travel to the provider as metadata this
  listener authors, never as a header a caller could set.

`[server] commands` serves a provider by its key too (no head needed), except a provider that signs with one person's
plan login. The `/<command>/...` routes of the heads that exist keep working beside the door, unchanged.

## Mode routes

A mode (modes/README.md) can answer a team's key holders on the same listener, under `/server/mod/<name>/...`. It is
off until splice.toml turns it on, per mode:

```toml
[modes.<name>]
server_routes = true       # key holders reach the mode at /server/mod/<name>/...
server_admin_only = true   # and only the people `[server] admins` names
```

- **A key first.** A mod path is never one of the open routes: a missing, unknown, revoked or expired key is the same
  401 as anywhere else, and no mode is reached.
- **Who is asking, never the key.** The mode is told the key's person (their email), their team (their own, else their
  domain's) and whether they are an admin. It is never handed the key, its prefix or its hash, and of the request's
  headers only `accept`, `content-type` and `user-agent`: `authorization`, `x-api-key` and `cookie` never reach it. It
  gets the method, the path after `/server/mod/<name>`, the query (the first value of each name) and the body.
  Headers and query parameters containing server keys or browser session proofs are omitted.
- **Gates.** A mode that is not carried, or whose `server_routes` is not true, is a 404 `not_found_error`: a key holder
  cannot tell it from a name no mode has. With `server_admin_only`, a caller must be listed in `[server] admins`
  and satisfy `admin_cidrs` if set; others get a 403 `permission_error` that says which of the two failed. The
  daemon loads the mode's settings at startup and checks the loaded settings on each request. Hand-edits to
  `server_routes` and `server_admin_only` take effect after a daemon restart or when changing a switch for that
  mode in the console reloads its settings. A console switch, `PUT /api/modes` or `splice setup` writes only `on`
  and `heads`, preserving the other keys as they are in splice.toml at the time.
- **Limits.** A body is read up to 1 MiB (past that, 413 `request_too_large`) and for at most 30 seconds (408). A mode
  that throws is a 500 naming its exception's class and nothing it held; a path it has no answer for is a 404.
- **Names.** A mode with `server_routes = true` may not be named `server`, `door` or `health`, nor a name
  `[server] commands` serves at (each entry, and the key and the command of every head an entry names by either):
  boot and `splice check-config` refuse it at `modes.<name>.server_routes`, so a mode's name never reads as a path
  the listener answers itself.

A mode answers with `Mode.serve(request)`: its status, content type and body, or null for a path it does not have. Its
code from built-in and external modes runs in the daemon.

## The Desktop window on a served machine

A splice that also serves Claude Desktop states the windows Desktop gives its Code tab in splice.toml's
`[desktop]`: `code_window` (200,000 shipped) and `code_window_1m` (1,000,000 shipped, never below `code_window`).
Desktop sets those windows itself in its Code tab's Claude Code, which divides by them. Discovery's
`max_input_tokens` stays each provider's real ceiling, never a configured window; Desktop reads that field, but
where it uses the number is not established (docs/specs/desktop-full.md). splice authors the counts each Desktop
request reports, so the door scales them by the configured window over the model's own and the Code tab compacts
where the terminal setup does, even where the two windows differ. A model is advertised to Desktop with a 1M variant
when its real window reaches `code_window_1m`. The daemon reads the table at start (a restart, and Desktop's next
launch). The team door's model list is for terminal Claude Code, so it keeps Claude Code's own million as the bar.

## P3

- **F13 forward_auth** for the team's dashboards on the same sign-in, with an email allowlist and admins; a
  person's own key opens them too.
- **F14 durable state.** Keys, ledger, teams and check-ins in SQLite, hourly snapshots to object storage, restore
  on a fresh disk, and never starting empty over a snapshot that exists.
- **F15 client distribution.** The server serves or points at the client build its policy wants, so a new laptop
  installs and joins with one command.
- **F16 audit log** of every admin action (who created, revoked or changed what, when), and a **console Server
  page** for keys, people, teams, budgets, upstreams and usage.

### Browser sign-in for dashboard forward auth

Without a browser origin, `GET/HEAD /server/forward-auth` keeps accepting a Splice key as a header or
the legacy `splice_forward_auth` cookie. For browser sign-in, set an HTTPS origin:

```toml
[server.forward_auth]
allow = ["ada@team.example", "ops@team.example"]
browser_origin = "https://dash.team.example"
session_seconds = 3600
email_header = "X-Auth-Request-Email"
role_header = "X-Auth-Request-Role"  # optional; values are admin or user
# A distinct web OAuth registration is optional and uses the same issuer and sign-in policy:
# client_id = "dashboard-web-client"
# client_secret_env = "SPLICE_DASHBOARD_OIDC_SECRET"
```

Register `https://dash.team.example/server/forward-auth/callback` with the identity provider. Use a
separate web client if the laptop OAuth registration only permits loopback redirects. If the named secret
variable is unset or empty, the listener refuses to start. The browser inherits
the verified-email domains and the optional hosted-domain check from `[server.signin]`; the dashboard allowlist
is checked after that. A configured admin CIDR restriction also applies when assigning the dashboard admin role.

Expose `/server/forward-auth/login` and `/server/forward-auth/callback` on that same HTTPS origin
without applying the forward-auth check to those two routes. Direct unauthenticated browsers to the login
route with an optional URL-encoded same-origin path `return_to`. Use `/server/forward-auth` as the proxy's
auth check: pass the browser cookie and a trusted client address, strip incoming identity/role headers,
and copy them only from a successful auth response. Do not cache auth responses or pass the Splice
auth cookie to the protected application. Header keys still work, but browser mode replaces the
legacy key cookie with a signed, expiring, dashboard-only session cookie.

Revoke a browser session from the existing key list (source `browser`) or revoke all keys for the person.
A browser session that expires unrevoked is dropped from the key list the next time the server mints a key (each
browser sign-in mints one), so the list holds one row per live session. A revoked session stays listed, so it can
never be imported or signed back to life.
Keep the identity provider's account policy current so a revoked person cannot sign in again. Restarting
the server also invalidates browser cookies; their signing key is held only in memory.

## What stays outside splice

The infrastructure (the VM, its schedule, the engine build and serving recipe, the model choice), the dashboards
built on the metrics, and each organisation's own values: its domain, team names, who may open the consoles, its
reference rate card.
