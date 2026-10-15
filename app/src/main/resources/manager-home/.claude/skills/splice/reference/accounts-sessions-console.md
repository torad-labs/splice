Last read: 2026-10-15 against splice 0.4.0 (feat/v0.4.0)

# How splice works for the person: accounts, models, sessions, Teams, the console

What the person sees and does, and what to tell them. TOML fields: toml-reference.md. Adding heads:
heads-and-backends.md. Retries, install, upgrade: reliability-mcp-install.md. Safe working order: operations.md.
Verbs: cli.md. Where a doc in the repo disagrees with the code, the code wins; the list is at the end.

## The model in one picture

- One daemon (`spliced`) runs on the machine. Its control plane listens on `127.0.0.1:3096` (`control_port`). Everything is loopback.
- A **head** is a Claude Code wrapper command on its own loopback port: `claudex`, `claude-grok`, `claude-kimi`,
  `claude-muse`, `claude-openrouter`, `claude-deepseek`, `claude-splice` (Claude). The person types it where they typed `claude`.
- Claude Code stays the tool executor. The head translates Anthropic's Messages API into the provider's dialect.
- A session is a Claude Code process. Splice launches some, reads all of them from Claude Code's registry, and types only into
  the ones it opened.

Source: `README.md` "How it works"; `app/src/main/resources/splice.example.toml:14,571-797`; `core/.../config/Knob.kt:350`.

## How the person opens the console

```
splice console
```

1. The CLI starts the daemon if it is down, writes a redirect page (mode 0600) in the state folder, and opens it in the browser.
2. The page carries the management key in the URL **fragment** (`#k=...`), which never reaches the daemon or a log. The
   console stores it in that browser's local storage and removes it from the address bar. Every call then sends it as Bearer.
3. With no browser, the verb prints the redirect page's `file://` path. The bare address `http://127.0.0.1:3096` lands signed out.
4. A page showing **Locked** with `splice console` and a Copy button means this browser holds no key splice accepts (a 401).
   The person runs `splice console` again.

You never run it and never ask for the key. If it says the key is absent, `splice restart` creates it (ask first).

Source: `app/.../cli/status/ConsoleCommand.kt:1-55`, `app/src/main/resources/console/api.js:1-30`.

## The eight pages

The top bar lists them. The composer sits at the bottom of every page (see "The splice manager").

| Page | What it shows and what the person can do |
|---|---|
| Accounts | Every login and key, plan windows, which login serves next, daily budgets. Add, rename, remove, pin, reorder |
| Compare | One prompt to up to four models, answers side by side with tokens, time and cost |
| Models | One card per command: sends-to, models and windows, sign-in, prices, instructions. Edit, start, stop, add, remove |
| Requests | Every request in time order, searchable; what was sent and returned |
| Sessions | Sessions grouped per project. Answer, message, stop, end, Continue on |
| Settings | Reliability, reasoning, plan limits, MCP servers, projects, what splice keeps, version and upgrade |
| Teams | Named setups of several sessions on several commands: start, watch, stop |
| Usage | Plans, keys, tokens or cost chart, breakdown, per-command performance |

**Models.** Tabs "Commands", "Every model", "Projects". A command card shows a state word ("Ready", "Not started", "Not answering",
"Signed out", "No key", "Credentials refused", "Command not found") and a fix button ("Start", "Sign in", "Add key", "Restore").
Opened, it shows "Where it goes", "Models" (tier pickers Opus, Sonnet, Haiku, Fable, each with a window), "Sign-in", "Prices"
(per 1M in, out, cached), "Running", "Instructions". Controls: "Restart", "Stop" (always asks), "Remove" (history stays), "Send one
request", "New command". "Save N changes" writes `splice.toml` (comments kept, backup first) and tags each change "Live" or
"Applies on restart"; "Restart splice" asks first if a session would be cut. "Your claude command" has "Through splice" and
"Straight to Anthropic". An unpublished window reads "Not reported", never 0.

**Settings.** Rail: Silent models, Many agents at once, Reasoning, Plan limits, MCP servers, Projects, Your data, Version. Each knob
shows "Live" or "Applies on restart"; "Set for one command" writes `[heads.<key>.overrides]`. Your data holds keep-for windows and
"Delete now" with a confirm. Version has "Check", "Restart splice", "Upgrade to X", "Go back to X". Reasoning effort, trace retention
days and trace body size have no control (TOML only, by decision).

**Requests.** Filters: window, command, session, model, account, outcome ("All", "Failed", "Stopped"), "Started over", "Compactions".
A failed row names the cause in plain words: Overloaded, Network error, Given up after a silence, Cut off, Rate limited, No quota,
Signed out, Credentials refused, No access, Invalid request, Over budget, Too many at once, splice restarted, Provider error, Internal
error. What each means and what happens next is in reliability-mcp-install.md 1.5.
Search needs two characters and covers bodies. The open request offers "Open session", "Copy JSON", "Download".

Source: `models.js:13-14,139-187,270-330,429-466,654-707,768-916,1096`, `settings.js:32-41,236,629-1144`, `knobs.js:39-120`,
`requests.js:130,695-756,1073-1075`, `kit.js:99-135,389-397`, `docs/specs/v0.4.0.md` section 9 (eight pages; "TOML only, by decision").

## Accounts and login

### Sign-in kinds

| Provider | Command | Auth kind | Sign-in | Credential file |
|---|---|---|---|---|
| Claude | `claude-splice` | `client` | `/login` inside the command | Claude Code's own config directory for that command |
| ChatGPT | `claudex` | `chatgpt-oauth` | `claudex login` (browser) or Accounts (device code) | `~/.config/splice/auth/codex.json` |
| Grok | `claude-grok` | `grok-oauth` | `claude-grok login` (browser) or Accounts | `auth/grok.json` |
| Kimi | `claude-kimi` | `kimi-oauth` | `claude-kimi login` (device code) or Accounts | `auth/kimi.json` and a `device_id` |
| Muse | `claude-muse` | `muse-oauth` | `claude-muse login` (device code) or Accounts | `auth/muse.json` (account token and a minted inference key) |
| API key | `claude-openrouter`, `claude-deepseek`, others | `api-key` | `splice key set <ENV_NAME>` or `<command> login` (masked) | `~/.config/splice/keys.toml`, or the environment |

- Sign-in happens on the vendor's page. Splice keeps its own token copy and does not read the vendor CLI's file unless
  `auth.file`, `CODEX_AUTH_PATH` or `GROK_AUTH_PATH` names it. Doctor warns when a head still names one.
- API-key lookup: the daemon's environment variable (`<KEY>_API_KEY` or `auth.env`), then `auth.file`, then `keys.toml`. The
  environment wins. `keys.toml` is re-read per request, so a saved key needs no restart. A variable exported AFTER the daemon
  started is invisible to it until `splice restart`.
- `splice login`, `splice key set`, `splice add` are the person's to run. Give the exact command, wait.
- Never read, print or copy these files. Name a key by its variable. `splice status` and `splice doctor` show presence only.

Source: `README.md` "Credential locations", "Troubleshooting"; `app/.../cli/auth/LoginCommand.kt:38-176`.

### More than one account per provider (the pool)

An OAuth head can hold several accounts of its kind; a head with one has no pool and shows nothing extra.

```
claudex login --label work        # the person runs it; adds an account beside the primary
```

- Without `--label` the primary is signed in again in place. `--label auto` derives a name from the plan plus a short hash.
- Extra accounts live at `~/.config/splice/auth/<kind>/<primary file>/<name>.json` with `<name>-quota.json` beside each.
- A credential file is used only by the provider whose kind it carries; a mislabeled file is refused at boot.
- Accounts offers the same: "Add account" (name, then "Sign in"). ChatGPT, Kimi and Muse use a device code (valid 15 minutes);
  Claude and Grok can also sign in there.
- Each account shows its name, plan, "5 hours" and "Week" windows ("Month" for Grok) with "Resets <time>", its models, its
  sessions, and "Next". A chosen name wins; else the provider-proved email; else a plan word with a number ("Max", "Max 2").
  No account id is shown.

Source: `README.md` "More than one ChatGPT, Grok, Kimi or Muse account"; `features/accounts/.../pool/AccountNaming.kt:49-58`;
`accounts.js:47,387,425,772-782`.

### Rotation and quota exhaustion

- Selection is **sticky per session**. A manual pin goes first, then a saved order ("My order"), else an available account whose
  weekly quota resets soonest (the five-hour reset breaks ties). A session moves when its quota is exhausted, its credential is
  rejected or a provider hold applies; it does not return to the primary because the primary's window reset.
- A switch happens between turns. A turn refused before any answer byte streams moves to a free login in the same turn.
- The first turn after a switch pays one cold prompt-cache read; its Requests row names the account.
- All accounts out before a turn: the turn is refused and names the earliest reset. All out during a turn: it ends with that login's
  own limit message.
- The daemon polls each account's usage every five minutes (`CLAUDEX_QUOTA_POLL=off` disables it). API-key heads do not poll.
- Switching is within ONE provider. Cross-provider fallback for a hard-down head is the owner's decision, never yours.
- Words on Accounts: "Limit reached until <time>", "Signed out", "Token expired", "No key", "Not answering", "No limits reported"
  (the provider answered with none; never zero, never unlimited), "Moved from X to Y" with "Resets sooner", "Rate limited" or "Needs sign-in".

"Why is claude-grok out of quota?" Run `splice status` and `splice sessions` for the account rows, then say which account is out
and when it resets. A head with one account has no pool: the fix is a second login or the reset.

Source: `README.md` "More than one ChatGPT, Grok, Kimi or Muse account", "Plan usage in Claude Code"; `docs/specs/v0.4.0.md`
section 11 (Superseded 2026-10-03; Deviations 2026-10-10); repo `CLAUDE.md` project laws; `accounts.js:369-376,801-828`.

### Pins, Claude accounts, budgets

- **Pin.** "Use now" pins a head to one login from the next turn; "Unpin" releases it. A session's pin outranks the head's for that
  session. A pin on an out-of-quota account falls back to automatic and stays set. A daemon restart clears every pin.
- **Claude.** Claude Code signs in; splice never refreshes the command's own login. Added Claude accounts live in splice-owned
  folders `~/.splice/state/claude-accounts/<head>/<label>/`, which splice may refresh. `splice login claude-splice --label <name>`
  is a different feature: it saves or switches named copies of the one login, changes nothing while any session of the command runs,
  saves the outgoing login first, and fails closed when it cannot tell whose login it is.
- **Budgets.** Accounts' "Day" meter, "Set budget": a dollar cap on the command's estimated API-rate cost for the local day, with
  **Warn** (serves; logs `[<command>][budget] WARN reached` once a day; posts to the webhook if saved) or **Block** (refuses each new
  turn until midnight; a running turn finishes; a Compare send is refused too). Plan-account turns do not count; an unpriced model
  counts as unpriced, so the day can read low. Stored in `budgets.json`, applies within a second.

Source: `README.md` "Pin a head or a session to an account", "More than one Claude login on claude-splice", "Daily budgets";
`app/.../auth/claude/ClaudeAccountFolders.kt:1-30`; `accounts.js:451-533,645-659,1183-1191`.

## Models and routing

### Where the model list comes from

- A head's **catalog** is its `[[providers.<p>.models]]` rows, filtered by the head's `models` allowlist or mapped by `model_slots`,
  plus rows discovered from the provider (OpenRouter's list is re-read at start and hourly).
- At each launch the daemon writes the catalog into the head's Claude config directory (`~/.claude-<head>/settings.json`
  `availableModels` and `.claude.json` `additionalModelOptionsCache`). That is what `/model` shows, as bare ids.
- The launch sets `ANTHROPIC_MODEL` to the head's `pinned_model` (without a pin the client chooses) and one
  `ANTHROPIC_DEFAULT_<TIER>_MODEL` per tier slot: `opus`, `sonnet`, `haiku`, `fable`. A tier left unset falls to Claude Code's built-in
  model, which the head rejects, so a subagent on it fails. Map every tier you can, even to a cheap model.
- A head serves only its own models. `discovery_prefix` (such as `claude-codex--`) wraps ids so Claude Code keeps them; it is
  stripped before the request leaves.
- The Models page shows the rows; "Not served" means the provider no longer lists a declared row. `splice models` is the CLI view.

Source: `features/launch/.../recipe/LaunchService.kt:1-12,340-352`, `LaunchModelEnvironment.kt:12-60`, `core/.../model/ModelCatalog.kt:183-202`,
`README.md` "API-key starter: OpenRouter", `models.js:13-14,429,438,920`. Row and slot fields: toml-reference.md.

### Model ids and the tier suffix

| Spelling | Meaning |
|---|---|
| `gpt-6-sol` | A row id, sent upstream as written |
| `k3[1m]`, `grok-4.6[500k]` | A **selector** row. The suffix is a hint to the client. Splice strips it, so the provider sees `k3` |
| `claude-codex--gpt-6-sol` | The discovery wrapper, accepted and unwrapped |
| a row's `client_model` | The Claude model name the client is told the row is; the head still sends the row's own id |

The suffix grammar is exactly `[<digits><k|m>]` at the end, case-insensitive. Any other bracket (`model[preview]`) is part of the vendor's id
and reaches the wire unchanged. Two picker rows can share one upstream id and differ in the window the client is told.

Source: `core/.../model/ModelTierSuffix.kt`, `ModelCatalog.kt:5-8,60,189-202`, `splice.example.toml:429-445`.

### Context windows: how one client serves a 1M and a 256k model

Claude Code fixes its window per PROCESS (`CLAUDE_CODE_MAX_CONTEXT_TOKENS`) and compacts when
`(input + cache_creation + cache_read) / window` crosses a line (85% here). Splice writes the numerator, so it makes any row compact
at its own real window.

1. At launch the daemon sets `CLAUDE_CODE_MAX_CONTEXT_TOKENS` to the pinned row's declared window and `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=85`.
2. After `/model` to a row with another window, the head **scales the input token counts it reports** by `client window / declared
   window` (`usageScale`). Output tokens and raw upstream accounting are never scaled.
3. An id with `[1m]` anywhere in it is different: Claude Code uses exactly 1,000,000 and never reads the env. A `[1m]` row must declare
   `context_window = 1000000`, not 1024*1024. Never spell a row `[1m]` past its real ceiling: the client would run to about 850k while the
   backend rejects at 500k. Scale instead.
4. An id starting `claude-` ignores the env; the client's own table or the window its status line reports applies.
5. Unsuffixed rows of 425,000 tokens or more are handed to the client with a `[1m]` hint (keeps Claude Code's 100-image cap from ending a
   screenshot session; 600 under `[1m]`), and the head scales to compensate.
6. A `context_window` edit in `splice.toml` needs **no restart**. The daemon re-reads model `context_window`, `extra_windows`,
   `window_rules`, `default_context_window` and a head's `context_window`; running sessions compact at the new size at once. Every other key
   is boot-only, and doctor reports the file stale until `splice restart`.

Source: `ModelCatalog.kt:15-30,204-282`, `ClientSpelling.kt:1-40`, `LaunchModelEnvironment.kt:55-70`, `LaunchService.kt:349`,
`README.md` "Long-session reliability", repo `CLAUDE.md` project law 22.

### Reasoning effort is part of the cache key

Effort precedence: explicit body field, then Claude Code's `/effort`, then config or env, then `high`. A compaction turn keeps the session's
own model and effort and is built like any turn; a mismatch invalidates the cache and re-reads the transcript cold. So splice never varies
effort per turn type, nobody proposes it, and `effort` has no console control. `mirror_reasoning` stays false on every head and cannot be
enabled by TOML, env or console: tell the person only the owner can revisit it.

Source: `AGENTS.md:49-53`, repo `CLAUDE.md` project laws, `docs/specs/v0.4.0.md` section 9.

## Sessions

### What a session is to splice

- Claude Code records each session in `~/.claude/sessions/<pid>.json` (the **registry**). Splice reads it and joins each row to a head.
  Every head's config links its `sessions` folder to the shared registry (default `[claude].share`), which is how sessions on different
  heads see and message each other through ListAgents and SendMessage.
- Head is known by the launch splice recorded, or for a session already running at upgrade by the head port its requests arrive on.
  `direct` means the session never went through splice; `unknown head` means splice cannot place it yet.
- Headless `claude -p` runs never register.

| Registry word | Meaning | Console word |
|---|---|---|
| `live` | Process runs, entry updated within 30 minutes | Working, Idle, Needs you, Stalled |
| `stale` | Process runs, entry older than 30 minutes | "Not heard from since <time>"; never shown as working off its own status |
| `gone` | Process exited | Ended (`splice sessions` prints `gone`) |

"Needs you" is a question waiting (permission, choice). "Stalled" is a turn retrying or given up: it shows the retry count and "since",
then "Resumes in <counter>" (splice will re-anchor a silence) or "Won't resume" (needs the person). "At limit" (with the window's reset)
and "Signed out" appear when a plan limit or a missing credential ended the turn. A working turn
reads "Working · <time>", "Compacting · <time>" or "In line · <time>" (queued at splice's gate). Stale is not dead: a long foreground tool
can outlast the window.

Source: `docs/specs/v0.4.0.md` section 4, `README.md` "Heads that see each other", `features/sessions/.../registry/SessionRegistry.kt:23,29,243`,
`console/kit.js:346-386`, `sessions.js:247,897-905`.

### The Sessions page

Grouped by Project (or Command, Team); six idle cards per group, the rest folded; search also reaches ended history. Controls:

| Control | What it does | Needs |
|---|---|---|
| Open a card | Transcript (newest 50, "Show earlier") and live screen | any |
| Answer buttons, "Deny" | Press the numbered choice the session waits on | a session splice opened |
| "Message", "Send" | Types the text into the session's terminal | a session splice opened |
| "Note to this session" | Writes to its messaging socket (202, no receipt, 8000 characters) | a session splice did NOT open |
| "Stop" | Stops the running turn and nothing else | a working turn |
| "End" | Sends `/exit`; warns first about a working turn or unsent text | a session splice opened |
| "Continue on" | Moves the session to another command and model | a session splice opened |
| "Use <login>", "Unpin" | Pins this session to an account | a head with a pool |

Refusals in plain words: "In the prompt" (unsent text in the terminal; "Cancel", "Clear and send"), "Not sent", "Not ended · Started elsewhere"
(`not_ours`: splice never types into a session it did not open), "Not moved", "Not switched". Sessions splice opens run in **tmux** panes. A
session started from the person's own shell can be read and sent a note, not typed into, ended or moved from here.

Source: `sessions.js:222-247,285-321,378-427,637,677-697,802-813,880-917,973-1097`, `app/.../control/mount/SessionsMount.kt:72-127`.

### Continue on

- Moves ONE session to another command (head), optionally another model. The transcript goes on; nothing is sent for the person.
- How: splice stops a running turn, sends `/exit`, waits up to 15 s for the client to leave, then starts `<command> -r <id>` in the same
  terminal. The page marks "Continued on <command> · from <command>".
- Between heads it resumes as a **copy under a new session id**. The original stays untouched on its head. The copy's assistant rows move to
  the new head's model and **thinking blocks are removed** (an emptied row gets `[Thinking removed]`). Another model on the same head, with
  all rows staying, is the same session.
- Refused: while the session waits on an answer; while its prompt holds unsent words; for a session splice cannot reach; onto the command
  it already runs on with no other model. A move that does not come back leaves the session where it was ("didn't start", "didn't answer in
  N s", "Still on <command>", with "Copy" and "Try again"). The menu shows "N% used", "Resets <time>", "Not installed"
  (`splice install --all`), "Signed out".
- By hand: `<command> -r <id>`. A bare `-c` stays with its head. Transcripts are shared by default (`projects` in `[claude].share`), so any
  head's `-r` and picker see every session; a head with `isolate = ["projects"]` copies a foreign session in. A session chosen in the resume
  picker or by `/resume` is rewritten in place after splice keeps the original in its state folder.
- Messaging between sessions is Claude Code's own ListAgents and SendMessage; splice prints the copyable line. A sent message is no proof it
  was read. Sessions carry a derived turn key and cannot call keyed `/api` routes.

Source: `features/sessions/.../http/SessionContinue.kt:1-40`, `docs/specs/v0.4.0.md` section 12 (Resolved, both Deviations 2026-10-13),
`README.md` "Heads that see each other", `sessions.js:973-1058`, `walks/console-shell/MANAGER-BRIEF.md`.

## Teams

A team is a few sessions working toward one goal, each on its own command with a role. Composition is the person's: splice records it, never
enforces it. A team is **slots, never processes**: a slot is a free-text role on a command with an optional model and account, a lead flag
and standing instructions. A session binds to a slot, so the team outlives terminal restarts, and each slot keeps every session it held.
Teams live in `teams.json` in the state folder (not `splice.toml`). Archive sets a flag; nothing is deleted. Each write first copies
`teams.json.bak`; a file that does not parse refuses all writes until it does.

Views per team: "Board", "By command", "By role", "Timeline", beside "Chat" (read from transcripts on demand), "Activity" (a sample every 30 s)
and "Hand-offs". A card reads "Needs you", "Working", "Idle", "Gone", "Ended", "No session", "Starting", "Not started"; "Lead" marks the lead.
Per member and team the page shows requests, tokens, dollars at API rates, "Since <date>" from each member's own join, and plan use
("Last 5 hours", "Last 7 days"). History reaches back only as far as the perf files rotate.

**What a slot does to its session.** A bound session carries, on every turn, a block appended after the head's prompt layers: team and slot, role,
who leads and the address to message, goal, the slot's instructions. An edit applies next turn and costs one turn without the prompt cache.
A session already on another active team is refused.

**How a slot starts, and why it never resumes.**
1. "Start new", "Try again" or "Start team".
2. Splice mints a NEW session id, binds the slot to it before the terminal opens, pins the slot's account, and opens a tmux pane in the team's
   repo running `<command> --session-id <id> --name <slot>`. The first request already finds the role.
3. It waits up to 45 s for Claude Code to register that id. Outcomes: up; asked something first such as the folder-trust question (202; the card
   shows it, splice answers only when the person clicks); "Not started" (504, "Waited N s"); "Terminal closed" (409); "Not answering". A failed
   start puts the binding back and closes the terminal.
4. **A start never resumes.** Each start is a fresh session. To continue earlier work the person uses "Add existing" (binds a running session)
   or Sessions' Continue on.
5. "Joins on its own" (`bind_by_rule`): a session started on the slot's command in a folder at or under the team's repo joins a slot with no
   running session. The first free rule slot wins. It needs a repo.
6. "Stop" stops the member's turn; "End" sends `/exit` to one member; "End all" ends every started member and keeps the team, listing losses
   first; "Archive" and "Restore" never delete.

Source: `docs/specs/v0.4.0.md` section 14, `features/sessions/.../http/TeamStart.kt:1-44`, `teams.js:248-258,270-340,530-579,1004-1260,919`; the
tmux command line is quoted from `walks/console-shell/MANAGER-BRIEF.md` (unverified, not re-read in `TeamStartWiring.kt`).

## Usage and economics

| Figure | What it is |
|---|---|
| Tokens | Input plus output per request. Cached and cache-written tokens sit INSIDE input and are not added again |
| Input, Cached, Written to cache, Output | The Usage "Tokens" table, "as each provider counted them". "Cold after a switch" counts cold-cache turns after an account switch |
| Cost | Sum of each request's `cost_usd`: an **estimate at API rates**, shown "≈$". `$0` means nothing was charged |
| On your plan | Requests a subscription covered, valued "at API prices". Never money spent, never added to Cost |
| No price | The model has no rate card. Rates come from the row's `rates = { input, cache_read, output }`, else the vendor's published price |
| Plan windows | The provider's own used percent for "5 hours" and "Week" ("Month" for Grok) with "Resets <time>" |
| Limits not reported by provider | API-key heads report no windows; never zero, never unlimited |

- Claude Code's status line calls an estimate `API est.` (and `no rate card`); the console says "at API prices" and "≈". The status line marks a
  plan reading with `⚠` at `usageWarnPct` (default 80; 0 turns the early mark off; 98% or more is always marked); it never blocks a request.
- "No project" holds requests whose session folder is in no project. Compare sends sit in their own group "Compare models", never under "No project".
- Usage: "Plans" and "Keys" cards, a chart of "Tokens" or "Cost" over "5 hours" or "7 days" (default 7 days), a "Breakdown" by Accounts, Projects,
  Agents or Models, and "How each command answered" (Requests, Failed, Waited in line, Time before first byte, Time streaming, Retries,
  Cache hit, Cold cache, Most at once). The last is the data of `splice perf --window 1h|24h|7d`.
- Cache: the prompt cache is per account and provider. A switched session pays one cold read. A compaction keeps the session's model and
  effort. Editing a Teams slot's instructions costs that session one cold turn (`slot_prompt_changed=1` on its perf row).
- **Compare** sends one prompt to up to four models, each on one of the person's commands. A send is a **real request**: it uses that command's
  login, counts against its daily budget (over budget: refused with HTTP 429, an "Over budget" row), and leaves a Requests row flagged Compare.
- **Privacy.** Every head records request and response bodies by default (sign-in headers removed) under `~/.splice/state/trace`, in a 32 GiB
  budget; `[heads.<key>.overrides] trace = "false"` stops it for one head. Requests and `splice trace` therefore show prompts: treat them as private.
  Retention windows and "Delete now" are on Settings, Your data; deleting cannot be undone, so send the person there.

Source: `usage.js:17-19,125,204,215-216,250-304,306-472`, `requests.js:125-137,736`, `kit.js:248-251,599`, `perf-heads.js:49-73,181`,
`compare.js:12,123-126,346-377`, `README.md` "Plan usage in Claude Code", "Projects and Compare", "Content from your sessions, kept with
nothing turned on"; `docs/specs/v0.4.0.md` sections 9, 11, 14.

## The splice manager (you)

You are one resident Claude Code session in the global settings folder (the folder holding `splice.toml`, usually `~/.config/splice`). The
person reaches you from the composer at the bottom of every console page. Your rules are in this folder's `CLAUDE.md`.

- **One manager.** The composer is the door to you, not a session picker, and it never messages other sessions. You may, with ListAgents and
  SendMessage, when the person asks. Say which session you wrote to and what you sent. A peer message gets no read receipt.
- **First run asks.** The card "Start the splice manager" lists the heads you can run on (not ready ones carry a short reason) with "Start" and
  "Not now". Consent, head and session id are stored as `manager.json` (0600) in the state folder.
- **It resumes.** An existing manager session that is not running is resumed with `-r <id>` ("Resume"). A live session already in this folder is
  adopted, not duplicated.
- **Changing the head keeps the conversation.** The head chip's menu is "Continue on". If you are live, splice stops your turn, sends `/exit` and
  resumes you on the new head, possibly as a copy under a new id (the pointer follows). Assistant rows lose thinking blocks and take the new model's
  name; nothing else changes. Refused while you wait on an answer (`waiting`), while your prompt holds unsent words (`draft`), or onto your own
  head (`same_head`).
- **Your messages go to the picked head**, possibly a hosted provider. That is why the fence denies you `keys.toml`, `auth/`, `key-ledger.json`
  and `mgmt-key`.
- A start answering 503 means splice has no terminal driver; sessions run in tmux, so check `command -v tmux`.
- `splice init` and every daemon start write or refresh your `CLAUDE.md`, `.claude/settings.json` and this skill from the release. A file the
  person edited is kept and stops taking updates, so an old copy of this skill can outlive an upgrade. If something here looks stale, say so.

Source: `docs/specs/v0.4.0.md` section 15, `README.md` "The splice manager", `walks/console-shell/MANAGER-BRIEF.md` (contract), `console/composer.js:1-20,183-192,
392-433`, `app/.../manager/ManagerHome.kt:1-50`. The routes, `ManagerHome` and the new composer were uncommitted in the shared checkout when read; the
card wording may change.

## Questions you will be asked

| Question | Answer |
|---|---|
| Log in a second Claude account? | Accounts, the Claude card, "Add account" (Claude Code's browser sign-in). Or the person runs `splice login claude-splice --label <name>` while no `claude-splice` session runs |
| A second ChatGPT account? | The person runs `claudex login --label <name>`, or Accounts, "Add account" |
| Why is claude-grok out of quota? | "Rotation and quota exhaustion" above |
| A session looks stuck | `splice sessions`: `stale` means no update in 30 minutes, not dead; `gone` is exited |
| Move a session to another model | Sessions, "Continue on": a copy on the new head, thinking blocks removed |
| Compaction too early or late | The row's `context_window` in `splice.toml`; a `[1m]` row must be exactly 1000000; edits apply live |
| Console says Locked | The person runs `splice console` |
| Use low effort for compaction, or turn on mirror_reasoning | No. See "Reasoning effort is part of the cache key" |
| Add Bedrock or another backend | heads-and-backends.md |
| Config will not boot | `splice check-config` (cli.md) |

## Stale docs found while writing this

| Doc | Claim | What the code shows |
|---|---|---|
| `README.md` "Sign in to a ChatGPT account" | No page shows an email address | A login is named by the chosen name, else the provider-proved email, else a plan word and number (`AccountNaming.kt:49-58`; `v0.4.0.md` Superseded 2026-10-15). Account ids are never shown |
| `docs/specs/web-console.md` 2.6, 4.5, 2.12 | A Claude head holds no pool; no manual switch or doctor route | Claude heads have pools of splice-owned folders (`ClaudeAccountFolders.kt`); `POST /api/auth/{head}/switch` (`accounts.js:1189`) and `/api/doctor` (`models.js:75`) exist |
| `docs/specs/web-console.md` 2.11, 2.12 | A `dashboard` verb; "only sizes and token counts are recorded" | `splice console` replaced `dashboard`; bodies are recorded by default |
| `docs/specs/v0.4.0.md` section 9 | Six pages in the top bar | Eight (Deviation 2026-10-13) |
| `docs/specs/v0.4.0.md` section 11 | Lowest seven-day use; return to the primary after reset | Soonest reset; stays until a limit (Superseded 2026-10-03) |
| `AGENTS.md` | Pinned model rides `ANTHROPIC_CUSTOM_MODEL_OPTION` | No such variable in the code; the launch sets `ANTHROPIC_MODEL` (`LaunchModelEnvironment.kt:16`) |
| `ModelCatalog.kt` header | Picker uses wrapped discovery ids | Picker rows are the materialized roster, bare ids (`LaunchService.kt:1-12`) |
| `README.md` "Heads that see each other" | A team is created with `PUT /api/teams` | That is the route; the person uses the Teams page's "New team" |
| `LogsCommand.kt` header | A missing log is empty output | Prints `[logs unavailable: ...]` |
| `docs/specs/web-console.md` 4.14 | "one of the three hot knobs" | `Knob.kt:14-28` names dozens of LIVE knobs (skill-core counted 39; not recounted here) |

## Claims not verified

- Composer wording and the manager routes' reasons come from uncommitted files (`composer.js`, `ManagerReplies.kt`, `ManagerHome.kt`).
- The tmux command line for a Teams start is quoted from `walks/console-shell/MANAGER-BRIEF.md`, not re-read in `TeamStartWiring.kt`.
- Models and Settings control labels were read by a helper from the page JS; the daemon behaviour behind each label was not traced.
