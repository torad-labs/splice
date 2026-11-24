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
admin_cidrs = ["192.0.2.0/24"]              # optional: empty preserves unrestricted admin addresses
key_prefix = "spk_"                         # optional: only new keys use this mark
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
- Every person with a live key has their series at 0 from startup; a person with none has no series.
- The same series go through the operator's OTLP export (`[telemetry.operator]`). These carry person and team by
  design; the operator families described in `docs/observability/exporters.md` stay identity-free.

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
  forwards it in the same encoding. The laptop recipe sets `CLAUDE_CODE_ENABLE_TELEMETRY=1` and
  `OTEL_EXPORTER_OTLP_ENDPOINT=<server>/server/otlp` with an `otelHeadersHelper` that sends the key, and never sets
  `OTEL_LOG_USER_PROMPTS`. Where the command's `remote-settings.json` (organisation-managed) sets a telemetry key,
  splice adds no telemetry of its own; managed settings that set none leave the relay on.
- **F9 client version policy.** `[server.client] minimum`. Each launch sends `x-splice-client: <version>` in
  `ANTHROPIC_CUSTOM_HEADERS`. An older client's turn is answered 200 with an assistant message naming the version
  to upgrade to, streamed or not, and nothing reaches the upstream (Claude Code retries a refusal about six times
  and shows a 200 once). A request with no version (a script) is served. The upgrade itself goes through
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
  last minute's tokens reach the cap.

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
Keep the identity provider's account policy current so a revoked person cannot sign in again. Restarting
the server also invalidates browser cookies; their signing key is held only in memory.

## What stays outside splice

The infrastructure (the VM, its schedule, the engine build and serving recipe, the model choice), the dashboards
built on the metrics, and each organisation's own values: its domain, team names, who may open the consoles, its
reference rate card.
