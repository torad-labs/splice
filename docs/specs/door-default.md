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
  own. Picking one runs the session on it, whichever provider serves it.
- `/login` opens splice's accounts page in the browser, already signed in to the console, where any account of any
  provider is signed in. Claude Code's own `/login`, which only ever signs in to Anthropic, is off.
- A fresh install works this way from the first run. A machine that already ran `claude` through a head keeps doing
  so until its owner switches, and is told how.
- Every command that is a head says, where its user will read it, that heads are going away and what replaces them.

## Decisions

### 1. `claude` has a lane

`claude` runs on one of two lanes: the door, or the head keyed `claude` (the lane it had before).

- `splice claude` says which lane `claude` is on, the door's address, the model a session starts on, the models the
  picker lists, and the heads left out with the reason. `splice claude --json` gives `lane` (`"door"` or `"head"`),
  and on the door lane `door`, `default_model`, `models` (each with `model`, `label`, `head`, `command`) and
  `left_out` (each with `head`, `command`, `reason`).
- `splice claude door [--model <selector>]` puts `claude` on the door. `--model` names the model a session starts
  on; it is kept until another is named. It refuses a selector the picker does not list, changing nothing.
- `splice claude head` puts `claude` back on its head, and says heads are going away. It refuses, changing nothing,
  when splice.toml has no head whose command is `claude`.
- With no choice made, the lane is the door when splice.toml has no head whose command is `claude`, and the head
  when it has one. A choice made with either verb is kept in splice's state, across restarts and upgrades.

### 2. A launch on the door

What `POST /launch/claude` answers the launch shim while `claude` is on the door:

- In `env`: `CLAUDE_CODE_USE_MANTLE=1`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL` (the door), `AWS_BEARER_TOKEN_BEDROCK`
  (the terminal key), `CLAUDE_CODE_MAX_CONTEXT_TOKENS` (the declared window, as the Desktop door declares it),
  `CLAUDE_CODE_AUTO_MODE_SERVER=0`, `ANTHROPIC_MODEL` (the selector of the model a session starts on, so the
  session shows the model it runs), `DISABLE_LOGIN_COMMAND=1` and `DISABLE_LOGOUT_COMMAND=1`.
- In `unset`: everything that would send the session somewhere else: `ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY`,
  `ANTHROPIC_AUTH_TOKEN`, and the Bedrock, Vertex and Foundry switches.
- No `CLAUDE_CONFIG_DIR`: the session runs over the person's own Claude folder, as the wrapped `claude` head does.
  splice changes nothing of theirs in that folder and lays one file of its own there, its `/login` (decision 5).
  Everything else the session needs from splice rides the launch: a `--settings` overlay for the picker and hooks.
- The lane answers whether or not a head keyed `claude` exists, and whether or not anything is signed in.

### 3. The terminal has a key of its own

The door admits two callers, Claude Desktop's Code tab and the terminal's `claude`, each with its own key. Both
start on the one model decision 1 names: since 0.6.4 Desktop has no default model of its own, and
`splice desktop on` takes no `--model`.
`splice desktop off` closes the door to Desktop and leaves `claude` running. `splice desktop rotate-key` changes
Desktop's key only. `splice claude head` retires the terminal's key, so a session started on the door before it is
refused afterwards, in Anthropic's error shape, with nothing sent upstream. A turn through the terminal's key is
recorded as a door turn of the head that ran it.

### 4. The models

The door's grammar is unchanged (`docs/specs/desktop-door.md`, decisions 1 to 3): a selector runs its model on its
head, an Anthropic ID is a tier of the session's head, anything else is refused by name with nothing sent upstream.

The picker of a terminal session lists every model of every head whose provider is called with a login splice holds
(a ChatGPT, Grok, Kimi or Muse sign-in), while that login is good. A head called with an API key, or a local model
server, is in the picker with the models splice.toml declares for it (`splice add api-key --model`), or with every
model it lists when it declares none; the rest of what its endpoint lists still runs when named
(`/model <selector>`). Until Nov 18, 2026 a head on a key was not in the picker at all, which kept OpenRouter's
hundreds of listed models out of it and also a custom API added with its own key and model, the case of a person
using splice at work. A head whose login is missing or has expired is left out until it is signed in again, and
`splice claude` names it with the reason. A head that forwards the caller's own Claude login is never in the door.

The picker rides the launch's `--settings` overlay as `modelPicker` with `replaceBuiltInOptions` true and one row
per model (`model` the selector, `label` the model's own label, `description` `splice · <command>`), in
splice.toml's order of heads. The model a session starts on is `--model`, or the one chosen the last time, or the
pinned model of the first signed-in head in the picker, or, for someone with keys and no sign-in, the pinned model of the first
head in the door. In the picker step the head whose command is `claude` goes first while its accounts are signed in
(Nov 18, 2026), so a `claude` session leaves Claude's accounts for another provider only when none is in the door; and
`splice claude head` takes the chosen model back, so a model named with `--model` does not return with the next
`splice claude door`. With no account signed in the overlay carries no picker, and Claude Code lists its own models,
which the door runs as tiers.

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

### 7. Heads are going away

What is deprecated is starting a session through a head's own command (`claudex`, `claude-grok`, a `claude` on its
head). splice.toml still describes each provider and its models as it does now, and every command keeps working.
What changes is that a person who depends on one is told:

- The launch of a head's command carries a one-line notice, printed by the shim before Claude Code starts: head
  commands are deprecated, `claude` on the door replaces them, and `splice claude door` is how to move.
- `splice status` and `splice doctor` say which lane `claude` is on, and on a machine whose `claude` is on its
  head, that the lane is deprecated and `splice claude door` moves it.
- `README.md`, `AGENTS.md` and the CHANGELOG describe the door as how splice is used, and heads as deprecated.

No head is removed, renamed or refused by this change, and no date is set for removal.

### 8. Install

`splice setup` on a machine with no splice.toml links `claude` to splice on the door and adds no head keyed
`claude`. The Claude plan leaves its tick list; `splice add claude` still adds that head for someone who runs Claude
models on their plan. An upgrade changes no lane.

## Not in this change

- Claude subscription logins. splice never calls Anthropic with a person's Claude login; a head forwards the
  caller's own, and the door has no caller login to forward. Someone who runs Claude models on their plan keeps
  `claude` on its head for now.
- Removing heads.
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
