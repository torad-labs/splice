# splice as the MCP hub: design for Marcos's review

Status: draft 2 by splice-lead, 2026-10-12, revised to Marcos's answers on draft 1 (through the
President). Nothing is built until he approves it. beed0812e, which gives plain `claude` the hosted
user-scope servers through `--mcp-config`, ships on its own.

## What it is for

Every Claude Code session on the machine gets its MCP tools from splice, however the machine is set
up, with nothing to opt into. A tool keeps its name. No session loses a tool, a terminal or a
server's state, during a setup, an update or a crash.

Measured on Oct 12 at 8 PM CT: 51 plain `claude` sessions ran 45 copies of ast-grep (3.0 GiB),
41 of Desktop Commander (6.5 GiB with its npx layer), 13 of Remotion (0.93 GiB) and 2 of lottie.

## How it works

1. **Every source.** For each session, splice works out the servers Claude Code itself would load for
   that folder, with Claude Code's own precedence: user scope, project overrides, approved repo
   .mcp.json servers, and the servers of enabled plugins. A repo server that's pending or rejected
   isn't hosted, so Claude Code's own approval prompt keeps deciding it.
2. **One launch path, no strict mode.** splice starts Claude Code with one owner-only file listing
   each hosted server as a hub address under its own name. A same-named entry replaces the server
   Claude Code would have started from that scope, as beed0812e showed for user scope. Strict mode
   is out (the President's ruling, Oct 12): it drops every claude.ai connector, and keeping them
   would mean splice reading Marcos's claude.ai login. Plugin servers don't yield to a same-named
   entry (proof a), so each hosted plugin is loaded for that launch with its MCP off and its skills
   and commands kept, if Claude Code honors such a mechanism (proof f). If it doesn't, plugin servers
   stay as Claude Code starts them, and Desktop Commander's cost goes back to Marcos as a plugin
   on or off decision. http, sse and ws servers and claude.ai connectors are left to Claude Code. A
   `claude` started outside splice reads the files as declared and runs its own copies, as today,
   because nothing of Marcos's is rewritten.
3. **Names kept.** One hub address per server under the name Claude Code gives it, so
   `mcp__ast-grep__<tool>` and `mcp__plugin_<plugin>_<server>__<tool>` don't change (proof a).
4. **Shared or per session, asked once.** `splice setup` asks once: "Share MCP servers between
   sessions? It uses less memory." Yes makes servers shared by default, one copy for every session.
   No makes them one per session. The console's MCP section shows the choice, changes it, and shows
   what sharing saves on this machine in measured memory: today's copies of each shareable server
   against one copy. Some servers stay one per session either way, and the section names them with
   the reason:
   - servers known to keep state per process, starting with Desktop Commander (terminals, its
     config, its history);
   - servers that need the session's folder: one that asks for roots, expands a `${VAR}`, takes a
     directory argument or sets a cwd. A per-session copy starts in the session's folder with the
     session's environment.
   A changed choice applies to new connections. Running per-session copies finish with their
   sessions, and nothing is cut.
5. **Started when used.** Connecting a session starts nothing. tools/list is answered from the
   listing the same server gave last time, and the server starts on the first tool call. A shared
   server stops after a while with no calls. A per-session copy stops when its session ends.
6. **Where the hub runs, and the fallback.** The hub runs on Linux x86_64 with systemd --user
   running, and with both splice-mcp-front and splice-mcp-holder beside splice.jar. The daemon
   checks all three at boot and logs the result. Anywhere one of them is missing, the daemon keeps
   hosting MCP in process, as before the hub. The launch offers hub addresses only after a front has
   run on the machine (its front.json exists) and while a front answers on port 3095. Otherwise
   Claude Code starts its own servers, and the launch says so in one line.

## Seamless setup, update and crash: one mechanism

Proof (c) set the bound: Claude Code 2.1.296 never resumes an MCP session id. It re-initializes
from scratch, and after about 15 to 17 s of outage it gives up, and the server's tools are gone until
the person runs /mcp. A 3 s outage recovered without a trace. A live daemon restart takes about 10 s
from the hosted server closing to the new daemon answering (the Oct 12 9:39 AM install). So the
endpoint sessions connect to can't be the daemon.

Two small processes sit under the daemon and outlive it:

- **The hub front** holds the MCP port and every session's stream. It checks the session's bearer,
  routes each call to the right holder, and is the only thing a session talks to. It carries no
  splice logic beyond that routing table, which the daemon gives it, and it's a native binary, not
  a JVM. An ordinary splice install never restarts it.
- **Holders**, one per server, each join the server's stdin and stdout to a socket in splice's
  state folder.

The daemon plans the servers, starts holders and tells the front where they are. It's no longer in
the path of a tool call.

- **Update:** the daemon is swapped while the front and holders keep running. Sessions' connections,
  their streams and every server's state are untouched. A call that needs a holder that doesn't
  exist yet, such as a server's first start, waits at the front until the new daemon starts one.
- **The front's own update** is rare, because its code is small and stable. The running front hands
  its listening socket and its routing table to the new front, which takes over. The gap stays in
  the ~1 s range that proof (c) showed recovers without a trace. If it can't stay under about 3 s,
  that comes back to Marcos.
- **Crash:** a daemon crash is an update nobody planned, and the front and holders carry on. A front
  crash restarts it on the same socket, which systemd holds, so connects queue rather than fail.
  Only calls in flight at the crash fail, each in words.
- **A new holder version:** running holders keep their servers until those stop. New servers start
  under the new holder. Nothing is handed over, because a holder is only a pipe.
- **Setup and the move to the hub:** sessions already running keep the copies Claude Code started
  for them, untouched, until they end. Each session joins the hub at its next start. No running
  session is restarted to move it, since a restart would end its turn and its terminals.
- **Daemon unreachable at launch:** the launcher starts it, as it does today. If it still can't
  serve, the session starts with its own copies and one line saying so. It is never started without
  its tools.
- **A server that crashes** restarts on the next call. If it was a per-session copy, the session is
  told in that call's result that the server's own state was lost.

Holders and servers run in hostshield's MCP slice (V4-147), so the box's memory cap still holds.

## What changes in Marcos's files

Nothing. The plan reads his files and writes only splice's own state folder. The one stored
choice, sharing yes or no, lives in splice.toml as the answer `splice setup` saved.

## Build order

0. **Desk proofs.** (a) and (b) passed, and (c) is in progress (results below). New proof (d): a
   holder keeps a stdio server running across a desk daemon restart, a call made during the restart
   completes, and the server's state is intact afterwards.
1. **The plan:** servers per launch folder, with precedence, repo approval and plugin enablement,
   reported in the census. It's read-only and ships first.
2. **The front and holders:** the front holds the port and the streams, holders hold the servers,
   and the daemon only plans and starts. First, a desk proof: a session's tool answers through a
   daemon swap with no /mcp reconnect, and the front's own socket handover gap is measured. This is
   what makes updates and crashes seamless, so it lands before anything moves onto the hub.
   hostshield-lead owns the front's socket and service units.
3. **Per-session copies:** in the session's folder and environment, stopped at session end, with the
   built-in list of stateful servers.
4. **Started when used:** tools/list from the kept listing.
5. **The setup question and the console's MCP section,** with the measured savings.
6. **The hub launch:** every hosted server through the hub by name, plugin MCP off where proof (f)
   allows, plus the fallback line. First, desk proofs (e) and (f).

Owners: splice-config (the host, holders, the console's MCP section and setup), splice-sessions (the
plan, census and launch, with splice-platform's launch module), and hostshield-lead (the slice).

## Step 0 results (desk, Claude Code 2.1.296, stand-in files only)

- **(a) Plugin names, passed (splice-config, Oct 12).** A `--mcp-config` entry named
  `plugin_standin_tools` gives exactly `mcp__plugin_standin_tools__<tool>`, the plugin's own names.
  Without strict mode it runs beside the plugin's server, not instead of it, and the plugin's own
  server takes the call. So a plugin's own server has to be switched off for the launch (proof f).
- **(b) Strict list, passed for interactive sessions (splice-config, Oct 12).** Strict mode with a
  file listing the same servers shows the same four. It starts neither the plugin's own server nor
  an unapproved repo server, and shows no approval prompt. Only the /mcp display differs: one
  "Built-in MCPs" heading, the plugin row under its config name, and Auth rows for http entries.
  `claude -p` with no flags starts an unapproved repo server, trusted folder or not, while a rejected
  one stays off. So the plan leaves pending servers out itself, and print mode's list is not the
  baseline.
- **(c) http across a daemon restart, failed (splice-providers, Oct 12).** Claude Code never sent
  its old session id, so V4-148 adoption never ran. A 3 s outage recovered by a fresh initialize
  (retries at 1, 2, 4 and 8 s) with nothing on screen. In a 40 s outage it gave up after 5 attempts,
  about 17 s: "Max reconnection attempts (5) reached". The tool stayed gone until /mcp. That desk had
  no socket activation, so each reconnect was refused. The President ruled on Oct 12 that sessions
  connect to a hub front that outlives the daemon (see above).
- **(d) A holder across a daemon restart, passed (splice-config, Oct 12).** A Kotlin/Native holder
  (576 KB, 4.7 MB RSS) ran a stateful stand-in in app-mcp.slice. A call sent 0.5 s into a desk
  daemon restart answered 8.5 s later with the count intact, on the same server pid. The 8.5 s was
  the JVM daemon's boot, which is why the front matters. Carried forward: the new daemon sent
  initialize again, so the front keeps each server's initialize result and an adopted holder is never
  handshaken twice.
- **Strict mode drops claude.ai connectors (splice-sessions, from the 2.1.296 binary, Oct 12).** With
  `--strict-mcp-config`, the MCP provider skips the claude.ai connector fetch, and a connector's
  proxy url and id come only from that fetch with the session's claude.ai login. The President
  ruled out strict mode.
- **(e) A same-named entry replaces each scope, passed (splice-config, Oct 13).** User scope passed
  with beed0812e. This run covered local scope and an approved repo `.mcp.json` server. It used a
  stand-in config home with no operator data, at `walks/` mode 0700, deleted after the run.
  - The home's `.claude.json` held a local-scope pair, `localsrv` and `localkeep`, under the repo's
    `projects` entry, and a user-scope `userkeep`. The repo's `.mcp.json` held `reposrv` and
    `repokeep`, both approved in `enabledMcpjsonServers`. Each was a stdio stand-in that marks a
    file when it is spawned.
  - `--mcp-config` carried http entries named `localsrv` and `reposrv`, which mark each request.
  - Print mode (`-p`) against a stand-in upstream: the stdio `localsrv` and `reposrv` never
    spawned and their http entries were asked. The three `keep` servers spawned. A control run without
    `--mcp-config` spawned all five stdio servers.
  - Interactive: the same marks. /mcp listed five servers, each once. `repokeep` sat under Project
    MCPs, `localkeep` under Local MCPs and `userkeep` under User MCPs. `localsrv` and `reposrv`
    sat under "Built-in MCPs", as the entries in (b) and (f) do.
- **(f) A plugin with its MCP off for one launch, passed (splice-providers, Oct 12).** The hidden
  flag `--plugin-dir-no-mcp <installPath>` does it, with the path taken from the config home's
  `plugins/installed_plugins.json`. A stand-in plugin was installed and enabled from a local
  marketplace, with one stdio server, one skill and one command. The plugin loads from that path,
  and the debug log says `Plugin "standin" from --plugin-dir overrides installed version`. Its
  `.mcp.json` is not read, so its own server never starts. Its skill and command keep their names,
  `/standin:hello` and `/standin:greet`. A `--mcp-config` entry named `plugin_standin_tools` takes
  the call as `mcp__plugin_standin_tools__ping`, the plugin's own tool name. This held in print mode
  and in an interactive session. /mcp lists the entry once, under "Built-in MCPs", as in (b).
  - Without the flag, the plugin's server and the entry both start, as in (a). In this run the
    entry took the call, so which copy answers is not fixed.
  - With the flag and no entry, the session has no plugin tool at all. So the launch passes the
    flag for a plugin only when the hub holds every server that plugin declares.
- **The front and holders across updates, passed (splice-config, Oct 12).** A 2.4 MB Kotlin/Native
  front on a systemd-held socket in app-mcp.slice served a real Claude Code 2.1.296 session, with a
  stateful counter under a holder. The session kept its tool, with no /mcp, through three updates:
  - a daemon swap: same server pid, count continued;
  - a front restart under the held socket: 35 ms, 1 of 239 probe calls reset, a kept-alive
    connection that the old front closed;
  - an in-place front upgrade, binary moved over and SIGUSR2, exec in the same pid: 0 of 250 calls
    failed.
  The handover gap is under 0.1 s. An ordinary front update is a reload (USR2), never a restart.
