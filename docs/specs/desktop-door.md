# Claude Desktop's Code tab on splice (`desktop-door`)

## What a person gets

Someone who uses Claude Desktop points its Code tab at splice, the way Claude Code is pointed at Amazon Bedrock or
any other provider it supports.

- `splice desktop on` is the whole setup. From the next session, the Code tab's model picker lists every model
  splice serves, from every head splice holds a credential for, and picking a model runs the session on that
  model's head.
- There is one connection, not one per head. A head or a login added to splice later shows in the picker with no
  command run.
- The Code tab keeps its sessions where the terminal's `claude` keeps its own, so each lists the other's.
- Chat and Cowork stay on claude.ai. The terminal's `claude` and every head's command run as they did.
- `splice desktop off` puts the Code tab back on the person's own Claude login.

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

Not seen: Desktop's own picker. The rows were read in the Code tab's Claude Code run in a terminal. See the live
test.

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
  on that head.
- A session is on the head of the last selector it named. A session that has named none is on the head of the
  default model (decision 4).
- Anthropic's own IDs are a tier, not a model: Claude Code sends them for its start-up check, for the picker's
  `Default` row and for a subagent or skill that names a tier, whatever the picker holds. They come in the Mantle
  spelling (`anthropic.claude-opus-5-5`), with a region prefix (`us.`, `global.`), with a version suffix
  (`-v1:0`), bare (`claude-opus-5-5`) or as the tier's alias (`opus`). Each runs the model the session's head
  names for that tier (`model_slots`), or the head's pinned model where it names none.
- Any other model is refused: a 400 `invalid_request_error` in Anthropic's error shape whose message names
  `splice desktop`, and nothing is sent upstream.

This is a fact of Claude Code's dialect and lives with the others, in `integrations/claude-code`.

### 3. The heads in the door

A head is in the door when splice holds the credential its provider is called with: an API key, a ChatGPT login,
any account splice signs in itself.

A head that forwards the caller's own Claude login (`auth = { kind = "client" }`) is not. The Code tab sends no
claude.ai token on this route, and splice never calls Anthropic with a Claude subscription login. Its models are
not in the picker, `splice desktop` names it with that reason, and a selector of its is refused as an unknown
model. Claude's models reach the door through a head on an Anthropic API key. On a subscription, they are the Code
tab with the door off.

A head whose login is missing or has expired is left out until it is signed in again, and `splice desktop` names
it with the command that signs it in.

### 4. `splice desktop`

`splice desktop on [--model <selector>]` writes into `~/.claude/settings.json` and keeps everything else in it:

- In `env`: `CLAUDE_CODE_USE_MANTLE=1`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL` (the door's address),
  `AWS_BEARER_TOKEN_BEDROCK` (the desktop key), `CLAUDE_CODE_MAX_CONTEXT_TOKENS` (decision 5),
  `CLAUDE_CODE_AUTO_MODE_SERVER=0` and `ANTHROPIC_MODEL` (the default model's selector).
- `modelPicker`: `replaceBuiltInOptions` true and one row for each model in the door, in `splice.toml`'s order of
  heads: `model` is the selector, `label` the model's own label, `description` is `splice · <command>`.

It never writes `model`: that key holds the pick the person made, in the Code tab or the terminal.

- The default model is `--model`, or the one chosen the last time, or the pinned model of the first head in the
  door. `on` says which it is. Run again, `on` changes it.
- `on` refuses, changing nothing, when no head is in the door, when `--model` is not in it, or when the file
  already points Claude Code at a provider (a provider switch set to anything but splice's own). It names what it
  found.
- While Desktop is on, the lineup in the file follows splice: a head or a login added or removed is in the picker
  of the next session, with no command run.
- `splice desktop off` takes out what `on` put in and puts back what it replaced. A file that `on` then `off`
  touched reads as it did before.
- `splice desktop` says whether Desktop is on, the door's address, the default model, the lineup, each head left
  out with the reason, and the last turn that came through the door. `--json` gives the same as data: `on`,
  `door`, `default_model`, `models` (each with `model`, `label`, `head` and `command`), `left_out` (each with
  `head`, `command` and `reason`) and `last_turn` (`at`, `session_id`, `head`, `model_asked` and `model`, or
  null).

### 5. The window

The Code tab's Claude Code has one context window for the whole session and compacts on the share of it a turn's
token counts fill. The models in the door have windows of their own. `on` declares one window in the file, and the
door reports each turn's counts and each `count_tokens` answer as the same share of the declared window that they
are of the model's own, by the scaling a head already applies to a session launched with another window. A session
compacts before its model's limit, whichever model it is on, and after a switch
of model.

### 6. The key

The door has a key of its own, the desktop key, kept in splice's state folder for its owner only.

- It is presented as `Authorization: Bearer` and opens only `/desktop`. Every other route refuses it.
- The turn key and the management key do not open the door. The settings file holds a value that every command in
  a session can read, so the one key in it opens nothing else.
- It is a proof, never a credential: it is not forwarded to any provider.
- `splice rotate-key` leaves it as it is. `splice desktop rotate-key` replaces it, writes the new one into the
  file when Desktop is on, and the replaced key is refused from then.
- A request with no key, a wrong one or a replaced one is answered 401 with an `authentication_error` in
  Anthropic's error shape, whose message names `splice desktop`.

### 7. Recorded

The row of a turn that came through the door says so on `/api/perf/turns`: `door` is `"desktop"`, and the row is
the head's, with the model that ran. Other rows carry no `door`.

### 8. The terminal stays as it was

The file `on` writes is the one the terminal's `claude` reads, and a shell inside a Code tab session carries the
file's `env`.

- A launch's recipe unsets `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_MANTLE`, `CLAUDE_CODE_USE_VERTEX`,
  `CLAUDE_CODE_USE_FOUNDRY`, `ANTHROPIC_BEDROCK_BASE_URL`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL` and
  `AWS_BEARER_TOKEN_BEDROCK`, so a head started from inside a Code tab session runs on its head. This also keeps a
  launch on splice on a machine whose Claude Code is set up for Bedrock.
- The wrapped `claude`'s settings overlay, which outranks the file, sets every variable `on` wrote to the launch's
  own value or to the empty string, which Claude Code reads as unset, and carries the picker the launch had before.
- Where the file's `model` holds a selector, picked in the Code tab, the wrapped `claude` starts on its head's own
  default and says so in the launch's warning.
- A head with a config folder of its own never reads the file.

### 9. One folder

The Code tab's sessions on every head are in the `claude` command's folder, written by Desktop. splice writes one
file there, `settings.json`, and only at the person's command. This is a stated exception to head isolation.

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

1. `splice desktop on`, then a new session in the Code tab: its picker lists the lineup, and the session opens on
   the default model.
2. A turn on a model of each head. Each shows on `/api/perf/turns` with `door` `"desktop"` on its head.
3. A switch of model mid-session, and a subagent that names a tier.
4. A message in Chat and one in Cowork, both on claude.ai.
5. The terminal's `claude` and one other head, each on its own models with its own picker.
6. `splice desktop off`, then a new session in the Code tab on the person's Claude login.

## Items

| Item | What it holds the build to |
| :- | :- |
| `desktop-door` | Decisions 1, 2 and 3 on the wire |
| `desktop-switch` | Decision 4: `on`, `off` and what `splice desktop` says |
| `desktop-lineup` | Decisions 3 and 4: the rows, the heads left out, and the lineup following splice |
| `desktop-window` | Decision 5 |
| `desktop-key` | Decision 6 |
| `desktop-recorded` | Decision 7, and `last_turn` |
| `desktop-terminal` | Decisions 8 and 9 |
