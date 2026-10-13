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
   is left out, because strict mode skips Claude Code's approval prompt and print mode starts
   pending servers on its own (proof b). The session's startup line names a server left out and how
   to approve it.
2. **One launch path.** Splice starts Claude Code with one owner-only file listing every server as a
   hub address, plus `--strict-mcp-config`, so Claude Code starts none itself. http, sse and ws
   servers go in unchanged. A `claude` started outside splice reads the files as declared and runs
   its own copies, as today, because nothing of Marcos's is rewritten.
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
6. **The strict launch:** every planned server through the hub, plus the fallback line.

Owners: splice-config (the host, holders, the console's MCP section and setup), splice-sessions (the
plan, census and launch, with splice-platform's launch module), and hostshield-lead (the slice).

## Step 0 results (desk, Claude Code 2.1.296, stand-in files only)

- **(a) Plugin names, passed (splice-config, Oct 12).** A `--mcp-config` entry named
  `plugin_standin_tools` gives exactly `mcp__plugin_standin_tools__<tool>`, the plugin's own names.
  Without strict mode it runs beside the plugin's server, not instead of it, and the plugin's own
  server takes the call. So hosting plugin servers needs strict mode.
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
