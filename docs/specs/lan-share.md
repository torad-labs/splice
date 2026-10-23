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

- `splice remote` finds the sharing machines (above), lists each with its heads and asks per machine. `splice remote add <host[:port]>` skips discovery
  (multicast blocked, Tailscale). `splice remote list`, `splice remote remove <server>`.
- Import of one server:
  1. `POST /share/v1/join` → token, saved in the key store (`splice key`) as
     `SPLICE_REMOTE_<SERVER>_KEY`.
  2. For each shared head, a provider and a head written through the `splice add` path (`AddWrite`,
     `AddPortChoice`, `AddWrapperLink`, the add finish with its restart prompt):

     ```toml
     [providers.torad-server-claudex]
     dialect = "anthropic-passthrough"
     base_url = "http://torad-server.local:3097/share/v1/heads/claudex"
     auth = { kind = "api-key", env = "SPLICE_REMOTE_TORAD_SERVER_KEY" }
     [[providers.torad-server-claudex.models]]   # mirrored rows: id, label, context_window, rates
     ...
     [heads.claudex]                              # same key, command, pinned model and slots
     provider = "torad-server-claudex"
     port = <AddPortChoice>
     ...
     ```

     A head key or command already taken on this machine is asked about: replace, or import as
     `<key>-<server>`.
  3. Restart, then `splice doctor`.
- This reuses an existing schema (anthropic-passthrough + api-key): the relay itself needs no new
  dialect and no new auth kind on the client.

## Code map

- Topology: `core/.../topology/Topology.kt` (root table), new `ShareConfig`; findings in
  `TopologyChecks.findings()` (`core/.../topology/TopologyWriter.kt`).
- Listener and relay: `app/.../share/` in phase 1 (no module-law change), wired in `app/.../Daemon.kt`
  beside `ControlPlane`; it moves to its own `features/share` module with the phase 2 console tab.
  Ktor Netty as `HeadEngine.kt` and `ControlServer.kt` use it; relay upstream on OkHttp as
  `integrations/upstream` does.
- Wall: `quality/rules/kotlin/kt-embedded-server-loopback.yml` exempts the one share listener file,
  with this decision cited. Every other `embeddedServer` stays loopback-only.
- CLI: `share` and `remote` verbs in `app/.../cli/CommandParser.kt` / `Command.kt`; prompts through
  the existing `AddPrompter` / `confirm`; key through `KeyStore`.
- Turn path: a provider quirk `forward_client_identity` (QuirksConfig) makes a head on its own key send the
  client's session id, wire knobs and identity upstream, never its credential (ClientAuth.forwardedClientHeaders);
  imported heads set it, so the sharing head keeps a session on one account and one prompt-cache key.
- No new dependency: JDK HTTP for the relay and the client, the existing Ktor Netty for the listener.

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

## Phase 2 (after phase 1 works for us)

Console tab (sharing on/off, clients with last seen and revoke, discovered servers with import),
TLS with a pinned certificate, usage and plan windows proxied from the server, refresh of the
mirrored model rows, and cross-machine session visibility.
