# LAN share: Claude Code on one machine, running on another machine's heads

- Status: rebuilt from Marcos's ruling of 2026-10-24 (below). The first build (phases 1 and 2, 2026-10-22 to
  10-24) relayed through a head on the using machine and copied tokens, models and sessions to it; commit
  c830a4250 removed it, and this spec starts again from the goal.
- Overturns: the `docs/specs/v0.4.0.md` boundary "Preserve the single-user, loopback product boundary.
  Remote/multi-user hosting is not a missing feature to quietly fold into this release." Single-user
  stays: one person's own machines. Loopback-only moves for exactly one opt-in listener.

## The goal

Marcos, at the Mac, types `claudex`, and Claude Code runs on torad-server's ChatGPT login, the way Claude
Code runs on Vertex or Bedrock: the Mac's Claude Code is pointed at torad-server's address and holds nothing
else. No credential, token or file is copied to the Mac or kept in step with it.

How anyone can tell it does:

1. On torad-server, `splice share on` opens one port on the LAN (3097) and prints, for each command it
   offers, the address another machine uses: `claudex  http://torad-server.lan:3097/claudex`.
2. On the Mac, `splice remote add http://torad-server.lan:3097/claudex` links `claudex`. What the Mac keeps
   for it is that address, one line in its `splice.toml`, and nothing else.
3. `claudex` on the Mac starts Claude Code with torad-server's address as its base URL. Its turns answer on
   torad-server's login, its `/model` picker lists torad-server's claudex models, and its statusline is
   torad-server's for that session. The session's environment holds no credential: its auth token is a
   fixed word that opens nothing, and torad-server signs every request with its own logins.
4. Query params on the address choose what the Mac gets, and torad-server filters by them:
   - `auth=<login>`: the Mac's sessions run on that one of torad-server's logins, as a Pin on the
     console's Accounts page does. The statusline's usage is then that login's.
   - `models=<id>,<id>`: the Mac is shown those models only (picker, tiers and pinned model).
   An unknown login or model is refused when the address is added and when `claudex` starts, naming what
   torad-server has.
5. When torad-server can't be reached, `claudex` says so before Claude Code starts: "claudex runs on
   torad-server.lan (http://torad-server.lan:3097), which can't be reached: <why>", naming the host as the
   address does. Mid-session, Claude Code says it cannot connect; nothing between them turns that into
   "overloaded". When torad-server answers but its claudex is not running, or does not answer, the turn's
   error says that, in torad-server's name.
6. `/login` in the Mac's `claudex` says its logins live on torad-server and how to sign in there. It never
   asks for a key. `claudex login` typed in the Mac's terminal says the same.
7. torad-server answers machines on its own networks only. There is no join, token, pin or certificate.

## Decisions (Marcos, 2026-10-24): a node is a client, nothing more

Marcos, after the Mac's `claudex` died on a LAN blip and its `/login` answered "this head signs in with an
API key" (2026-10-24): "a user using splice as a node, should connect to the server splice just like it
does, when using vertex or bedrock. That's it, it shouldnt care about credentials, it shouldn't care about
synching file, about tailscale, NONE OF that. And we can filter the details about models and usage, and
what the auth to use via query params. That's it, it's so simple."

- **A node is Claude Code pointed at the server.** The machine that uses another machine's heads is a
  node. It connects to the server's splice the way Claude Code connects to Vertex or Bedrock: a base URL,
  and nothing else to hold or keep in step. The server's splice listens on the LAN for its heads and
  injects every credential itself. The node holds no credential, no token and no key, and syncs no file.
  Whether the node runs a daemon of its own is the seat's design call; what it never does is the above.
- **What the node asks for rides in query params.** Which head, which of the server's logins (auth) a
  turn uses, and which models and usage the node is shown, are named by query params on the server's URL
  and filtered there. Nothing about them is copied to the node.
- **Trust the LAN, for turns too.** No join, no per-machine token, no trust on first contact, no
  certificate pin, no remotes store. The server's heads answer any machine on its private networks.
  Raised once: every device on that LAN can then use the heads. Marcos's decision.
- **Tailscale is not part of this.** A node reaches the server by its LAN address, as it would any proxy.

The 2026-10-22 decisions that stand: **relay, never copy** (the server keeps sole ownership of every
credential, its refresh, its account pool, its cooldowns and its usage meter: copying an OAuth file would
make two refreshers rotate one token and sign each other out), and **trust the LAN**.

What the Mac showed on 2026-10-24 that this replaces: the relay modeled as an api-key provider, whose
`/login` said "this head signs in with an API key"; and a dead connection to the server answered as a 529
`overloaded_error`, so Claude Code printed "(overloaded), retrying" and never the reason.

## Design calls (the seat's)

- **No relay on the node.** The node's Claude Code talks to the server's share port itself. A relay on
  the node is what turned a dead link into "overloaded"; without one, a dead link is Claude Code's own
  "Unable to connect to API", which is true. The node's daemon prepares the launch and nothing else: it asks
  the server for the head's models and timeout, and builds the same recipe a local head gets, with the
  server's address in place of a loopback port.
- **Plain HTTP.** Claude Code refuses a self-signed certificate, and with no pin there is nothing to check
  one against, so the share port speaks HTTP. Prompts and replies cross the LAN in clear; the LAN is
  trusted, as decided.
- **The selection rides in the base URL's path.** Claude Code appends `/v1/messages` to its base URL as
  text, so a query string there would end up in front of the path. The node's daemon turns the address's
  params into the path the server reads back: `auth=work` becomes `/claudex/@work`. `models` is the
  server's to apply to what it describes, so it never reaches a turn.
- **`usage` follows `auth`.** The statusline a node sees is the server's for that session, which shows
  the login the session runs on; `auth=` is the filter. No separate `usage` param.
- **Who is on the LAN.** A source on loopback, RFC 1918, link-local (`169.254/16`, `fe80::/10`), or an
  IPv6 address on a `/64` one of this machine's house interfaces holds (a house router that hands out global
  or unique local IPv6 puts the Mac there). Neither `100.64/10` nor the rest of unique local IPv6 is:
  Tailscale hands out `100.64/10` and `fd7a:115c:a1e0::/48`, so a tailnet peer is turned away over either.
  A source on any prefix a datacenter interface holds (one with a public IPv4 address) is refused whatever
  its range: a provider's private network, link and /64 are shared with its other customers. A machine
  whose interfaces can't be read answers loopback alone, and says why.
  A request must also name this machine in its Host: an IP literal, its host name, the host name under
  `.local`, `.lan`, `.home` or `.internal`, or a name in `[share] hostnames`. That keeps a web page a
  browser on the LAN opens from reaching the heads by rebinding its own name to this machine.
- **What is offered.** Every head that serves only on logins it holds. A Claude head, which takes the
  caller's own login, is offered once every login in its pool is one the server signed in itself (its native
  sign-ins, its added accounts): a node's turn runs on one of those, and the held login names Anthropic's
  OAuth beta on every turn it carries, which a node's Claude Code, holding no login, does not. While the
  caller's own sign-in is one of the head's logins, or it holds none, it has nothing to offer a node.
- **Plain `claude` on a node** (Marcos, 2026-10-25: "claude here is still using a local account instead of
  the server account on torad-server"). `claude` may name a remote command: `splice remote add
  http://torad-server.lan:3097/claude-splice --as claude`. The wrapped `claude` then launches on the server's
  head, over the node's own `~/.claude` as it does today, so its settings, instructions, sessions and models
  stay the person's: only where its turns go changes. The node gets no model list from the server, since a
  Claude head's caller picks its own, and auto mode keeps Anthropic's own checks, since its turns reach
  Anthropic through the server's Claude head. `/login` and `/logout` stay hidden, as on every remote command:
  the node holds no login, and signing in there would change nothing about where its turns go. Only a
  wrapped `claude` asks splice where to run, so on a node whose `claude` is Claude Code itself the add is
  refused, naming `splice setup`, which wraps it. `claude` stays Claude Code's own link, which no remote
  command makes, replaces or removes, `splice install` and `splice uninstall` included. An add that names the
  server alone takes the Claude head under its own command name, `claude-splice`, like any other command: a
  node with a head of that name refuses it as a name already taken, and plain `claude` takes it by its own
  address, `--as claude`.
- **The node's word never reaches the vendor.** A node's turn on a Claude head carries the word `splice-lan`,
  and the head's pool sends a login the server holds in its place. When the head has no such login left for
  it (its pool lost its own sign-ins between the offer and the turn), the head answers the turn itself, a
  503 the node's Claude Code shows and does not retry: sent upstream, the word would come back a 401 that
  marks the head's own sign-in rejected for every session. The same holds partway through a turn: when the
  held login is refused and the only login left is the head's own sign-in, the turn ends on the held login's
  refusal instead of moving onto it.
- **A login pin is the console's pin.** `auth=work` pins each of the node's sessions to `work` through the
  head's own pool, the call the Accounts page's Pin makes, before its turn is relayed. What a pinned session
  does when its login is at its limit is what it does on the server today.

## How it works

The server (`[share]` in its `splice.toml`, `enabled = true`, `port = 3097`):

`<head>` below is the command the head runs as on the server, or its key.

| request | answer |
|---|---|
| `GET /` | the machine's name and its offered commands: head, command, models, logins |
| `GET /<head>/launch?auth=&models=` | what a node's launch needs: the models block (pinned, offered, labels, tiers, picker rows, window), the client timeout, and the base path for turns |
| `POST /<head>[/@<login>]/v1/...` | relayed to the head on loopback with the head's turn key in place of whatever the node sent (a Claude head, whose door refuses splice's keys, gets the node's word `splice-lan`, and its pool sends a login it holds); `@<login>` pins the session (`X-Claude-Code-Session-Id`) to that login first |
| `POST /<head>[/@<login>]/statusline` | relayed to the control plane's statusline for that head, with the turn key; `@<login>` pins the session the body names (`session_id`) first, so the line is that login's from its first draw |

A source off the LAN, or a Host that does not name this machine, is answered 403 with the reason. So is a
request with an `Origin` header: a browser puts one on every request a page posts, and Claude Code puts
none. A turn whose body is not `application/json` is 400: a page can post JSON only after a preflight,
which nothing here approves. Together they keep a page from running a turn by addressing the machine by
IP or as `localhost`, which the Host check admits. A head
that is not offered is 404, naming the ones that are. A login or a model the head lacks is 400, naming the
ones it has. A head that is offered but not running, or not answering, is 503. Every error is an Anthropic
error body naming the machine and the head, and none is `overloaded_error`.

The node (`[remote.<command>]` in its `splice.toml`, `url = "<the address>"`): `claudex` runs the launch
shim as every command does. The daemon finds no head named `claudex`, finds the remote command (asked before the wrapped `claude`'s own head here), asks
`GET <server>/<head>/launch` with the address's params (3 s to connect), and builds the recipe: base URL
the server's turn path, auth token the word `splice-lan`, the head's models and timeout, its own
`~/.claude-<command>` tree (the wrapped `claude`'s is the node's own `~/.claude`), the server's statusline (on the login the address names), and a `/login` that
names the server. The server's host joins `NO_PROXY` and `no_proxy` (curl reads the lowercase one first), so a proxy the shell names takes neither the turns nor
the statusline. When the server can't be reached, or refuses, the launch is refused with that reason. The
session's start hook posts to the node's own daemon under the command's name, which records the session as that
tree's own, so a bare `claudex -c` continues the newest `claudex` session in the folder, as a head here does.

A node the first build left still has that build's relays: a head per command, on a provider it wrote
with `forward_client_identity`, and a share token in keys.toml. Boot refuses with a finding at
`heads.<key>` for each, naming the fix, and `splice remote add` with the server's address replaces each
relay under its own name, taking its provider and its share token out in the same add. The server removes
the first build's `share/clients.json` and its lock when it starts.

`splice share on|off|status` writes `[share]` and restarts the daemon; `status` prints each command's
address. `share on` refuses a port this machine can't open (another program holds it, or the OS refuses it), in the OS's words, before the file changes. `splice remote add <address>` checks the address against the server and writes
`[remote.<command>]`, links the command and restarts; `add <server>` with no head adds every command it
offers; `splice remote remove <command>` takes one out; `splice remote` lists them, each with whether its
server answers now.

## Bounds

- Any device on the server's LAN can run its heads, as decided. A server whose private network is shared
  with strangers (a datacenter's, a café's) lets them in too; `splice share on` is the person's choice to
  make on a network they trust.
- A node has no copy of anything, so a node with no route to the server has no `claudex` until it does.
