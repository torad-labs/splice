# Gemini in Claude Desktop through Antigravity (`desktop-antigravity`)

Plan, Nov 21, 2026. Step 1 is built (Gemini rows in Desktop's picker); step 2 is next.

## What a person gets

Antigravity's Gemini models show in Claude Desktop's Code tab picker, each with its reasoning levels, like any other
login provider's models. Pick one and the session runs on Marcos's Google AI subscription through Google's own
`agy acp`. It has streaming replies, tool cards, permission prompts, Stop and history, keeps the running-session
record any tool that lists Claude sessions reads, and takes turns from Desktop's own paths.

## The rules it keeps (Google's terms)

- **agy is never in a Claude Code loop.** For a Gemini row, the launcher runs the translator *instead of* `claude`.
- **agy is Google's binary, unmodified.** agy signs in by itself, splice never reads `~/.gemini`, and Desktop's
  system prompt is never sent.
- **The translator names itself** in `clientInfo` as `splice`, never as `zed`.

Prompts reach a Gemini session through Marcos typing, the Eli App, Desktop's own paths (`send_message`, dispatch,
scheduled tasks), and Splice's local peer messaging (`splice peer` / `PeerNoteSocket` via `splice desktop acp`'s
in-process UDS inbox and ACP `session/prompt`, framed as `<cross-session-message>`). Marcos approved keeping this
inbound peer inbox on Nov 23, 2026 (reversing the Nov 21 note that kept inbound Splice peer delivery out) so `agy`
sessions can both send and receive `splice peer` messages natively while preserving all three rules above (`agy`
unmodified, never in a Claude Code loop, never reading or writing `~/.gemini`, `clientInfo: splice`). Every inbound
peer connection is checked against `SO_PEERCRED` same-user ownership and constant-time `peerToken` validation when an
`auth` line is sent (see section 3 and `docs/peers.md`, "Trust boundary"), and every inbound peer message stays wrapped
in a defused `<cross-session-message>` envelope so it is explicitly distinguished from direct human prompts and carries
peer-message rather than human-operator authority. A Gemini session may also orchestrate other models.

## Wiring

```
Desktop --stream-json--> <state>/desktop-launch (splice desktop launch) --> splice desktop acp --ACP--> agy acp --> Google
```

Nothing outside splice is needed beyond Claude Code's managed `processWrapper` naming `<state>/desktop-launch`
(`splice desktop wrapper` shows it), and Google's `agy`.

### 1. The catalog: Antigravity is one more login provider

splice already builds Desktop's picker this way. `PublishedCatalog.publish` takes each signed-in provider's
`ModelCatalog` as a `CatalogSource`, `CatalogBuild` turns each model's `efforts` into reasoning levels, and
`DesktopModelSettings` writes the `inferenceModels` that Desktop loads at launch.

Antigravity is added as one more source:

- **Where the models come from:** splice asks agy what it offers, using ACP `initialize` and `session/new` (no prompt),
  then closes the session.
- **What goes in:** the `model` option's values become the catalog's entries, with prefix `claude-antigravity--`, and
  each model's level suffixes in `models.availableModels` become its `efforts` (see "What agy offers"). agy's Claude
  models are left out. Desktop reads a Claude id as Claude's own model and runs it on Claude Code, so such a row would
  stand in for the real Claude row and never run on agy.
- **When:** at daemon start, in the background, since agy asks Google (`AntigravityJoin`). A failed try is tried again
  after half a minute and after two more minutes. A signed-out agy isn't retried: a later sign-in shows after a splice
  restart. Once the models arrive, Desktop's list is published again.
- **It's a provider only Desktop runs.** `AntigravityJoin` hands it to the door as `DesktopLineup.desktopOnly`, apart
  from the door's providers. Its models are in Desktop's lists (`inferenceModels`, discovery, `splice desktop --json`)
  and in nothing else: not the terminal's picker, another machine's, the team door's or a starting model, and the door
  routes no turn to it. Until the launcher runs agy (step 2), a Gemini session's turns reach the door and are refused
  as any model it doesn't serve.

The code is in `integrations/providers/antigravity`: `AcpConnection` (the ACP client), `AgyModels`, `AgyRoster` and
`AntigravityCatalog`. App side: `app/.../provider/AntigravityProvider.kt` (`AntigravityJoin`, `AntigravityProvider`).

### 2. The launcher picks the program

`splice desktop launch` (`DesktopLaunch`, `app/.../desktop/pipe/`): when Desktop's command line has
`--model=claude-antigravity--*` (or `--model claude-antigravity--*`), the session runs on `splice desktop acp
--stands-in-for=<claude> <Desktop's arguments>` instead of Claude Code, so its turns never reach the door, which would
refuse them as a model it doesn't serve. On Linux, a launch that carries the no-new-privileges bit (Desktop after an
in-app restart) first runs itself again as a transient user service, `systemd-run --user --pipe --wait --quiet
--collect --same-dir`, passing the environment by name only (`-E NAME`) and relaying TERM, INT and HUP (and QUIT where
the Java runtime gives it up) to it (`PrivilegeEscape`, `EscapedLaunch`).

### 3. The translator is a `claude`

The translator plays the role of `claude` for Desktop. It reads and writes the same stream-json and writes the same
transcript and session files, so a tool that knows Claude sessions finds a Gemini one the same way. `install.sh` also
lays a shim called `claude` in `~/.local/lib/splice/gemini/` that `exec`s `splice desktop acp`, for a launcher of the
person's own that swaps the `claude` program by name; `splice desktop launch` needs none.

When a session opens, `splice desktop acp` binds an owner-only peer Unix domain socket (`DesktopPeerSocketFactory`,
matching Splice's `PeerWire` / `PeerNoteSocket` contract: 0700 socket folder, 0600 socket, 0600
`<pid>.<sha256(socket)>.key` holding `peerToken`, `SO_PEERCRED` same-user check) and advertises
`messagingSocketPath`, `pidDomain`, `procStart` and `peerProtocol: 1` in `<config>/sessions/<pid>.json`. Every
connection must pass `SO_PEERCRED` same-user verification; when a sender opens with an `auth` line its `peerToken` is
compared in constant time (a bad token returns `denied`), while same-user frames without an `auth` line are accepted
after `SO_PEERCRED` verification, matching Claude Code's Linux contract and `PeerHelper` (the trust boundary is the
local Unix user, and claimed `from` / `from-name` attributes never prove identity beyond same-user; see `docs/peers.md`,
"Trust boundary"). Incoming `user` frames are always re-wrapped in a defused `<cross-session-message>` envelope — never
promoted to bare human prompts — so peer messages remain visibly distinct from human instructions and carry peer rather
than human-operator authority under the session's active permission mode. Accepted frames replay into Desktop's
stream-json and transcript, steer `agy`'s `session/prompt` directly (bypassing slash-command interception such as
`/login`), expire atomically on delivery timeout so a timed-out frame is never delivered after a `dropped` receipt, and
return `delivered` / `dropped` / `refused` / `denied` receipts over `PeerNoteSocket`. On exit, the session record, key
file and socket are removed. For outbound peer calls, `DesktopPeerIdentity` best-effort registers an owner-bound
`nativeInbox` peer pointing at the live `splice desktop acp` process (no duplicate `peer-host` helper or `ListAgents`
entry; auto-retiring on exit) and injects `SPLICE_PEER_NAME` and `SPLICE_PEER_KEY_FILE` into `agy`'s environment so
`agy` can run `splice peer send` without `--as`. Nothing touches `~/.gemini` and `agy` runs unmodified.

The translator starts `agy acp` (`--add-dir` passed on;
`--dangerously-skip-permissions` when Desktop's mode is `bypassPermissions`). It speaks stream-json to Desktop and ACP
to agy:

| Desktop | agy |
|---|---|
| `initialize` request | ACP `initialize`; answer with the models, commands, `agents: []`, output styles, account |
| new session / `--resume=<id>` | `session/new` / `session/resume` |
| `user` line, also mid-turn | `session/prompt` (agy steers a running turn) |
| text and thinking stream, `assistant` | `agent_message_chunk`, `agent_thought_chunk` |
| tool cards (`Bash`, `Edit`, `Read`, `Grep`, `WebFetch`, `Task` + `system/task_started` / `system/task_notification`) | `tool_call` / `tool_call_update` by kind (`invoke_subagent` → `Task`) |
| `can_use_tool` | `session/request_permission` |
| `interrupt` | `session/cancel` |
| `set_model`, effort (`--effort`, `apply_flag_settings`) | `session/set_config_option` (`model`, `thought_level`) |
| `set_permission_mode` | `session/set_mode` |
| `result` | the prompt's `stopReason`, with usage when agy reports it |
| anything else | an error reply, so Desktop never waits |

The model name maps back to agy's id through `ReadableNames.upstreamOf`.

Like `claude`, it writes `~/.claude/projects/<cwd>/<id>.jsonl` and `~/.claude/sessions/<pid>.json`, which Desktop already
reads.

The ACP client is `AcpConnection` in `integrations/providers/antigravity` (step 1). The translator's mapping goes in
that module beside it, and its `splice desktop acp` subcommand in `app/.../desktop/acp/`.

### 5. A live session switches to Gemini and back (Marcos, Nov 21, 2026)

Desktop changes a running session's model with `set_model` on the stdin of the process it already runs. It starts no
new process, so the program itself has to change underneath the session. `splice desktop launch` is the one place that
can do that: for every stream-json session it relays Desktop's stdin (`DesktopPipe`, `PipeSession`), and it gives the
session's stdout straight to the program, so it can give that same stdout to a new program without ever reading it.
splice can't do it from the door: a turn that reaches the door comes from Claude Code, and Claude Code driving agy is
what Google forbids.

- **The launch pipe (splice):** it keeps the session's first line (`initialize`). When it relays a
  `set_model` to a `claude-antigravity--` model, it ends `claude`, starts the translator with the same session id,
  replays the kept `initialize`, then the `set_model`. A `set_model` back to any other model does the reverse:
  it ends the translator and starts `claude --resume <session id>`. The session id comes from Desktop's `--session-id`
  or `--resume`, else from the running program's `<config>/sessions/<pid>.json`. Claude Code is only told its input
  ended, so it finishes its turn; the translator is given the `set_model`, which cancels its turn and ends it.
- **The translator (splice):** started mid-session, it answers the replayed `initialize` (Desktop ignores an answer
  to a request it already had answered) and the `set_model`. It opens an agy session and gives it the conversation
  so far, read from the session's Claude transcript. That handoff is lossy: Claude's reasoning and tool details
  don't carry over, as T3 Code documents for its own switch.
- **Back to Claude:** the translator writes Claude-format transcript lines, so `claude --resume` picks the
  conversation up, Gemini's turns included.

## Not built (agreed Nov 21, 2026)

Each of these came up and was dropped. They stay out unless Marcos asks for them again.

- **No new provider type, dialect or `splice add` profile.** Antigravity is a `ServedProvider` held apart from the
  door's, in `DesktopLineup.desktopOnly`, because `DoorCatalog` builds Desktop's list from served providers.
- **No change to `DoorRouting`.** It already refuses a model it doesn't serve.
- **No profile for the translator in any session tool.** It keeps Claude Code's session files.
- **No sender check of any kind** on Desktop's stream-json stdin (the peer UDS inbox enforces `SO_PEERCRED` same-user
  and constant-time `peerToken` verification when an `auth` line is sent, and keeps inbound peer frames wrapped in
  `<cross-session-message>` rather than bare human prompts).
- **No filtering of prompts Desktop writes itself:** `send_message`, dispatch, scheduled tasks, usage-limit
  auto-resume and the skipped-form line.
- **No queueing of mid-turn messages for Send now / Cancel.** agy steers at once.
- **No Gemini API-key provider for agents.** The OpenRouter Gemini row already covers it.
- **No mapping file of session ids.** The probe showed agy's session ids are uuids, so agy's id is the session id.

## What agy offers (probe, Nov 21, 2026; agy 1.5.14, Google AI Ultra)

- **Handshake:** `initialize` answers protocol 1, `Antigravity ACP Server` 1.5.14. Sessions support `load`, `resume`,
  `list`, `close` and `additionalDirectories`, and auth reports `oauth-personal` among its methods. `session/new`
  returns the session id (a uuid), `models`, `modes` and `configOptions`, then sends `available_commands_update` and
  `usage_update {used: 0, size: 1048576}`.
- **Models:** the `model` option lists the base models:
  - Gemini 4 Neon, Carbon, Argon and Hydrogen;
  - Orchestrator (Neon, Carbon, Argon);
  - Gemini 3.8 Flash;
  - Claude Opus 5.5 and Claude Sonnet 5.5;
  - GPT-OSS 120B.

  These are the catalog's entries, except agy's Claude Opus and Sonnet (decision 1 says why). agy offers its Claude
  and GPT models to this client too.
- **Reasoning levels:** they come per model from `models.availableModels`, whose ids are the base id plus a level
  suffix:
  - Neon and Hydrogen: low, medium, high, xhigh;
  - Carbon, Argon, Flash, Opus and Sonnet: low, medium, high;
  - Orchestrators and GPT-OSS: none.

  The `thought_level` option (low, medium, high, xhigh "Extra") is what a turn sets. Each entry's `efforts` is its
  suffix list. The default is the level in `currentModelId` (high).
- **Context window:** 1,048,576 for every model, from `usage_update.size`.
- **Modes:** `default`, `accept-edits`, `auto`, `plan`. Desktop's `bypassPermissions` maps to
  `--dangerously-skip-permissions`; `acceptEdits` to `accept-edits`; `plan` to `plan`.
- **System prompt:** agy advertises `_meta.antigravity.systemPrompt.append`. The translator doesn't use it.
- **Commands:** agy's slash commands (`/goal`, `/compact`, `/plan`, `/teamwork`, `/swarm`, skills …) come in
  `available_commands_update` and go to Desktop as the session's `commands`.

## Steps

0. Marcos installs `agy` and signs in. A probe records agy's handshake: models, thought levels, modes, session id.
   agy 1.5.14 was installed on Nov 21 into `~/.local/bin/agy` from Google's release manifest, checksum checked. Its
   shell-setup step (`agy install`) was skipped, since `~/.local/bin` is already on PATH. Marcos signed in the same
   day, and the probe ran (see "What agy offers"). **Done.**
1. The ACP client and the Antigravity catalog source. Gemini rows appear in Desktop's picker with their reasoning
   levels. **Built Nov 21** (commit 361a0fb2c, then the review fixes).
2. The translator, its `claude` shim and the launcher line. A Gemini session works end to end.
3. The live switch (decision 5): the launch pipe's swap and the translator's mid-session start. **Built** in
   `splice desktop launch`, with no program outside splice.
4. Desktop's in-app tools for Gemini, so a Gemini session can orchestrate: the translator passes Desktop's MCP servers
   to agy in `session/new`.

## The live test

- Desktop's picker lists the Gemini rows with their reasoning levels.
- A turn streams.
- Bash and Edit cards show, and a permission prompt appears in default mode.
- Effort changes reach agy.
- Stop works.
- A reopened session shows its history.
- A live switch to Gemini and back keeps the conversation.

Builds and tests run `on-box`. The live test runs here.
