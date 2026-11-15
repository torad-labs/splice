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
4. **Keys are `spk_<8 lowercase alphanumerics>_<32+ alphanumerics>`.** The first two parts are the visible prefix
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
```

- Routes on the listener: `GET /health` (open), `GET /` and `GET /<command>/launch` (as share mode serves them),
  `ANY /<command>/v1/...` for turns, `/server/...` for the server's own API.
- A key arrives as `x-api-key` or `Authorization: Bearer`. A missing, unknown, revoked or expired key is a 401
  `authentication_error` that never echoes the key, and nothing reaches the upstream.
- CLI: `splice server key create --person <email> [--name <n>] [--expires <duration>] --json`,
  `server key list [--person <email>] --json`, `server key revoke <prefix>`,
  `server key revoke-person <email>` (offboarding; prints how many keys it revoked).
- Control plane (management key, loopback): `POST /api/server/keys`, `GET /api/server/keys`,
  `DELETE /api/server/keys/<prefix>`, `DELETE /api/server/people/<email>/keys`.
- Admin over the listener (a key whose person is in `admins`): `GET/POST /server/api/keys`,
  `DELETE /server/api/keys/<prefix>`. A non-admin key is 403.

### F2 signin-server and signin-client (`signin.test.ts`, `signin-client.test.ts`)

```toml
[server.signin]
issuer = "https://accounts.google.com"
client_id = "…apps.googleusercontent.com"
client_secret_env = "SPLICE_SIGNIN_SECRET"
domains = ["team.example"]
key_days = 30
rate_per_minute = 20
```

- `GET /server/signin` (open) gives the laptop everything it needs: issuer, client id, authorization endpoint,
  scopes, domains. Never the secret.
- `POST /server/signin/token {code, code_verifier, redirect_uri, nonce, device}` trades the code, verifies the ID
  token (JWKS signature, issuer, audience, expiry, nonce, a verified email in an allowed domain) and mints a key
  with source `signin`, the device, and an expiry of `key_days`. Every failure is a 401 and mints nothing.
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
  a restart. A team budget holds every member.

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
- Every person with a live key has their series at 0 from startup; a person with none has no series.
- The same series go through the operator's OTLP export (`[telemetry.operator]`). These carry person and team by
  design; the operator families described in `docs/observability/exporters.md` stay identity-free.

## P2: the laptop side

- **F7 server profile.** `GET /server/profile`: commands, models with their context windows, effort vocabulary,
  telemetry endpoint, version policy, sign-in configuration. `splice remote add <server>` links every command it
  offers; `splice remote refresh` resizes heads whose window changed, unless the person pinned one.
- **F8 telemetry relay.** `POST /server/otlp/v1/{metrics,logs,traces}` takes Claude Code's own OTLP with the
  person's key, stamps the verified person and team on every point, log and span, and forwards to the operator's
  collector. The laptop recipe sets Claude Code's OTLP endpoint and an `otelHeadersHelper` that sends the key,
  never logs prompts, and respects organisation-managed settings (`remote-settings.json`): telemetry is turned off
  only where those settings set a telemetry key.
- **F9 client version policy.** The server advertises a minimum client version; the laptop sends
  `x-splice-client`. An outdated client's turn gets a clear in-band message (a 200 the client shows, because
  Claude Code retries a refusal about six times and a 200 once) and the launcher upgrades itself: verified download,
  the new executable must run and name its version before it replaces the old, the old kept for rollback, one
  check at a time. Optional update-and-resume through one allowlisted command and `--resume`.
- **F10 fixed-cost providers.** `hourly_usd` on a provider, metered from the host's live boot time (never a value
  rendered at an earlier boot), with reports showing actual spend beside the API-equivalent cost.
- **F11 session affinity.** A session's turns go to the upstream that served its last turn while it is healthy, so
  the server's prefix cache stays warm (`cache_n` in the ledger shows it).
- **F12 token limits and allowlists.** Tokens per minute per person; commands and models allowed per person or
  team.

## P3

- **F13 forward_auth** for the team's dashboards on the same sign-in, with an email allowlist and admins; a
  person's own key opens them too.
- **F14 durable state.** Keys, ledger, teams and check-ins in SQLite, hourly snapshots to object storage, restore
  on a fresh disk, and never starting empty over a snapshot that exists.
- **F15 client distribution.** The server serves or points at the client build its policy wants, so a new laptop
  installs and joins with one command.
- **F16 audit log** of every admin action (who created, revoked or changed what, when), and a **console Server
  page** for keys, people, teams, budgets, upstreams and usage.

## What stays outside splice

The infrastructure (the VM, its schedule, the engine build and serving recipe, the model choice), the dashboards
built on the metrics, and each organisation's own values: its domain, team names, who may open the consoles, its
reference rate card.
