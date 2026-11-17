# Claude Desktop's Code tab on splice (`desktop-door`)

## What a person gets

Someone who uses Claude Desktop points its Code tab at splice, the way Claude Code is pointed at Amazon Bedrock or
any other provider it supports.

- `splice desktop on` opens the door and says five variables to set once in Desktop's own environment store
  (Settings, Claude Code, Environment variables). From the next session, the Code tab runs on the model `on` chose,
  on that model's head, whichever of Desktop's own models its picker shows.
- There is one connection, not one per head. `splice desktop on --model <selector>` moves the Code tab to another
  model with nothing set again in Desktop.
- The Code tab keeps its sessions where the terminal's `claude` keeps its own, so each lists the other's.
- Chat and Cowork stay on claude.ai. splice writes nothing into `~/.claude/settings.json`, so the terminal's
  `claude`, every head's command and every session already running stay exactly as they were.
- `splice desktop off` closes the door and says which variables to remove in Desktop to put the Code tab back on the
  person's own Claude login.

## How Desktop is reached

Desktop's Code tab runs its own copy of Claude Code. In its standard mode it sets that copy's `ANTHROPIC_BASE_URL`
and its claude.ai token itself, and its launch environment outranks the settings file, so a base URL is closed to
splice. It does not set Claude Code's provider switches, and Claude Code reads those from the `env` block of
`~/.claude/settings.json` for every local session
([Claude Code settings](https://code.claude.com/docs/en/settings)). One of them, Mantle, is the Anthropic Messages
API under another base URL
([Claude Code on Amazon Bedrock, "Use the Mantle endpoint"](https://code.claude.com/docs/en/amazon-bedrock)), which
is the API a head already serves. Desktop's other way to a gateway, its third-party inference configuration, moves
the whole app, Chat included.

Until 0.6.1, `on` wrote those variables into `~/.claude/settings.json`. Claude Code reloads that file's `env` in every
running session, so a terminal session started while Desktop was off went to the door when `on` ran. Desktop has a
store of its own for the Code tab's environment, read on November 16, 2026 from Desktop 5.11492.5 (its app bundle):

| | Read |
| :- | :- |
| Where | `ccd-environment-config.json` in Desktop's data folder: `~/.config/Claude` on Linux, `~/Library/Application Support/Claude` on macOS, `%APPDATA%\Claude` on Windows. Mode 0600 |
| Key | `envVars`: one JSON object of names and values, encrypted with the system keyring through Electron's `safeStorage` (Keychain, DPAPI, libsecret). With no keyring Desktop saves nothing |
| Set from | Desktop's settings, the environment variables of Claude Code. Desktop's own message points there to set `CLAUDE_CONFIG_DIR`. The exact path on screen is confirmed in the live test |
| Order | A Code tab session starts from Desktop's own environment, takes the stored variables over it, then Desktop's own session values (`ANTHROPIC_BASE_URL`, its claude.ai token, the entrypoint) |
| Dropped | Only `CLAUDE_CODE_PROCESS_WRAPPER`, `CLAUDE_CODE_HOST_OFFERS_DEVICE_MCP`, `CLAUDE_CODE_SIMPLE`, `CLAUDE_AGENT_SDK_MCP_NO_PREFIX` and the entrypoint's names. In its claude.ai mode Desktop sets no provider switch and strips none |
| Read by | The Code tab's sessions, and no terminal session |

So the Mantle switch, the door, the key and the window reach the Code tab's Claude Code from that store. Two limits:
splice can't write it, since only Desktop holds the keyring secret it is encrypted with, so the person sets it once;
and it holds environment variables only, so a `modelPicker` can't go there and Desktop's picker keeps its own rows.

Read on November 16, 2026 from the Code tab's own Claude Code (2.1.325) against a stand-in, with nothing set but
its settings file:

| | Seen |
| :- | :- |
| The switch | `CLAUDE_CODE_USE_MANTLE=1`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL=http://127.0.0.1:<port>/desktop` and `AWS_BEARER_TOKEN_BEDROCK=<key>` in the file's `env` are enough. No region, no login screen. The status line reads `Amazon Bedrock (Mantle)` |
| Start-up check | `POST /desktop/v1/messages`, `max_tokens` 1, not streamed, model `anthropic.claude-opus-5-5` |
| A turn | `POST /desktop/v1/messages?beta=true`, streamed, the usual Claude Code body and `anthropic-beta` list |
| `/context` | `POST /desktop/v1/messages/count_tokens?beta=true` |
| Key | `Authorization: Bearer <key>`. No `x-api-key`, no AWS signature, no claude.ai token |
| Session | `x-claude-code-session-id` on every request |
| Picker | A `modelPicker` in the file with `replaceBuiltInOptions` lists its rows under their labels, and `Default`. Model discovery from a gateway does not run under a provider switch, so the file is the one way to a lineup |
| Model | The picked row's `model` is sent as written, for the turn and for the small side calls. `ANTHROPIC_MODEL` in the file's `env` starts a session on a row |
| Window | `CLAUDE_CODE_MAX_CONTEXT_TOKENS=400000` in the file's `env` is the window of a model Claude Code does not know, whether or not its ID begins with `claude-`: `/context` read `2.2k/400k` |
| Cancelling it | A launch with `--settings` whose `env` sets those variables to the empty string, and whose `modelPicker` has no rows, ran as if the file held none of it: the turn went to the launch's own `ANTHROPIC_BASE_URL` and the picker listed the built-in models. The file's `model` still applied |

Not seen: Desktop's own picker. The rows were read in the Code tab's Claude Code run in a terminal. Since 0.6.1
the variables come from Desktop's store instead of the file, and the picker and the cancelling launch are no part
of the door. See the live test.

## Decisions

### 1. One door

splice answers the Code tab under `/desktop`, at one address that a restart does not move:

- `POST /desktop/v1/messages`, with or without `?beta=true`
- `POST /desktop/v1/messages/count_tokens`, with or without `?beta=true`

A turn through the door is a turn of the head its model names: that head's admission, its choice of login, its
cooldowns and its honest failures apply unchanged.

### 2. The model picks the head

- Every model in the door has one selector, the ID its head's own `/v1/models` lists for it
  (`<discovery_prefix><model id>`, as in `claudex--gpt-6.1-sol`). A request that names a selector runs that model
  on that head, and the session is on that head from then.
- Anthropic's own IDs are a tier, not a model: Desktop's picker lists Anthropic's models, and Claude Code sends them for
  its start-up check and for a subagent or skill that names a tier. They come in the Mantle spelling
  (`anthropic.claude-opus-5-5`), with a region prefix (`us.`, `global.`), with a version suffix (`-v1:0`), bare
  (`claude-opus-5-5`) or as the tier's alias (`opus`).
  - In a session that named a selector, a tier runs the model its head names for that tier (`model_slots`), or the
    head's pinned model where it names none.
  - In any other session, a tier runs the default model (decision 4). Claude Code's small tier (`haiku`), its side
    calls, runs the model the default model's head names for it, where it names one.
- Any other model is refused: a 400 `invalid_request_error` in Anthropic's error shape whose message names
  `splice desktop`, and nothing is sent upstream.

This is a fact of Claude Code's dialect and lives with the others, in `integrations/claude-code`.

### 3. The heads in the door

A head is in the door when splice holds the credential its provider is called with: an API key, a ChatGPT login,
any account splice signs in itself.

A head that forwards the caller's own Claude login (`auth = { kind = "client" }`) is not. The Code tab sends no
claude.ai token on this route, and splice never calls Anthropic with a Claude subscription login. Its models are
not in the door, `splice desktop` names it with that reason, and a selector of its is refused as an unknown
model. Claude's models reach the door through a head on an Anthropic API key. On a subscription, they are the Code
tab with the door off.

A head whose login is missing or has expired is left out until it is signed in again, and `splice desktop` names
it with the command that signs it in.

### 4. `splice desktop`

splice writes nothing into `~/.claude/settings.json` for Desktop.

`splice desktop on [--model <selector>]` opens the door and says what to set in Desktop's environment store, one
`NAME=value` line each: `CLAUDE_CODE_USE_MANTLE=1`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL` (the door's address),
`AWS_BEARER_TOKEN_BEDROCK` (the desktop key), `CLAUDE_CODE_MAX_CONTEXT_TOKENS` (decision 5) and
`CLAUDE_CODE_AUTO_MODE_SERVER=0`. They stay set until the person removes them, so a later `on` changes nothing there.

- The default model is `--model`, or the one chosen the last time, or the pinned model of the first head in the
  door. `on` says which it is. Run again, `on` changes it, and the next session runs on it.
- `on` refuses, changing nothing, when no head is in the door, when `--model` is not in it, or when the settings
  file would send the Code tab past the door: a provider switch of the person's own, which Claude Code applies over
  Desktop's environment. It names what it found.
- `splice desktop off` closes the door, and says which variables to remove in Desktop's store. While the door is
  closed it refuses every turn with a 403 `permission_error` whose message says how to open it or leave it, and
  nothing is sent upstream.
- What an older splice's `on` wrote into `~/.claude/settings.json` (its `env`, its picker and a selector the Code
  tab picked into `model`) is taken out at each start of the daemon, and by `on` and `off`, with what it replaced
  put back. A file it left readable by its owner alone gets the mode Claude Code gives a file it creates.
- `splice desktop` says whether Desktop is on, the door's address, the default model, the lineup, each head left
  out with the reason, and the last turn sent through the door. `--json` gives the same as data: `on`, `door`,
  `default_model`, `models` (each with `model`, `label`, `head` and `command`), `left_out` (each with
  `head`, `command` and `reason`) and `last_turn` (`at`, `session_id`, `head`, `model_asked` and `model`, or
  null). The last turn is recorded as it is sent, so it says nothing of how the turn ended: the head's requests do.

### 5. The window

The Code tab's Claude Code has one context window for the whole session and compacts on the share of it a turn's
token counts fill. The models in the door have windows of their own. The window Desktop is told is the default
model's at the first `on`, and it is kept, so what the person set stays right. The door reports each turn's counts
and each `count_tokens` answer as the same share of that window that they are of the model's own, by the scaling a
head already applies to a session launched with another window. A session compacts before its model's limit,
whichever model it is on, and after a switch of model.

### 6. The key

The door has a key of its own, the desktop key, kept in splice's state folder for its owner only.

- It is presented as `Authorization: Bearer` and opens only `/desktop`. Every other route refuses it.
- The turn key and the management key do not open the door. Desktop's store gives the key to every command in a
  Code tab session, so the one key there opens nothing else.
- It is a proof, never a credential: it is not forwarded to any provider.
- `splice rotate-key` leaves it as it is. `splice desktop rotate-key` replaces it and, while Desktop is on, says
  the new one to set in Desktop's store. The replaced key is refused from then.
- A request with no key, a wrong one or a replaced one is answered 401 with an `authentication_error` in
  Anthropic's error shape, whose message names `splice desktop`.

### 7. Recorded

The row of a turn that came through the door says so on `/api/perf/turns`: `door` is `"desktop"`, and the row is
the head's, with the model that ran. Other rows carry no `door`.

### 8. The terminal stays as it was

The door's variables live in Desktop's store, which no terminal session reads, and splice writes nothing into the
settings file the terminal's `claude` reads. A shell inside a Code tab session carries Desktop's environment.

- A launch's recipe unsets `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_MANTLE`, `CLAUDE_CODE_USE_VERTEX`,
  `CLAUDE_CODE_USE_FOUNDRY`, `ANTHROPIC_BEDROCK_BASE_URL`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL` and
  `AWS_BEARER_TOKEN_BEDROCK`, so a head started from inside a Code tab session runs on its head. This also keeps a
  launch on splice on a machine whose Claude Code is set up for Bedrock.
- A head with a config folder of its own never reads the file, and inherits none of an older splice's door from it.

### 9. One folder

The Code tab's sessions on every head are in the `claude` command's folder, written by Desktop. splice writes
nothing there for Desktop; it only takes out what an older splice wrote (decision 4).

A session started on one side and resumed on the other goes on with the model the resuming side is on. Resuming a
session whose last model another head served is the resume gap the terminal has today, and is fixed on its own.

## Out of scope

- The whole app on splice (Desktop's third-party mode), which moves Chat too.
- Claude's models on a subscription login through the door.
- A machine whose managed settings set a provider or a picker.
- Windows.
- The console's Desktop card, built after this as its own feature.

## The live test

Run on the real Desktop once the checks pass, since no check can open it:

1. A terminal `claude` session running, then `splice desktop on`: the terminal session stays on its own head, and
   `~/.claude/settings.json` is unchanged.
2. The five variables set in Desktop's settings as `on` printed them, then a new session in the Code tab: it runs on
   the default model, whichever of Desktop's models the picker shows. Note where on screen the setting is.
3. `splice desktop on --model <selector>` of another head, then a new Code tab session: it runs there. Each turn
   shows on `/api/perf/turns` with `door` `"desktop"` on its head.
4. A subagent that names a tier, and a long session that compacts.
5. A message in Chat and one in Cowork, both on claude.ai.
6. `splice desktop off`: the Code tab's next turn is refused with the message naming the variables. With them
   removed in Desktop, a new session runs on the person's Claude login.

## Items

| Item | What it holds the build to |
| :- | :- |
| `desktop-door` | Decisions 1, 2 and 3 on the wire |
| `desktop-switch` | Decision 4: `on`, `off` and what `splice desktop` says |
| `desktop-lineup` | Decisions 3 and 4: the lineup and the heads left out |
| `desktop-window` | Decision 5 |
| `desktop-key` | Decision 6 |
| `desktop-recorded` | Decision 7, and `last_turn` |
| `desktop-terminal` | Decisions 8 and 9 |
