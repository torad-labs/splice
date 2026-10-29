# Peers: a named mailbox every Claude Code session can message

A peer is a name you register with Splice. Every Claude Code session on this machine sees it in ListAgents and
can SendMessage to it, even though no Claude Code session runs behind it. The peer keeps what it receives in a
local journal, can push each message to a webhook, and can send to any session or peer under its own name.

Nothing here runs until you register a peer. With no peer registered there is no listener, no socket, no
process, no file and no registry entry.

One `peer-host` process serves every peer. Each peer still has a pid of its own, a small `cat` process the host
started, so Claude Code lists one session per peer. The host leaves 10 s after its last peer ends.

## Register a peer

```
splice peer register gary
```

This prints the peer's name, its session id, the path of its key file and its socket. It never prints the key.
The key file is `<settings dir>/peers/gary/peer.key`, mode 0600, where the settings dir is the folder holding
`splice.toml` (usually `~/.config/splice`).

When registration runs beneath a recognized native `codex` or `claude` process, the CLI binds the peer to that
ancestor's pid, kernel start time and process domain. Its detached helper watches that exact process's exit,
withdraws its registry entry and socket when the owner ends, and the supervisor revokes the registration instead
of restarting it. Daemon restarts do not end the owner. Registrations from an ordinary shell, and older peers
without an owner binding, remain persistent until revoked; Splice does not infer an owner for them.

Anything can be a peer: a bot, a script, another agent harness. Claude Code sessions are peers on their own; anything
else registers by hand and reads its inbox or a webhook, as below. A program that wants its peer to live and die with
it passes `--owner <pid>`: the CLI reads that process's kernel start time and binds the peer to it exactly as it binds a
`codex` or `claude` ancestor. A pid that names no running process is refused before the daemon is called.

Register is safe to run every time a program starts. A register that repeats one already there answers that peer,
`200` instead of `201`, with the same session id and key file, so the key a program saved still works. When the new
register asks for another `--cwd` or `--send-to`, the peer takes them and its helper restarts. A peer whose owner
process ended passes to the new owner. A name a running process still owns stays refused, and so does a peer with no
owner asked for by a process.

A management `POST /api/peers` can supply `owner: {pid, procStart, pidDomain}`. All three values must identify the
same live local process. An incomplete, stale or reused-pid tuple is refused before a peer is registered.

A name is 2 to 32 characters: a lowercase letter, then lowercase letters, digits or `-`. These names are
reserved: `system`, `user`, `operator`, `human`, `assistant`, `claude`, `anthropic`, `splice`, `splice-console`,
`telegram`, `president`, `marcos` and `dispatch`. A name a running session or another peer already carries is
refused. One user may have 16 peers.

Options:

- `--cwd DIR` sets the working directory the peer's registry record shows. It defaults to your home folder.
- `--send-to A,B,…` limits who the peer may send to, by name or session id. The default is anyone.
- `--owner PID` binds the peer to that running process instead of a `codex` or `claude` ancestor.

## Read what it receives

```
splice peer inbox --as gary
splice peer inbox --as gary --follow
splice peer inbox --as gary --after 40 --limit 20 --ack --json
```

Each record is a message, a receipt for one of the peer's own sends, or the outcome of a webhook push. A message
shows its sender as Splice checked it:

- `check: registered` means the sender's address names a live Claude Code session or peer, and the name shown is
  that session's name.
- `check: unregistered` means it does not, and the name shown is the one the sender claimed.

`--ack` marks everything shown as read. `--follow` keeps polling every 2 seconds.

A sender may send the same message again under the same `msg_id`, as one does when it never heard whether the first
arrived. The peer keeps one record of it, and answers the repeat with `delivered`, as it answered the first. Another
message under an id the journal already holds, from another sender or with other text, is answered `refused` and not
kept. Every id the journal's two files hold counts.

## Send as the peer

```
splice peer send --as gary walk-a "reply with the word PONG"
echo "a longer text" | splice peer send --as gary 3f2a9c1e -
splice peer status --as gary <msgId>
```

The target is a session or peer name, a session id, or the first 8 or more characters of one. Splice writes the
sender's address and name itself, from the peer's registration, so the recipient sees `from-name="gary"` and can
reply. Nothing in the request can change them.

`splice peer status` shows the send's outcome, then a receipt if one came back. A Claude Code recipient sends a
receipt only when it holds, expires or drops a message, so the usual answer is `no problem reported`.

`splice peer status --as gary <msgId> --json` prints the same record as one line of JSON: the message id, its
target, when it went, the outcome, and the receipt, or `null` when none came back. Only a receipt whose status is
`delivered` means the recipient accepted the message. A read the daemon refuses, such as an id this peer never sent,
prints nothing on stdout and exits non-zero.

A text is at most 32,768 bytes. A peer may send 20 messages at once and 60 a minute after that, enough for a program
that tells a whole team at once. `splice peer limit <name> <per-minute> <burst>` gives one peer its own limit, from 1
to 600 a minute and a burst of 1 to 100, and `splice peer limit <name> --default` gives it the default back.
`splice peer list` shows each peer's limit.

`--id <id>` sends the message under your own id, a letter or digit then up to 127 more of letters, digits and
`._:-`, and makes the send safe to repeat:

- Sending the same text to the same target under an id that already went answers that send again and sends nothing.
- Another text, or another target, under an id that already went is refused.
- An id whose send was refused before it left may be sent again.
- An id whose send failed part way may have arrived, so it goes again only to a Splice peer, whose inbox keeps one
  copy of each id. A Claude Code session keeps no ids, so to one it is refused: look it up with `splice peer status`
  or send under a new id.

`splice peer status --as gary <id>` looks a send up by your id too.

`--pid <pid> --proc-start <start>` pins the send to the process its target was when you resolved it, as
`splice sessions --json` shows them. When another process now answers to the name or session id (a session that was
restarted, or a pid the kernel gave to someone else), the send is refused rather than reaching it. `--pid` alone
checks the pid only.

For scripts, `SPLICE_PEER_KEY_FILE=<path>` replaces `--as`. The key is sent as a bearer header and never appears
in the command line or the output.

## Push each message to a webhook

```
splice peer webhook set gary https://example.com/hook
splice peer webhook set gary http://127.0.0.1:8787/hook --bearer-file ~/.config/hook-bearer
splice peer webhook test gary
splice peer webhook clear gary
```

The URL is `https://` to any host, or `http://` only to `127.0.0.1`, `[::1]` or `localhost`. It carries no user
name, password, query or fragment, and `set` refuses one: it prints the URL and keeps it in clear, so a credential
goes in the bearer file. `set` prints the URL and the path of the secret file. It never prints the secret or the
bearer.

Each push is `POST <url>` with these headers:

- `Content-Type: application/json`
- `User-Agent: splice-peer/<version>`
- `Authorization: Bearer <bearer>`, only when you gave `--bearer-file`
- `X-Splice-Peer: <name>`
- `X-Splice-Delivery: <journal seq>`
- `X-Splice-Timestamp: <unix seconds>`
- `X-Splice-Signature: v1=<lowercase hex HMAC-SHA256(secret, "<timestamp>.<raw body bytes>")>`

The body is:

```json
{"v":1,"peer":"gary","seq":17,"id":"<msg_id>","at":1792459759719,
 "from":{"name":"walk-a","sessionId":"…","address":"uds:…","claimedName":"walk-a","check":"registered"},
 "auth":"token","text":"…"}
```

`auth` is `token` when the sender presented the peer's token and `none` when it sent no auth line. A frame with no
auth line that names a live session's address arrives with that session's name and `check: registered`, so `from`
alone cannot tell it from the real sender. Require `"auth":"token"` before you act on a message, and know that even
that proves only that the sender could read the peer's registry record, which any process of your user can.

To verify a push, compute the HMAC over the exact body bytes with the secret in
`<settings dir>/peers/<name>/webhook.secret`, compare it to the signature in constant time, and refuse a
timestamp more than 300 seconds from your clock. The test push has the same shape, with `"test":true`, a seq of
0 and the text `splice webhook test`.

Pushes go out in order, one at a time, with redirects off and a 10 second timeout. A connection error, a
timeout, 408, 425, 429 or a 5xx is retried after 5 s, 30 s, 2 min, 10 min and 30 min. Any other 4xx, or the
sixth failure, is final. A failed push is never lost: the message stays in the inbox, and the journal records
the failure.

## Manage peers

```
splice peer list
splice peer rotate gary
splice peer revoke gary
splice peer revoke gary --purge
```

`list` shows each peer's name, whether it is running, its pid, its send scope, its webhook host and its unread
count. `rotate` writes a new key and the old one stops working at once. `revoke` stops the peer and removes its
registry record, socket and key. The journal stays unless you pass `--purge`.

The peer's process outlives a Splice restart. It runs as `splice-peer` and holds about 48 MB.

## A Codex thread as a peer

A native Codex conversation can be a peer too. Every Claude Code session then sees it in ListAgents as
`codex-<thread id>`, with the thread's id as its session id, and a message sent to it arrives in the conversation
as a turn.

```
splice peer codex attach
splice peer codex attach 019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e5f --codex-home ~/.codex --cwd ~/work
splice peer codex detach
```

Codex does not set `$CODEX_THREAD_ID` or `$CODEX_SESSION_ID` in a hook's environment (codex-cli 0.159). It writes one
JSON object to the command's stdin. The thread id is `session_id` and the working directory, when the object carries
it, is `cwd`. Those are the fields on `SessionStart`, `UserPromptSubmit` and `SessionEnd`
(`codex-rs/hooks/src/schema.rs`). `$CODEX_HOME` (else `~/.codex`) is still the Codex home. An argument or either
environment variable, when a caller did set one, names the thread instead of stdin.

The hooks attach on start and on each prompt and detach on end. In `~/.codex/hooks.json` they read:

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          { "type": "command", "command": "splice peer codex attach", "timeout": 30 }
        ]
      }
    ],
    "UserPromptSubmit": [
      {
        "hooks": [
          { "type": "command", "command": "splice peer codex attach", "timeout": 30 }
        ]
      }
    ],
    "SessionEnd": [
      {
        "hooks": [
          { "type": "command", "command": "splice peer codex detach", "timeout": 3 }
        ]
      }
    ]
  }
}
```

SessionEnd's timeout is 3 seconds because that is as long as Codex lets that event run. Omit `matcher` on all three:
SessionStart then runs for `startup`, `resume`, `clear`, `compact` and `fork`. `--hook` forces the same hook contract
when you are the one running the command at a terminal.

A hook writes nothing to stdout. Codex would add that text to the model's context, and a JSON object there is a
decision (`continue`, `decision`, `hookSpecificOutput`). A missing thread id leaves the session alone: exit 0, one
line on stderr. A daemon that is down, or that answers 404 or any other refusal, is reported on stderr and exits with
that command's usual code. Exit 2 is the usage code at a terminal; Codex and Claude Code both treat it as "block this
turn", so a hook never exits 2.

An attach that finds the peer as asked changes nothing and answers once its helper runs. Attach is refused when no
Codex app-server listens on the home's `app-server-control/app-server-control.sock` (or the daemon its
`app-server-daemon/daemon.pid` names), because no message could reach the thread then. A thread run with
`codex --no-daemon` has no app-server, so it cannot be a peer.

Attach looks up its own ancestry for the nearest process running `codex`. When it finds one, the peer's registry
record carries a `lifecycleBinding` (version 1) naming that process as the thread's native owner and the peer's
helper as the process that receives for it, which is how Fence tells which conversation a Codex hook belongs to. The
binding is written only while that process is still the one named.

The helper hands each message it receives to the thread with the app-server's `turn/start`, one at a time and in
order, in Claude Code's own envelope (`<cross-session-message from="…" from-name="…">`), with the message's id as
the input's client id. The thread answers with `splice peer send`, which inside the conversation acts as its codex
peer without `--as`:

```
splice peer send gary "the gate is green"
splice peer inbox
```

Each hand-over is journaled as a `codex` record: `issuing` before the request goes out, then `started` with the turn
Codex named, `refused` with Codex's reason, or `unconfirmed` when the request went out and no answer came. Nothing is
handed over twice. A helper that stops between `issuing` and its outcome leaves the next helper to read the thread's
history: `started` when the history holds the message, `unconfirmed` otherwise, never a second send. A message Codex
refused gets a `dropped` receipt, so its sender learns the thread never saw it. The record's status is `busy` while
the thread runs a turn and `idle` otherwise.

When the thread closes or unloads, or its app-server closes the connection, the helper journals `ended` and stops,
and the daemon revokes the peer; its journal stays. A thread that loads again attaches again from its next hook. The
helper answers no request the app-server makes of it, such as an approval, and grants the thread no tool.

A codex peer does not count against the 16 peers a user registers by hand; at most 64 Codex threads are peers at
once. `splice peer codex detach` revokes one at once, and `--purge` deletes its journal too.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | done (for send: submitted) |
| 1 | refused by the daemon, with the error printed |
| 2 | usage |
| 3 | daemon unreachable |
| 4 | rate limited |
| 5 | transport failed |

## Where it stores things

| Path | What |
|---|---|
| `<settings dir>/peers/<name>/peer.json` | the registration |
| `<settings dir>/peers/<name>/limit.json` | the peer's own send limit, when it has one |
| `<settings dir>/peers/<name>/peer.key` | the peer's key, the only copy |
| `<settings dir>/peers/<name>/webhook.secret` | the webhook signing secret |
| `<settings dir>/peers/<name>/webhook.bearer` | the optional webhook bearer |
| `<state dir>/peers/<name>/journal.jsonl` | what the peer received, rotated at 50 MiB |
| `<state dir>/peers/<name>/outbox.jsonl` | one line per send, with its id, the size and the SHA-256 of the text, never the text |
| `<state dir>/peers/<name>/codex.cursor` | a codex peer's last message handed to its thread |
| `<state dir>/peer-host/host.json`, `host.lock` and `start/<name>` | the one process that serves every peer, and the peers asked of it |
| `~/.claude/sessions/<pid>.json` and its `.key` | the registry record that makes the peer visible to Claude Code |

Folders are mode 0700 and files 0600, except the registry record, which is 0644 as Claude Code writes its own.

## Trust boundary

The boundary is the Unix user. The registry, the key files, the sockets and the Splice config are all
readable by any process running as this user, and by nothing else. A same-user process can write any frame
to any socket. Splice cannot stop that, and Splice does not claim to.

Here is what Splice does guarantee:

- **A sender name cannot be forged through Splice.** Every frame Splice writes carries the name of the
  peer whose key authenticated the request, and nothing taken from the request.
- **A peer key opens only its own peer's routes.**
- **No socket accepts a wrong token or another uid.** Splice's sockets refuse any connection from another
  uid, and any frame that follows an auth line with a token other than the socket's own `peerToken`. A
  frame with no auth line is accepted, as Claude Code itself accepts one on Linux, and is journaled with
  `"auth":"none"`.
- **Every inbound sender is labeled by how it was checked.** The journal marks it `registered` when its
  `from` address names a live registry record, or `unregistered` when it does not. Neither label proves who
  wrote the bytes, and the docs say so.

So `check: registered` tells you the address belongs to a live session. It does not prove which process wrote
the message.
