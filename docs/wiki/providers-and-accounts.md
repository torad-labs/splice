[README](../../README.md) · [Wiki Home](README.md)

# Providers, Accounts & Model Routing

Which backends splice serves, how you sign in to each, where those credentials live, and how a turn picks the account
and model it runs on. Start with [Provider support](#provider-support); come back to [Credential
locations](#credential-locations) before you back anything up or share a machine.

## Provider support

| Route | Auth | Status |
| --- | --- | --- |
| Claude (`claude`) | `client` (Claude Code sign-in) | **Primary**: per-head Claude account pool; Claude Code signs in, splice reads Claude Code's own login without refreshing it and refreshes only added account folders |
| OpenRouter | `api-key` (`OPENROUTER_API_KEY`) | **Supported**: pay-per-token, any OpenAI-compatible vendor |
| Moonshot | `api-key` (`MOONSHOT_API_KEY`) | **Supported**: pay-per-token Anthropic base |
| DeepSeek | `api-key` (`DEEPSEEK_API_KEY`) | **Supported**: pay-per-token over DeepSeek's Anthropic-format endpoint |
| codex (ChatGPT) | `chatgpt-oauth` | **Primary**: what splice was built for |
| grok (xAI) | `grok-oauth` | **Primary** |
| kimi (Moonshot) | `kimi-oauth` | **Primary** |
| muse (Meta) | `muse-oauth` | **Primary** |
| Local runtimes (Ollama, LM Studio, vLLM) | `api-key` on a loopback `base_url` with the `openai-chat` dialect | **Supported**: user-managed; rows validated against what the runtime serves |

The **OAuth-identity** routes are the reason splice exists: they run Claude Code on the subscription you already pay
for, signing in through each provider's page. The **api-key** routes are ordinary pay-per-token API access and make the
best zero-config starter.

### Models without a head

No head is needed to use any of them: declare the provider in `splice.toml` and the door serves it. Each model is
`claude-<provider>--<model id>` (`claude-xai--grok-4.7`), and a selector you already use under an old head prefix keeps
working:

| Selector prefix | Provider | Where it comes from |
| --- | --- | --- |
| `claude-<provider>--` | the provider named | every provider in `splice.toml` |
| `claude-codex--` | `codex` | built in, the old `claudex` head |
| `claude-grok--` | `xai` | built in, the old `claude-grok` head |
| `claude-kimi--`, `claude-muse--`, `claude-openrouter--`, `claude-fireworks--`, `claude-deepseek--` | `kimi`, `muse`, `openrouter`, `fireworks`, `deepseek` | built in |
| `claude--` | `anthropic` | built in |
| `zeta--` (any other `--` prefix) | the provider you name | `[door.selector_aliases]`, for a prefix of your own: `"zeta--" = "xai"` |

```toml
[door.selector_aliases]
"zeta--" = "xai"
```

A Claude model's own ID (`claude-opus-5-5`) runs on the Claude accounts added to splice when they serve it. Any other
ID is refused by name, and nothing is sent upstream. `splice status` lists every provider the door serves, with or
without a head.

New in 0.7.0: every list (terminal `/model`, Claude Desktop's picker, door discovery and `splice claude`) reads one
catalog, `model-catalog.json` in the state folder, rebuilt at daemon start and on sign-in, sign-out or a provider-list
change. `GET /door/v1/models` serves it. To let a provider's `claude-<tier>-*` IDs run on its own models, map the tiers
with `model_slots` on the provider table (`opus`, `sonnet`, `haiku`, `fable`); an unmapped tier is refused by name. See
[door-and-launchers.md](door-and-launchers.md) for the door itself and [configuration-reference.md](configuration-reference.md#providers-models-and-prices)
for every provider key.

### Custom providers

A provider splice does not ship is pure TOML: dialect, base URL, auth and models. 0.7.0 adds keys for team gateways and
self-hosted servers on `[providers.<key>]`:

| Key | What it does |
| --- | --- |
| `auth = { kind = "command", credential_command = "…", login_command = "…" }` | Runs a shell command (under `/bin/sh -c`, no sandbox) that prints a token or `{"token": …, "expires_at": …}`; re-run near expiry and once after a 401. The key is held in memory only. |
| `ca_file` | A PEM bundle trusted on top of the Java and OS roots. A missing, unreadable or non-PEM file refuses boot and names `providers.<key>.ca_file`. |
| `served_window = { url = "…", field = "…" }` | Reads the served context window once per daemon start; a failure is logged and shown in `splice doctor`, and the declared window stays. |
| `error_messages = "whole"` or `"filtered"` | `"filtered"` (default) or the provider's own error words, with IDs and keys still dropped. |
| `max_output_tokens` (on `[[providers.<key>.models]]`) | A positive cap on output tokens. |
| `picker` | Shows or hides the provider in the door's picker; a head's own `picker` wins. |

`splice add provider <file|https-url> [--check]` installs a published definition, checked exactly as boot checks
`splice.toml`; `--check` validates the schema and merge, not whether the definition is safe. Install a provider with
`auth.kind = "command"` only if you trust its command as a shell script: it runs with the daemon user's permissions.
This verb is not listed in `splice add --help`. A door command of your own is
`[door.commands.<name>]` with `provider = "<key>"`. The full grammar is in
[docs/specs/custom-provider.md](../specs/custom-provider.md).

### Local models

A provider that speaks the `openai-chat` dialect on a loopback `base_url` (Ollama at `http://localhost:11434/v1`, LM
Studio at `http://localhost:1234/v1`, vLLM at `http://localhost:8000/v1`) is treated as local. splice itself never
downloads a model or manages the runtime; on a Linux x86_64 machine with an NVIDIA card, `splice setup` can hand the
card to [rig](https://github.com/torad-labs/rig), which does: asked (default no), rig installs into
`~/.local/share/rig`, downloads bonsai-2-27b and an engine for the card (about 9 GB; about 18 GB of disk in all) and
serves it on 127.0.0.1, and setup adds a `bonsai` head (`claude-bonsai`) from what `rig describe` reports.

| Check | What happens |
| --- | --- |
| Boot and `splice doctor` | splice asks Ollama, LM Studio and vLLM what they serve and refuses an unlisted row, a context window larger than reported, or a model the runtime says cannot call tools, with the runtime's own words. Claude Code needs tools on every turn. |
| Generic OpenAI-compatible server | Its list is not authoritative, so its unlisted rows run; a window it does not report is trusted. |
| Runtime up and refusing a row | Boot stops; the refusal is listed with the file's other findings, with the fix session offered. |
| Runtime down | Boot is not blocked; its turns fail until it is up. |
| `splice doctor --live` | Sends one tiny streamed request with one tool for each configured model the runtime lists, so tool calling and streaming are proven before a session depends on them. |

Status labels these heads `local runtime (OpenAI-compatible)`, and doctor names each check after its provider and the
runtime it found. Neither implies subscription or quota state. Set `local = false` on a provider to opt out of the
loopback rule, `local = true` to force it elsewhere. A local head also asks its runtime for token counts, which is what
makes Claude Code's context meter move and its auto-compaction fire; a runtime that refuses that request field takes
`quirks = { stream_usage = false }`.

```toml
[providers.ollama]
dialect = "openai-chat"
base_url = "http://localhost:11434/v1"
auth = { kind = "api-key", env = "OLLAMA_API_KEY" }
quirks = { stream_usage = false }   # only if the runtime refuses the usage field
```

See [`tools/e2e/local-models/README.md`](../../tools/e2e/local-models/README.md) for what each runtime reports and how it
was tested. vLLM is documented, not tested, in 0.4.0: its tool calling needs `--enable-auto-tool-choice` and a
`--tool-call-parser` for the model on `vllm serve`.

### A second head on the same provider

`splice add <profile> --name <key>` adds a second head for a profile you already use, for example a second ChatGPT or
OpenRouter head with its own models.

| Flag | Effect |
| --- | --- |
| `--key provider` | Sends with the provider's key; adds only a head. |
| `--key new` | Sends with a new key; adds a provider and a head. A new API-key provider reads `<KEY>_API_KEY`, dashes written as underscores. Under `--yes`, one of the two is required. |
| `--command` | Needed for a second head on a profile that ships a command (such as `claude-openrouter`); only the local and api-key profiles name it `claude-<key>`. |
| `--model <id>` | Sets the head's models in place of the profile's starter ones; on a provider the file already holds, a model it does not declare is dropped, unless none is declared. |

The key takes lowercase letters, digits and dashes and must be new; splice picks a free port. A key, a command or a
table that already exists is refused with a sentence naming it, and a duplicated TOML table is reported by name and
line, never by value.

## Signing in

### Claude

The `claude` head is a head like the others, and deprecated: while it is in `splice.toml` and no choice is made,
`claude` runs on it rather than on the door, and `splice claude door` moves it. The `claude` head routes Claude Code
through splice over Claude Code's own home, `~/.claude`: the same login, settings and sessions as plain `claude`,
whether or not `claude` itself goes through splice. There is one Claude Code login, not one per command; sign in with
`/login` inside either. A Claude head can also hold additional subscriptions in separate splice-owned folders and select
between its available logins per request ([More than one Claude login](#more-than-one-claude-login-on-claude)). Splice
reads Claude Code's live access token for selection but never refreshes its credential. It refreshes only the added
accounts whose folders it alone owns.

### One place to sign in: Console Accounts

In 0.7.0 every sign-in path leads to the console's Accounts page:

| You run | What happens |
| --- | --- |
| `splice login` (no target) | Opens the console's Accounts page through `splice console`. `--no-browser` prints the single-use launch page instead; on Linux with neither `DISPLAY` nor `WAYLAND_DISPLAY` set it prints rather than opening a browser. |
| `splice login <head> [--label <name>]` | The head's own sign-in, as before. On a Claude head without `--label` it runs the same Claude Code sign-in the console runs. |
| `/login` or `/splice:login` in a `claude` session on the door | Runs `splice console` and stops the turn before it reaches the model. |
| `/login` in an Antigravity session in Claude Desktop | Intercepted before agy; opens Console Accounts. |
| An API-key head with no key | The model is told to send you to Console Accounts (`/login` or `splice console`) and not to ask for a key in chat; a bare key pasted into the prompt is caught and blocked. |

A script that ran bare `splice login` expecting a terminal flow now names the head: `splice login <head>`.

### Sign in to a ChatGPT account from the console or the terminal

In the console's Accounts page, a ChatGPT head signs in through the same browser sign-in as `claudex login`. The page
opens OpenAI's sign-in and finishes by itself once you approve, with no code to type and nothing to turn on in your
ChatGPT settings. A sign-in not finished within five minutes leaves the login Not completed on the page, with Try again.
A Claude account signs in the same way: the page opens Claude's sign-in, which finishes by itself. Kimi and Muse sign-ins
are a page to approve when the provider sends one. Sign in opens the provider's page in a new tab on the first press.
When the running command does not hold the new login, splice restarts to pick it up; a splice that cannot restart says
to run `splice restart`.

Pick a label when you sign in, or leave it unnamed: an unnamed login reads as its plan word ("Max"), then "Max 2" and so
on, counted per provider in the order splice first saw each. "Sign in again" on a head's first account replaces it in
place under its own label. No page and no API answer shows an email address or an account id: every login is shown by
its label, and splice keeps an opaque eight-character reference for it (`ref`) that the console and the API use to
point at one. Every login has Remove, one press.

To name a login, press "Name it" or "Rename" on its row, or send `PUT /api/accounts/names/<ref>` with `{"name": "..."}`
and the management key. A name is trimmed, at most 48 characters, and has no `@` or control character; a blank name sent
to the route clears your choice (on the page, a blank field keeps the old name). Chosen names live in
`account-names.json` in the state directory (`~/.splice/state/`, mode 0600) with the numbers given to unnamed logins,
and apply on the next request with no restart. A login that has no reference yet is renamed in its own file instead.

In a terminal, `splice login claudex [--label <name>]` opens the browser flow, as before. Kimi and Muse use the device
code in both places.

```bash
splice login                       # open Console Accounts
splice login claudex               # ChatGPT, browser flow, primary account
splice login claudex --label work  # add or select a named account
splice login --no-browser          # print the launch page address
```

### API keys on the Accounts page

An API-key head's key appears as an account: its variable name (for example `DEEPSEEK_API_KEY`) and the last four
characters of the key; once it has replaced an earlier key, the day splice first saw it too. Rotating a key keeps the
one card for that variable, with the key it replaced listed on it as Replaced, with its own day. The API reports a
fingerprint and first-seen day for every key. Only the first and last four characters of a long key appear on
`/api/auth`; the key itself is never logged. `splice key set|list|unset` or `PUT`/`DELETE /api/keys/<VARIABLE>` manage
the store. If the ledger (`key-ledger.json`) cannot be written, the list still answers, a key first seen at that moment
shows no day, and the daemon log says so once.

```bash
splice key set DEEPSEEK_API_KEY            # masked prompt
printf '%s' "$KEY" | splice key set OPENROUTER_API_KEY --stdin
splice key list                            # names only
splice key unset MOONSHOT_API_KEY
```

### The billing word in Claude Code's header

Claude Code's header prints a billing word beside the model. On `claude` signed in with a Claude subscription, it names
that plan, for example `Claude Max`. On every other head it reads `API Usage Billing`: Claude Code only has names for
Anthropic's plans and the clouds that sell Claude, so it prints that for any other service. It does not mean Anthropic is
billing you. Each head uses the plan, key or GPU you set it up with, and splice's status line shows an estimate at API
rates, never a charge, where splice has a rate card for the model.

## Credential locations

Each of these is a **password-equivalent secret**: anything that can read the file (or the environment variable) can
spend against your account. Keep files `600`, never commit them, never paste them.

Splice signs in on its own. Each OAuth head keeps its own credential file under `~/.config/splice/auth/`, written by
`splice login <head>`, and it may be a different account from the one the vendor's own CLI or desktop app uses. The
native apps' files (`~/.codex/auth.json`, `~/.grok/auth.json`, `~/.kimi/credentials/kimi-code.json`,
`~/.config/muse/auth.json`) are never read unless you name one in `auth.file` (the old `CODEX_AUTH_PATH` and
`GROK_AUTH_PATH` are retired, and splice refuses to start while either is set). Sharing a file lets both programs
rewrite the same credential, so a refresh or replacement can leave the other program using stale bytes. `splice doctor`
warns when a head still names one.

| Backend / route | Auth kind | Location | Notes |
| --- | --- | --- | --- |
| Claude (`claude`) | `client` | Claude Code's own login in `~/.claude`; additional accounts under `~/.splice/state/claude-accounts/<head>/<label>/` | Claude Code performs sign-in; splice never refreshes Claude Code's own credential and refreshes only the added folders it owns |
| codex (ChatGPT) | `chatgpt-oauth` | `~/.config/splice/auth/codex.json` | splice's own OAuth tokens (`splice login claudex`); `~/.codex/auth.json` only by explicit `auth.file` |
| grok (xAI) | `grok-oauth` | `~/.config/splice/auth/grok.json` | splice's own OAuth tokens (`claude-grok login`); `~/.grok/auth.json` only by explicit `auth.file` |
| kimi (Moonshot) | `kimi-oauth` | `~/.config/splice/auth/kimi.json` (+ `device_id` beside it) | splice's own device-flow token (`claude-kimi login`); the app's file only by explicit `auth.file` |
| muse (Meta) | `muse-oauth` | `~/.config/splice/auth/muse.json` | splice's own device-flow account token plus minted inference key (`claude-muse login`); the Muse Code CLI file only by explicit `auth.file` |
| OpenRouter | `api-key` | `$OPENROUTER_API_KEY` (env) or `~/.config/splice/keys.toml` | API key; password-equivalent |
| Moonshot (pay-per-token) | `api-key` | `$MOONSHOT_API_KEY` (env) or `~/.config/splice/keys.toml` | API key; password-equivalent |
| splice api-key store | - | `~/.config/splice/keys.toml` (0600) | env wins over the store; password-equivalent |
| splice control plane | - | `~/.splice/state/mgmt-key` (pre-0.4 installs: `~/.claude-codex/state/mgmt-key`) | control API key; password-equivalent |

Every other file splice writes, secrets included, is listed in
[disk-and-privacy.md](disk-and-privacy.md#what-splice-keeps-on-your-disk).

## Account pools

### More than one ChatGPT, Grok, Kimi or Muse account

An OAuth head (ChatGPT, Grok, Kimi or Muse) can hold several accounts of its kind and switch between them when one runs
out. Claude heads also have per-head pools, with different credential ownership and sign-in ([More than one Claude
login](#more-than-one-claude-login-on-claude)).

| Command | Where the account lands |
| --- | --- |
| `splice login <head>` | Always the primary account in the file above, so a revoked primary is replaced in place. |
| `splice login <head> --label <name>` | Beside it under `<primary directory>/<kind>/<primary file>/<name>.json`; for the default primary, `~/.config/splice/auth/chatgpt-oauth/codex.json/<name>.json`, with its quota state in `<name>-quota.json` next to it. A custom `auth.file` moves the pool beside that primary credential. |
| `splice login <head> --label auto` | The name is derived from nothing personal: the ChatGPT plan plus a short hash of the account id, or `grok-2`, `kimi-3` by ordinal. |
| `/login` / `/login --label <name>` inside a head | Signs in the primary, or adds an account the same way. A `/login` while an earlier sign-in is still waiting for its browser cancels one started by the hook and starts over. A terminal-started login is named and left alone. |

A file is only ever used by the provider whose kind it carries inside; a mislabeled file is refused at boot, never
silently used. A head holding one account has no pool and behaves exactly as before: nothing extra on the status line,
in `splice status` or in `splice doctor`.

A pool does not lift a plan's limits: it lets a session continue on another account you hold while one is exhausted.

**Selection is sticky per session.**

1. A manual pin goes first.
2. A saved order then gives explicit priority.
3. Without one, a session keeps its usable account, and fallback prefers an available account whose weekly quota resets
   soonest, with the five-hour reset breaking ties. Missing or expired quota does not outrank a usable current reading.

The session changes accounts when its quota is exhausted, its credential is rejected or a provider hold makes it
unavailable; it does not return to the primary just because that account's window reset. When a login refuses a turn
with a limit that waiting cannot clear, and no output has reached the client yet, that same turn moves to a free login.
The first turn after a switch pays one cold prompt-cache read, and its perf row says so and names the account.

When every account is out before a turn starts, the turn is refused and names the earliest reset across the pool. If
every login runs out during a turn, it ends with the refused login's own limit message. When the model a session asks
for is spent on every login and another model has room, the turn goes on under that one: the session's own fallback
model first (`splice fallback <session> <model>`), then the head's declared models, then the models its sessions ask
for; the first turn a login can serve the session's own model goes back to it.

When the head holds more than one login, the status line names the one the session runs on. `splice status` and
`splice doctor` show the head's accounts and its last switch, a pinned one included, with its reason; the daemon log
records each switch once under `[<head>]`.

### Pin a head or a session to an account

Selection is automatic (see above), and you can override it. On the Accounts page, "Use now" pins the head to that
account and the pin button on it releases the pin. The same two calls are on the control API, with the management key
as the bearer:

```sh
curl -X POST   -H "Authorization: Bearer $KEY" -d '{"label":"work"}' \
     http://127.0.0.1:<control port>/api/auth/codex/switch                    # the whole head
curl -X POST   -H "Authorization: Bearer $KEY" -d '{"label":"work","session":"<session id>"}' \
     http://127.0.0.1:<control port>/api/auth/codex/switch                    # one session only
curl -X DELETE -H "Authorization: Bearer $KEY" \
     "http://127.0.0.1:<control port>/api/auth/codex/switch?session=<session id>"
```

| Case | Answer |
| --- | --- |
| A session's pin vs the head's pin | The session's pin outranks the head's, for that session only. |
| `DELETE` without `session` | Drops the head's pin and answers `{"ok":true}` on a head with an account pool; a head with no pool answers 400. |
| A label the head does not hold | 400; nothing is pinned. |
| An account with no credential file | 409; nothing is pinned. |
| Use now on a login held for every model | 409, with the window and the time. |

A pin lets go at the first limit its login reads that it had not read when Use now was pressed (a plan window spent, the
provider holding the account, or a usage reading at 100%); a limit the login already read at the press keeps the pin.
Use now pins on its first press, even with requests in flight. While a pinned login cannot serve, the pin stays and its
card says why and which login the turns use instead.

To see a pin: `GET /api/auth` shows a head's pin as `pinned_label`, and `splice sessions` and `GET /api/sessions` show
each session's account and, when it has one, its pin (`account` and `account_pin`). The pin takes effect on the next
turn, and a daemon restart keeps it, since pins are saved in `account-pins.json` in the state folder (a pin that has let
go stays gone). Without any pin, a session keeps its account until a limit, then moves to the next free login in your
saved order, then the primary; with no saved order, to the login whose weekly window resets soonest.

### More than one Claude login on `claude`

A Claude head can hold multiple subscriptions. `POST /api/auth/<head>/login` on the control plane adds one through Claude
Code's own browser sign-in into a new folder under `~/.splice/state/claude-accounts/<head>/<label>/`, never replacing
another login. Once filed, it can join a running head through membership publication. The pool is per head, and uses
the same pin, saved order, sticky default selection and same-turn hand-off on a refusal described above. Claude Code's
own login, the one in `~/.claude`, is one selectable login on `claude`, whichever command started the session: splice
reads its live access token without refreshing or replacing its credential during selection. It can refresh an added
folder's credential because no other program uses that folder.

When every login is at its limit for the model a turn asked for, the turn waits and the session says so, with the time
the first login comes back. It continues at that time, or sooner: when a login frees, when an account is added, when
Claude Code signs in to another account with `/login`, or when you press Try again on a login's card. `splice status`
and the Accounts page name a model that is used up on a login while that login's week still has room. A Claude
account's card lists its limit resets.

Removing or renaming a Claude login requires its explicit target kind and stable id; a saved display name never chooses
between a command's own login and an added account with the same name. Removing a command's own login takes it out of
splice: splice stops serving turns on it and stops listing it, and leaves Claude Code's own login file as it is, so a
`claude` started outside splice stays signed in. The removal is kept by account, so the login stays removed when Claude
Code renews its token, and a different account signed in at that place shows as usual.

#### Labelled copies for a Claude command of your own

The labelled-copy CLI is separate from automatic pool selection, and it is for a second Claude command you declare
yourself: a head with `auth = "client"` under a name other than `claude`, which keeps a Claude Code config directory of
its own, `~/.claude-<head>/`, with one login at a time. `splice login <head> --label <name>` saves or switches copies of
that login. On `claude` it changes nothing and says where to sign in, since that command runs on Claude Code's own login
and its other accounts are added on the Accounts page.

- **Save.** With a login the label has never seen, it saves the head's live login under `<name>` and selects it. With
  the label the head already holds, it saves the newer copy over the old.
- **Switch.** With another saved label, it first saves the head's live login under its own label, then puts `<name>`'s
  copy in the head. The next session of that command signs in with it.
- **Add an account.** With a new label while the head's login is already saved under another, it saves that one and
  signs the head out. Start the command, `/login` with the new account, exit, and run the same command again.

A saved login can become stale after the head refreshes it. A launch never copies a saved login into the head, and
`splice login` changes the head's login only under four rules:

1. **Save before switching.** The login being replaced is saved under its own label first, so every saved copy is its
   account's newest. If that save fails, nothing is switched.
2. **Never under a running session.** While any session of the head runs, or while splice cannot tell, it changes
   nothing and names the session.
3. **Filed by account.** Each label records its account's id and email from the head's `.claude.json`, never a token;
   this labelled-copy operation copies the credential file byte for byte and never calls Anthropic with it. A login is
   filed only under the label of its own account. A `/login` to another account inside the head is refused under the
   selected label, which the refusal names without naming either account: save it under a new label, or add `--discard`
   to a switch to drop it.
4. **Fail closed.** If splice cannot read whose login the head holds, it changes nothing.

A saved copy that may be older than its account's last refresh is never put back. That covers a label whose login left
the head without being saved first (a `/login` to another account, or a `/logout`), and a copy saved before splice
recorded accounts. Re-save it from a live login: sign in to that account with `/login` in the command, then run
`splice login <head> --label <name>`.

## How a turn is shaped

These settings change what reaches the provider on each turn. Each has its full reference elsewhere; this is the map.

### Reasoning effort

A session starts at its head's configured effort (`effort` in `splice.toml` or on Settings, read at each launch, else
`high`), handed to Claude Code as `--effort` unless you gave one; `/effort` changes it. Every adapter then sends the
effort Claude Code's request names. Precedence, strongest first:

1. The turn's explicit effort (Claude Code's `/effort` or `--effort`).
2. A head override, then a provider override (`effort = "high"` in `[heads.<key>.overrides]` or `[providers.<key>.overrides]`).
3. The global default: a Settings change made since boot, then `CLAUDEX_REASONING_EFFORT`, then a saved Settings value, then `effort` in `splice.toml` (`[daemon]` or `[defaults]`), else `high`.

Provider and dialect clamps apply last: a Kimi turn lands on the highest of Kimi's own rungs (low, high, max) not above
the word asked for, Grok floors invalid values to low, and an openai-chat provider with no published levels can declare
them in `[providers.<key>.quirks.chat_effort_vocabulary]`. Effort is part of the prompt-cache key, so splice never varies
it by turn type, compaction included. Details: [configuration-reference.md](configuration-reference.md#serving-knobs-in-defaults-and-override-tables).

### Reasoning and cache replay

splice never has, exposes or reconstructs a model's raw chain-of-thought; "reasoning" means provider summaries, readable
reasoning fields the backend supplies, or the backend's own encrypted reasoning items carried forward unread.
`replay_reasoning` ships on, `mirror_reasoning` stays off. See
[Reasoning](architecture-and-troubleshooting.md#reasoning) and [The cache-replay
experiment](architecture-and-troubleshooting.md#the-cache-replay-experiment).

### Gateway headers, standing prompts and compaction text

| Setting | Where it is documented |
| --- | --- |
| `extra_headers`, `forward_headers`, `send_version` for a gateway in front of a provider | [Headers for a gateway in front of a provider](configuration-reference.md#headers-for-a-gateway-in-front-of-a-provider) |
| `system_prompt`, `system_prompt_file`, `system_prompt_mode` per provider, head or project | [Standing system prompts](configuration-reference.md#standing-system-prompts) |
| `[compaction]` instructions, global, per model or per project | [Compaction instructions](configuration-reference.md#compaction-instructions) |

### Code mode and capabilities

Code mode is on by default for ChatGPT-shaped providers (`auth.kind = "chatgpt-oauth"`, `dialect = "openai-responses"`),
and `code_mode = false` under `[providers.<key>.quirks]` turns it off. Pre-turn capability workers and modes are covered in
[modes-and-extensions.md](modes-and-extensions.md).

| Provider | Auth | Dialect | Account pool | Usage polling | Code mode |
| --- | --- | --- | --- | --- | --- |
| codex (ChatGPT) | `chatgpt-oauth` | `openai-responses` | yes | usage endpoint | on by default |
| grok (xAI) | `grok-oauth` | `openai-responses` | yes | billing endpoint | — |
| kimi | `kimi-oauth` | `anthropic-passthrough` | yes | usages endpoint | — |
| muse | `muse-oauth` | `openai-responses` | yes | key-mint response | — |
| Claude (`claude`) | `client` | `anthropic-passthrough` | yes | Anthropic usage endpoint | — |
| OpenRouter, Fireworks | `api-key` | `openai-chat` | no | no | — |
| Moonshot, DeepSeek | `api-key` | Anthropic format | no | no | — |
| Local runtimes | `api-key` | `openai-chat` | no | no | — |

## Related pages

- [getting-started.md](getting-started.md): first install and the first provider.
- [door-and-launchers.md](door-and-launchers.md): `claude` on the door, door commands and the model picker.
- [claude-desktop.md](claude-desktop.md): the same catalog in Claude Desktop.
- [console-and-sessions.md](console-and-sessions.md): the Accounts page, plan usage and budgets.
- [configuration-reference.md](configuration-reference.md): every provider, head and quirk key.
- [cli-reference.md](cli-reference.md): `splice login`, `splice key`, `splice add` and the auth routes.
- [disk-and-privacy.md](disk-and-privacy.md): every credential file and what else splice keeps.
- [modes-and-extensions.md](modes-and-extensions.md): code mode and capabilities.
- [team-server-mode.md](team-server-mode.md): serving these providers to a team.
- [upgrading-to-0.7.0.md](upgrading-to-0.7.0.md): what changed in sign-in and providers.
