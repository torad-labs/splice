# The door is how `claude` runs

One command, `claude`, reaches every model a person has signed in to, and signing in to another account is one
command typed inside the session. A command per provider (`claudex`, `claude-grok` and the rest, which splice calls
heads) is the older design. It keeps working and now says it is going away.

The door is what `docs/specs/desktop-door.md` built for Claude Desktop's Code tab: one address that answers Claude
Code's Mantle dialect, where the model a request names decides which provider runs it. This spec puts the terminal's
`claude` on it.

## What a person sees

- `claude` starts as it always did, in their own Claude folder, with their own sessions, settings, skills, hooks and
  MCP servers.
- `/model` lists the models of the accounts they are signed in to, and the models they added on an API key of their
  own: the Splice gateway's one list, the same in every session. Picking one runs the session on it, whichever
  provider serves it.
- `/login` opens splice's accounts page in the browser, already signed in to the console, where any account of any
  provider is signed in. Claude Code's own `/login`, which only ever signs in to Anthropic, is off.
- A fresh install works this way from the first run. A machine that already ran `claude` through a head keeps doing
  so until its owner switches, and is told how.
- Every command that is a head says, where its user will read it, that custom heads are deprecated and will be removed
  in a coming release, and what replaces them; `splice claude retire` retires them, with a way back.

## Decisions

### 1. `claude` has a lane

`claude` runs on one of two lanes: the door, or the head keyed `claude` (the lane it had before).

- `splice claude` says which lane `claude` is on, the door's address, the model a session starts on, the models the
  gateway lists, and the heads left out with the reason. `splice claude --json` gives `lane` (`"door"` or `"head"`),
  `door` (the Splice gateway's address) and `window` (the window every session on it holds) on either lane, `models`
  (each with `model`, `label`, `head`, `command`) and `left_out` (each with `head`, `command`, `reason`), and on the door
  lane `default_model`.
- `splice claude door [--model <selector>]` puts `claude` on the door. `--model` names the model a session starts
  on; it is kept until another is named. It refuses a selector the picker does not list, changing nothing.
- `splice claude head` puts `claude` back on its head, and says heads are going away. It refuses, changing nothing,
  when splice.toml has no head whose command is `claude`.
- With no choice made, the lane is the door when splice.toml has no head whose command is `claude`, and the head
  when it has one. A choice made with either verb is kept in splice's state, across restarts and upgrades.

### 2. A launch on the door

What `POST /launch/claude` answers the launch shim while `claude` is on the door. Since Nov 19, 2026 (head-catalog) it
starts Claude Code in its gateway mode, the way Claude Code runs as a client of any LLM gateway
(code.claude.com/docs/en/llm-gateway-protocol.md), pointed at the Splice gateway, the server this spec calls the door:

- In `env`: `ANTHROPIC_BASE_URL` (the Splice gateway's address, `http://127.0.0.1:<control port>/desktop`, which
  speaks the Anthropic Messages format), `ANTHROPIC_AUTH_TOKEN` (the terminal key, a bearer, so no API-key approval
  prompt shows), `CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1` (so `/model` is filled from the gateway's
  `/v1/models`, decision 4), `ANTHROPIC_MODEL` (the selector of the model a session starts on, so the session shows
  the model it runs), `CLAUDE_CODE_MAX_CONTEXT_TOKENS` (the declared window), `CLAUDE_CODE_AUTO_MODE_SERVER=0`,
  `DISABLE_LOGIN_COMMAND=1` and `DISABLE_LOGOUT_COMMAND=1`. Since Nov 23, 2026 (#791), also what a head's launch sets
  from the same code: the client's timers (`API_TIMEOUT_MS` from the longest wall of the providers and heads a turn
  can run on, or for another machine's door the wall that machine says; `API_FORCE_IDLE_TIMEOUT=0`,
  `CLAUDE_CODE_RETRY_WATCHDOG`, the non-streaming retry cap, the stream and byte idle timers) and the wall header in
  `ANTHROPIC_CUSTOM_HEADERS`; `CLAUDE_CODE_AUTO_COMPACT_WINDOW` from the declared window and
  `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`; and `NO_PROXY` and `no_proxy`, the caller shell's lists (which the launcher sends,
  shim-30) and the daemon's, with loopback and the door's host added. A caller's `--model` (an option, read with
  Claude Code's own grammar) leaves `ANTHROPIC_MODEL` unset.
- `ANTHROPIC_CUSTOM_HEADERS` in the recipe holds splice's own headers alone (the wall, and a caller's `--settings`
  headers taken out of the overlay). The launcher puts the caller shell's headers before them, without the four splice
  plants (`x-splice-client-wall-ms`, `-client-window`, `-launch-proof`, `x-splice-client`), so a header that holds a
  secret of the caller's never reaches the daemon and an outer session's never rides along.
- In `unset`: everything that would send the session somewhere else or lay a list of models beside the gateway's:
  every provider switch (`CLAUDE_CODE_USE_BEDROCK`, `_VERTEX`, `_FOUNDRY`, `_MANTLE`), the Mantle variables
  (`ANTHROPIC_BEDROCK_MANTLE_BASE_URL`, `AWS_BEARER_TOKEN_BEDROCK`), `ANTHROPIC_API_KEY`, `CLAUDE_CODE_OAUTH_TOKEN` and
  its refresh token, `_CLAUDE_CODE_ASSUME_FIRST_PARTY_BASE_URL`,
  the tier variables (`ANTHROPIC_DEFAULT_{OPUS,SONNET,HAIKU,FABLE}_MODEL` with their name and description), and
  `ANTHROPIC_SMALL_FAST_MODEL` and `ANTHROPIC_CUSTOM_MODEL_OPTION`.
- No `CLAUDE_CONFIG_DIR`: the session runs over the person's own Claude folder, as the wrapped `claude` head does.
  splice changes nothing of theirs in that folder and lays one file of its own there, its `/login` (decision 5).
  Everything else the session needs from splice rides the launch's `--settings` overlay: the launch mark and the
  hooks, and no model list.
- The overlay's `env` says again every value of `env` but the key and `ANTHROPIC_CUSTOM_HEADERS` (which can carry the
  shell's own secrets), and every variable of `unset` spelled taken out: each provider switch `0`, every other
  credential, tier and model option empty (#791). A person's settings files apply their `env` over the process
  environment, and only the `--settings` overlay outranks them, so a settings file's model, tiers, switches, window or
  timers no longer win over the launch's. The overlay is an argument any local user can read, so no secret goes in it.
  A `--settings` the caller passes merges into the overlay; its `env` cannot move these values, and every other key it
  carries, a `statusLine` among them, stays the caller's.
- The lane answers whether or not a head keyed `claude` exists, and whether or not anything is signed in.

Why the Mantle switch went: until Nov 19 the launch set `CLAUDE_CODE_USE_MANTLE=1` with the door as its address, and
Claude Code does not run gateway discovery while any `CLAUDE_CODE_USE_*` switch is set. Door-default chose Mantle for
two things, and gateway mode keeps both (see the gateway-mode rows of the table below): no sign-in screen shows, since
the bearer is a credential Claude Code takes as it is, and a selector in `ANTHROPIC_MODEL` (or `--model`) starts the
session on it over a `model` in the person's settings.

### 3. The terminal has a key of its own

The door admits two callers, Claude Desktop's Code tab and the terminal's `claude`, each with its own key.
`splice desktop off` closes the door to Desktop and leaves `claude` running. `splice desktop rotate-key` changes
Desktop's key only. `splice claude head` retires the terminal's key, so a session started on the door before it is
refused afterwards, in Anthropic's error shape, with nothing sent upstream. Every head's command starts its session on
the Splice gateway with the terminal's key too (decision 7), so the gateway admits it on either lane. A turn through the terminal's key is
recorded as a door turn of the head that ran it.

### 4. The models

The door's grammar is unchanged (`docs/specs/desktop-door.md`, decisions 1 to 3): a selector runs its model on its
head, an Anthropic ID is a tier of the session's head, anything else is refused by name with nothing sent upstream.

The models are every model of every head whose provider is called with a login splice holds (a ChatGPT, Grok, Kimi
or Muse sign-in), while that login is good, and of a head called with an API key, or a local model server, the
models splice.toml declares for it (`splice add api-key --model`), or every model it lists when it declares none; the
rest of what its endpoint lists still runs when named (`/model <selector>`). Until Nov 18, 2026 a head on a key was
not listed at all, which kept OpenRouter's hundreds of listed models out and also a custom API added with its own key
and model, the case of a person using splice at work. A head whose login is missing or has expired is left out until
it is signed in again, and `splice claude` names it with the reason. A head that forwards the caller's own Claude
login is in the door only on the Claude accounts added to splice.

The one list (head-catalog, Nov 19, 2026: every session sees the same models, and logins differ only in which models
they serve). The Splice gateway answers Claude Code's model discovery itself: `GET <gateway>/v1/models` (`limit` up to
1000, `after_id` for paging), behind the terminal's key as a bearer or `x-api-key` (and the desktop key), answers at
once, from the lineup the daemon holds, with no upstream asked and never a redirect, in Anthropic's own list shape
(`data`, `has_more`, `first_id`, `last_id`). Its rows are `splice claude --json`'s `models`, in splice.toml's order of
heads, each once, read at each session's start, so a sign-in, a sign-out or an expired login reaches the next session
with no restart. Each row's `display_name` is the model's own label (with a fully used plan window beside it, `7d
100%`), and its `description` `splice · <command>`. Its `id` is one Claude Code keeps (it keeps only ids containing
`claude` or `anthropic`) and one the gateway runs when a turn names it:

- a Claude model, on the head the gateway's own routing of its bare Anthropic id lands on, by that bare id
  (`claude-opus-5-5`), so Claude Code folds it into the built-in alias row that resolves to it;
- a selector Claude Code would drop (a hand-written `discovery_prefix` such as `zeta--`) as `claude-<selector>`, which
  the gateway reads back as that selector;
- every other model by its selector (`claude-codex--gpt-6.2-sol`).

No list of splice's rides any launch: no `modelPicker` (with `replaceBuiltInOptions` Claude Code hides discovered
models, and any lineup in `--settings` is the whole lineup), no `availableModels` or `enforceAvailableModels` (Claude
Code adds only discovered models such an allowlist allows), no `modelOverrides`, no `additionalModelOptionsCache` and
no tier variables. The rows decision 9 added after Claude Code's own models on `claude`'s head are retired with them:
`claude` on its head lists Claude Code's own models.

The model a session starts on is `--model`, or the one chosen the last time, or the pinned model of the first
signed-in head in the gateway, or, for someone with keys and no sign-in, the pinned model of the first head in the
gateway. The head whose command is `claude` goes first while its accounts are signed in (Nov 18, 2026), so a `claude`
session leaves Claude's accounts for another provider only when none is in the gateway; and `splice claude head`
takes the chosen model back, so a model named with `--model` does not return with the next `splice claude door`.

An organization that wants every Claude Code on its machines pinned to the gateway can set `allowedProviders` in its
own managed settings. It is a managed-only Claude Code setting; splice documents it and never writes managed
settings.

### 5. `/login`

In every session splice launches where Claude Code's own `/login` is off (the door lane and every head splice holds
the credential for), `/login` is splice's: it is in Claude Code's menu, described as opening splice's accounts, and
typing it opens the console's accounts page in the person's browser, signed in, by the same single-use page
`splice console` opens. It is caught before it reaches the model: no turn is sent. On a head it replaces the
`/login` that started that head's own sign-in; signing in to a provider is done on the accounts page.

It has to be the bare `/login`. Claude Code 2.1.326 offers a bare command from two places only (see the table
below): a folder handed by `--add-dir`, which opens every session on a trust dialog, and the session's own Claude
folder. So on the door lane splice lays one file in the person's folder, `commands/login.md`, and nothing else:

- `splice claude door` and every launch of `claude` on the door lay it when it is not there. The daemon starting
  does not.
- A `/login` the person wrote themselves, as a command or a skill, is never replaced. splice leaves it as it is,
  and `splice claude` says that their own `/login` is in the way and where it is.
- `splice claude head` and uninstalling splice take splice's file out, and only splice's.
- The file reads sensibly in a session that has no hook to catch it: its text says what it is for and how to open
  the accounts page (`splice console`).
- The file is in the person's own folder, so a Claude Code that splice did not start and that shares the folder (an
  editor's extension, a plain `claude`) runs it too: Claude Code 2.1.326 lists its own `/login` beside splice's and
  runs the file for either row (see the table). That is accepted while `claude` is on the door, and it is said. The
  file's text, which is what such a session submits, says that `/login` is splice's on this machine and names both
  ways on: `splice console` for splice's accounts, and `claude auth login` for Claude Code's own sign-in to
  Anthropic. `splice claude` says the same in one line while `claude` is on the door, and so does the README where
  it describes the door.
- The hook catches `/login` as Claude Code hands it over: 2.1.326 hands the bare command, and the file's text is
  caught too.

A head keeps its `/login` where it already is, in the head's own folder.

A head that forwards the caller's own Claude login keeps Claude Code's `/login`, which is that login's own door.

### 6. Nothing signed in

`claude` on the door starts with no head in the door: no account signed in and no key held. A turn is then refused in Anthropic's error shape, with a 4xx
that Claude Code does not retry and a message that says no account is signed in and names `/login`. Nothing is sent
upstream. Signing in on the accounts page is enough: the next session's picker lists the account's models with no
restart.

### 7. Custom heads are deprecated and will be removed in a coming release

What is deprecated is a custom head: a head's own command (`claudex`, `claude-grok`, a `claude` on its head) and its
own Claude folder. On Nov 19, 2026 splice's maintainers set its removal (head-catalog): custom heads are deprecated and
will be removed in a coming release. One Claude Code replaces them, `claude` through the Splice gateway, with every
model from the gateway's list, and every model name of today keeps working there: `/model <selector>`, `--model
<selector>` and a subagent's `model` run the same model they ran on its head. splice.toml still describes each
provider and login, which keep serving the gateway.

While they still work, in this release:

- Every head's command starts Claude Code through the Splice gateway in gateway mode, as `claude` does (decision 2),
  in the head's own Claude folder and on the head's own model, with no list of its own: its `/model` is the gateway's
  list. A head that forwards the person's own Claude login, which the gateway never carries, keeps its own way to
  Anthropic, with no list of splice's either.
- A launch of a head's command, or of `claude` in a head's own Claude folder, prints one line before Claude Code
  starts: "`<command>` is a custom head, and custom heads are deprecated and will be removed in a coming release: run
  `claude` and type `/model <selector>`", and where `claude` is not on the Splice gateway, "run `splice claude door`,
  then `claude`, and type `/model <selector>`".
- `splice status` and `splice doctor` say the same, one line per head the install still has, with how to retire it.

A person retires heads with `splice claude retire <command|key>...` or `splice claude retire --all`. For each head it
moves into a dated backup under splice's state (`head-backups/<key>/<local time>/`, with a manifest naming each entry's
original path, kind and sha256), and removes, the head's command link splice laid in the install's bin folder and
every setting splice wrote in the head's own Claude folder: its settings.json, splice's keys in its .claude.json (the
rest of that file, which Claude Code writes, stays), and the links, `/login` command and hook scripts it laid there.
The folder's sessions and transcripts stay, and every file splice did not write is left and named. Retiring the head
`claude` runs on puts `claude` on the Splice gateway, as `splice claude door` does. A head a live session still runs on
is refused, naming each session, and nothing of it changes; nothing is killed. Nothing brings a retired command back
by itself (install, setup, an upgrade and a daemon start skip it), and a launch of it is refused, naming `splice claude
restore`. Its models stay in the gateway's list under the same names. `splice claude restore <command|key>...` or
`--all` puts back, from the newest backup, every link and file exactly as it was, and refuses a head whose file the
person changed since, naming it. Neither command touches a file splice did not write.

`splice claude check [--json]` says how Claude Code starts on the machine and what list each start gets: `claude`,
every head command splice wrote that is still there, and every launcher on `PATH` that starts Claude Code, each with
whether it goes through the Splice gateway and whether its list is the gateway's; then every model list it finds that
would change a session's list, in a head's folder (splice's, which retiring removes) and in the person's own Claude
settings, a launcher's `CLAUDE_CONFIG_DIR` and managed settings (reported, never edited). It reads only. On a machine
whose heads were retired it says one way and one list: `claude` on the Splice gateway.

An upgrade changes no lane and retires no head by itself: retiring is the person's act.

### 8. Install

`splice setup` on a machine with no splice.toml links `claude` to splice on the door and adds no head keyed
`claude`. The Claude plan leaves its tick list; `splice add claude` still adds that head for someone who runs Claude
models on their plan. An upgrade changes no lane.

## Not in this change

- Claude subscription logins. splice never calls Anthropic with a person's Claude login; a head forwards the
  caller's own, and the door has no caller login to forward. Someone who runs Claude models on their plan keeps
  `claude` on its head for now.
- Removing heads: they are deprecated and will be removed in a coming release (decision 7).
- The console: its pages keep saying what they say.

## Seen with Claude Code

Filled in by whoever builds each part, from a real session, with the Claude Code version it was seen on.

| Question | What was seen |
|---|---|
| A launch with the Mantle variables in its environment and a `modelPicker` in `--settings` lists exactly those rows | Claude Code 2.1.326, Nov 17, 2026, a throwaway daemon with two ChatGPT heads: no sign-in screen; `/model` lists the overlay's rows, each `<label>  splice · <command>`, with Claude Code's own `Default` above them and nothing else |
| `ANTHROPIC_MODEL` naming a door selector starts the session on it, over a `model` in the person's settings | 2.1.326: with `model` = `claude-opus-4-1` in `~/.claude/settings.json`, the header read `gpt-6-astra · Amazon Bedrock (Mantle)` and the first turn ran on that head. With no `ANTHROPIC_MODEL` (nothing signed in), the session took the settings' `Opus 4.1` |
| The bare `/login`, with `DISABLE_LOGIN_COMMAND=1`, runs splice's command, laid outside the person's folder | **No**, on 2.1.326. The slash menu offers the plugin's command only as `/splice:login` ("(splice) Open splice's accounts page…"), and a bare `/login` typed and sent answers `Unknown command: /login. Did you mean /plugin?` without submitting the prompt, so no hook sees it. `/splice:login` runs splice's command: the hook ran `splice console` and blocked the turn, nothing reached the model. Two other ways, Nov 17, 2026, on 2.1.326 with a throwaway config folder: a folder handed by `--add-dir` holding `.claude/commands/login.md` puts a bare `/login` in the menu ("Open splice's accounts page, where any account is signed in (project)"), but every session then opens on Claude Code's "Adding a new directory… Is this a directory you created or one you trust?" dialog, with `No, exit` selected, whose answer "holds for this session" (it is skipped only when the folder is under a directory the person already trusted). The same folder named in the `--settings` overlay's `permissions.additionalDirectories` asks nothing and loads no `/login` |
| A 4xx from the door with no account signed in is shown once, with its message, and not retried | 2.1.326: `API Error: 400 splice claude: no account is signed in, so no model is in the door; type /login to sign in on splice's accounts page`, shown once, then the prompt with `Once fixed, resend your message`; no retry was shown (the door's own request count was not read) |
| The bare `/login` from `commands/login.md` in the session's own Claude folder, with `DISABLE_LOGIN_COMMAND=1` and splice's hook in `--settings`, runs splice's command | **Yes**, 2.1.326, Nov 17, 2026, a throwaway Claude folder holding only the file splice lays, the door's variables at a dead address and a stand-in `splice` on `PATH`: no dialog; typing `/login` lists `/login  Open splice's accounts page, where every account splice runs on is signed in (user)` first. Sent, the hook was handed `"prompt":"/login"` (the bare command, not the file's text), blocked the turn with "Opened splice's accounts page in your browser, signed in…", and `splice console` ran; nothing reached the model |
| Where Claude Code's own `/login` is on (no `DISABLE_LOGIN_COMMAND`, no splice hook), `/login` stays Claude Code's own beside the file | **No**, 2.1.326, Nov 17, 2026, the same folder in a plain `claude`: typing `/login` lists splice's row first, then `/login  Sign in with your Anthropic account`, and Enter on either row ran the file: the transcript shows its text submitted as the prompt, answered `Not logged in · Please run /login`, and Claude Code's sign-in never opened. Decision 5 accepts this while `claude` is on the door and says it: the file's text names `splice console` and `claude auth login`, and so do `splice claude` and the README |
| In gateway mode (`ANTHROPIC_BASE_URL` at the Splice gateway, discovery on), does discovery fill `/model` from the gateway's `/v1/models`, and what does `/model` list? | **Yes**, Claude Code 2.1.329, Nov 19, 2026, a throwaway daemon in a HOME of its own with stand-in providers (a `claude` head on an added Claude account, a ChatGPT head `claude-astra`, a key head `claude-borealis`): `/model` lists Claude Code's built-in rows (`Default`, `Opus`, `Fable`, `Sonnet`, `Haiku`), then the discovered ones by `display_name` and `description`: `gpt-6-astra  splice · claude-astra`, `borealis-large  splice · claude-borealis`. Claude Code wrote the gateway's four rows (`claude-opus-5-5`, `claude-haiku-5-5`, `claude-astra--gpt-6-astra`, `claude-borealis--borealis-large`, each with its label and `splice · <command>`) to `~/.claude/cache/gateway-models.json` with the gateway's `baseUrl` |
| In gateway mode (`ANTHROPIC_BASE_URL` at the Splice gateway, discovery on), how does a discovered Claude model sit beside the built-in alias rows? | 2.1.329, Nov 19, 2026: the discovered `claude-opus-5-5` folds into the `Opus` alias row and `claude-haiku-5-5` into `Haiku`; neither gets a row of its own. Seen first: one more row, `Opus 5.5 ✔ (claude--claude-opus-5-5)`, the session's starting model, because the launch named it by its selector in `ANTHROPIC_MODEL`, an id no row has. So the launch now hands Claude Code the starting model as the gateway lists it (`claude-opus-5-5`; GatewayModels.idFor). Seen after, the same day on 2.1.329: `/model` lists `Default`, `Opus ✔`, `Fable`, `Sonnet`, `Haiku`, `gpt-6-astra`, `borealis-large`, each model once, the session on the `Opus` row, and its first turn was answered by the Claude account's stand-in |
| In gateway mode (`ANTHROPIC_BASE_URL` at the Splice gateway, discovery on), does a sign-in screen or an API-key approval prompt show? | **No**, 2.1.329, Nov 19, 2026, in a HOME whose Claude folder was fresh: the theme picker, the security notes and the folder-trust question, then the session, its header reading `Opus 5.5 · API Usage Billing`; no sign-in screen and no prompt to approve the key, which rides `ANTHROPIC_AUTH_TOKEN` |
| In gateway mode (`ANTHROPIC_BASE_URL` at the Splice gateway, discovery on), does a selector in `ANTHROPIC_MODEL`, or `--model <selector>`, start the session on it over a `model` in the person's settings? | **Yes**, 2.1.329, Nov 19, 2026, with `model` = `claude-opus-4-1` in `~/.claude/settings.json`: `ANTHROPIC_MODEL=claude-borealis--borealis-large` started the session on `borealis-large`, and its first turn was answered by borealis's stand-in; with no `ANTHROPIC_MODEL`, `--model claude-astra--gpt-6-astra` started it on `gpt-6-astra` |
| In gateway mode (`ANTHROPIC_BASE_URL` at the Splice gateway, discovery on), what window does the session hold for a selector, and does `CLAUDE_CODE_MAX_CONTEXT_TOKENS` move it? | 2.1.329, Nov 19, 2026, `/context` on `claude-astra--gpt-6-astra`: `5.9k/200k tokens` with the variable at 200000, `/400k` with it at 400000, and `1m` with it unset ("default for an unrecognized model"). So the variable is honoured for a selector, and the launch keeps it set to the window the gateway reports every count as a share of (`splice claude --json`'s `window`). A model Claude Code knows is not moved by it: a session started on `claude--claude-opus-5-5` held `1m` with the variable at 200000 |
| In gateway mode (`ANTHROPIC_BASE_URL` at the Splice gateway, discovery on), does a head's command's session show the same `/model` list as `claude`'s? | **Yes**, 2.1.329, Nov 19, 2026: a session from `claude-astra`'s launch (its own Claude folder, `ANTHROPIC_MODEL=claude-astra--gpt-6-astra`) lists `Default`, `Opus`, `Fable`, `Sonnet`, `Haiku`, `gpt-6-astra ✔`, `borealis-large`, the same rows as `claude`'s session, and its launch printed the custom-head line naming `/model claude-astra--gpt-6-astra` |
