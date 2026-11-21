# Follow a session's reply as it streams

A Claude Code transcript only gets whole messages. splice sees every response as it streams to Claude Code, on
every head, so the control plane can show you a session's reply while it is being written. The route is read-only
and answers only on this machine.

## The route

```
GET /api/sessions/{id}/live
```

- It is served by the control plane, which listens on loopback only (port 3096 unless you changed it). Nothing
  serves it on a head's port, on the door or on the LAN share.
- It needs the management key as a Bearer token, as every other `/api` route does. The console's key opens it too.
  Without a key, or with a wrong one, it answers `401`.
- `{id}` is the Claude Code session id: the one each request carries as `x-claude-code-session-id`, and the one
  `GET /api/sessions` lists as `session_id`.
- It answers `200` with `Content-Type: text/event-stream` and `Cache-Control: no-cache`, and stays open until you
  leave. It never ends because a turn ended. An id with no turn in flight, or one splice has never seen, is no
  error: the stream opens, says so in `hello`, and the next turn's events come when it starts.
- It is read-only. Only `GET` is served; nothing can be sent to a session or a turn through it.

## Events

Each event is an `event: <name>` line and one `data:` line holding a JSON object, then a blank line. Every field
is always present; a value that does not apply is JSON `null`.

| event | fields | when |
|---|---|---|
| `hello` | `session`, `live` | First, once. `session` is the id you asked for; `live` lists the ids of this session's turns in flight right now. |
| `turn` | `turn`, `message`, `agent`, `model`, `head`, `compact`, `at` | A response for this session started reaching Claude Code. |
| `text` | `turn`, `index`, `delta` | Text Claude Code was sent, in order. |
| `thinking` | `turn`, `index`, `delta` | Thinking Claude Code was sent, when it is sent thinking. |
| `tool` | `turn`, `index`, `id`, `name` | A tool call started. |
| `end` | `turn`, `stop_reason`, `error` | The turn's response ended. Comes exactly once for every `turn`. |

The fields:

- `turn`: an opaque string id splice gives the turn, unique while the daemon runs. Every event of a turn names it.
- `message`: the id of the message Claude Code was sent (the id in its `message_start`, or of a non-streamed
  answer). Claude Code writes the same id into its transcript, so a live reply and the transcript's message can be
  matched.
- `agent`: the request's `x-claude-code-agent-id`, which names the subagent that sent it; `null` on the session's
  main thread. Subagents share their session's id, so one stream shows the main thread and its subagents.
- `model`: the model Claude Code asked for, as it named it. `head`: the key of the head serving the turn, as
  `GET /api/sessions` names heads. `compact`: whether the turn is a compaction. `at`: when the head admitted the
  request, in epoch milliseconds.
- `index`: the content block's index, exactly as Claude Code got it. `delta`: the text or thinking written to that
  block. `id` and `name`: the tool call's `tool_use` id and the tool's name.
- `stop_reason`: the stop reason Claude Code got (`end_turn`, `tool_use`, `max_tokens`, ...), or `null` when none
  was sent. `error`: `null` when the response ended normally; the type of the error event Claude Code was sent
  (`overloaded_error`, `api_error`, ...) when it got one; `"cut"` when the response ended with neither, for example
  because Claude Code hung up or the turn was stopped.

Every head and every backend streams the same way: the events are read off the Anthropic Messages frames splice
writes to Claude Code. A compaction Claude Code hung up on, which splice finishes so its retry can be answered,
keeps streaming to you until it ends. A turn Claude Code asked for without streaming shows when its answer is
ready: its `turn`, one event per content block holding the block whole, then its `end`. It is never listed in
`live`, since nothing of it streams. A retry splice answers from an answer it already holds is not a new turn and
shows nothing.

## Joining mid-reply

If you connect while a turn is being written, you get, right after `hello`, each turn in flight's `turn` event,
then one `text`, `thinking` or `tool` event per block holding everything written to it so far, in block order, then
the live deltas from where that stopped. Nothing is lost or repeated between the two, so joining mid-reply shows
the whole reply. A turn that has ended is no longer listed in `live`.

## Keeping up

While nothing else is sent, the comment `: ping` comes once 15 s have passed with nothing written. Event-stream
readers ignore comments.

A follower that reads slowly never slows a turn. Each follower may fall 4096 events behind; one that falls further
is disconnected, with no further event. Connect again to get `hello` and the snapshot of whatever is in flight.
Any number of followers can watch one session, and each gets every event.

## What is never sent

Only what splice writes to Claude Code as reply text, thinking and tool-call starts goes out. Never a request body,
a header, a key or token, an account or login name, a backend's raw frames, an error's body, a thinking signature
or a tool call's input. With nobody following a session, its turns cost nothing more than they did before.

## Example

```sh
curl -N \
  -H "Authorization: Bearer $(cat ~/.splice/state/mgmt-key)" \
  http://127.0.0.1:3096/api/sessions/<session id>/live
```

```
event: hello
data: {"session":"0b6f…","live":[]}

event: turn
data: {"turn":"turn-41","message":"msg_01X…","agent":null,"model":"claude-opus-5-5","head":"claude","compact":false,"at":1763600000000}

event: thinking
data: {"turn":"turn-41","index":0,"delta":"The user wants the notes file"}

event: text
data: {"turn":"turn-41","index":1,"delta":"I'll read it now."}

event: tool
data: {"turn":"turn-41","index":2,"id":"toolu_01A…","name":"Read"}

event: end
data: {"turn":"turn-41","stop_reason":"tool_use","error":null}

: ping
```
