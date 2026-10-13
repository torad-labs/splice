# splice as the MCP hub: design for Marcos's review

Status: a draft by splice-lead, 2026-10-12, for Marcos's review through the President. Nothing here
is decided and nothing is built. beed0812e, the user-scope servers given to plain `claude` through
`--mcp-config`, ships on its own and is the first brick of this design.

## What it is for

Every Claude Code session on the machine gets its MCP tools from splice, however the machine is set
up. Nobody opts in. No session spawns its own copy of a server that could be shared, and no session
loses a tool it had before. A tool keeps its name, so permissions, skills and prompts that name it
keep working.

Measured tonight, 8 PM CT: 51 plain `claude` sessions ran 45 copies of ast-grep (3.0 GiB),
41 of Desktop Commander (6.5 GiB with its npx layer), 13 of Remotion (0.93 GiB) and 2 of lottie.
The 15 claudex sessions ran no ast-grep, because their head home already points at the hosted copy.

## What exists today (the base)

- The host (integrations/mcp, McpHost.kt) serves a stdio server over Streamable HTTP at
  `127.0.0.1:<control>/mcp/<name>`. It starts the child on first use and reaps it after idle with no
  open stream and no request. It evicts the longest-idle server at capacity, and caches tools/list.
  A crash fails pending calls in words, and the next call respawns.
- A restart keeps sessions working: an unknown client session id is adopted, never refused (V4-148),
  because Claude Code ignores the reinitialize signal and its tools would stay dead.
- Children run capped in hostshield's MCP slice (McpContainment.kt, V4-147).
- The census (/api/mcp) already finds all five kinds: user scope, project overrides inside
  .claude.json, repo .mcp.json, plugin .mcp.json and plugin inline servers.
- A process's identity is its launch tuple (command, args, env). Today one tuple is one process for
  every session, and only user-scope servers with no cwd, no `${VAR}`, no directory argument and no
  roots request are hosted.
- Head homes get rewritten copies. Plain `claude` runs over ~/.claude.json untouched (V4-445), and
  from beed0812e gets the hosted user-scope entries through `--mcp-config`.

## The design, part by part

### 1. Every source hosted

The census becomes the plan. For each session launch, splice resolves the servers Claude Code itself
would load for that cwd, with Claude Code's own precedence (local, project, user, plugin), and only
those:

- **User scope:** ~/.claude.json and each head home, as today.
- **Project overrides:** `projects[<cwd>].mcpServers` in .claude.json, for the launch cwd.
- **Repo .mcp.json:** only servers the person approved for that repo
  (`enabledMcpjsonServers`, `enableAllProjectMcpServers`, minus `disabledMcpjsonServers`).
  An unapproved server is never started. Claude Code's approval prompt is the guard that strict mode
  would bypass, so splice must keep it: an unapproved server is left out and named in the session's
  startup line, with how to approve it.
- **Plugins:** servers of enabled plugins only (`enabledPlugins`, per scope), from the plugin's
  .mcp.json or plugin.json. `${CLAUDE_PLUGIN_ROOT}` and the other variables Claude Code expands are
  expanded by splice the same way.

Changes in Marcos's files: none. The plan reads them.
Risks: getting precedence or approval wrong. Too few means a lost tool, and too many means an
unapproved server runs. Both are checked against Claude Code's own /mcp list on a desk before strict
mode turns on.

### 2. Claude Code starts no MCP server itself

A splice launch passes one owner-only file (as beed0812e does, never argv, since entries carry the
bearer) with every planned server as a hub address, plus `--strict-mcp-config`. Strict mode makes
Claude Code ignore every other source, which is now safe because the hub serves them all.

- **A claude started outside splice** (another PATH, the official binary called directly, a CI
  box): it reads the files as declared and spawns its own servers, exactly as today. Nothing it
  depends on was rewritten. It just doesn't get the savings. The census counts these sessions so
  the gap is visible.
- **Heads:** their homes keep today's rewritten copies until this ships, then launch the same way as
  plain claude, so one mechanism serves all.

Changes in Marcos's files: none.
Risks: strict mode drops anything the plan missed. That's why it ships only after a desk walk shows
the plan's list equals Claude Code's own /mcp list without strict mode, for a cwd with every kind.
The plan also carries a switch, `[daemon] mcp_hub = false`, that falls back to beed0812e's behavior.

### 3. Each server keeps its name

One hub address per server, under the server's own name. Plugin servers are registered under the
name Claude Code gives them, so a tool stays `mcp__plugin_<plugin>_<server>__<tool>` and
`mcp__ast-grep__<tool>` stays as it is. Nothing is folded into one server.

To prove first, on a desk: a `--mcp-config` entry named `plugin_<plugin>_<server>` produces the same
tool names as the plugin's own server. If it doesn't, plugin servers can't be hosted under strict
mode, and that part stops and comes back to Marcos.

Changes in Marcos's files: none.

### 4. Start on first tool call, stop when idle

The host already starts a child on first use. The change: tools/list is answered from the cache
kept from the last run of the same launch tuple, so connecting a session doesn't start anything.
The child starts on the first tools/call. A server never run on this machine starts once to answer
its first listing, and its answer is kept across restarts.

Stopping depends on the class (part 5). A shared stateless server stops after idle, as today. A
per-session copy stops when its session ends, never on idle, because its state (open terminals,
running processes) belongs to a live session.

Risks: a cached listing that's stale after the server's version changes. The cache key includes
the launch tuple and the resolved package version where the command names one, and a
list_changed notification drops it.

### 5. Shared or per session

Each server is one of two classes:

- **Shared:** one process for every session. Only for servers known to hold no per-session state
  and to need no cwd or roots.
- **Per session:** one process per Claude Code session, started in that session's cwd with that
  session's environment for `${VAR}` expansion. This is the default for anything not known to be
  shared, so the fallback is today's behavior (one copy per session) and never a leak between
  sessions.

How a server is classed, in order:
1. The operator's word in splice.toml, `[mcp.servers.<name>] share = true | false`.
2. Evidence that forces per session: the server asks for roots, a value expands `${VAR}`, an arg
   is an existing directory, or the entry sets a cwd. These are today's rules.
3. Otherwise, per session, unless splice's built-in list names it shared. The list starts with
   ast-grep, since it's hosted shared today with no reported problem.

Desktop Commander stays per session: it holds terminals, its config and its history per process.
Sharing it would let any session see and kill another's processes.

When it's unclear, the census row says which class a server got and why, and Settings shows it.
Marcos or marlin flips it with the TOML line. A seat that thinks a server is misclassed asks
marlin, not Marcos.

Changes in Marcos's files: none, unless he chooses to classify a server in splice.toml.
Savings: shared servers drop to one copy. Per-session servers save through lazy start alone, plus
dropping the npx and sh layers (2.5 GiB of Desktop Commander's 6.5), since the hub launches the
resolved binary.

### 6. http, sse and ws pass straight through

These servers already serve many clients, so their entries go into the launch file unchanged. Strict
mode would otherwise drop them. Headers and their secrets stay in the owner-only file.

### 7. When the daemon is down or crashes

- **At launch:** the launcher already starts the daemon if it's down. If the daemon still can't
  serve, the launcher runs Claude Code without `--strict-mcp-config` or the hub file and prints one
  line saying the session's servers are its own copies this time. A session never starts without
  its tools.
- **Mid-session:** today a daemon restart kills every hosted child. For shared servers that's
  harmless: the next call respawns and V4-148 adoption keeps the session id valid. For per-session
  copies it would kill open terminals and running processes in every session at once, on every
  install. So the hub's children move into their own long-lived process, the hub host, which a
  daemon restart doesn't touch. The daemon proxies to it over a local socket, and the hub host
  restarts only when its own version changes. That's the same split code mode already uses for
  its hosts.
- **If the hub host dies:** shared servers respawn on the next call. Per-session state is lost, and
  each affected session gets one tool-result line saying so, never silence.

Changes in Marcos's files: none. hostshield owns the slice the hub host runs in, so the unit
change is hostshield-lead's.

## Build order

0. **Desk proofs, no product code.** On a desk with Claude Code 2.1.296:
   (a) plugin naming through `--mcp-config` (part 3);
   (b) `--strict-mcp-config` with the hub file gives the same /mcp list as no strict for a cwd with
   every kind (part 2);
   (c) Claude Code's http MCP client keeps working across a daemon restart under V4-148 (part 7).
   If (a) or (b) fails, the plan comes back to Marcos before step 1.
1. **The plan:** resolve the servers per launch cwd, with precedence, repo approval and plugin
   enablement. Make the census report it. Read-only, and it ships first.
2. **Per-session hosting:** a copy per Claude Code session, with its cwd and env, stopped at session
   end. Add the classification and its census and Settings row.
3. **Lazy start:** tools/list from the kept cache.
4. **The hub host process:** children survive daemon restarts. hostshield-lead places it in the
   MCP slice.
5. **Strict launch:** `--strict-mcp-config` with every planned server, the fallback line, and the
   `mcp_hub` switch. It turns on after a desk walk on a cwd with every kind, and marlin's look at the
   Settings row.

Owners: splice-config (integrations/mcp, the host and classification), splice-sessions
(integrations/claude-code, the plan and census, and the launch with splice-platform), and
hostshield-lead (the slice and unit).

## For Marcos to decide

1. Is per session the default for an unclassed server, with shared only by evidence or a list?
   The other choice is shared by default, which saves more and risks leaks between sessions.
2. Do per-session copies stop only at session end, or also after a long idle? A long idle saves
   memory and drops the session's open terminals.
3. Does the hub host, a second long-lived process, carry its own install rule? It restarts only when
   its version changes, which interrupts everyone's per-session tools.
