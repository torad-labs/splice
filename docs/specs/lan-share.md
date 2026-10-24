# LAN share: use another machine's heads from this one

- Status: **phase 1 authorized** (Marcos, 2026-10-22, in session): "let's start by making it work for us,
  then we'll make a proper feature with a tab in the console UI for management"
- Overturns: the `docs/specs/v0.4.0.md` boundary "Preserve the single-user, loopback product boundary.
  Remote/multi-user hosting is not a missing feature to quietly fold into this release." Single-user
  stays: one person's own machines. Loopback-only moves for exactly one opt-in listener.

## The user outcome

torad-server runs splice with `claudex` and `claude-grok` signed in. On the Mac:

```
splice remote
```

finds torad-server on the network, shows its heads, asks "Use torad-server (claudex, claude-grok)? [Y/n]",
and on yes writes the same heads into the Mac's `splice.toml`, links the same commands and restarts.
`claudex` on the Mac then runs Claude Code on torad-server's ChatGPT login. No login is copied and no
login is asked for.

## Decisions (Marcos, 2026-10-22)

- **Relay, never copy.** A turn on the Mac goes to the Mac's head, which forwards it to the server's
  head. The server keeps sole ownership of every credential, its refresh, its account pool, its 429
  cooldown, its inflight gate and its usage meter. Copying an OAuth file would make two refreshers
  rotate one token and sign each other out (README, "Credential locations").
- **Trust the LAN.** A machine on this LAN that asks to join a sharing server is accepted
  without a code. On this LAN means a private source address — loopback, RFC 1918, link-local,
  100.64/10 or `fc00::/7` — or an IPv6 address on a prefix a house interface of this machine holds.
  The second is what a house needs: a router that hands out an ISP-delegated IPv6 `/64` puts two
  machines one hop apart on globally routable addresses, and the private ranges never see them, so
  every imported command read unreachable the moment anything preferred IPv6 (2026-10-23). A house
  interface holds an RFC 1918 address its router NATs and no public IPv4 one. A datacenter server's
  interface holds a public IPv4 address, and what sits beside it is the provider's, shared with its
  other customers: Linode puts a `/64` every customer in the datacenter shares there, and its private
  network (`192.168.128.0/17`) is one network for every customer in the region. So a source on any
  prefix a datacenter interface holds is refused, whatever its range, its link-local addresses
  included in either family, since no prefix says which link one came in on (IPv6 puts `fe80::/64` on
  every interface, and macOS and RHEL route `169.254/16` onto one without holding it); a private
  network routed to the machine some other way is still admitted. A machine whose interfaces cannot
  be read cannot tell a house from a datacenter, so it admits loopback alone for that request, and the 403 says so
  ("this machine could not read its own networks"), never that the source is off its LAN. An interface
  with no IPv4 address says nothing either way and admits no one on its own prefixes. An address past
  every house prefix is still refused, and IPv4 is otherwise left to the private ranges. Marcos's "a
  private-network machine that asks is accepted" (2026-10-22) is read as his house: a datacenter's
  private network is not one. Bound: on a LAN that hands out DHCPv6 addresses with no SLAAC, a Linux
  host holds only a `/128` and the `/64` lives only in its route table, so a neighbour there is still
  refused over IPv6 and reaches it over IPv4. Bound: no address rule tells Marcos's house from any
  other RFC 1918 network, so a machine sharing from a café, hotel or friend's Wi-Fi lets any device
  there join with no code and run its commands (trust the LAN is kept for commands, the President's
  round-1 decision; sessions still wait for their switch). Guardrails that cost no
  step: sharing is off until `splice share on`; the listener admits only those sources; each client
  gets its own token, listed and revocable. Trust
  reaches commands only: a joined machine crosses no Claude Code session until its Sessions switch is
  on (milestone 6, Bounds).

## How it works

```
Mac                                          torad-server
claudex ─▶ head :3099 (anthropic-passthrough) ──TLS, pinned──▶ share listener :3097 ─▶ head :3099 (codex) ─▶ ChatGPT
            auth splice-remote = client token                    checks token, swaps   turn key, loopback
```

### Server: `[share]` and the share listener

- Topology: a new root table, default off.

  ```toml
  [share]
  enabled = true
  port = 3097            # default; must not collide with control or any head
  # heads = ["claudex", "claude-grok"]   # default: every head except client-auth heads
  ```

  Boot findings (fail-closed, same list as today): port range and collision, unknown head in `heads`,
  a client-auth head in `heads` (it forwards the caller's own Claude login; relaying it is meaningless).
- One extra Netty listener, started by the daemon only when `enabled`, bound on all interfaces, TLS only (phase 2).
  Its certificate is minted at the first start with sharing on: an EC P-256 key and a self-signed certificate for the
  machine's names (`<host>`, `<host>.local`, `<host>.lan`, `share.hostnames`), valid ten years, kept in
  `<state>/share/tls.pem` (0600, `ShareCertificate`). It is never minted again over a file that is there: every
  joined machine pinned it, so a file that will not load stops sharing, said in the log. `splice share status` shows
  its fingerprint (`sha256:<hex>`, the same as `openssl x509 -in tls.pem -noout -fingerprint -sha256`). A phase-1
  client still on plain HTTP gets a 426 in the Anthropic error shape naming the one command that moves it
  (`ShareTlsOnly`). The listener speaks HTTP/1.1 only: with HTTP/2 on, Netty builds the pipeline after the ALPN
  handshake, and a plain request never reached that guard. Routes:

  | route | auth | purpose |
  |---|---|---|
  | `GET /share/v1/info` | none | server name, splice version, the certificate's fingerprint, and for each shared head: key, command, pinned model, model rows (id, label, window, slot, rates) |
  | `POST /share/v1/join` | none | `{ "client": "<hostname>" }` → a new per-client token (shown once) |
  | `POST /share/v1/heads/{head}/v1/messages` | client token | relay, streamed |
  | `POST /share/v1/heads/{head}/v1/messages/count_tokens` | client token | relay |
  | `GET /share/v1/heads/{head}/v1/models` | client token | relay |
  | `GET /share/v1/heads/{head}/roster` | client token | the head's picker as it stands now (a `SharedHead`, as `/info` lists it): the using machine's hourly roster refresh reads it instead of `/v1/models`, so wrapped ids never reach its picker (phase 2) |
  | `GET /share/v1/heads/{head}/usage` | client token | the head's plan windows now, in `QuotaJson`'s shape, `{}` for none: the using machine's quota poll for that head, so its bars show this machine's plan (phase 2) |
  | `GET /share/v1/health` | client token | this machine's name, version, when its listener came up, and each offered head's `running`: the using machine's reachability check, every minute (phase 2) |

  Every request: source address must be loopback, RFC 1918, 100.64/10 (Tailscale), link-local or
  fc00::/7, else 403. Host must be an IP literal or one of this machine's names (`<host>`,
  `<host>.local`, `<host>.lan`, plus `share.hostnames`), which keeps DNS rebinding out the same way
  `LoopbackHost` does for loopback.
- The relay forwards the body and the client's headers to `http://127.0.0.1:<head.port>` with the
  client token replaced by the head's turn key (`TurnKey`), and streams the response back unbuffered.
  The head's own admission, inflight gate, account selection and honest-failure paths run unchanged.
- Client tokens: 32 random bytes, stored hashed with name, address, first and last seen in
  `<state>/share/clients.json` through `SecureFile.writeAtomic0600`. Never the management or turn key.
- Activity (phase 2): every guarded request marks its machine seen, and a relayed turn counts toward its turns today.
  The daemon keeps both in memory and writes `<state>/share/activity.json` at most once a minute and when sharing stops
  (`ShareActivity`), so a turn never waits on the disk for it. `splice share status` shows them beside each machine.
  A relayed turn carries the machine's name in `x-splice-share-relay`, the header the relay already set, and its perf
  row records it, so the Requests page says where the turn came from.
- Discovery (phase 1): the client asks every address of each private IPv4 network it is on (a /24 around
  itself on a network wider than /22) for `/share/v1/info` on the share port, all at once under one deadline.
  No multicast and no new dependency. Since phase 2 it is one of three sources (milestone 5 below).

### Client: `splice remote`

- `splice remote` finds the sharing machines (above), lists each with its heads and asks per machine. `splice remote add <host[:port]>`
  skips discovery (multicast blocked, Tailscale). `splice remote list` shows the machines joined here and their
  commands; `splice remote sync [<machine>]` rewrites each imported head's rows, pin and tiers as its machine serves
  them now; `splice remote remove <machine>` drops the machine, its heads, their command links and its token.
- Import of one machine (`RemoteImport`):
  1. `POST /share/v1/join`, over a connection that must show the certificate the machine's info stated (trust on
     first contact, `RemoteJoin`) → this machine's token there, kept in `<state>/share/remotes.json` (0600, one per
     machine, `RemotesStore`), never in `keys.toml`. splice.toml names the machine, never the token.
  2. `[remotes.<name>]` with the address that answered (the machine's own name when it resolves there) and the
     certificate's fingerprint, pinned from then on.
  3. Each shared head written through the `splice add` path (`AddVerb.addRemote`: the same refusals, checks, atomic
     save, port choice and wrapper link), one line per command and one restart at the end:

     ```toml
     [remotes.torad-server]
     url = "https://torad-server.lan:3097"
     fingerprint = "sha256:4f1c…"   # the certificate it showed at the join

     [providers.claudex]
     dialect = "anthropic-passthrough"
     base_url = "https://torad-server.lan:3097/share/v1/heads/claudex"
     auth = { kind = "splice-remote", remote = "torad-server" }
     quirks = { forward_client_identity = true }
     [[providers.claudex.models]]   # the sharing head's rows: id, label, window in force, compaction reserve
     ...
     [providers.claudex.discovery]
     family = "openai"              # the sharing machine's family for it, so the console colours it the same

     [heads.claudex]                # same key, command and pinned model; tiers in model_slots
     ...
     ```

     A head key or command already taken here is asked about: import it as `<key>-<machine>`, or skip it.
  4. Heads imported before are refreshed in place; phase-1 imports from the same machine (api-key providers on a
     share relay) move onto `[remotes.<name>]` and their per-head keys leave `keys.toml`.
- `splice-remote` is a registered auth kind (anthropic-passthrough only): the provider presents the machine's token
  as its bearer, read from the token store on every request, so a re-join lands with no restart. Its turns count as
  the sharing machine's plan ("on your plan"), never as API spend.
- Trust (`RemoteTrust`): every client path to a machine (`RemoteHttp` for info, join and sync; the relay's upstream
  client, through `UpstreamTrust`) trusts exactly the pinned certificate, by its SHA-256, with no CA and no name check:
  the pin is the stronger claim, and the machine keeps it at a new address. A changed certificate is refused in words
  naming the old and new fingerprints and `splice remote add`, which pins the new one. Boot refuses a
  `[remotes.*]` without an `https` url or a fingerprint.

## Code map

- Topology: `core/.../topology/Topology.kt` (`share`, `remotes`), `ShareConfig` and `RemoteConfig`; findings in
  `TopologyChecks.findings()` (`core/.../topology/TopologyWriter.kt`) with `RemoteFindings` (`TopologySchema.kt`).
- Module `features/share` (`:features-share`): the listener, relay, client book and network rules (`splice.share`),
  and the client side (`splice.share.remote`): discovery, remote HTTP, the token store and `RemoteAuthProvider`, and
  `RemoteTopology`, the splice.toml edits a join, sync, removal and migration make. app wires it: `Daemon.kt` starts
  the listener (`ShareStart`), `PassthroughArm` builds a `splice-remote` provider, and the CLI verbs live in
  `app/.../cli/share/` (`ShareCommand`, `RemoteCommand`, `RemoteImport`, `RemoteMachines`).
- Wall: `quality/rules/kotlin/kt-embedded-server-loopback.yml` exempts the one share listener file,
  with this decision cited. Every other `embeddedServer` stays loopback-only.
- Turn path: a provider quirk `forward_client_identity` (QuirksConfig) makes a head on its own key send the
  client's session id, wire knobs and identity upstream, never its credential (ClientAuth.forwardedClientHeaders);
  imported heads set it, so the sharing head keeps a session on one account and one prompt-cache key.

## Phase 1 trade-offs, stated

- **Plain HTTP on the LAN.** Under trust-the-LAN any LAN host can join anyway, so TLS would protect
  prompt content in transit, not access. Phase 2 replaced it with a pinned self-signed certificate.
- **The server must be up.** A Mac turn with torad-server down fails in words, like any upstream outage.
- **A changed certificate is retried like any TLS failure.** The turn ends with the refusal's words, what to run
  first, but through the generic bounded retry and as an overloaded error, which Claude Code retries too. Naming it
  a permanent failure is a change to the shared retry plan, left for its owner.
- **Server-side only:** account pools and the logins themselves live on the server. Since phase 2 the Mac's plan bars
  read the server head's windows (`/usage`), and its turns bill as on-plan.
- **No cross-machine `ListAgents`.** Sessions on the two machines did not see each other; phase 2's sixth milestone
  mirrors them.
- **Project system-prompt layers** resolve on the server from a cwd it does not have; only head-level
  prompts apply to relayed turns.

## Verify (phase 1)

1. torad-server: `splice share on`; `splice share status` lists the listener and the shared heads.
2. Mac: `splice remote` finds torad-server, imports `claudex` and `claude-grok`; `splice doctor` is clean.
3. Mac: `claudex -p "reply with the word PONG"` and the same on `claude-grok` answer; the server's
   request log shows both turns on its own heads.
4. Mac: a long streamed turn with tool calls in an interactive `claudex` session streams without stalls.
5. torad-server: `splice share revoke <mac>`; the next Mac turn fails with a clear 401 message.
6. A request from a public source address and one with a foreign Host are refused with 403.

## Phase 2 (Marcos, 2026-10-22: "make this a proper feature")

Built on `feat/lan-share`, merged into `feat/v0.4.0` through the gate. Milestones:

1. **Module and model** (landed): `features/share`, the `splice-remote` auth kind, `[remotes]`, the token store,
   one-line imports, `remote list | sync | remove`, phase-1 migration.
2. **TLS and pinning** (landed): a self-signed certificate minted at the first `share on`, the listener on TLS only, and the
   certificate's SHA-256 pinned in `[remotes.<name>] fingerprint` at join, on every client path.
3. **Fidelity** (landed): roster, usage and health routes on the listener. On the using machine one `RemoteLinks`
   (each `[remotes]` entry's pin, and its token read per request) serves every daemon path to a remote: the relay's
   upstream trust; the hourly roster refresh, which reads a `splice-remote` head's rows from `/roster`
   (`RosterSources`); the quota poll, which reads `/usage` (`HeadQuotaPolling`); and a check of `/health` every minute.
   A head whose machine did not answer, or whose head there is not running, is "Not answering" in the console as a
   stopped local runtime is; `/health` names each machine's state under `remotes`, and doctor shows one row per
   machine. `splice models` says a remote head lists its models on its own machine. On the sharing machine:
   last seen and turns today per joined machine, and the machine's name on each relayed turn's perf row.
4. **Console** (landed): a Sharing page, the console's ninth (Marcos asked for a Sharing tab; the 0.4.0 spec records
   eight pages, and his later words hold). Left, this machine: sharing on or off, the port, the certificate's pin, which
   commands are offered ("Shared" or "Only here", or why one cannot be), and the machines that joined, each with last
   seen, turns today and a two-press Revoke. Right, the other machines: each one used here, whether it answers and its
   commands, with Update models and a two-press Remove; Find machines and the machines found; and a field for a name or
   address the scan cannot see. Its rows, all behind the management door (`SharingMount`):

   | route | does |
   |---|---|
   | `GET /api/share` | this machine's sharing, its joined machines, and the machines it uses (`SharingView`) |
   | `PUT /api/share` | `{enabled?, port?, heads?}` written to `[share]`; the listener follows it at once, with no restart |
   | `DELETE /api/share/machines/{name}` | revoke a joined machine |
   | `PUT /api/share/machines/{name}/sessions` | `{on}`: whether a joined machine may cross sessions with this one |
   | `PUT /api/remotes/{name}/sessions` | `{on}`: whether a machine this one joined may cross sessions with it |
   | `POST /api/remotes/scan` | the sharing machines found, each marked when already used here |
   | `POST /api/remotes` | `{host, port?}`: import one, as `splice remote add` does |
   | `POST /api/remotes/{name}/sync` | its commands as it serves them now |
   | `DELETE /api/remotes/{name}` | drop it, its commands and its token |

   The last three run `splice remote`'s own classes and change which commands splice runs, so each answers
   `restart: true` and the page offers the console's own restart. `[share]` is a live key: the daemon
   (`ShareListener`) opens, moves or closes the listener whenever the file changes.
5. **Discovery** (landed): `splice remote` and the Sharing page's Find machines look in three places at once, and each
   machine one names is asked for `/share/v1/info` the moment it is named, all under the scan's one deadline
   (`RemoteDiscovery`):
   - **mDNS.** While it listens, a sharing machine advertises `_splice._tcp` under its name at its own port, and stops
     when the listener closes (`ShareAdvert`). On Linux and elsewhere through JmDNS (Apache-2.0, approved 2026-10-22).
     On a Mac through the system's mDNSResponder (`dns-sd -R` to advertise, `dns-sd -Z` to browse): macOS gives a
     process of ours no multicast at all, from a terminal or from launchd (a send to 224.0.0.251 answers "no route to
     host"), while `dns-sd` works from both. A small shell around the advert ends it when the daemon is gone.
   - **The subnet.** Every address on this machine's private networks, as in phase 1: what still finds a machine where
     multicast is blocked.
   - **Tailscale.** Each online peer `tailscale status --json` lists, at its Tailscale IPv4 address: the machines on
     another network. The CLI is found on the PATH or where it installs outside one (the Mac app included).

   One machine reached two ways is one entry, at the address of the first source that found it (mDNS, then the subnet,
   then Tailscale, so the network path is kept). Machines are told apart by the certificate each serves; one that states
   none (phase 1) by its name. The certificate's fingerprint is that stable identity, so `/share/v1/info` gains no
   separate install id. A source that cannot look, and an address that has not answered by the deadline, leave the
   other machines found.
6. **Sessions across machines** (landed): each machine's running sessions are Claude Code peers on the other, as
   `<machine>/<name>` (`torad-server/rig-lead`), so ListAgents lists them and SendMessage reaches them, both ways.
   Not `<name>@<machine>`, as first planned: ListAgents lists that name, but SendMessage refuses any `to` with an `@`
   as a teammate's team ("to must be a bare teammate name", Claude Code 2.1.309, Oct 23), and delivers to the `/` form.
   A mirror record whose `updatedAt` is three hours old is still listed and still takes SendMessage (the same probe), so
   a session idle on the other machine stays reachable while its mirror reads stale.
   - **Mirrors.** A session of the other machine is a mirror here: a registry record and key file in this machine's
     Claude Code sessions folder, and a socket in its sockets folder (`$XDG_RUNTIME_DIR/cc-socks`, else
     `/tmp/cc-socks`), served by the daemon with the peers' inbox loop (this user's processes only, the key file's
     token, a line cap). Claude Code 2.1.309 lists a record only as `<pid>.json` for a live pid, one per pid (probe:
     `tools/probes/claude-code-mirror-anchor.ts`, Oct 23: a second record for one pid, and one not named for a pid,
     are never listed; one named for a child process's pid lists and takes SendMessage while the daemon holds the
     socket). So each mirror holds a pid of its own: `/bin/cat` reading a pipe from the daemon, which ends when the
     daemon does, however it ends. Its record says `kind: "splice-mirror"`, so a machine never offers a mirror back.
   - **The link.** Every 5 s the joining machine reads each machine it joined (`GET /share/v1/sessions`, its running
     sessions, mirrors left out), mirrors them, and offers its own (`PUT`, with how often it looks). The sharing
     machine keeps those mirrors for three looks past the last offer (from 1 s to a minute) and withdraws them when its
     listener closes; a machine that does not answer has none mirrored on the other until it does.
   - **Messages.** A message to a mirror goes to its machine at once (`POST /share/v1/sessions/{id}/message`), which
     delivers it from its own mirror of the sender, so the envelope's `from` and `from-name` (`<machine>/<sender>`)
     are a mirror there and a reply comes back the same way. A message for the joining machine's sessions waits in its
     outbox on the sharing machine until the joining machine collects it with a held request
     (`GET .../outbox?wait=25`), so the joining machine takes no connection in. A note leaves the outbox only once a
     later collection acknowledges it (`&box=<outbox>&after=<seq>`): a collection cut on the way, because the machine
     slept, restarted its daemon or lost its network, takes nothing with it, and a change to `[share]` keeps what waits.
     A note whose sender has no mirror left on the machine it reaches (withdrawn since that machine's last look, by a
     look that failed, a switch turned off and on, or the end of its offers) is delivered from one made then with the
     carrier it came by, so it is never refused for the want of one and acknowledged gone.
     A restart of the sharing daemon loses what its outbox held. A note refused where its sender wrote it, or by the
     machine it was posted to (a session gone there, a switch off on either side, a machine revoked), goes back to the
     sender as a receipt, in words; one refused after it waited in an outbox is the "Not yet" below.
   - **Bounds.** Every route needs a joined machine's token, and the link names the machine, never the request. A
     machine is booked under its `[remotes]` spelling (`RemoteNames`), so two machines that each joined the other
     name each other once. No joined machine can list, write into or read from this machine's sessions until Marcos
     turns that machine's Sessions switch on, in either direction of the pair (the President's decision on the
     round-1 review, Oct 23: these sessions run with permissions bypassed, and an open join reaching them is a step from
     a key file leaving the machine). Each machine card on the Sharing page carries one, off until turned on and off
     again when the machine joins again: on a machine that joined this one it gates the four session routes, which
     answer 403 in words while it is off; on a machine this one joined it gates this machine's link, which then neither
     looks, offers nor collects, and withdraws that machine's mirrors here. Sessions cross only while both ends are on.
     Two machines that each joined the other run two links, and each holds the other in both its books: sessions cross
     with it only while each card that names it is on (`SessionsSwitch`), so an Off on either stops both links (the
     round-2 review). Each card turns its own book alone (the round-3 review): anyone on the network may join under any
     name, so the machine on one card may not be the one on the other, and only the card that shows where a machine
     joined from lets it in. A machine this one joined is one splice.toml's `[remotes]` names as it is now, a card the
     page draws: one imported from the page has its switch before splice restarts and starts its link (the round-5
     review), and a token kept for a name `[remotes]` no longer lists holds no machine back (the round-4 review). Under trust the LAN, a device that joins under a machine's name takes that machine's place
     in the book of joined machines, as any re-join does, so it holds the machine's sessions off until the machine
     joins again and Marcos turns that card on.
     A switch turned off ends the crossing at the link's next look, and each look while it is off withdraws what a
     collection already under way made since. Every mirror, whatever made it (a look, an offer or a note), ends once
     its machine's time is past (`SessionMirrors`): a look or an offer gives three looks from then, and a note gives a
     minute to a machine that has none; and a listener that closes withdraws every machine
     it mirrored. Any machine on the network may join and each mirror is a process, so a daemon hosts at most 256, and a
     note past that is refused with that reason. Each mirror's inbox waits on a virtual thread, so however many there
     are, none holds a thread the rest of the daemon needs (at 64 they once stalled it). A mirror's name is
     what ListAgents shows and an envelope carries (`[A-Za-z0-9._-]`, 64 characters), and two sessions of one name are
     told apart by the start of their ids.
   - **The pages.** On the Sessions page a mirror is a row of its own machine: a badge names the machine, no folder
     here is claimed for it, it groups under the machine's name (by project and by command), and it is read only
     (SendMessage and Close; no composer, note, stop or move). It reads stale or live as its session does on the other
     machine, since the link carries when that session was last heard from (`heard_at`), and it leaves the list with
     its record, never kept as ended. On the Sharing page each machine's card has its Sessions switch, Off or On; while
     it is on, its line says how many of its sessions are listed here and how many of this machine's it was last shown
     ("2 of its listed here · 1 of this machine's listed there"), and while it is off, that nothing crosses. A machine on
     both lists has a switch on each card, and while one is on and the other off, the one on says nothing crosses until
     both are on.
   - **Not yet.** A message refused on the far side of the outbox reaches no one.
   - **Receipts on a Mac.** A Mac's Claude Code names its socket `/tmp/cc-socks/<pid>.sock`, and `/tmp` there is a
     link to `/private/tmp`. `PeerNoteSocket.inOwnFolder` allows a link above the sender's sockets folder, since it
     moves the folder and the socket together, and still refuses a link at the socket, a `..` or another folder.
