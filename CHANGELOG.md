# Changelog

## splice v0.4.0 — a console, self-upgrade, account pools and local models, and sessions that never hold the management key - 2026-09-24

### Highlights
- **Sessions hold a turn key, never the management key.** Every session launched by 0.3.x held
  the one credential that opens the whole control plane. Upgrade, relaunch your sessions, then
  rotate the key ([Security](#security), [Upgrading from 0.3.x](#upgrading-from-03x)).
- **`splice upgrade`** fetches, verifies and stages a release, waits for in-flight turns, restarts
  the daemon and runs doctor. `--rollback` puts the previous release back.
- **Several accounts per provider.** `splice login <head> --label <name>` adds an account, and a
  head switches accounts on its own when a provider's limits are hit.
- **Local models are first-class** on the `openai-chat` dialect. splice asks the runtime what it
  serves and refuses a row it doesn't. llama-server conversations keep their own slot. Local heads
  report token usage, so Claude Code auto-compacts, and their errors say what happened.
- **The console** signs accounts in, switches, removes and relabels them. It adds opt-in body
  capture, budgets, alerts, a playground and a per-project view of the rules that govern a repo.
- **New commands:** `splice add <profile>`, `splice add-model`, `splice sessions`,
  `splice perf`, and `splice doctor --json` (a shareable, redacted report).
- **New heads and modes:** `claude-muse` (a Meta Muse Code subscription); Claude head mode (wrap
  the default `claude` command); per-head system prompts, including a `strip` mode; custom
  compaction instructions; shared MCP hosting.
- **Code mode is out of beta** and on by default for ChatGPT.

### Upgrading from 0.3.x
0.3.x has no `splice upgrade`. Re-run the installer pinned to this release:

```bash
curl -fsSL https://github.com/torad-labs/splice/releases/download/v0.4.0/install.sh \
  | env SPLICE_VERSION=v0.4.0 bash
```

- **The installer leaves the running 0.3.x daemon alone.** The next `claude-<head>` launch
  replaces it: the launch shim sees the old version on `/health`, stops that daemon and starts
  0.4.0.
- **Config, credentials and the state root are untouched.** An install made before 0.4.0 keeps
  its state under `~/.claude-codex/state`, where it always was.
- **A session launched under 0.3.x keeps working until you relaunch it.** Its environment holds
  the management key, and that key still runs a turn. Relaunch each one to move it onto the turn
  key.
- **Rotate the management key after the relaunch.** Under 0.3.x it sat in every session's
  environment, where the model's tools could print it into a transcript. Delete
  `<state>/mgmt-key`, then stop the daemon: `systemctl --user stop splice.service` where a unit
  runs it, otherwise end its process. Then run `splice restart` or launch any head. A fresh key
  is minted, the old key is refused, and `turn-auth-header` follows on the next launch.
- **The installer records 0.3.x as the previous release,** so `splice upgrade --rollback` has a
  target.
- **From 0.4.0 on,** `splice upgrade` does all of this in one command.

### Security
The management key opens the whole control plane: every head's system prompt, every transcript,
config writes and daemon shutdown. Until 0.4.0 it reached every launched session in three ways:
- `ANTHROPIC_AUTH_TOKEN` in the session's environment, which every tool the model runs inherits.
  It was seen in transcripts that went to model providers.
- Inline in each head's `settings.json`, and in curl's argv on every statusline tick
  (`/proc/<pid>/cmdline` is world-readable).
- In the resume hook's argv.

0.4.0 closes each path:
- **A session holds a turn key.** The turn key is derived one-way from the management key
  (HMAC-SHA256 under its own scope), so holding it reveals nothing about the management key. It
  opens a head's turn routes and the session's own statusline and resume-hook routes, nothing
  else. The management key still opens everything and still runs a turn.
- **No key in `settings.json` or argv.** The statusline and the resume hook read the bearer from
  `<state>/turn-auth-header` (0600), which is rewritten on every launch.
- **DNS rebinding is refused.** The control plane and every head refuse a request whose `Host`
  names anything but `127.0.0.1`, `localhost` or `[::1]`, before any route runs. Each refused
  Host is logged once, up to 16 names, then counted at each doubling.
- **State stays owner-only.** The daemon holds the state root (when splice chose it), the state
  dir and the logs dir at 0700. It logs any directory that stays open, with the mode it kept.
- **A client-auth head never forwards splice's own keys upstream.** A turn that carries the turn
  key or the management key in `Authorization` or `x-api-key` is refused with a 401 that names
  the header and the variable that set it. This includes a key behind a blank first header line.
- **`splice dashboard` prints the key to a terminal only,** never into piped or captured output
  such as an agent's transcript. On JDK 22–24, `System.console()` is non-null even when output is
  piped, so the launch shim runs the CLI with `-Djdk.console=java.base`.

splice is single-user, and its boundary is your Unix account. A process running as you can read
`mgmt-key`, as it can read your other credentials. What 0.4.0 closes is every place the key left
that boundary: transcripts sent to providers, argv other accounts can read, and pages on another
origin.

### Known limitations
- **A rate limit after text has streamed ends the turn on a head that cannot continue from a
  prefill.** A limit before any text restarts the turn on every head. Heads measured to resume
  (kimi, or any provider with `reanchor_prefill = true`) continue where they stopped. Muse answers
  an assistant prefill with a 400, as Anthropic documents for Claude 4.6 and later, and
  restarting would repeat what you already read. Re-send the turn.
- **A restart waits for turns in flight, but only for 45 s.** A turn still streaming after that
  is cut, and Claude Code does not retry a turn cut after its text began. `splice upgrade` waits
  until every head is idle unless you pass `--now`.
- **Anthropic treats routing Claude Code through a custom gateway as unsupported.** splice is
  tested against Claude Code 2.1.281. When a session runs a newer one, doctor, `splice status`
  and the status line say so once.

### Added
- **The project page says what governs the repo.** `GET /api/projects/{id}` (and each row of the
  list) now carries `compaction`, the rules a compaction in that repo resolves to in the daemon's own
  precedence (core's `CompactionInstructions.rulesFor`: a project rule shadows the model and global
  ones, and a rule a longer path shadows is left out), as the `{scope, source, chars}` the
  instructions route writes; `[]` when no rule applies (the client's own instructions stand) and
  `null` when the daemon never wired its table. Beside it, `statusline_roots`: per head, because
  `statuslineGitRoots` is per-head overridable, the trusted root that head's statusline probes the
  repo under (`home`, `tmp` or `statuslineGitRoots`), or none, where it shows no branch. The console
  prints both in the project detail.
- **Perf history is kept past two generations.** The 64 MB perf rotate used to discard the
  generation before `.1`; V4-133 built an archive for it and wired it to nothing. Every head now
  archives each retired generation into `<state>/perf-archive` for `perfArchiveRetentionDays` (90;
  `0` turns it off), the perf readers (the console's windows, team economics, `splice perf`) read the
  archive oldest first and skip, unopened, any generation that ended before the window, and a team's
  lifetime tally starts at the team's creation. A new wall, `kt-perf-history-archived`, refuses a
  production `PerfStats` or `PerfRowsFileSource` that does not name the archive.
- **Each head's picker offers what its provider serves, not only what splice.toml lists.** At start
  the daemon asks every head's endpoint for its model list — the URL `splice models` already asked,
  presenting the credential the head's turns present — and adds each model no row declares, windowed
  as the endpoint publishes it, so a model a vendor ships is offered at the next restart with no TOML
  edit, and a provider may declare no rows at all. Declared rows keep everything they decide (order,
  label, window, rates, slot), and a discovered model is never placed behind a tier slot. What the
  endpoint itself says cannot run a turn stays out (OpenRouter's non-text or tool-less models, the
  Codex backend's hidden ones), and a new per-provider `discovery = { include, exclude }` glob filter
  names out the rest (xAI's `grok-imagine-*`); `exclude = ["*"]` turns it off. Every head is asked at
  once under a 10s deadline, and no answer ever costs a head its start: the last list the endpoint
  served is kept per head under the state directory, and with neither the head boots on its declared
  rows exactly as before. The Codex backend now lists too (`/models?client_version=…`, which
  answered HTTP 400 without one), and Muse presents its minted API key rather than the OAuth access
  token it refuses. `splice models` marks each undeclared model `+` discovered or `–` kept out, with
  the reason.
- **A team member's `checks` field reads a real outcome (V4-159).** `GET /api/teams/{id}/economics`
  shipped every slot's `checks` as an honest empty (`checks: null`) because the daemon had no
  per-member signal to fill it with. It now reads the outcome tag of the slot's most recently
  tallied turn (`PerfRow.outcome`, the same perf rows already tallied for tokens and cost):
  `"pass"` when that tag is `OutcomeTag.OK`, `"fail"` for anything else (rate-limited, cancelled, an
  upstream failure and the like) — a turn-health signal, never a build or test verdict, since the
  daemon observes nothing inside a session's own tool calls. A slot that has tallied no turns at all
  keeps the honest empty, `checks_source` naming why.
- **Shared MCP hosting's census now covers all five places a server can be declared, not one
  (V4-146).** McpGlobalRead read exactly `<home>/.claude.json`'s top-level `mcpServers` and
  nothing else, so `/api/mcp` — and the benchmark V4-08 shipped against — could see under a
  tenth of what a box actually runs: measured here, 10 servers in that one file against 139
  registrations once every project-scoped override nested inside `.claude.json`, every repo's
  own `.mcp.json`, and every plugin's `.mcp.json` or inline `plugin.json` `mcpServers` are
  counted too. `/api/mcp`'s new `sources` section reports a per-kind scan (roots looked at,
  files scanned, registrations found — a kind given nowhere to look refuses construction rather
  than reporting a quiet zero) and a disposition for every server found: `migrated` when
  McpSharing's own plan already hosts it, `excluded` with a written reason (a different Claude
  identity's file, or a plugin-owned manifest — Claude Code's plugin precedence matches by
  endpoint, so a user-scope override pointed at the daemon would add a process rather than
  replace one), or `pending` for a project or repo override the census can now see but the
  rewrite pipeline does not yet touch. The pipeline that actually hosts a server is unchanged —
  only the canonical home's global entries are rewritten, exactly as before.
- **Claude head mode: wrap the default `claude` command, and several Claude logins on the
  splice-owned head (V4-129).** `GET /api/claude-head` reports which of the two modes is active
  (Separate, the default: `claude-splice` stays a splice-owned head with its own config dir; Wrap:
  the operator's plain `claude` becomes a splice launcher over the vanilla `~/.claude`), what
  `claude` on PATH resolves to right now, and the shim path. `POST /api/claude-head/wrap` installs
  the shim at `claude`, preserving the shadowed symlink's exact target for byte-identical restore,
  and materializes `~/.claude/settings.json` + `~/.claude/.claude.json` through a deliberately
  narrow door that never bypasses `ClaudeConfigMaterializer`'s DR-102 guard (that guard still
  refuses `~/.claude` on its general entry point); both files are backed up first. `POST
  /api/claude-head/unwrap` restores both. Every OTHER head's launch is protected too: LaunchService
  now plants the real absolute claude binary instead of the bare `"claude"` string whenever wrap is
  active, because every head shares one `claude`-on-PATH argv[0] and a wrapped `claude` would
  otherwise resolve straight back to the shim mid-launch. Several of Claude Code's own
  `.credentials.json` files can be stored under a label; the selected one materializes into
  `claude-splice`'s config dir at session launch only — no mid-session switch, one login per head at
  a time; splice never reads the bytes or calls Anthropic with them. `splice doctor` reports the
  mode.
- **The console can sign accounts in, switch, remove and relabel them, from one joined view
  (V4-132).** `POST /api/auth/{head}/login` starts a device or browser login off the request that
  asked for it and answers immediately with a login id; `GET /api/auth/{head}/login/{id}` polls it
  for the user code and verification link (device flow) or the browser URL (OAuth flow), through
  `signed_in` once the credential lands and `live_after_restart` once the head has restarted and
  the account is confirmed in its pool. `DELETE`/`PATCH /api/auth/{head}/accounts/{label}` remove
  or relabel a pooled account; the primary is refused for both, never silently accepted.
  `POST /api/auth/{head}/switch` is a REAL pin now — `AccountPool.select` tries the pinned account
  FIRST, ahead of the primary preference, from the next turn, and falls through to ordinary policy
  the moment the pin names an unavailable or unknown account, so pinning never wedges a head that
  would otherwise still be serving turns. `GET /api/accounts` joins every pool's accounts across
  every head, on the credential path — two heads sharing one login are one row, not one per head —
  carrying each window's own reported length (a 30-day provider is never rendered as a 7-day one),
  the operator's pin, the next target by the real selector order, and single-login heads read from
  their `/api/auth` view. The statusline now records each session's `rate_limits` object (five-hour,
  seven-day, per-model weekly windows), window fields only and gated on `rate_limits_available`, so
  a follow-up console surface can read a session's real Claude-reported windows rather than
  splice's own derived quota.
- **Console daemon table stakes: opt-in body capture, budgets, alerts and a playground (V4-133).**
  `GET`/`PUT /api/heads/{head}/capture` reports and flips the TRACE knob per head — the capture
  store V4-174 already built, not a second one — and always answers `restart_required: true`,
  because making the switch hot would touch the turn-serving path outside this change.
  `GET`/`PUT /api/budgets` holds per-head daily USD budgets with a `warn`/`block` action, defaulting
  the action from the new `budgetDefaultAction` knob when a row omits one. `GET`/`PUT /api/alerts`
  holds a desktop-notification flag and one webhook URL, and `POST /api/alerts/test` fires one real
  delivery to whatever is SAVED, never an unsaved draft. `POST /api/playground` sends one prompt
  through one head's own credential and provider — anthropic-passthrough, openai-chat or
  openai-responses, whichever the head is on — as an independent one-shot call that never touches
  the turn pipeline, so it writes no perf row, no trace record and no economics: the request and
  response ride back in the HTTP response and nowhere else. Every new store (`budgets.json`,
  `alerts.json`) is a plain 0600 JSON file under the state dir, same shape as `teams.json`. The perf
  JSONL rotate that discarded its next-oldest generation on every rotation now archives it first,
  timestamped, when a head sets `archiveDir`; `perfArchiveRetentionDays` sweeps generations past its
  window.
- **A llama-server head keeps each conversation on its own slot (V4-165).** llama-server picks a
  slot by prompt similarity measured against the new prompt, and skips empty slots while doing so,
  so a new session sharing Claude Code's ~30K preamble took an idle conversation's slot and that
  conversation re-prefilled from zero — the model's recurrent layers cannot be rewound, so a slot's
  state is all or nothing. With `quirks = { slot_affinity = true }` splice sends `id_slot` per
  conversation (its session plus its opening messages, so a subagent or title request sharing the
  session id does not queue behind the main conversation), gives a new conversation the least
  recently used slot with no turn in flight, reads the slot count from the server's `/props`, and
  sends a turn unpinned whenever no slot is free or the count is not known yet. A slot is held until
  the turn truly ends, detached compaction drives included.
- **Local heads report their token usage, so Claude Code auto-compacts.** Token counts are
  opt-in on the OpenAI-compatible chat dialect (`stream_options.include_usage`), and splice asked
  only on grok — so every other head on that dialect reported zero tokens per turn, Claude Code's
  context meter never moved, and a session ran into the context wall instead of compacting. A
  provider that is local (a loopback `base_url`, or `local = true`) now asks by default; hosted
  vendors are unchanged, because strict ones reject unrecognized `stream_options` members. The new
  `[providers.<key>.quirks] stream_usage` overrides it either way, for a local runtime that refuses
  the field or a hosted vendor known to accept it.
- **A `context_window` edit needs no restart.** The running daemon re-reads the windows in
  `splice.toml` (a model's `context_window`, `extra_windows`, `window_rules`,
  `default_context_window`, a head's `context_window`) when the file changes, so running sessions
  compact at the new window through usage scaling, the next launch plants it as
  `CLAUDE_CODE_MAX_CONTEXT_TOKENS`, and the console's models page shows it. A local runtime is
  asked about a new window the way boot asks it, off the request path, and a window it refuses is
  not applied; a file that does not parse, or a head it no longer declares, keeps the windows in
  force. Each outcome is one daemon.log line. `/health`'s `topologyDigest` now names the version the
  daemon runs: a window-only or comment-only edit leaves `splice doctor` with nothing to restart
  for, any other edit still reads stale until `splice restart`, and `PUT /api/topology` answers
  `restart_required: false` for a write that moved only windows.
- **Per-head system prompt.** `system_prompt` (or `system_prompt_file`) under `[heads.<key>]` gives
  a head standing instructions that ride on **every** turn, at that dialect's own system seam —
  anthropic-passthrough, openai-chat and openai-responses each place it the way their wire expects.
  Setting both keys, or naming a file that cannot be read, is a config error at load, never a
  silently empty prompt; absent or `""` leaves the request bytes byte-identical to before, pinned
  per dialect. `system_prompt_mode` picks the seam: `"append"` (the default) places the text beside
  Claude Code's own system field so the client's bytes and every `cache_control` breakpoint survive
  and the prompt cache still hits from turn two; `"replace"` substitutes that whole field, which
  strips the entire operating instruction set Claude Code ships there and leaves the head behaving
  like a bare model with tools attached — `splice doctor` warns on any head that sets it.
- **`splice add <profile>` adds a second provider without editing TOML.** Five profiles as data
  (`codex`, `grok`, `kimi`, `claude`, `api-key`); the command authenticates with the login verb's
  own flow, takes models and context windows (`--model id:window`), always checks the candidate
  before writing (the file parses, the credential is present, the base URL answers, the models are
  listed where the dialect publishes a list), runs one short live turn only on `--live`, then
  appends the provider and head tables through a sibling temp file and one rename. Anything that
  stops the flow leaves the previous file byte-identical, and a command line it cannot mean (an
  unknown flag, a flag without its value, a second word) is refused with the usage, never swallowed.
  A model id may itself carry colons (`--model qwen3:4b:32768` is the id `qwen3:4b`); a window that
  is not a positive number is refused. The config is read again right before the rename, so a file
  edited while the sign-in and checks ran is left alone and the command says to rerun; and when the
  daemon restart that was asked for fails, the command exits non-zero (the head is saved and
  `splice restart` is named) instead of printing a launch line for a head the daemon does not serve.
  A config saved without a trailing newline is not "changed" on every run; a flag followed by
  another flag (`--model --yes`) is a missing value, never a model named `--yes`; a config with no
  head yet gets the first head port. A model id given twice is refused (the catalog keys rows by
  id, so the second window would silently win). A config that cannot be read again at save time
  (deleted or replaced meanwhile) is refused, never recreated from the stale candidate; a typed
  context window that is not a positive integer (`32k`, a decimal, a number past Long) is asked
  again instead of silently becoming 128000, and three misses refuse the add.
- **`splice add-model` puts more OpenRouter models on a head.** It asks which OpenRouter head,
  offers the curated models that head cannot reach yet, and writes the picked rows (and, for a head
  that lists its own `models`, its roster) through one rename. A composed file that does not parse
  is refused and the old one is left as it was.
- **`splice upgrade [--to vX] [--now] [--rollback]`.** Fetches and verifies a release exactly as
  `install.sh` does (sha256 against `sha256sums.txt`, GitHub build-provenance attestation through
  an authenticated `gh`), stages it under `~/.local/share/splice/releases/<version>/`, runs the
  candidate's own doctor, waits until every head's in-flight count on `/api/heads` is zero (or
  `--now`), repoints the live jar, restarts the user unit when one supervises this install, and
  runs doctor. The launch shim is always refreshed with the release (it is version-locked to the
  jar by the launch handshake, so a kept old shim could launch nothing); a shim edited since its
  release was installed, or one with no pristine copy (every flat 0.3.x install), is saved beside
  its release as `splice-launch.edited` with its diff printed, never lost; a flat install's live
  shim is recorded with its jar so a rollback has one to restore. The restart is judged by what
  `/health` serves afterwards, never by an exit code: the old CLI no longer polls for its own
  version and calls a good restart a failure, a loaded-but-inactive user unit (a daemon started by
  hand) takes the stop-and-start path instead of restarting a JVM that exits on the daemon lock,
  and a daemon still on the old version is named. `--to 0.4.0` and `--to v0.4.0` name the same
  tag; a candidate older than 0.4.0 skips the `doctor --json` preflight it cannot answer instead of
  running its text doctor against the live install and being refused for it; another running
  upgrade's staging directory is never pruned, and a pruned one is a refusal, not a stack trace.
  `install.sh` records the flat install it replaces as `previous`, so the first `--rollback` after
  it has somewhere to go. Release downloads follow same-scheme redirects only (an HTTPS to HTTP step
  would carry the jar and its sums over the same downgraded hop); every process the upgrade runs
  (`gh attestation verify` included) has a deadline; `--rollback --to` is refused instead of the
  version being ignored; install.sh records the release it replaces as `previous`, so the first
  rollback after an install has somewhere to go; temp links are named per process and instant.
  `--rollback` repoints at the previous release, kept until the next successful
  upgrade. Config and credentials live elsewhere and are never touched; a failed verification
  activates nothing. `install.sh` keeps the pristine release copy the comparison needs. One
  upgrade runs at a time per install (an OS lock under `releases/`; a second run is refused before
  it fetches anything, so two runs can never prune each other's release). A download that fails
  (DNS, TLS, a timeout, a 403 or 5xx) is refused by its class, never reported as a missing asset.
  An activation that began without a live shim and failed removes the shim it wrote. `install.sh`
  reads the `previous` link back after writing it and warns when something else is in its way.
- **`splice sessions` and `/api/sessions`.** The Claude Code sessions on this machine
  (`~/.claude/sessions/*.json`) joined to the head that launched each, with live / stale / gone
  derived from the pid and the last update rather than the file's own status, and the copyable
  `SendMessage` line per live session. A live pid whose process started long after the
  registration is a reused pid and reads as gone. Read-only; headless `claude -p` runs never
  register and the footer says so.
  Liveness reads the identity Claude Code writes beside the pid: a registration from another pid
  domain (a container sharing `~/.claude`) or a pid whose kernel start time is not the registered
  one (reused after the session exited) is GONE, so a stranger's process is never listed live or
  read for its head. A sessions directory that exists but cannot be listed (a permission failure,
  a file in its place) is reported by the command (non-zero) and the API (`error`), never read
  as no sessions; a missing directory is genuinely none.
- **`splice perf [--window 1h|24h|7d]` and `/api/perf/summary`.** Per head: p50/p95/max of time
  before first byte, time streaming and total, outcomes by tag, failure share overall and per
  outcome tag (rows whose outcome cannot be read are shown as unattributed, never as failures),
  retries and refreshes, cache hit ratio, peak in-flight, and a lower bound on the file-io writes
  the daemon dropped inside the window (a dropped perf row is absent, so this is the evidence one
  is missing; a restart whose counter catches up hides its drops). A window the rotated perf files
  cannot reach back to is reported clamped, with how far they reach; a quiet window over files that
  do reach past it is sparse traffic, not a clamp; no rows at all says so; a perf file that cannot
  be read is reported as a read error with the coverage marked unknown, never as short retention;
  a file whose every line is unparseable says "no valid perf rows read" with the skipped count
  (`skipped_lines`), never "no perf rows recorded yet".
  Empty data is reported as empty, never as zero-latency traffic. Reads stream the
  files, never hold a generation whole, and a rotation during the read is read again. `splice
  perf` is read-only and refuses malformed flags.
- **`/login` inside a head starts over, and can name an account.** A second `/login` while a
  sign-in was still waiting for its browser callback used to die silently on the callback port
  while the hook promised a browser; now the waiting sign-in is cancelled first and the reply
  says so. `/login ` with a trailing space or `/login --label NAME` is intercepted like `/login`
  (it no longer reaches the model as a prompt), and `--label NAME` rides through to `<head> login`,
  so a second account of the head's kind can be signed in from inside the client. Any other
  argument, or a label the CLI would refuse, is refused by the hook itself and nothing is started
  (a bare login would sign the primary in again). The receipt for a labeled sign-in says the
  account is saved beside the primary and joins the pool after `splice restart`; only an unlabeled
  sign-in is "using the new credentials", because that is the only one this session switches to.
  The hook proves the login command resolves BEFORE cancelling a waiting sign-in, and reports a
  replacement that exits as soon as it starts; the launch shim marker is now `shim-3`, so an
  installed shim from before the `--label` forwarding is reported stale by the daemon and doctor.
  The hook cancels only the sign-ins it started (each carries `SPLICE_LOGIN_ORIGIN=hook` in its
  environment): a sign-in you began in a terminal and are finishing in the browser is named and
  left alone, never killed and replaced by a primary login. An input whose top-level prompt cannot
  be read is refused with the same "no prompt string" reason on an api-key head as on a browser
  head, instead of being answered as a bare `/login`.
  A sign-in started as `splice login <key>` is found under that spelling too (the hook matches the
  wrapper word or the head key), so it is named and left alone, or cancelled when the hook started it.
- **`splice doctor --json [--with-logs] [--out FILE]`: a shareable, redacted report.** Schema
  version 1 carries the splice and Claude Code versions, OS and JVM, the topology's SHAPE
  (kinds, dialects, model ids and windows, quirk names, a host but never a URL with credentials),
  every check with its fix, and the last 200 perf rows per head (across both perf file
  generations) restricted to the named numeric fields, the compact and cache-cold flags, and
  model, outcome and account as safe tokens. Emission is an
  allowlist and every string still passes one redaction: account ids, e-mails, tokens, UUID-shaped
  ids and working directories never appear, only splice's own and system paths survive (under the
  home directory as `~`, every other path masked), an operator-authored name that is not a plain
  token (a provider or head key, a model id, a prefix) is omitted or aliased rather than shown,
  and `--with-logs` appends the last 500 daemon events reduced to their structure (timestamp,
  tags, event, key=value pairs) with a count of the lines that were not daemon events; an MCP host
  line keeps its server name as a safe token and its event head, so a report of a hosting problem
  carries the hosting lines. A malformed flag prints usage and writes nothing. Nothing is uploaded.
  Per-turn daemon log lines keep their `compact=` and `model=` pairs in the report (the event head
  had swallowed the first key, leaving every turn line empty).
  A JSON-quoted credential key (`"refresh_token": "..."`, `"api_key": "..."`) in free text is
  masked like a bare one, quotes and structure kept, so a short quoted secret no longer slips past
  every shape pass.
- **Automatic account switching when a provider's limits are hit.** `splice login <head> --label
  <name>` adds a second (third, ...) OAuth account of the same kind under
  `~/.config/splice/auth/<kind>/<primary file>/<name>.json` (`chatgpt-oauth/codex.json/work.json` for the default
  primary, so two same-kind heads never share a pool); the first login stays the primary in the file it always
  had, so nothing migrates. Selection is per turn and sticky per session: a session keeps its account
  until the provider reports it exhausted (a window at 100 % or a 429 whose reset outlasts the turn),
  then the next turn goes out on the pool account with the lowest seven-day usage whose five-hour
  window is open, and the session returns to its primary once that account's reset has passed. A turn
  in flight finishes where it started; the first turn after a switch is accounted as cache-cold and
  its perf row names the account. When every account is out the turn fails honestly, naming the
  earliest reset. A credential is only ever used by the kind it carries; a mislabeled file is refused.
  The upstream wait budget of a turn counts from the drive's start, beside the watchdog, never
  from admission: time queued behind the inflight gate is no longer charged to the provider, so a
  turn that waited longer than the cap still makes its first upstream call. On a pooled head the
  status line draws each window from the selected account's own tracker first and the client's
  headers fill only a window the tracker lacks; a locally answered side query carries the session's
  selected account's quota, not the primary's; an account's headers ride on top of the provider's,
  never instead of them; and a 401 from `/api/auth` reads as a failed read, not "no pools". A file
  in a pool directory that is not a credential at all (unparseable, or without a kind) is skipped
  with one diagnostic naming it instead of taking the whole head down; a credential that declares
  the wrong kind or label is still refused. Per-session account stickiness is kept for at most
  4096 sessions, least recently used first out.
  The status line, `splice status` and `splice doctor` name the account in use and the last switch.
  Labeled credential files are read without following symlinks (a linked file is skipped, its
  ordinal stays occupied), matching how they are written; the primary file is resolved as before.
  A terminal authentication rejection (401) excludes only future turns from that account, never the
  turn it was issued on; the exclusion lifts at once on an evidence-backed re-login (the credential
  file changed) and otherwise admits one timed recovery probe after holds of 5, 10, 20, 40 and 60
  minutes (60 minutes at most). Unknown file-stat evidence preserves the hold and its failure
  count; a stale success cannot clear a newer credential generation; cancellation and a failed
  startup release the probe. Auth exclusion is separate from rate-limit state and is shown in
  account status, `splice doctor` and the control JSON. Every observed 429 gives up that request
  without an in-request wait; followers fail fast; a short or missing pushback never reselects the
  account; a bare 429 protects the account locally for 20 s without inventing a provider reset, and
  only a supplied wait over 15 s excludes future selections (transport re-probes stay capped at
  120 s, provider reset reporting at seven days; real quota exhaustion respects its actual reset).
  Kimi automatic and explicit labels share cross-process login leases: a concurrent login to the
  same label is refused, a re-login may replace its credential, cancellation frees the lease, and
  validation precedes lock-directory creation; the login receipt names the label actually
  persisted, collision suffix included. Admission with every account exhausted is a local health
  failure with zero upstream calls and carries an IMF-fixdate `Retry-After` only for a known reset.
  A turn whose upstream wait budget runs out before its next request is issued ends in the same
  overloaded terminal the stream watchdog uses (same wording, no continuation partial), never as a
  cancellation, and makes no further upstream call. A missing pool credential no longer logs the
  refresh latch diagnostic on every status poll; the `Retry-After` header and the body text share
  one normalized reset instant (four-digit HTTP years), so a hostile provider reset can no longer
  turn an honest 429 into a 500.
- **Custom compaction instructions.** `[compaction]` in `splice.toml` carries global text (inline
  or `file =`), `[[compaction.model]]` rows keyed by upstream model id and `[[compaction.project]]`
  rows keyed by absolute directory (optionally per model). The most specific scope replaces the
  less specific ones (project+model, project, model, global); `instructions = ""` opts out. The
  text rides after Claude Code's own summarizer prompt on compaction requests only, so the cached
  request prefix is byte-identical with and without it. `/api/compact` shows the effective text and
  its source.
  A `[[compaction.project]]` path may start with `~/`, and a relative one resolves under the
  topology directory, exactly like `file =`; a tilde no longer stops the daemon at boot. A session
  whose project cannot be resolved is not looked up again for 5 seconds (a hit is never cached as
  a miss, so a new registry entry becomes visible). On the `openai-chat` wire the instructions
  extend a trailing user message instead of adding a second user message after it.
  A `file =` rule re-reads its file when it changes, so an edit is live at the next compaction
  without a restart (a file that becomes unreadable disables the rule, as at boot). Project paths
  and the session's cwd compare as physical paths, so a project configured through a symlink
  matches the cwd Claude Code records. A compaction retry is matched to its detached first attempt
  on the request BEFORE the instructions tail, so a project found late or an edited file cannot
  start a second upstream compaction; and when a dialect cannot place the tail (no user text to
  extend) the compact row says `(not applied)` instead of claiming instructions the wire never carried.
- **Shared MCP hosting.** stdio MCP servers that do not depend on a project directory or client
  roots are started once by the daemon and served to every session over Streamable HTTP on
  loopback (`/mcp/<name>`, one MCP session per client session, JSON-RPC ids remapped, notifications
  fanned out, `tools/list` cached); each head's `.claude.json` is rewritten to point at the hosted
  URL while the operator's file is never edited. Servers named `http`/`sse`/`ws` pass through
  untouched. Lifecycle mirrors code mode: start on first use, idle reap, eviction under pressure; a
  crash fails pending calls honestly and the next call restarts the server, never replaying tool
  operations. `[daemon] mcp_hosting = false` turns it off, `mcp_hosting_exclude` keeps named
  servers per session; `/api/mcp` shows eligibility and ownership. Measured on the reference
  machine's own MCP set with four parallel sessions (`checks/mcp-host/bench.ts`). A client that
  falls a full buffer (256) of notifications behind on its stream loses the stale backlog, never the
  fact that its lists may have changed: the backlog collapses to the three `list_changed`
  notifications plus the newest one, so the client re-lists once it catches up. A hosted child
  runs in the home directory (stated, never the daemon's accidental cwd); a server that reads its
  working directory without naming it belongs in `mcp_hosting_exclude`. A server whose last
  session ended is idle from then, not from forever, so the next session reuses the process
  instead of the next sweep killing it; a server mid-initialize is never swept; and a reservation
  taken by an initialize always ends, so capacity can no longer leak until restart. A client's
  progress token is private to its session: the child sees the host's request id and the progress
  notification goes back to that one session with the client's token restored. A second crash in a
  row waits before respawning (5 s, 10 s, ... 60 s; calls in between fail in words); a child that
  ignores TERM is torn down outside the registry lock, so the other servers keep answering.
  The generated hosted-MCP configuration (and the benchmark) carries a domain-separated HMAC
  bearer accepted only on `/mcp/*`; a management bearer still works there. This keeps management
  authority out of generated MCP headers, NOT out of the head process: a head still receives
  `ANTHROPIC_AUTH_TOKEN`, which a passthrough child may inherit, and daemon-hosted children
  inherit the daemon's environment, so no environment-isolation claim is made. Only list-change
  notifications coalesce; an overflowed backlog of anything else invalidates the MCP session and
  requires reinitialization on its next request, sessions without an open GET stream included,
  and a closed overflow pump releases its stream accounting immediately. A malformed config entry
  (a non-string transport type, a non-string member in `args` or `env`) passes through whole with
  the reason, never hosted with a different launch tuple; the respawn backoff exponent is clamped;
  every child's shutdown shares one 2 s budget behind a stopped-host barrier; a child's stderr is
  drained bounded and only its presence is logged. Benchmark measurement failures are scoped to
  demonstrated workload lineage, not unrelated system processes, and a benchmark run bounds its
  client waits, cleans its owned children on every exit and refuses a receipt without the jar hash.
- **Local models are first-class on the `openai-chat` dialect.** A provider on a loopback
  `base_url` is local by default (`local = true|false` overrides). At boot and in doctor splice asks
  the runtime what it serves (Ollama `/api/version`, `/v1/models`, `/api/show`, `/api/ps`; LM Studio
  `/api/v0/models`; vLLM `max_model_len`) and REFUSES a row the runtime does not list or that
  declares more context than the runtime serves, with the runtime's own words; a runtime that is
  down boots as before, and so does one whose model list does not answer yet (a list call that
  fails is not a runtime that lists nothing: no row is refused for it, doctor shows one WARN).
  The boot probe is bounded (2 s to connect, 5 s per request, `/api/show` only for the configured
  rows), so a wedged local runtime cannot hold the daemon's other heads hostage. The rows checked are each head's effective ones (a head `context_window`
  override applied, picker suffixes stripped) and the probe carries the provider's headers and
  bearer. `splice doctor --live` adds one tiny streamed request with one tool per listed model.
  Status and doctor label these heads `local runtime` and never imply subscription or quota
  semantics. Proven live against Ollama 0.30.5 and LM Studio (llmster 0.0.24), one model each
  (`checks/local-models/`); vLLM documented.
  A row named without its tag matches the runtime's `:latest` listing (Ollama lists `qwen3:latest`
  and serves `qwen3`), and a generic OpenAI-compatible server's model list (a proxy's aliases, a
  llama-server file path, listing turned off) is not authoritative, so an unlisted row there is
  trusted rather than refusing the head at boot; a refusal now names `local = false` as the opt-out.
  The live probe proves a tool call by shape (a `choices[0]` delta or message whose `tool_calls`
  names `ping`), never by marker strings an error chunk could carry, and a non-200 reply is
  described by its status class and size only, its body never printed into the doctor.
- **Version-drift warning.** `Versions.kt` records the Claude Code version the fresh-machine e2e
  ran against; the daemon reads the client version from the `User-Agent` already on every request,
  and when a session's Claude Code is newer than that, doctor, `splice status` and the status line
  say so once. Equal or older is silent; no scheduled job, no live probe.
- **`claude-muse`: a head on a Meta Muse Code subscription.** `splice login claude-muse` runs the
  RFC 8628 device flow against auth.meta.com (client id 1031625952748946) and writes
  `~/.config/splice/auth/muse.json`, then mints the inference key with one
  `POST https://api.meta.ai/muse-code/key`. The account token is not itself an inference
  credential, so the mint runs at the end of the login and the first turn never waits on it; a
  mint that fails leaves a signed-in file behind instead of failing the login, and the next
  refresh mints. The head speaks Anthropic Messages at `https://api.meta.ai/v1/messages` through
  the anthropic-passthrough dialect and sends the minted key as a bearer plus splice's own user
  agent and nothing else: the `x-api-version` header every other harness sends is required
  nowhere on this wire, which a capture of the real Muse client through a reverse proxy settled
  on 2026-09-15. `splice add muse` writes the provider and head tables with `muse-spark-1.3[1m]`
  and `muse-spark-1.2[1m]` at a 1,000,000 window, and the example config carries
  `[heads.claude-muse]`. Like every other OAuth head it owns its credential file, never the Muse
  CLI's, and that file is merged rather than rewritten, so a key minted at runtime cannot drop the
  fields the login wrote. A rate-limited mint is held for at least a minute instead of retried, a
  mint still in flight when the daemon stops writes nothing afterwards, and a re-login while the
  usage poller is minting cannot be mistaken for a rejected credential.
- **Muse subscription usage on the status line and in doctor.** Meta publishes no usage endpoint
  (`/muse-code/usage`, `/subscription`, `/quota`, `/entitlements`, `/me` and `/account` all
  answer 404), so the allowance rides in the mint response as `subs_usage`: the five-hour window
  and the weekly window come from a poll on the quota poller's own cadence, never from the
  request path, and a rate-limited mint is held rather than retried.

### Changed
  The tracker remembers at most 4096 sessions and forgets the oldest first, so a daemon that
  lives for months never grows on session ids.
  Its tests render the warning from `GATEWAY_VERSION`, so the version bump does not turn them red.
- **Code mode is out of beta and on by default for ChatGPT.** A `chatgpt-oauth` +
  `openai-responses` provider gets the bundled JavaScript runner and orchestration guidance with
  no config line; `code_mode = false` still turns it off, and every other provider shape stays off.
  A single tool result over the 64 KiB text frame that admission used to reject is now truncated
  at admission behind a `[truncated N chars]` marker and the turn completes.
- **Errors the client sees name splice, not another vendor's product.** A stream that ends without
  its completion event, a refused or failed upstream response and a context-overflow refusal used
  to reach Claude Code prefixed `claudex:` or attributed to the `ChatGPT backend`, on every head
  including the ones that have nothing to do with ChatGPT. They now read `splice:` and `upstream:`.
  The proxy-hardening oracle carries the new bytes with dated authority lines; the recordings
  themselves are untouched.
- **Each head owns its code path.** The vendor tables that had accumulated in the shared dialects
  moved into the module that owns the vendor: kimi's quirk profile into provider-kimi, grok's
  profile, its xhigh model regex and the enforced xAI image-edge floor into provider-grok, the
  effort tables behind a seam in provider-spi, and the local-runtime probe out of the chat dialect
  entirely. The passthrough, responses and chat dialects now know no vendor, and a new head is a
  new provider module plus one dispatch line rather than an edit inside a shared file. The wire is
  unchanged for every existing head: the codex lite header keeps its single emission site and its
  model gate, codex effort normalisation and budget floors are identical input by input, and
  grok's and kimi's tables are byte-identical.

- **Code-mode guidance rewritten as a rule with an example.** It states the cost it avoids (every
  tool call re-sends the conversation), the trigger (two or more calls with known arguments), the
  failure contract (`Promise.allSettled`, catch), and carries a worked cell; the hedges are gone. The
  `splice_exec` tool description carries the trigger too. A client that disables parallel tool use
  gets the sequential variant from its own resource file.
- **`parallel_tool_calls = true` is documented as refused.** Tried live on 2026-09-20 against the
  ChatGPT lite backend: every turn answered 400 `X-OpenAI-Internal-Codex-Responses-Lite requires
  parallel_tool_calls to be false`. The knob stays for other Responses backends; on lite turns the
  only batching is the code-mode runner (a two-Read probe on `gpt-5.6-sol` ran in one round trip).
- **Repository layout consolidated.** `docs/` is now `.docs/`. The `experiments/` cache-replay
  reproducer, the `goals/` note and the `.superpowers/` leftovers are gone; the one tracked
  milestone report now sits under `.dev/campaigns/head-decoupling/`, and the untracked `dev/`
  tree is folded into `.dev/`. Local machine paths are gone from the tracked ledgers and plans.
- **The shipped code compiles warning-free, and a new warning fails the build.** Every main
  source set compiles with `allWarningsAsErrors`, and a discarded result the compiler flags
  (`RETURN_VALUE_NOT_USED`) is an error in tests too.

### Fixed
- **Any head joins any session again (V4-168).** A session started on one head resumed on any
  other because every head's `projects` tree was the shared `~/.claude/projects` (V4-64). The
  2026-09-17 head-isolation change read the ruling — head configuration must never leak — as
  covering transcripts too, gave every head a private tree, and left cross-head resume only as an
  explicit `-r SESSION_ID` copy, while the operator's `splice.toml` still asked for `projects` in
  its share list. Transcripts are shared session state, not head configuration: a head whose
  policy shares `projects` has its private tree merged into the vanilla one (the same files, never
  copies, so a live session keeps appending) and linked, so the `-r` picker on every head lists
  every session again. A head that isolates `projects` keeps a private tree and reaches a foreign
  session by name, as before.
- **A resumed session follows the resuming head's model (V4-169).** A transcript carries the model
  id of the head that wrote it, and Claude Code refuses to restore a model the head does not serve
  ("Session model X could not be restored"). Splice now moves the transcript's assistant rows that
  sit on a model the resuming head does not serve onto its model, where they lie; a row on a model
  the head serves is left byte-identical. This happens at launch for `-r SESSION_ID`, and for the
  `-r` picker and `-c` through a SessionStart hook (matcher `resume`) each head's `settings.json`
  now carries, which tells the daemon which session was chosen. The hook authenticates with the
  turn key from `<state>/turn-auth-header`, never from the session's environment, may only touch
  transcripts under that head's tree, and never blocks a session: every refusal answers 200 and
  lands one line in the daemon log.
- **A third system prompt mode, `strip`, edits the client's system field in place (V4-170, V4-171).**
  A head or a project layer can now delete paragraphs from the client's own system field instead
  of replacing it: the layer's text is a pattern list (one regex per line, `#` comments), and
  every blank-line-separated paragraph of the client's system text that a pattern matches is
  removed at the wire, on every dialect. Everything else — the other paragraphs, the block order,
  the `cache_control` breakpoints — rides through byte-identical, so the prompt cache still warms
  from turn two, which `replace` could not offer. A pattern that is not a regex is a config error
  at load. Nothing is stripped unless an operator writes a strip layer, splice ships no pattern
  list, and `splice doctor` WARNs on every strip layer as it does on replace: the client's
  instructions are being edited, and what a pattern removes is the operator's to own.
- **The full request/response trace, opt-in per head (V4-174).** What Portkey and LiteLLM do:
  `[heads.KEY.overrides] trace = true` makes that head write everything it does to
  `<state>/trace/KEY-YYYY-MM-DD.jsonl` — every request it receives (method, path, headers, exact
  body), every upstream attempt as it actually left (the body byte for byte, the headers with every
  credential-class value redacted, the status and headers that came back or the transport failure
  that ended it, the raw response text), every frame it streamed back, the outcome, the round and
  attempt counts and the perf marks. The trace sits INSIDE the retry loop, so a backoff retry, a
  refresh's free retry, a torn-stream reissue and an amended resend are each their own record; a
  WebSocket round is one too. `splice trace <head>` reads the files with no daemon (a table per
  turn; `--turn ID` for one turn in full; `--session`, `--last`, `--json`; `--purge` deletes them
  and says what went). Off by default, opt-in per head for the reason V4-173 gives; the directory
  is owner-only, whole day files older than `traceRetentionDays` (7) are deleted, a body past
  `traceMaxBodyChars` is cut and flagged, and `splice doctor` warns on every run naming the head,
  the directory, the retention and the purge verb while it is on. Nothing is written for a head
  that did not opt in.
- **See what splice sent upstream, once you ask it to keep it (V4-173).** A proxy that cannot show
  the request it sent cannot be audited — and nothing kept one: the perf row records how many bytes
  went upstream, never which. `[heads.KEY.overrides] wireTap = N` now makes that head keep its last
  N upstream request bodies, in memory only, and `splice wire <head>` prints them exactly as they
  left (every round's — a folded or re-anchored turn is several requests). It is off by default and
  opt-in on purpose: a body carries the whole conversation it was sent for, so no head keeps one
  unless its operator named a count, nothing is ever written to disk, a restart forgets them, the
  route on the head's port answers only to the management key (a client-auth head's own callers
  cannot read it), and `splice doctor` warns on every run naming the head while it is on.
- **The strip mode's own review repairs (V4-172).** Two adversarial reviews of the above found, and
  this fixes: paragraphs are now split on a blank line in ANY line ending (CRLF text was one
  paragraph, so a single matching pattern deleted the whole system field, and a gap of two blank
  lines silently unhooked every `^`-anchored pattern); the responses dialect strips the client's
  `instructions` AND every base developer item, instead of only the first item — which was the shape
  splice's own append layer creates, so an append-then-strip fold edited splice's text and left the
  client's untouched; the turn's perf row now carries `system_prompt_layers` and
  `system_prompt_applied`, because nothing in production read the "(not applied)" marker and a
  pattern gone stale on a client upgrade was therefore invisible; a layer whose text a later strip
  deleted is no longer reported as carried; `splice doctor` gives a strip layer its own remedy
  (following the replace remedy would have shipped the regex list upstream as prompt text) and warns
  when strip is set with no pattern list at all; an empty pattern file is a load error rather than a
  layer that strips nothing forever; a `system` sent as a bare string stays a bare string; and each
  pattern list is compiled once instead of on every turn at every seam.
- **Slot affinity follows its server, costs a turn nothing, and says when it is off (V4-166).** The slot count is re-read from `/props` in the background every 10 s instead of once: a llama-server restarted with a different `-np` silently wraps an out-of-range `id_slot` onto a slot another conversation holds, and splice kept pinning to the old count. A turn no longer waits on that read (it ran on the request path, holding a process-wide permit, for up to the probe timeouts against an unreachable server). Every head on one runtime now shares one slot table, which also survives a config reload, so two heads, or a reload with turns in flight, can no longer pin two conversations to one slot. A runtime that gives no slot count (router mode, a non-llama server, a keyed `/props`) is logged once with its reason instead of leaving slot affinity off without a word, and `splice doctor` lists `slot_affinity` and `stream_usage`. A compaction retry routed to a different slot now still matches its recording: the replay key leaves out `id_slot`, which routes a request and is no part of it.
- **Hosted MCP servers can be reclaimed, and are capped when the host declares a place for them (V4-147).** A child spawned by splice inherited splice's own `oom_score_adj` of -1000, so the ~14 GB of MCP servers this host is meant to gather would have become memory no out-of-memory killer was allowed to touch, in a cgroup with no ceiling. Each hosted child is now raised off that protection at spawn — written to the child's pid alone, splice's own never — and the value is read back from the child rather than assumed. Placement follows the same rule: a child runs inside the memory-capped slice when one is declared for it, and when none is (systemd will happily name a slice nobody defined, with no ceiling at all) splice says so once in the log instead of reporting containment it does not have.
- **A hosted MCP server keeps working across a daemon restart, an idle reap and an eviction (V4-148).** When splice did not recognise a client's `Mcp-Session-Id` it answered the spec's "session not found", which means reinitialize — and Claude Code does not reinitialize: its tools for that server silently stop working for the rest of the session, `/mcp` still shows the server connected, `/mcp reconnect` does not recover it, and only a full relaunch does. Splice writes the value that triggers it, so an id it does not know is now adopted: the child is initialized under the id the client already holds, and the restart is invisible. A session the client explicitly ended still answers 404, and so does one whose notification stream overflowed, because that one must reinitialize to re-list what it missed. An adopted session keeps speaking the protocol version the client negotiated before the restart, since refusing it would only trade the 404 for a 400.
- **A connection that failed is named for what actually failed (V4-167).** The JDK client wraps every connect failure in the same `ConnectException`, so a host that does not resolve and a host with no route were both told as `connection refused … nothing is listening there` and sent the operator to start a server; each is now named from the link that says what happened (`cannot resolve the host of …`, `could not connect to …: No route to host`), and only the client's real refusal is called one. A connect timeout's detail no longer carries the request's full URL, and the per-attempt retry lines in the log use the same words as the ending instead of an empty message. A 429 whose text mentions tokens (a per-minute token quota) is a rate limit again rather than an overflow: it takes the cooldown, and Claude Code is no longer told to compact a conversation that fits. On the OpenAI chat dialect, an in-band error's own numeric code is no longer read as an HTTP status, and an error the vendor typed or gave a status, which the same bytes reproduce, is no longer advertised to Claude Code as retryable; an in-band error with neither keeps the retryable wire it had.
- **A local model server's errors say what happened, and an in-band chat error keeps its class (V4-164).** llama-server's three failures reached Claude Code as the bare text it sent, and the banner printed "API error" over them: a 503 `Loading model` now reads as the local server still loading its weights; `exceed_context_size_error` becomes the `prompt is too long: N tokens > M maximum` line Claude Code compacts on (its wording and code were invisible to the overflow rule, so a conversation that only needed compacting ended); and the mid-decode `Context size has been exceeded.` is named as the shared KV pool being full — retried, never reported as an overflow, because the request fits and the other conversations hold the pool. The OpenAI chat dialect's in-band `error` event now goes through the same classifier as every other path instead of being called `UPSTREAM_REPORTED` whatever it said, so an in-band overflow compacts and an in-band rate limit is a rate limit; an event with no recognisable shape keeps the wire it had. A failed connection names what happened and where instead of `upstream connection failed (no detail)`: the JDK client's refused connect carries no message, so a local server that was simply not running read as nothing at all; it now reads `connection refused by 127.0.0.1:8099 — nothing is listening there; the server is down or still starting`, and connect timeouts, DNS failures, read timeouts, TLS failures, resets and early closes are each named with the endpoint's host and port (never its path or query). A context overflow is also sent upstream once rather than retried: the same bytes overflow the same window, and every re-send only delayed the compaction that fixes it (measured on the bonsai head: ten 1.4 MB re-sends over 43 s).
- **Only failures a retry can heal are advertised as retryable, and the wire-type rule can no longer be skipped (V4-78, V4-79, V4-81).** A failure arriving before any content now reaches Claude Code as the one in-band error it retries (`overloaded_error`) instead of a terminal `api_error` it reads as the end of the session — and an ending cannot forget that rule, because it lives at the single place an error frame is written rather than at each of the four surfaces that used to restate it. A failure that an identical re-send reproduces exactly (a model refusal, a content-filtered turn, a vendor `invalid_parameter`, an unparseable base URL) is exempt and keeps its real type: it is not a retry but a bill, up to 300 client re-sends at six upstream attempts each. A buffered (`stream:false`) failure is untouched by the rule and keeps its real status — a 429 stays 429 with its rate-limit headers, an api_error stays 502.
- **No error class can stall a session on a rate limit or a spent account any more (V4-71, V4-72, V4-73).** The first turn to meet a persistent 429 now reaches Claude Code as the one in-band error it retries (`overloaded_error`, rate-limit words kept) instead of a terminal `rate_limit_error`; every launched client runs in persistent retry mode (`CLAUDE_CODE_RETRY_WATCHDOG=1`, native head included) and sleeps until the reset splice already sends; and a spent account (grok `spending-limit` 403, any 402) is a 429 to every layer — cooldown, account pool, classifier and admission — rather than an `invalid_request_error`.
- **An empty model turn is retried, not re-sent identically (V4-42).** A 200 with no content blocks (muse reasoning its whole budget away) now ends as `overloaded_error` with the honest words kept, so the client backs off and retries instead of stalling. The kimi failure-text golden now freezes the wire type and provider-tagged message rather than an internal data-class rendering (V4-69).

- **The kimi usage probe never ran.** The shared bearer probe required `Credentials.Bearer`
  while the kimi provider yields `Credentials.ApiKey` with an `x-api-key` header, so the poller
  recorded no window for a kimi head at all. The probe now sends whatever headers the credential
  carries, and each head's probe is pinned by a test that asserts the exact header map.
- **Every codex and kimi quota probe carried xAI's headers.** `x-grok-client-mode`,
  `x-grok-client-version` and `X-XAI-Token-Auth` were built into the shared probe, so they rode
  on requests to ChatGPT and to Moonshot as well. Each head now owns its probe, its URL and its
  parser, and the shared part is vendor-blind by construction; the codex header order is back to
  the one proven against the live backend.
- **A malformed reset date killed the quota poller.** A vendor window whose `resets_at` did not
  parse threw past the poller's own error handling and ended the loop for the daemon's lifetime,
  with no bar and no log after it. The date parse is caught per vendor, and the poll loop carries
  the same completion guard the auth probe loop uses: it restarts up to five times in ten minutes
  and says so.
- **Api-key responses heads sent ChatGPT's internal lite marker, and the whole lite request body
  with it.** The responses-lite model regex defaulted to `gpt-5.6|gpt-6` on the shared dialect, so
  an OpenRouter head pinned to `openai/gpt-6` or `openai/gpt-6-mini` (or any id containing those substrings) emitted
  `x-openai-internal-codex-responses-lite` on every turn, compaction included, and built the
  ChatGPT-internal lite input: tools as an `additional_tools` item, empty top-level `instructions`,
  plus `parallel_tool_calls`, `reasoning.context` `all_turns`, `text.verbosity` and
  `client_metadata`. Grok heads were never affected in practice — their ids are `grok-4.5` and
  `grok-4.6`, which do not contain those substrings — but they inherited the same default and
  would have the day a grok id matched. The regex and the header name are now a pair a provider
  must declare together; only the ChatGPT/codex profile sets them. A third-party endpoint no
  longer inherits the marker or the lite body. After upgrading, an OpenRouter head on
  `openai/gpt-6` sends a non-lite request: `instructions` at top level, tools not in
  `additional_tools`, and those extra fields dropped.
- **An api-key head on a Gemini id silently refused effort max, and any id containing spark lost
  its reasoning summaries.** Pinning `google/gemini-2.5-pro` (or any model id containing `mini`)
  made `effort` `max` clamp without a word; pinning a spark-named model dropped reasoning
  summaries. Both shipped in 0.3.0 and are present in released v0.3.2. The clamp and the drop now
  apply only to the vendor they were written for.
- **Code mode now reaches Sol.** Eligibility matched `gpt-6-(astra|sol)`, a Sol id the catalog never
  had; the real `gpt-5.6-sol` and with it every sonnet/haiku-tiered subagent ran without the runner
  (measured 2026-09-20: advertised on 938 of 7,070 calls in one session). The default list is now
  `gpt-6-astra`, `gpt-6-sol`, `gpt-5.6-sol`, and `code_mode_models` in the provider quirks replaces it.
- **A failed cell says why.** The JavaScript harness dropped the rejection reason, so a rejected tool
  call, a syntax error or an oversized `console.log` all surfaced as a bare `Code execution failed`
  and the model reran every call directly. The error now carries the reason (a SyntaxError keeps only
  its position line, never the source) and the output logged before the failure; output past the
  64 KiB text ceiling is cut behind a `[truncated N chars]` marker instead of failing the cell.
- **An unplaceable record is abandoned once.** A running script whose history no longer placed was
  marked LOST but stayed findable by its client call ids, so every later turn of the conversation
  found it, failed to place it and logged `abandoned record` again (82 lines for one record on
  2026-09-20). It now retires with its interruption evidence on the first abandonment.

## splice v0.3.2 — code mode keeps its workers and its evidence, and fails in words - 2026-09-07

### Fixed
- **Code mode no longer runs out of workers behind clients that never came back.** A script whose
  client calls were never answered (the session was abandoned, compacted or killed) kept its worker
  slot indefinitely; with four such cells parked, every new script on every session failed with
  `code-mode runtime failed to start` for the rest of the day. A cell parked longer than 30 minutes
  without results is now closed, and at capacity the oldest cell parked over 2 minutes is evicted
  for the newer script. Both are recorded on the record and logged once under `[<head>][code-mode]`.
- **A lost script's evidence is never too big to report.** The interruption output (results so
  far, unresolved calls, the reason) rides upstream as the outer call's own output, but it was graded
  against the worker's 64 KiB text frame, which it never crosses: any script whose accumulated
  results passed 64 KiB (five `Read`s) poisoned its record, and the same request then failed
  identically on every retry (47 turns over 80 minutes on 2026-09-07). Evidence is now bounded by
  the upstream output ceiling only, and past that each result is cut to an equal share behind a
  `[truncated N chars]` marker instead of the turn failing.
- **A code-mode failure is now readable in Claude Code.** Every gateway failure went out as an
  SSE `error` event. Claude Code 2.1.x re-sends an `api_error` identically until it gives up when
  the event arrives before content, and after content drops the message for a fixed "API Error:
  Server error mid-response" line, so a code-mode verdict that no retry can change (a record it
  cannot resume, a script it cannot admit) surfaced as a retry storm or an unreadable line. Such
  failures now end the turn with the explanation as a `\u26A0 splice:` text block and a clean stop;
  transient upstream faults keep the error event the client is right to retry.
- **Capacity and lost cells report to the model instead of failing the turn.** When no slot can be
  freed, the script's own output tells the model nothing was executed and to call the tools
  directly, and the turn continues. A lost cell retried with the same request likewise completes
  with its evidence (results so far, unresolved calls, the reason) and goes upstream once, rather
  than answering 502 until new user content arrived. The spawn failure's cause is now logged; it was
  swallowed before, which is why the pool being full went undiagnosed for an hour.

## splice v0.3.1 — silent-stream reliability and the code-mode beta - 2026-09-06

### Install

Install this exact version (requires an authenticated GitHub CLI; run `gh auth login` once):

```bash
curl -fsSL https://github.com/torad-labs/splice/releases/download/v0.3.1/install.sh \
  | env SPLICE_VERSION=v0.3.1 bash
```

### Fixed
- **Code mode no longer refuses a conversation whose environment moved.** A completed script's
  history baseline is now measured on the conversation alone; the lite preamble (the eager tool
  list and the base instructions) is excluded. Claude Code grows its tool list mid-conversation
  (ToolSearch loading a deferred schema, an MCP reconnect), and one such growth after a completed
  script made every later Astra/Sol turn fail with `code-mode logical history does not match its
  persisted baseline` until the session compacted. The same growth during a script's own resume
  turn was misread as new user content and interrupted the script.
- **History that cannot be placed degrades instead of failing.** A record that no longer lines up,
  a record past its 24-hour retention, a result from another session or model, or a running script
  whose history moved underneath it now continues upstream on the client's own history, where its
  client calls are ordinary tool calls. Each degradation logs once per conversation under
  `[<head>][code-mode]`. Persisted records from 0.3.1 carry the old measurement and are omitted the
  same way, so an already-stuck conversation recovers on its next turn.

### Added
- **Default-off JavaScript code mode for ChatGPT providers.** Set `code_mode = true` in a
  `chatgpt-oauth` + `openai-responses` provider's quirks to enable the bundled GraalJS runner and
  orchestration guidance together on eligible GPT-6 Astra/Sol turns. Custom head names work;
  compaction, toolless turns and forced named-tool choices retain the ordinary path. No Node or
  Codex installation is required. Real operations remain Claude Code's permission-checked client
  tool calls; JavaScript has no direct shell, filesystem, network or MCP access.
- **Bounded execution with honest recovery.** Worker, time, heap and wire limits bound each cell.
  Durable ownership and history retain completed evidence, but a lost worker's JavaScript is never
  rerun automatically. New user content interrupts before further execution, infrastructure faults
  remain failures, and cancellation retains only known completed-round usage. Graal community is
  not an OS-hardened sandbox against same-user attackers.
- **An explicit beta switch, not a silent default change.** False or omitted disables both the
  runner and its guidance. The provider setting applies to every head using it and is read at
  daemon boot: finish pending work before changing TOML, then perform a full `splice restart`.
  A head restart alone does not reload topology. Bounded synthetic comparisons support the beta;
  they do not establish general output-quality or efficiency gains.

### Fixed
- **Silent streaming turns send real SSE ping events.** Keepalive traffic is recognizable to
  Claude Code during long upstream silences. Optional, default-on `progressLine` messages identify
  themselves as splice-authored status, not model reasoning. Every streaming head uses the same
  mechanism; disabling progress lines does not disable pings. The switch requires a restart.
- **Progress respects the stream lifecycle and accounting.** Separate progress and model writers
  share block indexes without letting a blocked model write silence keepalives. Progress waits for
  the published opener, stops at terminal sealing, and never counts as model output or first-delta
  timing. Rejected pre-opener writes do not consume the introductory status line.

### Changed
- **Every OAuth head signs in on its own credential file.** `chatgpt-oauth`, `grok-oauth` and
  `kimi-oauth` now default to `~/.config/splice/auth/{codex,grok,kimi}.json` (kimi's `device_id`
  beside it), written by `splice login <head>` on whichever account you choose there, which may
  differ from the account the vendor's own CLI or desktop app uses. The apps' files
  (`~/.codex/auth.json`, `~/.grok/auth.json`, `~/.kimi/credentials/kimi-code.json`) are never read
  unless `auth.file` names one. Sharing a file was a trap: a refresh rotates the refresh token, so the
  app and splice signed each other out, and the head had no credential while the other side rewrote
  the file (24 failed turns in one 16-second rotation on 2026-09-05). Existing configs that name an
  app's file keep working; `splice doctor` now warns about them, and the fix is to drop `file` and run
  `splice login <head>`. The `CODEX_AUTH_PATH` / `GROK_AUTH_PATH` overrides still apply.

### Fixed
- **A compaction outlives its client.** Claude Code abandons an auto-compaction at 600 s and
  retries the same bytes minutes later, and every abort used to cancel the upstream turn (Astra
  compactions run 5-10 minutes; 7 were cut off this way on 2026-09-05). A compact stream turn now
  runs on a scope the call's cancellation cannot reach and records its frames: a lost client
  detaches, the turn finishes, and the byte-identical retry is served from the recording (or
  follows the turn live if it is still running), with no second upstream turn. A head stop ends
  the compactions still driving; the scope itself survives a restart (review: the first cut
  cancelled it, and the first compaction after a restart came back empty with its slot leaked).
- **Claude Code's activity-label side query is answered locally.** Every 30 s during a subagent
  turn the client re-sends the whole transcript asking for a 3-5 word present-tense label
  (294M input tokens in a day at 48% cache hit, 2026-09-05). The head recognises the query and
  answers it from the transcript's last tool call, with no upstream turn.
- **codex-rs's session and routing headers ride every turn** (`session-id`, `thread-id`,
  `x-codex-routing-hint`, and the WebSocket handshake's `x-client-request-id`), so a reconnect
  can land on the same backend replica and its prompt cache.
- **A session's learned client window survives a daemon restart** (`<head>-client-windows.json`
  in the state dir), so the first turn after a restart is scaled against the right window instead
  of reported raw. A rejected request body is now logged with its byte counts and failure class.
- **An auto-compaction no longer re-reads the whole transcript cold.** Claude Code compacts
  between a tool call and its execution, so the compaction body ends at the previous tool result
  and the call the backend just emitted is never answered. Chained over the WebSocket, the backend
  refused every such turn ("No tool output found for function call …", 4 of 4 on 2026-09-05) and
  the round fell back to a cold SSE send with the prefix cache lost (0-37% hit on 200k-token
  compactions, 5-10 minutes each). The chaining state now remembers the calls a response left
  open, a turn that answers none of them full-sends on the same socket instead (prefix cache
  kept), and the fallback line names the backend's failure terminal (type, code, message) so the
  next refusal of this kind is diagnosable from `daemon.log` alone.
- **A compaction is built byte-identical to a turn, so it hits the prompt cache.** Every dialect
  used to reshape the compaction request (a directive appended to the instructions or system,
  tools and `tool_choice` stripped, tool results folded to text, images dropped, the lite shape
  off, cached reasoning left out, an effort pin), and the backend's exact-prefix cache missed the
  whole transcript on every compaction (`cached_tokens=0` on every model, 2026-09-05). The request
  now carries the session's model, reasoning, tools and history unchanged; `compact` only reaches
  the response side. The `compact_effort` quirk is retired and a config that sets it fails at load.
- **A running session's counts are scaled against ITS window, never a guessed one.** Its process
  divides by the window it was launched with, so the head learns each session's real window from
  its status-line post (`session_id` + `context_window_size`) and scales that session's counts
  against it. A session that has not posted yet is assumed on the pinned row's current window,
  i.e. exact. For one morning on 2026-09-05 every launch planted a constant 1e6 instead, and the
  sessions launched before it were scaled 2.5-3.7x against a window their process never had:
  each compacted at a third of its row's window, forever (eight compactions in forty minutes on
  one session, every one immediately re-triggered). Live, no relaunch.

### Changed
- **A context window edited in `splice.toml` now reaches running sessions.** A launch plants the
  pinned row's window (`CLAUDE_CODE_MAX_CONTEXT_TOKENS`), every other row is usage-scaled on the
  wire, and a session keeps compacting at the row's CURRENT window after an edit because its
  counts are scaled against the window it reports on its status line. Before, lowering the pinned
  row's number (the 2026-09-05 move of the codex rows to 272k, under OpenAI's 2x long-context
  price line) changed nothing for the six sessions already running until each was relaunched. Now
  `splice restart` is enough. Ids starting with `claude-` (a passthrough head's own models, a
  discovery-wrapped tier) ignore that env in Claude Code and keep reporting raw counts.

## splice v0.3.0 — plan usage on every head, GPT-6 Astra on claudex, and a proxy that matches its reference client - 2026-09-04

### Added
- **GPT-6 Astra is a row on `claudex`.** OpenAI shipped GPT-6 Astra on 2026-09-03 and it is
  included in the ChatGPT plan allowance. The example config carries it as `gpt-6-astra` (label
  "Codex 6 Astra") and plants it on the head's fable tier, so `/model` lists it beside the 5.6 rows
  and the default pin is untouched. Codex's own catalog serves it responses-lite with efforts up
  to `max`, so the lite gate now matches the gpt-6 family as well as gpt-5.6.
- **Every head shows its plan usage the way the native Claude head does.** The daemon tracks each
  head's 5-hour and 7-day windows (ChatGPT's usage endpoint and its `x-codex-*` round headers,
  Kimi's usage endpoint, SuperGrok's billing period, Anthropic's own unified headers on the
  passthrough head), stamps them onto every response as the `anthropic-ratelimit-unified-*`
  headers Claude Code reads into its `rate_limits`, and draws them on the status line as the 5h
  and 7d bars with the reset time once a bar is worth acting on, beside effort and session spend.

- **The plan-usage poll is stated plainly, and it has an off switch.** To draw those bars before
  the first turn, a subscription head asks its own provider every five minutes for the daemon's
  life, with that head's own bearer: `claudex` reads `chatgpt.com/backend-api/wham/usage`,
  `claude-kimi` reads `<kimi base>/v1/usages`, and `claude-grok` reads
  `cli-chat-proxy.grok.com/v1/billing`. API-key and client-auth heads poll nothing. This is new
  outbound traffic since the beta, so it is named here rather than left implicit;
  `CLAUDEX_QUOTA_POLL=off` stops every poller (daemon restart), and the bars then draw only from
  the rate-limit headers each round already carries.

- **Heads that see each other.** Every wrapper's `sessions` directory is linked at the one registry
  under `~/.claude/sessions` on first launch (created if plain `claude` never ran on the machine), so
  claudex, claude-grok, claude-kimi, claude-openrouter, claude-splice and plain `claude` sessions all
  appear in each other's `ListAgents` and can message each other with `SendMessage`: a session on one
  backend can orchestrate a session on another. On by default through `[claude].share`;
  `isolate = ["sessions"]` walls a head off. (Shipped in this release without a changelog entry;
  recorded here.)

### Changed

- **The shipped example config now matches the daily-driven one.** `claudex` ships with the
  Responses WebSocket transport, zstd request bodies and the deferred tool surface (LSP deferred)
  on, plus the inflight ceiling that account has sustained; `claude-kimi` offers Kimi K3 at 256k and
  Kimi K2.7 Code beside the 1M row; `claude-openrouter` offers GLM 5.3 beside Llama 4 Maverick.
- **Every perf row and every client-abort line names the client session.** The daemon now stamps
  the Claude Code session id on every dialect's turn (only the Responses dialect kept it before),
  writes its short tag into the perf JSONL and the perf log line, and the "client gone" line names
  the session and the failure class instead of `keepalive write failed: null`. A client abort in
  the log is now one grep away from the session that hung up and the transcript that says why.

- **A WebSocket end of stream reports what was observed instead of naming a culprit.** The
  transport logged "socket closed by the server (status=1006)" twelve times a day without saying
  which socket. Status 1006 is reserved by the WebSocket RFC and can never be sent by a peer. Our
  own client synthesises it whenever the stream ends with no close frame, so the origin, a load
  balancer and the network are indistinguishable from here, and the old wording asserted an actor
  the client cannot see. The line now says the stream ended with the actor unknown, and carries the
  socket, its age, whether a round was in flight, the last frame, the last peer ping and the open
  socket count. A real close frame still names the peer, its code and its reason. Read that way,
  six of one day's twelve were sockets left idle in our own pool for three to twenty-five minutes,
  and eleven of the twelve ended no round at all.

- **Every daemon log line now carries its own date, at a fixed width.** `daemon.log` rotates by
  size and never by day, so a single file spans as many days as 64MB buys — 265,321 lines over four
  of them in the 2026-09-02 audit — while each line was stamped with a bare wall clock. A line read
  on its own could not say which day it belonged to, and a reader attributed a full day of watchdog
  stalls to the build running the next morning; they were the previous day's, on code already
  replaced. The same audit found the stamp was not even fixed-width: `LocalTime.toString()` drops
  the seconds field when it is zero, which had stamped 4,339 live lines `[13:47]` and quietly broke
  column-oriented reads. Lines now read `[2026-09-02 13:47:00]`.

- **A watchdog-ended turn says which tier fired, the limit it held, and the silence it measured.**
  The stall message printed the configured idle cap whatever tier had actually tripped, so a
  compaction killed at its first-output tier read as a mid-output stall and the log could not break
  the tie. The turn line now carries the fired sentinel's own three numbers
  (`watchdog=idle(tier=… limit=… idle=…)`); a turn the watchdog did not end prints byte-identically
  to before.

- **The mid-stream stall detector now matches the reference client instead of guessing tighter.**
  `streamIdleMs` judged a stream that had already begun flowing and aborted it after 180s of
  silence. codex-rs sets its only stream timer to 300s and applies it to the receive side alone
  (`DEFAULT_STREAM_IDLE_TIMEOUT_MS`, model-provider-info/src/lib.rs:26). Against the same backend
  our tighter number ended 129 compactions in one day, each already mid-output and each costing a
  full transcript re-read. The default is now 300s, equal to `firstByteTimeoutMs`, so one number
  judges a stream before and after its first frame. Heads that want a tighter stall still set it.

- **The socket gets five attempts before the turn falls back, matching the reference client.**
  A retryable mid-stream failure re-anchored at most twice before the turn dropped to the SSE
  transport. SSE is a fallback, not a co-equal second path: reaching it discards the socket's
  cache key and re-uploads the whole body, which on today's traffic means a median 618KB and a
  p99 of 5.2MB sent twice. codex-rs retries a retryable stream five times
  (`DEFAULT_STREAM_MAX_RETRIES`) before it switches transport, and this controller exists to be
  the proxy-side answer to that loop, so the budget is now five. Turn recovery and its cooldown
  backoff are unchanged; only the number of times they may run has widened.


- **The watchdog turn-line tests pin what production does.** A test claimed a compaction's
  first-output tier is lifted to the whole-turn cap; production switches that tier off
  (`WatchdogBudget.forCompact`), so only the total cap ends a silent compaction. The arm now pins a
  non-compact first-output fire and a compact total-cap fire, and two new `TurnFinish` arms fire
  real pollers on virtual ticks and assert the rendered line carries the sentinel through the
  production hop.
- **`CLAUDEX_QUOTA_POLL=off` has an effect test.** `ManagedHeadFactory` exposes a poller-start
  seam; assembling a real ChatGPT subscription head with the knob off starts nothing, with `auto`
  starts one.
- **The stable pin recipe is exercised.** `checks/release/accept.sh` now installs with
  `SPLICE_VERSION` pinned to the jar's own stable tag as well as a synthetic prerelease, and fails
  when `install.sh` ignores the pin.
- **Changelog bookkeeping.** "Heads that see each other" landed after `v0.3.0-beta.1` and now sits
  in the v0.3.0 section, so the beta.1 list matches its tag.

### Fixed
- **An empty answer is an answer, not an API error.** When the model closes a message with no
  text in it, the turn now ends clean, the way codex ends it (only a tool call asks for a
  follow-up there). Found on GPT-6 Astra: after its final answer, a project Stop hook echoed an
  end-of-turn report back as a continuation, Astra correctly had nothing to add, and the empty-turn
  gate graded that `empty_model`, so Claude Code retried the identical 120k to 320k-token request
  eleven times per incident, thirteen times in one evening. A round with no message item at all
  is still the honest error it was, a compaction that returns nothing is still an error, and the
  clean case is tagged `empty_message` in the perf row and names the shape the backend sent
  (`reasoning(summary=0,enc=1700)`, `message(output_text:0)`) in the log.

- **Compactions no longer die on the ChatGPT backend's capacity signal.** An in-stream
  `server_is_overloaded` (or `slow_down`) used to classify as a non-retryable `api_error`, so the
  proxy neither reissued nor salvaged the turn and Claude Code reported the compaction failed. Any
  overload-shaped error code on an in-stream or server-side (5xx) failure is now a transient
  `overloaded_error`, the type Claude Code retries with backoff, matching codex's own handling of
  the same two codes; a 4xx keeps its deterministic verdict whatever its code spells.
- **Claude Code now waits out the proxy's own whole-turn wall.** Every wrapper plants
  `API_TIMEOUT_MS` from the head's `upstreamTimeoutMs` plus a minute of grace (960 s on the
  default 900 s cap). With Claude Code's 600 s default, every compaction longer than ten minutes
  ended as a client abort while the daemon was still streaming the summary.
- **A silent compaction is ended by the whole-turn wall alone.** The compact budget used to raise
  the first-output tier to the wall instead of switching it off, leaving two pollers on one
  deadline; on a loaded runner the idle poller could win the tick and end a round as "first-output
  cap" (salvage invited) where the wall should have ended the turn. The tier is now off for
  compactions; `streamIdle` still reaps a stall once output has begun.
- **A torn perf row no longer swallows the row after it.** When the disk filled mid-append
  (2026-08-25) a short write left a fragment with no newline and the next row fused onto it, so
  readers lost both. The JSONL sink now heals a torn tail before appending.
- **The status line follows the picked model row.** Claude Code fixes its context window per
  process and splice scales the counts it reports so any other row compacts at its own window,
  which left the bar showing the session's window and scaled counts however the operator switched
  (`grok-4.6[500k]` on a 256k grok head still read `…/256k`). The daemon's status line now renders
  the picked row's label, its declared window and the real counts.

- **`splice restart` no longer loses the new daemon to the old one's last second.** The restart
  spawns the new daemon the moment the old one's ports are free, but the old process releases the
  daemon lock only after its engines' stop grace and log drain, up to a second later on a loaded
  box. The new daemon tried the lock once, conceded to "the winner" that was already leaving, and
  exited, so the restart reported "did not come up" with nothing serving. The loser now waits out
  the old daemon's whole teardown floor and yields only to a peer that answers `/health`.

- **A torn compaction restarts inside the proxy instead of failing the turn.** Every other round
  already re-anchored on a stream that ended without `response.completed` or on a transient server
  error; compaction alone was excluded on the belief that the pre-stream retry covered it, and the
  daemon log shows it did not (five compactions and three `server_is_overloaded` events surfaced
  as `overloaded_error`, each re-sent cold by Claude Code). A compaction's partial is usage-only,
  so its restart is the verbatim whole request with backoff, the same retry Codex CLI performs for
  its remote compaction. Deterministic verdicts (`cyber_policy`, refusals, the content filter) are
  still never re-POSTed.

- **A large WebSocket frame is no longer killed for being large.** The send budget was a flat 10s,
  sized when the biggest frame on the wire was 1.5 MB; frames now reach 7.7 MB, and every "send
  failed stalled" of a morning (13) landed while a 5–6.5 MB frame was in flight — a healthy socket
  poisoned and the same megabytes re-sent over SSE. The budget is now the floor plus the frame's own
  transfer time at 100 KB/s, and the stall line names both the budget and the frame size.


- **The `/model` picker lists each model once.** Every head showed a slotted model twice (Sol
  twice on `claudex`; Kimi K3 (256k) and Kimi K2.7 Code twice on `claude-kimi`). Claude Code draws
  one row per planted tier (`ANTHROPIC_DEFAULT_{OPUS,SONNET,HAIKU,FABLE}_MODEL`) and dedupes rows
  by their alias, so two tiers on one model drew it twice: Fable shares the frontier with Opus, and
  on a two-model head Haiku shares Sonnet. A tier cannot be left unset, because its alias then
  resolves to Claude Code's built-in model, which the head rejects. A repeated tier is now planted
  under the head's discovery-wrapped spelling (`claude-codex--gpt-5.6-sol`): the head routes it
  like the bare id, and the picker's allowlist hides the row. Cache rows are unchanged; they are
  where the Default line's label comes from.

## splice v0.3.0-beta.1 — native Claude auth and a hardened multi-head gateway - 2026-08-30

### Added

- **`claude-splice`, the native-auth Claude head.** Claude Code keeps its own Anthropic login and
  sends the caller credential through the local passthrough head; splice never stores, reads,
  refreshes, or logs that credential. The management key is not reused on this route.

- **Per-model context windows in the live model picker.** Each configured row reports its effective
  window without spelling a model above its real backend ceiling, so `/model` can switch windows
  without restarting Claude Code.

- Provider OAuth sign-in plans for ChatGPT, Grok, and Kimi now resolve from the configured auth kind,
  with deterministic matrix coverage for every supported head.

### Changed

- Provider-native readable reasoning remains visible as thinking blocks, while
  `mirror_reasoning` is locked off after every configuration layer. TOML, state, environment, runtime
  PATCH, and direct construction cannot enable synthetic transcript reinjection.

- The release pipeline now accepts SemVer prerelease tags and marks versions containing `-` as GitHub
  prereleases. Beta installs use a version-pinned URL; the stable `latest` installer remains stable-only.

### Fixed

- **Codex compaction no longer dies at the idle cap while the model is still reading.** The stall
  watchdog switched to its short `streamIdleMs` tier on the first upstream byte, and on the
  Responses API that byte is the `response.created` handshake, not output — so a compaction that
  reasoned silently over a large transcript for longer than 180s was aborted and re-sent cold by the
  client, in a loop (109 stalls on one head in a single day). The tier now follows the first client
  content frame: until the client has seen output the silence is judged on `firstByteTimeoutMs`,
  after it on `streamIdleMs`, on both the SSE and WebSocket transports. A compact turn's pre-output
  silence is bounded by `upstreamTimeoutMs` alone (compactions on the corrected tier still
  died silent at the 300s cap). The stall message names the tier that actually fired.

- **A content-policy refusal is terminal, not retried.** ChatGPT's `cyber_policy` flag (and the
  Responses API's documented prompt refusals: `invalid_prompt`, `bio_policy`,
  `image_content_policy_violation`) reached Claude Code as a retryable `api_error`, so every refusal
  became a backoff storm of the same multi-megabyte transcript. They now surface as
  `invalid_request_error` with the vendor's own remedy text, matching the HTTP 400 the vendor returns
  for the same refusal pre-stream.

- Refresh failures for Codex, Grok, and Kimi no longer risk logging vendor response bodies, and
  KeyStore values containing `#`, quotes, or backslashes round-trip without corruption.

- Request-body torn wakeups become an Anthropic-shaped HTTP 400 without swallowing genuine coroutine
  cancellation; chat and Responses stream translators also stop draining runaway producers.

- A newly created Responses WebSocket can no longer evict itself while older pooled sockets are busy.

- Session-registry migration now preflights destination collisions, rolls back earlier transfers after
  a later failure, preserves stale links when replacement fails, and retains cross-filesystem support.

- OAuth callback paste handling no longer double-encodes URLs, and stopped auth-probe loops cannot
  restart themselves after shutdown.

- Repeated statusline ticks reuse a bounded branch cache instead of spawning an uncached Git process
  every time.

- The release gate now rejects invalid SemVer tags and a mutated prerelease flag; the concentration
  gate rejects masked commands and contradictory ratchet modes; the head-E2E gate rejects unmatched
  head selectors and duplicate stream terminals. All previously reported false green.

### Security

- Client-auth providers reject configured `Authorization` and `x-api-key` headers case-insensitively,
  preventing a splice-held upstream credential from sharing the local management-gate bypass.

- OAuth wrapper overrides are restricted to portable command names; paths, shell syntax, whitespace,
  blank names, and option-like names are rejected before launch.

- The transitive netty floor is raised to 4.2.17.Final (GHSA-8c42-7qj2-3j46, CORS `Vary` cache
  poisoning in `netty-codec-http`); the constraint stays a floor, so a newer ktor-shipped netty still
  wins.

## splice v0.2.0 — reasoning continuity, the cache-drain fix, and every-head login - 2026-08-02

### Changed — BREAKING

- The OpenRouter head's wrapper command is renamed `claudeor` -> `claude-openrouter`, matching
  `claude-grok` / `claude-kimi`. **Existing installs keep a stale `claudeor` symlink**: `install
  --all` links the topology's commands but never prunes one whose name disappeared, so the old
  wrapper survives and resolves to no head. Remove it once: `rm ~/.local/bin/claudeor`.

### Added

- **Gateway-held reasoning cache** (codex provider, default on): each turn's
  `reasoning.encrypted_content` envelopes are held in memory per conversation and replayed on tool
  round-trips, restoring the reasoning continuity the codex CLI gets natively. Retention is
  activity-based with hard caps (256 rounds / 64 MB across a head, whole-conversation eviction);
  the envelopes are opaque ciphertext, never written to disk, and scoped to their conversation so
  concurrent sessions can never receive each other's. `quirks = { reasoning_cache = false }`
  disables it. Documented in SECURITY.md.
- **Deferred tool surface**: responses-dialect heads advertise a small eager slice of the tool
  surface and defer the rest behind a gateway-answered `tool_search` — the model asks, the gateway
  answers from the deferred inventory, and the continuation round is invisible to the client. Cuts
  tens of KB from every upstream request; across a full daemon log, well under 1% of turns needed a
  search round.
- **Responses WebSocket transport** (opt-in, `websocket = true`, default off): rides the upstream
  v2 WebSocket with `previous_response_id` chaining, sending per-round deltas instead of the full
  replay. Cuts wire bytes and prefix drift. Measured NOT to reduce billed input tokens — the
  receipt (`gateway/spikes/results/responses-websocket.md`) is explicit — so this is a latency and
  robustness lever, not a quota one.
- **Mid-stream re-anchoring**: a turn torn by a provider brownout after frames were already
  forwarded is re-anchored upstream and continued instead of failed.
- **Turn-scoped summary dedup**, and continuation rounds no longer re-request reasoning summaries:
  detailed reasoning on every round with zero duplicated summary text.
- **Loop guard**: a circuit breaker for the identical-failed-tool-call pathology, which previously
  burned rounds repeating a call that could never succeed.
- **API-key store and token capture** for api-key heads: `splice key`, and paste-to-store during
  `/login` for providers whose token shape splice knows (today: OpenRouter).
- `/login` now reports its outcome back INTO the session. The sign-in runs detached, so everything
  it printed was lost and the session never learned whether it worked; it writes a one-line receipt
  that the head's `/login` hook reads and consumes on the next prompt. This is the only channel
  that can confirm a kimi login at all — an RFC 8628 device flow has no browser redirect to render
  a page in, which is why opencode and Kilo Code both confirm in-client rather than via a callback.
  Failures are reported too, which is the case that previously said nothing at all.
- The browser login accepts a PASTED redirect URL (or a bare code) on stdin, racing the loopback
  callback. A loopback can simply never arrive — browser on another machine, SSH, a container
  without shared localhost — and the only outcome was a silent five-minute timeout. xAI's own CLI
  accepts both channels for this reason.

### Fixed

- **The prompt-cache drain.** The reasoning cache expired envelopes on ACTIVE conversations, which
  rewrote the replayed prompt prefix mid-conversation and invalidated the provider's prefix cache
  turn after turn — measured at 350,920,932 wasted input tokens across 7,056 turns in one window,
  a 66.6% hit rate against 98.0% (grok) and 96.3% (kimi) on the same daemon
  (`gateway/spikes/results/prompt-cache-drain.md`). Envelopes now expire wholesale on idle
  conversations only; the measured hit rate recovered to ~90%.
- responses-lite turns send `tool_choice=auto` — fixes broken tool-calling on gpt-5.6.
- `tool_search_call.arguments` is emitted as a JSON object, not a string.
- Catalog membership recognizes `[1m]`-suffixed models — unbreaks kimi k3.
- The paste-capture hook is installed ONLY while an api-key head's key is missing. On a configured
  head it was pure downside: a bare `sk-or-…` message was swallowed and stored, silently
  overwriting a working credential, and the message never reached the model — so merely discussing
  a key by pasting one broke the session's auth. The key-missing advertiser was already gated this
  way; the hook that acts on the paste was not.
- `install.sh` now detects a stale `claudeor` symlink left by the rename and prints the one command
  that clears it. It does not delete anything: that bin dir holds links splice did not create. The
  notice is scoped to a symlink pointing at splice's own launch shim, so a user's unrelated
  `claudeor` script is never mentioned.
- The OAuth callback page said "close this tab and head back to your terminal", but `/login` is
  usually invoked from inside a session where there is no terminal to return to. It now names the
  destination, matching what xAI's CLI does ("You can close this window and return to Grok Build").
- `/login` on an api-key head promised "a masked terminal prompt is asking for your key" while
  spawning `<command> login` DETACHED with output to `/dev/null`. Detached means no TTY, so
  `System.console()` was null, the CLI printed its pipe-instead hint into the void, and the
  promised prompt could never appear — the user waited on nothing. A head that can capture a
  pasted token is now told the path that actually works (paste it as a message; splice stores it
  and blocks it before it reaches the model), the residual is stated plainly (the session log on
  disk still records the pasted line), and nothing is spawned.
- `/login` still works for EVERY head — each has its own sign-in path, and being in the topology
  is what makes it supported. Only the wording differs: a head whose token shape splice knows
  gets the in-session paste path; one it does not gets pointed at `<command> login` in a terminal,
  with the reason stated. Capture patterns stay deliberately one-provider-at-a-time (today:
  OpenRouter) — that scoping applies to CAPTURE, never to whether `/login` exists.
- The frozen migration oracle's `--check` mode never compared against the committed fixtures, so
  no behaviour drift could fail it despite being wired as a verification gate. It now diffs fixture
  bytes, the vendored mock's checksum, and the scenario roster in both directions.
- Three ast-grep walls were narrower than their own messages claimed: the cancellation wall
  accepted a type check without the rethrow it demands; the `pkill` wall fired on unrelated string
  concatenation in exec arguments; the silent-`Result`-collapse wall missed `var` bindings.

### Security

- **Wall grants are signed.** The write-time gate that protects `.rules/`, `.claude/hooks/`,
  `.claude/settings.json` and `sgconfig.yml` trusted `.claude/state/walls-grant.json` on sight,
  and that path was not itself walled — so an assistant could write its own grant record and open
  every wall in a single tool call, leaving no git trace (the file is gitignored). "Operator-only
  by construction" held for *issuing* a grant and not for the record the gate *trusts*, which is
  the half that matters.

  Grants are now HMAC-SHA256 signed and the signature is verified before the expiry field is read.
  The key lives outside the repo (`~/.local/state/splice/walls-grant.key`, mode 0600) and is
  created only by `dev/walls-grant/install.sh` — never by the gate, since a verifier that can mint
  its own key proves nothing. The grant record is refused as a tool-write target unconditionally,
  so a grant cannot extend itself. Grants may now be scoped to specific wall paths, and the issuing
  session id is part of the signed payload, so the audit record says who opened a wall and from
  where rather than only when it expires. Every failure mode — missing key, bad signature, expired,
  out of scope — leaves the wall shut.

  Known limitation, deliberately recorded rather than implied away: `Bash` tool calls do not pass
  through the write-time hook, so a key readable by the same process it defends raises the bar
  without sealing it. See the header of `.claude/hooks/lib/walls_grant.py`.

- The `/grant` installer no longer reports a repo carrying the pre-signature gate as already
  installed; it refuses loudly rather than silently leaving a forgeable gate in place. Re-running
  it also no longer revokes an active grant out from under the operator.
- Closed every open CodeQL alert: a measured ReDoS, an unescaped OAuth-callback echo, error
  de-leaking, and the legacy Node log-tail endpoints reflecting exception text — errno plus the
  absolute host path — back to clients. Detail goes to stderr, a fixed string to the wire; the
  Kotlin gateway's own log endpoint was never affected.
- Dependency floors: netty 4.2.16.Final and jackson on the Gradle plugin classpath — the alerts
  Dependabot could not raise PRs for.
- The secret-scan allowlist is now GENERATED from a TOML source. The three `grep -vEf` hazards
  that blinded the scan during review (an unanchored entry, prose acting as a live regex, an
  invalid ERE breaking the whole pattern file) are inexpressible rather than merely detected, and
  a canary self-test guards the generator's output in the gate.

## splice v0.1.1 — release integrity and supported defaults - 2026-07-21

### Fixed

- Launching a head by its wrapper command now works when the command differs from the topology
  key — the starter's supported route (`openrouter` head, `claudeor` command) failed its very
  first launch with "head not launchable". `/launch`, `login`, `install <head>`, and
  `uninstall <head>` accept either name now.
- The release installer now fetches and verifies both the fat JAR and launch shim, fails on
  missing or mismatched assets, rejects dangling wrapper links, and is safe when piped through
  stdin. CI and publication run the same hermetic staged-bundle install test.
- Fresh installs now materialize a supported OpenRouter API-key topology. Codex, Grok, and Kimi
  OAuth implementations remain available only as explicitly configured experimental opt-ins.
- `splice doctor` now reports management-key coverage: a missing mgmt-key while the daemon runs is
  a failure ("admin endpoints will 401") rather than "Everything checks out", and the state dir and
  `daemon.lock` paths are shown for orientation.
- `splice doctor`'s split-brain check no longer vanishes silently when the daemon is up but its
  side can't be read (no mgmt-key or `/api/auth` unreachable) — it now emits an explicit warning
  instead of quietly skipping exactly when the daemon is busiest.
- `splice restart` no longer false-fails when the daemon drops the shutdown connection during a
  graceful teardown: the health poll, not the POST result, decides whether the daemon stopped.
- `splice doctor`/`restart` no longer render a foreign listener's `{"version": null}` as the
  literal string "null" (JsonNull-filtered read).
- `splice doctor`'s daemon and auth sections now resolve the control port and probe `/health` once
  through the injected environment reader, so the hermetic tests no longer depend on an ambient
  local daemon.
- `experiments/cache-replay/real-ab.sh` derives its repo root from its own location instead of a
  hardcoded personal path (the last stray reference to the project's pre-rename directory).

### Added

- The installer now preflights the whole machine before doing anything: platform detection
  (Linux/macOS native, Windows pointed at WSL2 with exact guidance), Java 21+ as a hard
  requirement, and every runtime dependency (curl, python3, node, Claude Code) verified with the
  exact per-package-manager fix — offered interactively with consent, printed otherwise. The
  install finishes by running `splice doctor`, so it ends on a verified state, not a hopeful one.
- README: a requirements table with per-OS fixes, three install paths (release one-liner, from
  source, and a copy-paste prompt that lets a coding agent drive the whole install-and-verify
  loop against `splice doctor`'s fix lines).
- `splice doctor` now actually diagnoses: five sections (prerequisites, installation,
  configuration, daemon, auth) with an actionable fix line under every failing check, and exit 1
  only on real failures. It detects the exported-after-boot trap — an API key visible in the
  shell but not to the running daemon — by comparing both sides.
- `splice restart` — stop the daemon (stale or current) and cold-start it with the invoking
  shell's environment; the documented fix for a key exported after the daemon booted.
- Launching a head whose upstream credentials are absent now warns, naming the missing env var
  (or login command) and the fix, instead of failing silently upstream on the first request.
- The release installer preflights `gh` presence and authentication before downloading anything —
  provenance verification needs an authenticated GitHub CLI, and learning that after the download
  was the worst first-run moment.
- Release bundles and the shaded JAR include the project license, third-party notices, provenance,
  a CycloneDX 1.6 SBOM, and an exact runtime dependency-license inventory. Publication fails on
  unresolved licenses or sidecar/JAR/checksum drift.
- CodeQL, dependency review, artifact provenance attestations, release-version validation, and
  bounded/concurrent CI release jobs.
- Gateway hardening: an 8 MB cap on incoming request bodies, rejected with HTTP 413 when exceeded;
  a bounded request-materialization gate limiting how many requests can be decoded/translated
  concurrently; SSE frame-size limits on data read from upstream; and a cap on upstream
  error-response bodies (64 KB) before they're surfaced to the client.
- Ceilings on configuration values — ports, fold rounds/tier, and max inflight/queued — so
  out-of-range operator or environment input can no longer reach the runtime uncapped.

### Changed

- The ChatGPT, Grok, and Kimi subscription routes are now presented as what they are: the
  primary routes splice was built for — unofficial, at your own risk — rather than
  "experimental" afterthoughts. The API-key routes remain the zero-config starter. The
  OSS posture check now pins the risk language instead of the word "experimental".
- Public reasoning language now describes provider-generated summaries without implying access to
  raw, private, or exact chain-of-thought.
- Reasoning replay now ships off. Measurement showed that replay encouraged reuse of thin prior
  thinking; `CLAUDEX_REPLAY_REASONING=1` remains available as an explicit cache-warmth trade-off.
- A rate-limited (429) turn now terminates immediately instead of retrying in-gateway, so the
  client re-sends; a real 429 arms a shared per-account cooldown so concurrent turns fail fast
  together instead of each burning its own retries against the same limited account.

## splice — codex-proxy v35, claudithos removed, renamed from "mythos" - 2026-07-15

Public release under the new name **splice** (was "mythos", which collided with Anthropic's
model line). Two functional changes ship alongside the rename.

### Fixed

- **Compaction re-read the whole transcript cold and drained quota (codex-proxy v35).** The
  stream idle-watchdog was reaping big-context compaction PREFILLS: a ~160k compaction is
  silent for minutes while the backend prefills before its first byte, and the watchdog's
  `streamIdleMs` treated that silence as a zombie and aborted — so every compaction died
  mid-prefill and retried, re-reading the transcript uncached each attempt. The idle abort now
  uses `firstByteTimeoutMs` until the first byte arrives; `streamIdleMs` applies only once
  streaming has actually started. Compaction also inherits the session's own model AND reasoning
  effort — a mismatch on either invalidates the prompt cache.

### Removed

- **The `claudithos` head** (a Claude-on-Claude memory-architecture experiment, port 3098): the
  launcher arm, proxy branch, auth panel, `claudithosMode` config knob, and its tests are gone.
  The stack is now the `claudex`/codex head plus a scaffolded Grok head.

## splice — control server v1 (spliced) - 2026-07-15

Split the dashboard out of the proxies into a centralized control plane. Each head
(codex, grok later) used to serve its own single-head `/dashboard` +
`/mgmt`; now a loopback control server (`spliced`, :3096) hosts ONE dashboard over
an aggregated `/api/*` spanning every head. The heads keep `/mgmt` as their machine
interface but no longer serve a dashboard.

### Added

- **`spliced` control server** (`src/control-server.mjs` + `src/control/api.mjs`,
  loopback :3096, `controlPort`). Bearer-guarded `/api/*` sharing the proxies'
  mgmt-key: `GET /api/status`, `GET /api/heads` + `POST /api/heads/:head/{start,
  stop,restart}` (full lifecycle), `GET|PATCH /api/config`, `GET /api/usage`,
  `GET /api/auth` + `POST /api/auth/:head/{refresh,login}`, `GET /api/compact`,
  `GET /api/logs/:head`. Serves the dashboard at `/`. Mints the mgmt-key at boot.
- **Shared head lifecycle** (`launcher/heads.mjs`): the head registry + health /
  spawn / kill / start / stop / restart, used by BOTH the CLI launcher and the
  control server so process logic is never forked. Control-side spawns strip the
  config env (`CONFIG_ENV_NAMES`) so `config.json` — the dashboard's source of
  truth — wins over a stale inherited value.
- **Soft-warn usage caps** (`src/usage/warn.mjs`; `usageWarnPct` /
  `usageWarnTokens5h`): never blocks. Classifies each head's headroom ok / warn /
  critical from the rate-limit remaining, with a 5h output-token cap as fallback.
  Feeds a dashboard banner and a subtle statusline `⚠` that stays hidden until near
  the cap.
- **`claudex dashboard`**: ensures spliced is up and
  opens the browser; the launcher also best-effort-starts spliced alongside any
  head launch (non-blocking).
- **Multi-head dashboard** (`webui/`, FSD React): a fleet of instrument head-plates
  (live status + start / stop / restart with a two-step confirm on the destructive
  actions + a per-head usage meter tinted by warn level), per-head auth cards
  (codex Sign-in-with-ChatGPT + refresh, claude plain-claude + refresh), and a
  shared config editor with layer provenance and guided enum dropdowns. Retired the
  single-head models / reasoning / proxy-status surfaces.

### Changed

- `codex-proxy.mjs` no longer serves `/dashboard` (it
  moved to spliced); it keeps `/mgmt`. Dashboard config changes reach a running
  head through a `PATCH /mgmt/config` fan-out (the runtime layer, which beats the
  launcher's env pin), falling back to writing the config file when no head is up.

## splice — codex-proxy v31 - 2026-07-14

Codex-parity prompt-cache warmth for the claudex head. Native Codex keeps the
backend prompt cache hot with three coupled mechanisms
(`codex-rs/core/src/client.rs`): `include=["reasoning.encrypted_content"]`,
`store=false`, and a stable `prompt_cache_key = session_id`. claudex sent none of
them, so the growing conversation prefix went cold every turn (no cached-input
discount, higher latency) — acute once account-level limits made cache hits
load-bearing. This ships all three, on by default, without abandoning the mirror.

### Added

- **Reasoning replay (default on, `replayReasoning`).** The backend's encrypted
  reasoning rides through the transcript as a `redacted_thinking` block
  (`reasoning/replay.mjs`, tag `splice-reasoning` v1) and decodes back into a
  Responses `reasoning` input item, so the reasoning KV / prompt-cache prefix
  stays byte-stable across the agent loop. Emitted on both response paths — the
  stream path via the sole SSE emitter (`addRedactedThinking`), the non-stream
  path via `translateResponse`. Never on compact. Opt out with
  `CLAUDEX_REPLAY_REASONING=0` to run the pure distillation loop.
- **`prompt_cache_key` (always on).** `splice-<sha256(first user message)[:32]>`
  — keyed on the first user message: stable for the whole conversation and immune
  to per-turn system-reminder drift (keying on the system prompt would bust it
  every turn). Routes every turn of one conversation to the same cache shard.
- Both run ALONGSIDE the mirror (L2), unchanged: the mirror carries the reasoning
  SUMMARY to the model as readable text; replay carries the ENCRYPTED reasoning to
  the backend. Different channels — they compose, they don't compete.

### Changed

- **L1 retired.** The former locked invariant "no reasoning-item replay" (the bet
  that per-turn amnesia beat replay on the hardest multi-day work) is overturned:
  the power came from the mirror, not from dropping replay. The
  `l1-no-reasoning-replay` wall + rule-test are removed; the L1 behavioral test is
  replaced by a replay round-trip / gating / cache-key / both-channels-coexist
  suite; orchestrator routing tests re-pointed to L3. The pure-amnesia A/B is
  preserved behind the flag.
- `replayReasoning` is a hot-applicable config knob (defaults ← file ← env
  `CLAUDEX_REPLAY_REASONING` ← runtime PATCH) — toggle the A/B live from the
  dashboard, no restart.

### Gates

- server 87/87 (10 new), gate:rules 10 rules green, test:hooks 13/13, webui
  lint+test+build green, `webui/dist` byte-unchanged.

## splice — codex-proxy v30 - 2026-07-13

Productization: the 1783-line `codex-proxy.mjs` v29 (which diverged through six
local versions in two days inside a forked npm package) becomes this repo —
npm workspaces `server/` + `webui/`, 18 server modules, walls-first ast-grep
policy, a bearer-guarded management plane, and a committed single-file
dashboard. All three autocompact locks fixed as part of the move:

### Fixed

- **Autocompact trigger never fired** (binary trace, Claude Code v2.1.207):
  Claude Code hard-skips autocompact when it cannot resolve an explicit
  context window for the model; no claudex model matches its tables and only
  `CLAUDE_CODE_AUTO_COMPACT_WINDOW` un-gates it. The launcher now sets it
  (resolved window, floor 100k) alongside the kept
  `CLAUDE_CODE_MAX_CONTEXT_TOKENS` and `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=85`.
- **Overflow on the SSE path** (Eli P0): the "prompt is too long" rewrite
  existed only on the HTTP non-ok path; live failures arrive via SSE
  `response.failed` and became raw `api_error` (hard error, no compaction).
  ONE `classifyUpstreamFailure(kind, text, status)` now serves both
  transports; overflow order also fixes v29's auth-regex shadowing of
  wordings containing "tokens".
- **Compaction detection inverted** (Eli P0/P1 + trace): real compaction
  requests DO carry tools; v29's `tools.length>0 → false` guard rejected the
  real shape, and a tooled compaction could answer with `tool_use`, gating
  the promote-to-text net off. `classifyCompact` is now a tools-agnostic
  positive-marker classifier (the verbatim summarizer prompt); on detect the
  builder strips tools upstream. A shadow classifier logs
  `{has_marker, tool_count, sys_len}` on EVERY request, and a canary test
  pins the marker sentence.
- Dead claude-* passthrough is an honest error; every `listen()` binds
  127.0.0.1 explicitly; context windows resolve exact-match + explicit prefix
  rules (no substring fuzz); `body.__claudex*` magic props replaced by the
  pure `{req, meta}` translation contract; mirror/promote/honesty thresholds
  named in one place; kill-stale is a pgrep/lsof loop excluding own PID
  (never `pkill -f`), and a surviving wrong-version proxy is a loud failure,
  never the EADDRINUSE silent-exit version-loop.

### Added

- Layered hot config (defaults ← state file ← env ← runtime PATCH) read per
  request; `/mgmt/*` management plane on both proxies (status, config
  round-trip, usage, compact + shadow, auth + refresh, logs, models);
  `/dashboard` serving the committed single-file WebUI (React 19 + Zustand,
  FSD lint-enforced, Torad tokens, Reasoning + Compaction instrument pages).
- Launchers: `ensure-proxy` (health/version handshake), `assemble-env`
  (section-aware TOML replacing the sed that leaked [profile] values;
  models_cache + ceiling resolution), `prepare-config` (config-dir isolation
  half of claudex-prepare), thin `bin/claudex` exec-env shim. Proxy logs move to
  `~/.claude-codex/logs/` (out of /tmp).
- Walls: single Python hook orchestrator routing every write-time policy to
  ast-grep rules (L1/L2/L3 structural invariants, loopback bind, magic
  props, pkill, FSD fetch gate, em-dash copy gate, CSS token scales), same
  rules re-run by `npm run gate:rules` and CI.

### Left behind (deliberate)

`claude-wrapper` (pinned proxy v6 vs real v29 — abandoned), `set-model-mode`
(SMELTER-coupled; only the pure config-isolation helpers were extracted),
`build-codex-server` (bundled a nonexistent file), `lib/auto-update` (npm
self-update + network call per launch), `bin/claude-codex`.

---

## Inherited history (codex-for-claude-code local fork)

> Provenance + external upstream license clearance for this inherited lineage: see [PROVENANCE.md](PROVENANCE.md).

## local codex-proxy v29 - 2026-07-13

### Fixed

- **Large-context and compaction requests aborted mid-prefill** ("operation was aborted", 31× in one session vs 5 genuine over-window). The v25 first-byte timeout was 90s, but a near-window prompt or a compaction re-sending the whole transcript legitimately takes minutes to prefill before the first token. Raised to **300s** (`CLAUDEX_FIRST_BYTE_TIMEOUT_MS`), still catching a truly-dead connect. This was the dominant cause of "autocompact not working" — the compaction request itself was being killed before it could respond.

### Changed (launcher)

- **Reverted the reported context window 220k → real 272k, kept autocompact at 85%.** The over-window 502s were caused by Claude Code's **autocompact thrashing guard** (it disables autocompact after the context refills within 3 turns of a compact, 3× in a row — triggered by large tool-result reads), not by the threshold. A *lower* reported window fires autocompact more often, leaving fewer turns before a big read refills it → more thrashing → autocompact disabled → session grows unbounded → 502. Reporting the real 272k fires at ~231k with ~4 turns of headroom, above the 3-turn thrash trigger.

## local codex-proxy v28 - 2026-07-13

### Fixed

- **Only one codex model (the pinned default) showed in the `/model` picker.** `additionalModelOptionsCache` is replaced wholesale on every bootstrap and `ANTHROPIC_CUSTOM_MODEL_OPTION` is singular; the only durable way to list N custom models is **gateway model discovery**. The proxy serves `GET /v1/models` (launcher sets `CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1`); Claude Code drops ids not matching `/^(claude|anthropic)/i`, so codex ids are wrapped (`gpt-5.6-luna` → `claude-codex--gpt-5.6-luna`) and unwrapped on the way in. The pinned default is excluded from discovery to avoid a duplicate.

## local codex-proxy v27 - 2026-07-13

### Fixed

- **Claude Code `/effort` picker was ignored.** The picker arrives as `thinking.budget_tokens`; the resolution chain put env (config.toml `model_reasoning_effort = "max"`) above it. Reordered: explicit body effort field > harness picker > env fallback > `high`.
- **`SHOW_REASONING=text` force-raised a deliberate low pick.** The visibility floor now only bumps `none`/`minimal`/absent → `low` and never overrides an explicit selection.

## local codex-proxy v26 - 2026-07-13

### Fixed

- **Compaction "response exceeded N output token maximum"**: the ChatGPT backend rejects token-limit params so generation is uncapped, and reasoning tokens count in `output_tokens` — an undetected max-effort compaction tripped Claude Code's output guard. Reported `output_tokens` is now clamped to the client's `max_tokens`, with a stderr diagnostic on every clamp.

## local codex-proxy v25 - 2026-07-13

### Fixed

- **Multi-part reasoning summaries**: per-part `done` events closed the thinking block after part 1 (protocol violation; visible thinking truncated). Blocks close only on `output_item.done`; parts separated with blank lines in the thinking stream and the mirror.
- **Honest failures**: `response.failed`/`response.error`, idle-aborted streams, and streams ending without `response.completed` emit an SSE `error` event instead of a clean empty `end_turn`. Empty compacts and fully-empty completions are errors too.
- **Wire framing**: `res.end()` in the same tick as a corked SSE write put the terminal chunk before the buffered frame (raw socket capture). All stream ends drain the cork queue first.
- **Client abort**: `AbortController.abort()` replaces `body.cancel()` (which rejects unhandled under an active reader lock and leaves upstream streaming).
- **Non-stream path** collapsed onto the shared translator: fixes per-chunk UTF-8 corruption, `name: undefined` tool calls, and function_calls missing from final-output harvest.
- **Compact detection**: removed the tertiary "huge toolless dump" heuristic (misfired on WebFetch-style utility calls).
- **State files** moved from `$CWD/.smt/state` to `~/.claude-codex/state/`.

### Added

- Image passthrough (incl. images inside `tool_result`); `[document omitted]` markers; 401 single-flight OAuth refresh via the Codex CLI client id; first-byte timeout; `response.incomplete` → `stop_reason: max_tokens`; context-overflow errors rewritten to Anthropic's "prompt is too long" phrasing; the 11-test behavior suite.

### Deliberate non-goals (operator-locked)

- **Reasoning-item replay (`include: ["reasoning.encrypted_content"]`) is intentionally NOT implemented.** The no-replay + mirror configuration forces re-derivation from transcript evidence at every tool boundary while distilled conclusions persist via the mirrored summaries — operator A/B experience shows this outperforming native Codex CLI (which replays) on hard multi-day debugging. Do not "fix" this. Revisit only as an explicit, measured A/B on disposable tasks.
