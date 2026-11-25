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
  system prompt is never sent. The only text splice adds to agy's system prompt is the local skill index (see "Local
  skills").
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
`--dangerously-skip-permissions` when Desktop's mode is `bypassPermissions`) and sends it ACP `initialize` at once,
before Desktop writes anything, to learn what agy offers (images in prompts, `systemPrompt.append`). It speaks
stream-json to Desktop and ACP to agy:

| Desktop | agy |
|---|---|
| `initialize` request (or the first `user` line, if Desktop sends none) | `session/new`, or `session/resume` for `--resume=<id>`, with Desktop's MCP servers; answer with the models, commands, `agents: []`, output styles, account; then `system/init` |
| `reload_skills` | nothing: the local skills are found again and listed (see "Local skills") |
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
- **System prompt:** agy advertises `agentCapabilities._meta.antigravity.systemPrompt.append: true`, and a request
  passes `"_meta": {"antigravity": {"systemPrompt": {"append": "<text>"}}}`. The translator uses it only for the
  skill index (see "Local skills"). A probe on Nov 24 (agy 1.5.15) showed that `session/new` takes it, while
  `session/resume` with an append adds the new text to what the session already holds instead of replacing it.
- **Commands:** agy's slash commands (`/goal`, `/compact`, `/plan`, `/teamwork`, `/swarm`, skills …) come in
  `available_commands_update` and go to Desktop as the session's `commands`. agy finds skills in `.agents/skills`
  in the session folder and its parents up to the repository root, plus its own built-in ones. It doesn't read
  `.claude/skills` or `~/.agents/skills`.

## Local skills (Nov 24, 2026)

A Gemini session offers the person's Claude skills and commands as a Claude Code session does, without ever reading
or writing `~/.gemini`.

- **Where they come from** (`ClaudeSkillCatalog`, in this order, and the first one with a given name, or with a
  real folder already taken, wins): the person's own folders first, `<CLAUDE_CONFIG_DIR or ~/.claude>/skills`,
  `~/.agents/skills` and the skills in `$CLAUDE_CODE_DESKTOP_SKILLS_PLUGIN_DIR/skills` that its `manifest.json`
  doesn't turn off; then, nearest first, `.claude/skills` and `.agents/skills` in the session folder and each parent
  up to the repository root (the folder holding `.git`), or up to the filesystem root outside a repository. The walk
  never includes the home folder itself, in any case: it stops at the folder just below home, even for a repository
  rooted at `$HOME`, and is empty for a session run in `$HOME`, because the person's folders cover `~/.claude` and
  `~/.agents`. Personal over project is Claude Code's rule: when the person and a project both have a skill of that
  name, the slash menu, `/name` and the index all use the person's copy, whether the two copies are links to one
  install or differ, and the project's is not offered. Then `.claude/commands` along the same walk and
  `<config>/commands`, which a skill of the same name shadows. They are scanned when the session starts, before agy opens, and
  again on each `reload_skills`. Discovery keeps only bounded metadata and file paths; the selected skill or
  command's body is read from disk when invoked.
- **Who may start one**, from the frontmatter as Claude Code reads it: `user-invocable: false` keeps a skill out of
  what the person can type (the slash menu, `reload_skills`, `system/init`'s `skills` and `/name` expansion), but the
  model may still choose it, so it is in the skill index. `disable-model-invocation: true` keeps a skill out of the
  index only; the person can still type it.
- **Autocomplete:** the `initialize` answer's `commands` and `system/init`'s `slash_commands` list agy's commands
  and then the local ones the person can type. On a shared name, agy's command wins. `skills` lists those local
  skills.
- **`system/init`** comes right after the `initialize` answer, so Desktop has the commands before any turn. The first
  turn doesn't say it again unless the model, mode, commands or skills changed in between. Each later turn begins with
  it, as each of Claude Code's stream-json turns does.
- **`/name args`:** when a message's first text is `/name args` and `name` is a local skill or command that the
  person can type and agy doesn't have, agy is sent `<command-name>/name</command-name>`, `<command-args>args</command-args>`, the skill's
  folder (`Base directory for this skill: …`, so relative `scripts/` and `references/` paths resolve), and then the
  body. In the body, `$ARGUMENTS` is replaced by the arguments; if the body has no `$ARGUMENTS` and there are
  arguments, they are added after it as `ARGUMENTS: …`. In a message made of blocks, only that text block changes and
  images stay where they were. Desktop's replay and the transcript keep what was typed. agy's own commands go through
  as typed, as do all commands until agy announces its native roster (which may be empty) or answers the session's
  first prompt. agy announces one after `session/new`; whether it does after `session/resume` or `session/load` is
  not known (the installed agy's readable files, its changelog included, don't say). agy writes a prompt's updates before its reply, so a roster not
  heard by that reply is taken as none, and a resumed session without one expands local skills from its second
  message on. A roster heard later still takes its names for agy. `/login` and
  `/splice:login` are never expanded, even when a local command has that name: as text alone
  they open Splice Console's Accounts page and never reach agy, and in a message with an image they go to agy as
  typed.
- **`reload_skills`** scans the folders again and answers `{"skills": [{name, description, argumentHint}, …]}`, as
  Claude Code does, with the local skills and commands the person can type. It leaves out only the names of commands
  agy has already reported in `available_commands_update`; before agy reports any, nothing is left out. It never
  opens the agy session. Autocomplete, `/name` and the index of a session not yet opened use the new list from then
  on.
- **The skill index.** When agy offers `systemPrompt.append`, a new session (`session/new`, including the retry
  without MCP servers) gets a short index appended to agy's system prompt. It is built from the skills as they stand
  when `session/new` is sent, so a `reload_skills` before then counts. The index lists each local skill's name,
  a one-line description when explicitly provided in frontmatter, and its `SKILL.md` path, so agy reads a skill when a
  task calls for it and loads nothing otherwise. Body-derived summaries stay in local autocomplete. The boundary it keeps:
  - only when agy's ACP `initialize` advertised the capability as JSON `true` (not the string `"true"`), and only on
    `session/new`, never on `session/resume` or the `mcp_set_servers` resume, because agy adds a resumed append to
    the one the session already has;
  - skills only, and only those the model may choose (not `disable-model-invocation: true`; a `user-invocable:
    false` skill is included);
  - trust follows who wrote the folder, not where a link lands (`SkillRoot.indexPath`). A skill in one of the
    person's own folders (`<config>/skills`, `~/.agents/skills`, the Desktop plugin's `skills`) is listed whatever its
    entry or the folder itself links to, because a link there is how toolkits, stow and dotfile managers install
    skills. It is listed by the path where the person put it (`~/.claude/skills/<name>/SKILL.md`), never by the
    link's target, so the index never names where a link lands and agy's read follows the link to what is installed
    now, as Claude Code's would. A skill in a project's `.claude/skills` is listed only when its real folder and real
    `SKILL.md` lie inside the project (the repository root that ends the walk, or outside a repository the folder
    holding that `.claude`), and by its real path, so a link the project wrote can point neither the index nor a
    later read the model starts at a file outside the project. Because the person's folders come first, a skill a
    toolkit links into both `~/.claude/skills` and a project's `.claude/skills` is listed once, by the person's path;
    the project's copy only ever drops out, so it can neither keep the person's out nor bring a path outside the
    project in. A skill left out this way still appears in local autocomplete, where only the person can start it.
    What goes with a skill the person starts is another matter: typing `/name` sends agy the skill's body and a
    `Base directory for this skill:` line holding its real folder, the link's target included, so its relative
    paths resolve;
  - leaving out every skill agy serves itself: one found in an `.agents/skills` folder of the
    session's walk (even when its entry or its `SKILL.md` links elsewhere), one whose real folder is that of such a
    skill (a `.claude/skills` alias that links into `.agents/skills`), and one sharing such a skill's name;
  - bounded to 100 entries, 160 characters per description and about 12,000 characters, then
    `(N more skills omitted)`;
  - never Desktop's system prompt; no `--add-dir` per skill folder (a probe with 106 `--add-dir` flags stalled
    `agy acp`); never `~/.gemini`.

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
