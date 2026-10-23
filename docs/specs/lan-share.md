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
- **Trust the LAN.** A machine on a private network that asks to join a sharing server is accepted
  without a code. Guardrails that cost no step: sharing is off until `splice share on`; the listener
  admits private source addresses only; each client gets its own token, listed and revocable.

## How it works

```
Mac                                          torad-server
claudex ─▶ head :3099 (anthropic-passthrough) ──HTTP──▶ share listener :3097 ─▶ head :3099 (codex) ─▶ ChatGPT
            auth api-key = client token                  checks token, swaps   turn key, loopback
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
- One extra Netty listener, started by the daemon only when `enabled`, bound on all interfaces. Routes:

  | route | auth | purpose |
  |---|---|---|
  | `GET /share/v1/info` | none | server name, splice version, and for each shared head: key, command, pinned model, model rows (id, label, window, slot, rates) |
  | `POST /share/v1/join` | none | `{ "client": "<hostname>" }` → a new per-client token (shown once) |
  | `POST /share/v1/heads/{head}/v1/messages` | client token | relay, streamed |
  | `POST /share/v1/heads/{head}/v1/messages/count_tokens` | client token | relay |
  | `GET /share/v1/heads/{head}/v1/models` | client token | relay |

  Every request: source address must be loopback, RFC 1918, 100.64/10 (Tailscale), link-local or
  fc00::/7, else 403. Host must be an IP literal or one of this machine's names (`<host>`,
  `<host>.local`, `<host>.lan`, plus `share.hostnames`), which keeps DNS rebinding out the same way
  `LoopbackHost` does for loopback.
- The relay forwards the body and the client's headers to `http://127.0.0.1:<head.port>` with the
  client token replaced by the head's turn key (`TurnKey`), and streams the response back unbuffered.
  The head's own admission, inflight gate, account selection and honest-failure paths run unchanged.
- Client tokens: 32 random bytes, stored hashed with name, address, first and last seen in
  `<state>/share/clients.json` through `SecureFile.writeAtomic0600`. Never the management or turn key.
- Discovery (phase 1): the client asks every address of each private IPv4 network it is on (a /24 around
  itself on a network wider than /22) for `/share/v1/info` on the share port, all at once under one deadline.
  No multicast and no new dependency; mDNS advertising replaces this in phase 2.

### Client: `splice remote`

- `splice remote` finds the sharing machines (above), lists each with its heads and asks per machine. `splice remote add <host[:port]>`
  skips discovery (multicast blocked, Tailscale). `splice remote list` shows the machines joined here and their
  commands; `splice remote sync [<machine>]` rewrites each imported head's rows, pin and tiers as its machine serves
  them now; `splice remote remove <machine>` drops the machine, its heads, their command links and its token.
- Import of one machine (`RemoteImport`):
  1. `POST /share/v1/join` → this machine's token there, kept in `<state>/share/remotes.json` (0600, one per machine,
     `RemotesStore`), never in `keys.toml`. splice.toml names the machine, never the token.
  2. `[remotes.<name>]` with the address that answered (the machine's own name when it resolves there).
  3. Each shared head written through the `splice add` path (`AddVerb.addRemote`: the same refusals, checks, atomic
     save, port choice and wrapper link), one line per command and one restart at the end:

     ```toml
     [remotes.torad-server]
     url = "http://torad-server.lan:3097"

     [providers.claudex]
     dialect = "anthropic-passthrough"
     base_url = "http://torad-server.lan:3097/share/v1/heads/claudex"
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
  as its bearer, read from the token store on every request, so a re-join lands with no restart.

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
  prompt content in transit, not access. It comes in phase 2 with a pinned self-signed certificate.
- **The server must be up.** A Mac turn with torad-server down fails in words, like any upstream outage.
- **Server-side only:** usage bars, account pools and plan windows live on the server. The Mac's
  status line shows the relay head's own counts; dollar estimates come from the mirrored rates.
- **No cross-machine `ListAgents`.** Sessions on the two machines do not see each other yet.
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
2. **TLS and pinning**: a self-signed certificate minted at the first `share on`, the listener on TLS only, and the
   certificate's SHA-256 pinned in `[remotes.<name>] fingerprint` at join, on every client path.
3. **Fidelity**: roster, usage and health routes on the listener; the using machine's plan bars, live rows and
   reachability come from them; turns carry the joined machine's name; last seen per joined machine.
4. **Console**: a Sharing tab for both sides.
5. **Discovery**: mDNS (`_splice._tcp`), Tailscale peers, the subnet probe as fallback.
6. **Sessions across machines**: each machine's live sessions mirrored on the other, so ListAgents and SendMessage
   reach `<name>@<machine>`.
