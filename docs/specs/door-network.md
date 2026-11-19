# The door, served on the network

One splice runs on a box. Its door answers on the network, the way Bedrock does, and any machine pointed at it gets
every model of every account signed in on that box plus every local server it runs, with one command and no credential
on the machine.

`docs/specs/door-default.md` built the door for `claude` on one machine: Claude Code's Mantle dialect, where the model a
request names decides which provider runs it. This spec lets another machine use that door.

Decided by Marcos on Nov 18, 2026: heads are deprecated, so the network goes through the door and not through a command
per head; and the tailnet is part of "the network", which reverses the Oct 24 "Tailscale is not part of this" in
`lan-share.md`.

## What a person sees

- On the box: `splice share on` (the share listener, port 3097) answers the door, and since share-door-only nothing
  else. `splice share status` prints the door's address, how many models it serves, the one command another machine
  runs, and the three variables a Claude Code without splice runs on it.
- On another machine: `splice remote add http://<box>` and its plain `claude` runs on the box's door. `/model` lists
  every model the box serves, labelled `splice · <command> on <box>`. The session runs over that machine's own
  `~/.claude`, and no credential lands there.
- A Claude Code without splice, or Claude Desktop's Code tab, runs on it from the three Bedrock-style variables
  `splice share status` prints: `CLAUDE_CODE_USE_MANTLE=1`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL=http://<box>:3097/door`
  and `AWS_BEARER_TOKEN_BEDROCK=splice-lan`.
- On the box itself the same door answers at `http://127.0.0.1:<control_port>/door`, for its own `claude` and its
  own Claude Desktop, each with its key. A call at the share listener that presents one of those keys is answered
  as that caller.
- A machine that answers no door (an older splice) is not added: `splice remote add` says why, in the door read's own
  words (it can't be reached, its door is still starting, or it answers none), and writes nothing.

## Decisions

### 1. The door is a path of the share listener

`/door/launch` (GET) and `/door/v1/messages`, `/door/v1/messages/count_tokens` (POST) on the share listener. No head
may be offered under the name `door`. Every request passes the share listener's admission first, then, for a turn, the
JSON-only rule every turn there has. A door whose heads are still starting answers 503 saying when it opens. Every
daemon that shares runs a door, and since share-door-only it answers nothing else but its name at `/` (decision 5).

### 2. Who may reach it: where a request comes from

There is no key. The box admits a source by where it is: this machine's LAN as `lan-share.md` defines it, and its
tailnet. A source on 100.64/10 or fd7a:115c:a1e0::/48 is admitted while the box holds an address there itself (so it is
a peer of the same tailnet, which Tailscale already admitted); on a box with no tailnet address those ranges are a
carrier's NAT and are turned away. The Host may also be the box's MagicDNS name (`<box>.<tailnet>.ts.net`). A request
with an Origin, and a turn that is not JSON, are still refused, so a web page cannot use the door.

Mantle sends a bearer token whatever happens. The box ignores it; the machine sends the fixed word `splice-lan`, so a
door key per device costs the user nothing and the LAN alone is trusted, as `lan-share.md` decided.

### 3. What a turn runs on

The same door the terminal's `claude` uses: the model a request names picks the head (`DoorRouting`), Claude's own
models run on the Claude accounts added to splice, and a model the door does not serve is refused by name with nothing
sent upstream. The caller is the terminal's (`runsNamedModels`), the window and starting model are the terminal lane's,
and a turn's row reads as a `claude` session of this door.

### 4. The launch on the other machine

`splice remote add http://<box>` writes `[remote.claude] url = "http://<box>:3097/door"` (the share port is taken when
the address names none; `--as <name>` links another command instead of `claude`). Each launch asks `GET /door/launch`
for the box's models, window and starting model, so a sign-in on the box reaches the next session with no restart, and
builds the launch `claude` on the door has here (`DoorSession`), with the box's door as the base URL. A `[remote.claude]`
is asked before this machine's own door and head. A box that can't be reached, or refuses, is said before Claude Code
starts, naming it and the reason.

### 5. Retired: one command at an address of its own

Decided by Marcos on Nov 18 and 19, 2026: with the door there is one way for a person's machines to share, and the
per-command way of `lan-share.md` goes (share-door-only). Two ways to do the same thing is what he wanted gone, and head
commands are deprecated in favour of `claude` on the door.

- `splice share status` prints the door alone: its address, how many models it serves, `splice remote add
  http://<box>`, and the variables for a Claude Code without splice. While the door is still starting it says so.
- Any path on the share listener other than `/` and `/door` answers 404 in Claude Code's error shape, saying the machine
  no longer shares one command at an address of its own, that its door serves every model it has, and
  `splice remote add http://<the Host the request used>`. Admission still runs first.
- `GET /` still answers the machine's name, with an empty list of commands, because a 0.6.4 node's `splice remote`
  reads it to say whether its door remote is ready.
- `splice remote add` of one command's address on a box this machine holds no key for is refused with that add line,
  and nothing is written. `splice remote add http://<box>` no longer falls back to linking each command of a box that
  answers no door: it says why there is no door to link.
- A `[remote.<command>]` written before still loads, and its launch prints the box's 404 before Claude Code starts;
  `splice remote` lists it with the add that replaces it.
- Server mode (`server-mode.md`) is untouched: a team's server still serves each command to key holders at an address
  of its own.

## Not in this change

- Server mode's keys, ledger, budgets and metrics for whoever reaches this door. They stay on the server-mode listener.
- The plan-login refusal in server mode's check-config.
- A door key per device, and a join step.
