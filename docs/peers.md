# Peers: a named mailbox every Claude Code session can message

A peer is a name you register with Splice. Every Claude Code session on this machine sees it in ListAgents and
can SendMessage to it, even though no Claude Code session runs behind it. The peer keeps what it receives in a
local journal, can push each message to a webhook, and can send to any session or peer under its own name.

Nothing here runs until you register a peer. With no peer registered there is no listener, no socket, no
process, no file and no registry entry.

## Register a peer

```
splice peer register gary
```

This prints the peer's name, its session id, the path of its key file and its socket. It never prints the key.
The key file is `<settings dir>/peers/gary/peer.key`, mode 0600, where the settings dir is the folder holding
`splice.toml` (usually `~/.config/splice`).

A name is 2 to 32 characters: a lowercase letter, then lowercase letters, digits or `-`. These names are
reserved: `system`, `user`, `operator`, `human`, `assistant`, `claude`, `anthropic`, `splice`, `splice-console`,
`telegram`, `president`, `marcos` and `dispatch`. A name a running session or another peer already carries is
refused. One user may have 16 peers.

Options:

- `--cwd DIR` sets the working directory the peer's registry record shows. It defaults to your home folder.
- `--send-to A,B,…` limits who the peer may send to, by name or session id. The default is anyone.

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

A text is at most 32,768 bytes. A peer may send 20 messages a minute, in bursts of up to 5.

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

Inside a Codex conversation the thread is `$CODEX_THREAD_ID`, the Codex home is `$CODEX_HOME` (else `~/.codex`) and
the working directory is the command's own, so `attach` needs no argument. A Codex hook runs it on each event: an
attach that finds the peer as asked changes nothing and answers once its helper runs. Attach is refused when no
Codex app-server listens on the home's `app-server-control/app-server-control.sock` (or the daemon its
`app-server-daemon/daemon.pid` names), because no message could reach the thread then.

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
| `<settings dir>/peers/<name>/peer.key` | the peer's key, the only copy |
| `<settings dir>/peers/<name>/webhook.secret` | the webhook signing secret |
| `<settings dir>/peers/<name>/webhook.bearer` | the optional webhook bearer |
| `<state dir>/peers/<name>/journal.jsonl` | what the peer received, rotated at 50 MiB |
| `<state dir>/peers/<name>/outbox.jsonl` | one line per send, with the size and never the text |
| `<state dir>/peers/<name>/codex.cursor` | a codex peer's last message handed to its thread |
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
