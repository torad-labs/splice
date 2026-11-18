# The harness: work splice runs around a turn

splice sits under every request a Claude Code session makes, and it can start sessions of its own. So it can do work
around a session's turns: run a model, a check or another session, hold the turn while that runs, tell the person
what is happening, and put the result back. That is the harness, and each piece of work it can run is a capability.

A capability is declared in `splice.toml`, and its work can run outside splice's code, so anyone can add one: a
company's policy check, a team's reviewer, a person's helper. The first capability splice ships is the Context
Getter: on a session's first turn, the provider's fast model explores the folder, and the session's model receives
the request with what it found.

This spec covers three features, built in this order: capabilities (decisions 1 to 6), what a held turn tells the
person (decisions 7 and 8), and the Context Getter (decision 9). A second round makes the harness a module of its own
(decision 11), lets the model call a capability itself (decision 12), and adds a worker reached through an MCP
server (decision 13).

## What a person sees

- A capability is a table in `splice.toml`. With none declared, or none switched on for a head, every request is
  the bytes it is today.
- When a turn calls a capability, the turn waits: Claude Code shows a live turn, and the status line says what is
  running and how long ago it last said so.
- When the work is done, the turn goes on. What the capability returned sits in the session's first user message,
  as if the person had pasted it, and stays there, byte for byte, on every later request until the first compaction
  replaces that message.
- When the work fails, the turn goes on as if the capability were not there, or is refused with a reason,
  whichever its declaration says.
- A turn that waits on a usage limit says so in the same place on the status line.

## Decisions

### 1. A capability is a table in splice.toml

```toml
[capabilities.policy]
heads = ["claude"]
call = "rule"
when = { turn = "first", thread = "main", mode = "interactive" }
place = "first-user-message"
may = "add"
message = "Checking the request"
color = "cyan"
timeout_s = 60
on_failure = "open"
send = ["first_user_message", "folder", "model"]
worker = { kind = "http", url = "http://127.0.0.1:9000/check", auth_env = "POLICY_WORKER_KEY" }
```

- The table's name is the capability's name: lowercase letters, digits and `-`.
- `heads`: the heads it is switched on for, by key. Absent or empty, it is declared and off. Switching a
  capability off stops new calls only: a session that already carries its result keeps it (decision 5).
- `call`: who may call it. `"rule"` is splice, by the rule in `when`. `"agent"` is the model, through a tool splice
  offers it (decision 12), and `"both"` is either.
- `when`: the rule, from facts splice already reads from a request (decision 2).
- `place`: where the result goes. `"first-user-message"`, or `"none"` for a capability that only lets a turn pass
  or refuses it.
- `may`: what a result may do. `"add"` (text where `place` says), `"refuse"` (refuse the turn with a reason), or
  `"add-or-refuse"`.
- `message` and `color`: what the held turn says while the capability starts (decision 7). `color` is one of the
  status line's own colour names.
- `timeout_s`: how long the work may take, 1 to 3600.
- `on_failure`: `"open"` (the turn goes on as today) or `"closed"` (the turn is refused, with the reason).
- `send`: the fields the worker receives, by name: `first_user_message`, `folder` (the session's folder),
  `model`, `head`. A worker gets nothing its declaration does not name, so what leaves the machine is written
  down. The capability's name and the session and turn ids always go.
- `worker`: what runs the work (decision 4).

Boot fails closed. A declaration that names an unknown key, a head splice.toml does not have, a value outside the
lists above, a `place` and `may` that do not fit (`"none"` with anything but `"refuse"`, text with no place), a
`turn = "any"` with `place = "first-user-message"`, a worker kind or a built-in worker splice does not have, or an
HTTP worker with no `url`, an MCP worker with no `server` or `tool`, a `tool` with `call = "rule"`, a `call` of
`"agent"` or `"both"` with no `tool` or on a head whose dialect is not Anthropic's Messages, or `place` or
`when.turn` named with `call = "agent"`, is a config finding: every one is listed at once, and the daemon does not start. Each finding names its capability
and the key or value at fault, never a value that could be a secret; a `place` and `may` that do not fit names
`place`.

`splice status` lists each declared capability on a line of its own: its name, the heads it is on for (`off` when
none), and its worker. With none declared it says nothing of capabilities.

### 2. The rule

`when` holds any of these; each has the default shown, and a request has to meet all of them.

| Key | Values | Default | Read from |
|---|---|---|---|
| `turn` | `"first"`, `"any"` | `"first"` | the history: a first turn has no assistant message, which also rules out a compaction and the turn after it |
| `thread` | `"main"`, `"subagent"`, `"any"` | `"main"` | Claude Code's agent header: a main-thread request has none |
| `mode` | `"interactive"`, `"print"`, `"any"` | `"interactive"` | the user agent's client name, compared whole: `cli` is interactive, `sdk-cli` is print mode |
| `models` | a list of model ids | every model | the model the request names, after splice resolved it |

A thread's first turn is decided once, at its first request that streams and carries tools, and the decision is
kept: Claude Code sends side requests without tools before the first turn, and those decide nothing. The main
thread is one thread, and each subagent, by its agent id, is another. A thread first seen mid-conversation never
has a first turn later, not even when a compaction leaves it with one message again.

splice adds its own conditions to every rule. A capability is never called for a request of a worker's own session
(decision 9), for Claude Code's auto-mode review, or for a turn that holds a model-server slot of its own, which
would sit idle while the turn waits. At most one capability holds a turn: where two rules match a request, the
one declared first in splice.toml runs first and the next runs after it, in order.

### 3. The turn waits

While a capability runs for a turn, splice holds that turn the way it holds a turn on a usage limit: the response
opens at once and is pinged, so Claude Code reads a live turn and draws no retry; the turn gives back its place in
the head's queue and takes one again when it goes on; the notice splice writes into a long silent turn is off. The
held turn writes nothing into the conversation.

A turn over its budget is refused before the capability is called, not after.

### 4. The worker

**HTTP.** splice posts one JSON object to `url`, with `Authorization: Bearer <key>` when `auth_env` names a key
(read the way a provider's API key is):

```json
{"capability": "policy", "session": "<session id>", "turn": "<turn id>",
 "fields": {"first_user_message": "…", "folder": "/home/me/project", "model": "astra-large"}}
```

The worker answers 200 with a stream of JSON lines, one object per line:

```json
{"type": "progress", "message": "Reading the request"}
{"type": "result", "text": "…"}
{"type": "refusal", "reason": "…"}
```

Any number of `progress` lines, then exactly one `result` or one `refusal`, after which splice stops reading. Each
progress message becomes what the held turn says (decision 7). `first_user_message` is the text of the client's
first user message, its text blocks joined, as the client sent them.

**Built in.** `worker = { kind = "builtin", name = "<name>" }` names a worker that ships with splice. The Context
Getter's is the first (decision 9).

**MCP.** A worker reached through an MCP server is decision 13.

### 5. Where the result goes

`place = "first-user-message"`: the text goes into the request's first user message, as a text block of its own
after the client's blocks, in the dialect's own shape for that provider. It does not name splice or a model, and
the client's own blocks are not changed. A dialect that sends each text block as an item of its own sends the
text as one more user item after them, before the first answer. A dialect that cannot place it leaves the body
alone, and the turn's record says so.

The client never saw that block, so its history does not hold it. splice stores the text with a hash of the first
user message's content as the client sent it (its `cache_control` marks left out, since Claude Code moves them from
turn to turn), and adds the same bytes, through one function, to every request of the session whose first user
message has that hash: the first turn, every later turn, and the compaction request. After the first compaction
the first user message is the summary, the hash no longer matches, and nothing is added. So:

- The added bytes are the same on every request, and every cached prefix of the session already includes them.
- `/clear` starts a new session. `/model` and `--continue` keep the session and its first message, so the text
  stays.
- Every state a session can be in for a capability (called and waiting, placed, failed, never called) is written
  under splice's state folder before the first turn goes upstream, so restarting the daemon changes no live
  session's request. Removing an old record leaves a mark that the session was seen.

### 6. Failure

- The worker fails (cannot be reached, answers anything but 200, sends a line that is not one of the three, ends
  with no result) or passes `timeout_s`: with `on_failure = "open"` the turn goes upstream with today's bytes, and
  with `"closed"` it is refused. The reason goes to the log and the turn's record, and never the worker's key.
- A `refusal` from a capability that `may` refuse: the turn is refused with the worker's reason, in Anthropic's
  error shape, and nothing goes upstream. A refusal from one that may not is a failure. A refused turn was not the
  thread's first turn: the next request that meets the rule is called.
- The response is already open when a turn is refused, so the refusal reaches Claude Code as the stream's error,
  as other failures of an open turn do, and its message names the capability and the reason.
- The person presses Esc, or the client goes away: the work is stopped and nothing is placed.
- The head drains or stops: the work is stopped, nothing is placed, and the turn goes upstream as today, so the
  drain counts it from then on.
- A second request of the same thread while the first still waits (Esc and send again does this) ends the wait
  with nothing placed, and both go on as today.

### 7. The status line

While a turn of a session is held, the session's status line says, where the model's name usually sits: the held
turn's latest message, in its colour, and how long ago that message came. With more than one turn of the session
held, it also says how many. A message that keeps changing reads as working, and one that stops changing reads as
stuck. When the turn goes on, the status line is what it was.

The held turn's part leads the line, and the model's name follows it as it follows a limit hold today. It reads
`<message> · <age>`, and with more than one turn of the session held, `<message> · <age> · <N> turns`, where the
message is the one that came last among them. The age is `Ns ago` under a minute and `Nm ago` from there, counted
from when that message came to when the line is drawn. Each progress line a worker sends becomes the message, and
its age starts again. The interval at which Claude Code draws the line is not changed here.

Messages are kept per held turn, so a subagent's wait and the main thread's wait in one session do not overwrite
each other. A client that runs no status line shows Claude Code's own working line, and the turn waits the same.

### 8. A usage limit holds a turn through the same piece

Holding a turn and saying why is one piece, which knows nothing of why a turn is held. A turn that waits on a
usage limit, an overload or credit keeps what it does today: its wait, what wakes it, and the line it writes into
the conversation. Its message now reaches the status line through that piece, per turn, with the age and the count
of decision 7, and a head that drains tells the turns it holds.

### 9. The Context Getter

The first capability that ships with splice. It is off until switched on for a head:

```toml
[capabilities.context-getter]
heads = ["claude"]
worker = { kind = "builtin", name = "context-getter" }
```

Everything else has the Context Getter's own defaults, which a declaration may override: the rule is the first
main turn of an interactive session, the result goes into the first user message, it may only add, it fails open,
and its message is `Exploring files with <the fast model's label>`.

**What runs.** A normal Claude Code session in print mode (`claude -p`), in the session's folder, on the head's
fast model: by default the head's small tier, or `worker.model`. Nothing about its tools is restricted. It thinks
at its own model's level: the person's Claude Code setting for that model when their settings name one
(`modelSettings.<model>.effortLevel`, keyed by the model id the gatherer is started with), else Claude Code's own default for it, and never the head's effort or a
launcher's. A gather of this repo at `xhigh` thought 140k tokens and was still running after 15 minutes; at Haiku's
own level it took 81 s. splice otherwise starts it as it starts any session on that head: the head's login, the MCP
hub's tools and the person's settings, and it mints its session id. Its input is the first user message as the client sent
it, inside a prompt that asks for what another agent needs to start on that request: the files that matter with
paths and line ranges, the entry points, how the project builds and tests, and what in the repo's own instructions
applies, as facts with their locations, changing nothing and not doing the task. Its result is the final result of
its stream.

**Its own session never calls a capability.** The gatherer's requests carry one splice header, `x-splice-gathers-for`,
naming the session they work for. A request with that header never calls a capability, casts no vote on the head's
usual model, and its record names the session it worked for (`worked_for`, decision 10).

**What the person sees.** The held turn says `Exploring files with <label>`, then each step the gatherer reports
(its tool-use summaries), and `Exploring stopped, continuing without it` if it stops before a result; that message stays until the turn's answer begins. The summaries come from Claude
Code itself: the gatherer runs with `CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES=1`, and each `tool_use_summary` event of
its stream is a progress message.

**When it does not run.** Beside decision 2's rule: the session's folder cannot be found (a session relayed from
another machine has none here), or the head has no login that can serve the fast model. Each is recorded as not
called, with the reason, in the log and in the turn's record (`capability_skipped`: the capability and the reason).

**Limits.** splice owns the gatherer's process: it is ended at the time limit, when the client leaves, when the
head stops and when the daemon shuts down, and it runs under a deadline of its own, so a killed daemon leaves none
behind. The default time limit is set from gathers measured on real work, and the table at the end of this spec
says which numbers it came from. Whether the Context Getter is switched on by default is decided from the same
measurements, before the feature is released.

**On the door.** A session on the door (`docs/specs/door-default.md`) is gathered for on the head that holds the
waiting turn: the gatherer names a selector of that head, so the door sends it there.

### 10. What is recorded

A turn's record, as the perf route answers it, gains three fields: `capability_text_chars`, the size of the
capabilities' text the request carried (0 with none); `held_on`, the capabilities the turn was held on, in order
and joined by commas (null when it was not held); and `held_ms`, how long it was held, as a count of its own, so
time to the first byte can be read without it. A turn the person left during a hold has its record like any other,
with those fields and the outcome a turn the client left has today. A gatherer's records carry the session it
worked for as `worked_for`, so the two can be joined.

### 11. The harness is a module of its own

`:features-harness` (package `splice.harness`) holds the capabilities, their rule, their workers, the built-in
Context Getter and the agent's call. `:features-turns` holds the turn and the held turn, and knows the harness only
through a port it owns: called when a thread is first seen, before each request goes upstream, and for each round
the agent's call answers. The harness implements that port, so it depends on `:features-turns` and never the other
way. `:app` wires the two. The module law lists the harness's edges: `:core`, `:features-turns`,
`:features-sessions`, `:features-launch`, `:integrations-claude-code`, `:integrations-http`,
`:integrations-upstream` and `:integrations-mcp` (the daemon's side of the MCP hub; `:integrations-mcp-hub` is the
hub's own native program, which nothing depends on). Nothing under `features/turns` is in `splice.harness`, and
nothing in `:features-turns` names a capability's worker, its rule or the Context Getter.

The held turn knows nothing of why it holds a turn (decision 8); the harness and a usage limit are two of its users.
What the harness needs of a turn (its session's folder, its thread, a way to hold it and say what it is doing) comes
to it through the port, never by reaching into the turn's own packages.

### 12. The agent's call

`call = "agent"` or `"both"` offers the capability to the model as a tool. The declaration names it:

```toml
[capabilities.advisor]
heads = ["claude"]
call = "agent"
message = "Asking the advisor"
color = "magenta"
timeout_s = 600
on_failure = "open"
worker = { kind = "http", url = "http://127.0.0.1:9100/advise" }

[capabilities.advisor.tool]
name = "ask_advisor"
description = "Ask a second model for a review of a plan."
input = { type = "object", properties = { question = { type = "string" } }, required = ["question"] }
```

The tool reaches the model as `name`, `description` and `input` as its `input_schema`. What the worker returns goes
into the tool result, so a capability with `call = "agent"` names no `place`; `may` keeps its meaning (`"add"`
returns text, `"refuse"` may refuse the turn), and a worker that may refuse and lets the turn pass returns its text
like any other.

Claude models from 4.6 on refuse a request that ends with an assistant message ("This model does not support
assistant message prefill"), so the call cannot be a block the model leaves empty for splice to fill and continue
in place. The one pause the API keeps, with thinking on, is a tool call answered by a tool result, and that is the
agent's call.

- **Offered.** On every request that carries tools, of a thread the rule allows (`when.thread`, `when.mode` and
  `when.models`; `turn` does not apply), the tool is added after the client's tools, as the same bytes every time.
  The client's tools and system text are not changed. A request without tools, and one decision 2's own conditions
  rule out (a worker's own session, the auto-mode review), is not offered it. With `call = "rule"` nothing is
  offered. A request that carries a kept call (below) carries the tool too, whether or not it is offered there, so
  the provider never sees a call of a tool it was not given.
- **Answered.** When the model's message calls that tool and nothing else, the client never sees the call. splice
  holds the turn as decision 3 does, though its response has begun, saying the capability's message in its colour
  (decision 7), sends the worker the tool's input as the field `input` beside the fields `send` names, then sends
  the provider the same request with the model's message and a `tool_result` carrying the worker's text, and streams
  that answer to the client as the rest of the same response. The client receives one assistant message: the
  model's blocks before the call, then the answer's. The model knows it called the tool and reads what came back.
  An answer that calls the tool again is answered the same way. The first response's ending (its `stop_reason` of
  `tool_use`) never reaches the client: the message has one start and one end, its blocks numbered in order, and
  its usage is the answer request's input and cache counts with the output tokens of both, since the answer's
  request holds the whole conversation. A turn refused before any of the model's blocks reached the client is
  refused the way a rule-called capability's refusal is (decision 6), which Claude Code does not send again.
- **Kept.** splice stores the call and its result, keyed by the content of the client's copy of that assistant
  message (its `cache_control` marks left out, as decision 5 leaves them out), and on every later request of the session puts them back where the model made them, byte for byte,
  thinking blocks and their signatures included. The provider sees the conversation it produced and every cached
  prefix holds; the client's history is never what changes. A compaction request carries them like any other. The
  record is on disk before the answer reaches the client, so a restarted daemon puts it back the same way. A later
  request whose record is gone (removed, or the session copied to another head) still goes upstream in a shape the
  provider accepts.
- **Beside the client's calls.** When one message calls the capability's tool and tools of the client's, the client
  gets its own calls and never the capability's. The worker runs while the client runs its tools, and when the
  client sends its results splice adds the capability's result beside them, in the order the calls were made. A
  failure with `on_failure = "closed"` refuses the request that carries the client's results. When the client's
  next request answers none of its calls (the person pressed Esc), the capability's call is left out with them.
- **Failure.** With `on_failure = "open"` the tool result says the capability failed and why, and the model goes on.
  With `"closed"` the turn is refused as decision 6 says. A refusal from a worker that `may` refuse refuses the turn.
  Esc, the client leaving, a drain: decision 6.
- **Dialects.** The Anthropic Messages dialect in this change. A capability with `call = "agent"` or `"both"` on a
  head whose dialect is another is a config finding.

### 13. The MCP worker

`worker = { kind = "mcp", server = "<a server the MCP hub runs>", tool = "<its tool>" }` calls that tool through the
MCP hub, with the declared fields (and `input`, for the agent's call) as its arguments and nothing else; the
capability's name and the session and turn ids go in the call's `_meta`. The server is the one the hub would give a
session in that session's folder: a server the folder's own `.mcp.json` declares is found there, and where nothing
hosts it for that folder yet (the hub's front is not running, or the server runs one copy per session) splice starts
it in that folder for the call and stops it when the session ends. splice asks for progress (a `progressToken`),
and each progress notification is a progress message; one with no message keeps the message and starts its age
again. The tool's text content is the result. An error result, a JSON-RPC error, a result with no text, a server or
tool the hub cannot find for the session's folder, a session whose folder cannot be found, or the time limit is a
failure handled by `on_failure`. When the work is stopped (the time limit, Esc, the client leaving, a drain) the
server is sent `notifications/cancelled`. An MCP worker with no `server` or `tool` is a config finding naming
`worker.server` or `worker.tool`. `splice status` shows `mcp <server>/<tool>`.


## Not in this change

- Switching a capability on per model or per session.
- Results placed anywhere but the first user message.
- A capability called by rule that holds a turn the model has already started to answer (the agent's call is the
  one capability that does, decision 12).
- The agent's call in a dialect other than Anthropic's Messages.
- Keeping a session's cache warm during a long hold.

## Seen with Claude Code

Filled in by whoever builds each part, from a real session, with the Claude Code version it was seen on.

| Question | What was seen |
|---|---|
| A turn held for the longest time limit a capability may have draws no retry and no second request | Claude Code 2.1.326, November 17, 2026, 06:24 to 07:24 CT: a capability with `timeout_s = 3600` held a session's first turn while its worker took 59 min 50 s to answer. The screen showed the usual spinner the whole time ("Baked for 59m 50s · done 7:24 AM") and no error. The worker was asked once; the provider got the title request at the start and the turn itself once, with the capability's 45 characters at the end of the first user message; no request was dropped and none came again. The perf row reads `held_on = "brief"`, `held_ms = 3590025`. |
| The status line is drawn again often enough to follow a capability's steps, and what its interval costs across a machine's sessions | |
| A turn refused once its response is open shows the capability's reason to the person | Claude Code 2.1.326, November 17, 2026, 06:23 CT: a capability that may refuse refused a session's first turn 3 s in. The screen showed "Thought for 3s" (splice's notice block, finished just before the error), then printed `API Error: [splice] brief refused this turn: the request names no file to change.` and sent nothing more; the provider got only the session's title request. Without that notice (the error alone, before any block) 2.1.326 sent the same turn again 30 ms later without streaming, which no rule names, so it reached the provider without the capability and the person saw the provider's answer instead of the refusal. |
| A gatherer started the planned way, with the header naming its session, runs and returns a result | Claude Code 2.1.326, November 17, 2026, 16:55 CT: a gatherer started from the `claude` head's own launch recipe (the person's login, the MCP hub's file, the head's settings), without the head's `--effort xhigh` or its `MAX_THINKING_TOKENS`, as `claude -p --model haiku --output-format stream-json --verbose` with `CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES=1` and `x-splice-gathers-for` naming a real session, in a copy of this repo, the first message "Add a --json flag to `splice status`…" inside the gathering prompt on stdin. It ran on `claude-haiku-5-5` at Claude Code's own effort and ended in 29 s on a `result` of subtype `success`, 6 turns, 5,673 characters: the files with line numbers (`CommandParser.kt:143`, `Command.kt:189`, `StatusTable.kt:45-90`), the `usage --json` precedent, the tests to extend and what CLAUDE.md says of tests. All 9 of its `/v1/messages` requests carried the header with the session's id, and their client was `sdk-cli`. A relay in front of the head logged them. The recipe came from the installed splice 0.6.3's `/launch/claude`, and the changes the Context Getter makes to it were applied by a script, so this build's daemon did not start this run. |
| The gatherer's tool-use summaries: what they look like, and whether each costs a request of its own | Claude Code 2.1.326, November 17, 2026, 16:55 CT, the same gather: 4 `tool_use_summary` events, one after each round of tool calls but the last, each a short past-tense line of its own, for example "Inspected repo layout and status references", "Read StatusCommand.kt and CLI parser" and "Read Command.kt and UsageJson.kt", with the ids of the tool calls it sums up. Each one costs a request: beside its 5 turns (13 tools each), the gatherer sent 4 requests with no tools and one message, each in the same millisecond as the next turn, on the same fast model and with the same header. |
| On the door: a `claude -p` run with the door's settings reaches the head that holds the waiting turn | Claude Code 2.1.326, November 17, 2026, 17:26 to 17:28 CT: this build's daemon in a scratch home, with `claude` on the door and two heads, `astra` (the door's default model) and `borealis`, each on a local stand-in provider. A first door turn of a new session on `claude-borealis--borealis-large`, with the Context Getter on, was held, and the daemon started the real Claude Code 2.1.326 from the door's own launch in the session's folder, as `claude -p --model claude-borealis--borealis-small` with the header naming the session. All its requests reached `borealis` on `borealis-small` (its row: `worked_for` the session, `door = "terminal"`) and none reached `astra`. The session's turn then went to `borealis-large` with the gatherer's 25 characters placed (`held_on = "context-getter"`). Without `ANTHROPIC_DEFAULT_OPUS_MODEL`, 2.1.326 on the door first probes which Opus there is with one-token requests (`anthropic.claude-opus-5-5`, then `-5`, `-4-8`, `-4-7`) whatever `--model` and `ANTHROPIC_MODEL` say, and the door ran that probe on `astra`, its default head. With the opus tier named as the selector there is no probe, so the gatherer's launch on the door names it. |
| The agent's call: a call the client never sees and the answer after it reach Claude Code as one assistant message, with no retry | Claude Code 2.1.326, November 17, 2026, 17:06 CT, an interactive session on a head over a scripted Messages provider (no real model answered). The model's first message thought, said "Let me ask the advisor first." and called `ask_advisor`; splice asked the worker once, with the call's input as `input`, and the provider's answer thought and said what the advisor found. The screen showed one answer: "Thought for 1s", the first text, "Thought for 1s", the advisor's answer, then "done", with no tool call and no error. The provider got the turn once and splice's request with the result once; Claude Code sent nothing again. Its next request, the prompt suggestion, already carried the call and its result in place of the one message it holds, and the provider took it. The turn's log line read `out=40`, the two rounds' output together. |
| The agent's call: a later turn after one reads the provider's cache up to the turn before it | Not seen against a real provider yet, so the provider's own cache counts were not read. What was seen, Claude Code 2.1.326, November 17, 2026, 17:06 CT, on the scripted provider above: the second turn's request carried the same `system` and `tools` bytes as the first turn's last request, the person's first message, the model's message with the call and the user message with its result byte for byte, then the model's answer and the new message. The one difference was Claude Code's own `system`-role message, which it sent as a block holding its cache mark on the first turn and as a plain string on the second, as it moves its mark; splice passes that message as it comes. Whether the provider reads its cache past that message is still to be seen on a real one. |
| An MCP worker's progress notifications reach the status line as the held turn's message | |
| The agent's call: Claude Code sends back the one assistant message it was streamed, both thinking blocks unchanged, and the provider accepts it as the client holds it when no record puts the call back | |
| The Context Getter's default time limit, and the gathers on real work it was set from | Default 180 s, the limit a Context Getter declared with no `timeout_s` gets. It was set on November 17, 2026 from gathers of this repo for real first messages, on Claude Code 2.1.326 with Haiku at its own level: they took 72 s to 101 s (the plan's measurements; decision 9's 81 s is one of them), and a narrower request took 29 s (the gatherer row above). 180 s leaves the slowest of them 79 s to spare. The same gather at the head's `xhigh` was still running after 15 minutes, which is why the gatherer never thinks at the head's level, not a reason for a longer limit. Past the limit splice ends the gatherer and the turn goes on without it; the gatherer's own deadline (coreutils `timeout`, 5 s past the limit, then a kill 5 s later) ends it if the daemon was killed and could not. |
