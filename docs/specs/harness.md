# The harness: work splice runs around a turn

splice sits under every request a Claude Code session makes, and it can start sessions of its own. So it can do work
around a session's turns: run a model, a check or another session, hold the turn while that runs, tell the person
what is happening, and put the result back. That is the harness, and each piece of work it can run is a capability.

A capability is declared in `splice.toml`, and its work can run outside splice's code, so anyone can add one: a
company's policy check, a team's reviewer, a person's helper. The first capability splice ships is the Context
Getter: on a session's first turn, the provider's fast model explores the folder, and the session's model receives
the request with what it found.

This spec covers three features, built in this order: capabilities (decisions 1 to 6), what a held turn tells the
person (decisions 7 and 8), and the Context Getter (decision 9).

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
- `call`: who may call it. `"rule"` is splice, by the rule in `when`. A capability the agent calls itself is not
  in this change, and `"agent"` or `"both"` is a finding.
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
HTTP worker with no `url`, is a config finding: every one is listed at once, and the daemon does not start. Each finding names its capability
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

A worker reached through an MCP server is not in this change.

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

Messages are kept per held turn, so a subagent's wait and the main thread's wait in one session do not overwrite
each other. A client that runs no status line shows Claude Code's own working line, and the turn waits the same.

### 8. A usage limit holds a turn through the same piece

Holding a turn and saying why is one piece, which knows nothing of why a turn is held. A turn that waits on a
usage limit, an overload or credit keeps what it does today: its wait, what wakes it, and the line it writes into
the conversation. Its message now reaches the status line through that piece, per turn, with the count of
decision 7, and a head that drains tells the turns it holds.

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
fast model: by default the head's small tier, or `worker.model`. Nothing about its tools or its thinking is
restricted. splice starts it the way it starts any session on that head, so it has the head's login, the MCP hub's
tools and the person's settings, and mints its session id. Its input is the first user message as the client sent
it, inside a prompt that asks for what another agent needs to start on that request: the files that matter with
paths and line ranges, the entry points, how the project builds and tests, and what in the repo's own instructions
applies, as facts with their locations, changing nothing and not doing the task. Its result is the final result of
its stream.

**Its own session never calls a capability.** The gatherer's requests carry one splice header naming the session
they work for. A request with that header never calls a capability, casts no vote on the head's usual model, and
its record names the session it worked for.

**What the person sees.** The held turn says `Exploring files with <label>`, then each step the gatherer reports
(its tool-use summaries), and `Exploring stopped, continuing without it` if it stops.

**When it does not run.** Beside decision 2's rule: the session's folder cannot be found (a session relayed from
another machine has none here), or the head has no login that can serve the fast model. Each is recorded as not
called, with the reason.

**Limits.** splice owns the gatherer's process: it is ended at the time limit, when the client leaves, when the
head stops and when the daemon shuts down, and it runs under a deadline of its own, so a killed daemon leaves none
behind. The default time limit and whether the Context Getter is switched on by default come from measuring it on
real work, before the feature is released.

**On the door.** A session on the door (`docs/specs/door-default.md`) is gathered for on the head that holds the
waiting turn: the gatherer names a selector of that head, so the door sends it there.

### 10. What is recorded

A turn's record, as the perf route answers it, gains three fields: `capability_text_chars`, the size of the
capabilities' text the request carried (0 with none); `held_on`, the capabilities the turn was held on, in order
and joined by commas (null when it was not held); and `held_ms`, how long it was held, as a count of its own, so
time to the first byte can be read without it. A turn the person left during a hold has its record like any other,
with those fields and the outcome a turn the client left has today. A gatherer's records carry the session it
worked for, so the two can be joined.

## Not in this change

- A capability the agent calls itself (the empty block), and a worker behind an MCP server.
- Switching a capability on per model or per session.
- Results placed anywhere but the first user message.
- Capabilities that hold a turn the model has already started to answer.
- Keeping a session's cache warm during a long hold.

## Seen with Claude Code

Filled in by whoever builds each part, from a real session, with the Claude Code version it was seen on.

| Question | What was seen |
|---|---|
| A turn held for the longest time limit a capability may have draws no retry and no second request | Claude Code 2.1.326, November 17, 2026, 06:24 to 07:24 CT: a capability with `timeout_s = 3600` held a session's first turn while its worker took 59 min 50 s to answer. The screen showed the usual spinner the whole time ("Baked for 59m 50s · done 7:24 AM") and no error. The worker was asked once; the provider got the title request at the start and the turn itself once, with the capability's 45 characters at the end of the first user message; no request was dropped and none came again. The perf row reads `held_on = "brief"`, `held_ms = 3590025`. |
| The status line is drawn again often enough to follow a capability's steps, and what its interval costs across a machine's sessions | |
| A turn refused once its response is open shows the capability's reason to the person | Claude Code 2.1.326, November 17, 2026, 06:23 CT: a capability that may refuse refused a session's first turn 3 s in. The screen showed "Thought for 3s" (splice's notice block, finished just before the error), then printed `API Error: [splice] brief refused this turn: the request names no file to change.` and sent nothing more; the provider got only the session's title request. Without that notice (the error alone, before any block) 2.1.326 sent the same turn again 30 ms later without streaming, which no rule names, so it reached the provider without the capability and the person saw the provider's answer instead of the refusal. |
| A gatherer started the planned way, with the header naming its session, runs and returns a result | |
| The gatherer's tool-use summaries: what they look like, and whether each costs a request of its own | |
| On the door: a `claude -p` run with the door's settings reaches the head that holds the waiting turn | |
