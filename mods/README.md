# Writing a Splice mod

A mod is a small folder of TypeScript that Splice runs beside your sessions and tells about them. This page has
two parts. [What runs today](#what-runs-today) says what this version of Splice does with a mod: what it serves,
what it refuses, how to install one and the limits it runs under. The rest of the page is the full contract, the
shapes in [`contract/index.ts`](contract/index.ts), with worked JSON in
[`contract/examples/`](contract/examples/) and an example mod in [`example/`](example/). Parts of that contract
are not served yet, and each says so.

Where a mod's files live, what it is shown and what it can reach are on
[Disk and privacy](../docs/wiki/disk-and-privacy.md#mods).

## What runs today

### In short

- Only TypeScript mods run, each in its own [Bun](https://bun.sh) process that Splice starts, watches and stops.
- A mod observes. Handlers are told about sessions and turns; they cannot change, answer or slow a turn.
- A mod is an ordinary process running as you. It is **not sandboxed**: it can read any file you can.

### Bun

Bun is required and is not bundled with Splice. Install it from <https://bun.sh>. Mods run on Linux and macOS and
also need `/usr/bin/perl` to keep their child processes tied to the daemon. At install Splice looks for Bun
in `SPLICE_BUN`, then on `PATH`, and records the path it found with the mod. At each start it uses the recorded
path if it is still an executable, then `SPLICE_BUN`, then `PATH`, then `~/.bun/bin/bun`. When it finds none, the
mod is `failed` with `Bun not found`, and `splice mod install` says how to fix it:

```
Bun not found. Install Bun from https://bun.sh, then run splice mod enable <name>.
```

To point Splice at a particular Bun: `SPLICE_BUN=/path/to/bun splice mod install <folder-or-tarball>`. Splice
starts Bun with a clean environment. A version-manager shim that adds variables back is caught at start, and the
mod fails with `This Bun launcher adds environment variables. Set SPLICE_BUN to the Bun binary.` when Bun cannot
drop them.

### Installing and managing mods

`splice mod` talks to the running daemon. Every command prints what it did.

| command | what it does |
| --- | --- |
| `splice mod install <folder-or-tarball>` | Checks the folder's `mod.json` and `hooks/hooks.json`, copies the files into Splice's state, records the SHA-256 of every file, enables the mod and starts it. A mod already installed under that name is replaced. |
| `splice mod install <folder> --link` | Records the folder by its path instead of copying it, so edits you make there run at the next start. A linked mod is **not hash-checked**. |
| `splice mod list` | One line per installed mod: version, enabled or disabled, its state and why, the last handler error, dropped events by kind, and the Bun version; then its load notices. |
| `splice mod disable <name>` | Stops the mod's process. It stays installed. |
| `splice mod enable <name>` | Starts it again. A copied mod whose files changed since install is refused, naming the files. |
| `splice mod enable <name> --accept` | Accepts the files as they are now: re-reads `mod.json`, records new hashes and starts the mod. |
| `splice mod remove <name>` | Stops the mod and deletes its installed copy. A linked mod's folder is left alone. Its store and log stay on disk. |

`install` prints, for the example mod in [`example/`](example/):

```
Installed reviews 0.1.0. Adds a row under each session that asks for a review, with buttons to approve it or ask for changes, and a page listing every open review.
Declared capability: watch-sessions
Declared capability: add-rows
Declared capability: draw-ui
Declared capability: answer-tools
Copied into Splice state and recorded SHA-256 hashes.
Mods run as you, not in a sandbox. Only TypeScript mods run for now.
Mod reviews: handlers observe only in this version of Splice.
Mod reviews: sessions.list is not served yet.
Mod reviews: ui.render is not served yet.
Mod reviews: $.ui is not served yet.
Mod reviews: $.sessions.invalidate is not served yet.
Mod reviews: $.ui.respond is not served yet.
Mod reviews: pages: queue is declared but not served yet.
Mod reviews: tools: resolve is declared but not served yet.
Mod reviews: tools: board is declared but not served yet.
Mod reviews: views: ui://reviews/board is declared but not served yet.
It will hear turns from now on, including in sessions already running. No session.start is replayed; new sessions announce their start.
```

Its `session.start` handler runs. Its `tool.call` handlers load but never fire, because they filter for the mod's own
tools, which no session is offered yet.

With `--link`, the copy line reads `Linked to <path>; not hash-checked. It reads that folder at each start.` When
the mod is still loading, the last line is `Still loading. See splice mod list for its state.`; when it could not
start, `It is not running:` and why.

The other commands print:

```
Disabled reviews. It will stop receiving events. To run it again: splice mod enable reviews.
Accepted the current files and recorded new SHA-256 hashes.     (enable --accept only)
Enabled reviews.                                                 (then the same notices and last line as install)
Removed reviews. It will no longer receive events. Its installed copy was removed.
Removed reviews. It will no longer receive events. Its source folder was left alone.   (a linked mod)
No mods installed. Use splice mod install <folder|tarball> to add one.   (list, when empty)
```

If a mod left a store or logs behind, `splice mod remove` also prints the retained paths and a `trash` command.

`splice mod list` prints a line such as:

```
reviews 0.1.0: enabled; running; dropped events: 0; Bun 1.3.1
  Mod reviews: handlers observe only in this version of Splice.
```

The state is one of `starting`, `running`, `restarting`, `stuck`, `failed` or `disabled`.

A mod installed while sessions are running hears their turns from then on. Splice does not replay a
`session.start` for a session that began before the mod; sessions that start later announce themselves as usual.

**On a team server**, installing, enabling, disabling and removing a mod need the machine's management key on the
loopback control plane. A person holding a personal team key cannot do any of them; installing is the
administrator's alone.

### What is served

- **Layout:** `.splice-mod/mod.json` and `hooks/hooks.json` as in [Layout](#layout); each module in `hooks.json`
  exports `register(on)`.
- **Events:** `mod.start`, `mod.stop`, `session.start` (with `seenBefore`), `session.end`, `turn.start`,
  `request.send`, `stream.event`, `tool.call` (with its `{ server?, tool? }` filter), `response.receive` and
  `turn.complete`. A handler's `.catch` is served too.
- **The handle `$`:** `$.store`, `$.clock`, `$.log` and `$.sessions.list()`.

### What a handler is told

A handler for `Register` (the default) receives metadata, never a body. These are the shapes in
[`contract/index.ts`](contract/index.ts), each with a worked file in [`contract/examples/`](contract/examples/):

| event | fields beyond the common ones |
| --- | --- |
| `turn.start` (`TurnStart`) | `head`, requested `model`, `compact`, `agent` (null when absent) |
| `request.send` (`RequestSend`) | upstream `model`, `round` |
| `stream.event` (`StreamEvent`) | `seq`, `type`, `bytes` of one client SSE frame |
| `tool.call` (`ToolCall`) | `caller: "model"`, tool call `id`, `server` (null for native tools), `tool`. A model opened a tool block; it does not say the tool ran. |
| `response.receive` (`ResponseReceive`) | requested `model`, `status`, transport `attempt` |
| `turn.complete` (`TurnComplete`) | `model`, `stopReason` (the client's clean ending, or null), daemon `outcome`, `durationMs`, `usage` |

Every one of them also carries `session` (null when the turn is not attributed to one), `turn`, `labels` and
`at` (Unix milliseconds). None carries a request or response body, stream data or a model's tool arguments.

`usage` is `{ input, output, cacheRead, cacheWrite }`. `input` includes `cacheRead` and `cacheWrite`: it is every
token the request sent, so the uncached part is `input - cacheRead - cacheWrite`.

`mod.start` carries `served` (the events the mod registered that this host serves, `mod.start` itself among them when
it registered one) and `refused`, whose lines each begin with `Mod <name>: `. `mod.stop` carries `reason`.

### Handlers observe only

Splice hands each event to the mod and goes on; it never waits for a handler, and a turn never waits for a mod.
A handler's return value, and a changed event passed to `next()`, have no effect on anything. Splice says so when
the mod loads, in the install output, in `splice mod list` and in the mod's log:

```
Mod <name>: handlers observe only in this version of Splice.
```

and the first time a handler for an event returns a value or passes a changed event to `next()`, it writes this
once per event to the mod's log:

```
A handler for <event> passed a changed event to next() or returned a value; in this version of Splice that has no effect on the turn.
```

Events reach a handler frozen; calling `next(e)` runs the mod's next handler for that event.

### What is not served

Each of these is refused in words when the mod loads, and the mod's other handlers still run. The refusals appear
in the install output, in `splice mod list` and in the mod's log, and they are passed to `mod.start` as `refused`,
declarations included.
The three handle refusals are listed for every mod, whether or not it uses them; an event or a declaration is listed
only when the mod registers or declares it.

| not served | what the mod is told |
| --- | --- |
| `$.sessions.invalidate()` | `Mod <name>: $.sessions.invalidate is not served yet.` (also thrown if called) |
| `$.ui`, `$.ui.respond()` | `Mod <name>: $.ui is not served yet.` and `Mod <name>: $.ui.respond is not served yet.` |
| `on("sessions.list", …)` | `Mod <name>: sessions.list is not served yet.` |
| `on("ui.render", …)` | `Mod <name>: ui.render is not served yet.` |
| `on("command.run", …)` | `Mod <name>: command.run is not served yet.` |
| `on("http.request", …)` | `Mod <name>: http.request is not served yet.` |
| `on("launch.prepare", …)` | `Mod <name>: launch.prepare is not served yet; Splice does not run mod code while a session launches.` |
| `pages`, `commands`, `tools`, `views` in `mod.json` | `Mod <name>: pages: <page> is declared but not served yet.`, and likewise `commands: <name>`, `tools: <name>` and `views: <uri>` |

No mod's tools are offered to a session yet, and no app draws a mod's pages, rows or views.

### Imports

A mod's modules may import only files inside the mod's own folder (its own `node_modules` included) and Bun's built-in
modules (`bun:`, `node:`). An import that resolves anywhere else, by an absolute or `../` path, a link, a `tsconfig.json`
alias, a `node_modules` folder above the mod or a `file:` URL, fails the load with
`Mod <name>: <file> imports <specifier>, which is outside the mod's folder.` Splice hashes the installed copy, and this
keeps the code that runs the code it hashed. It does not sandbox the mod.

### Limits

| limit | what happens |
| --- | --- |
| Load: 30 s | A mod whose modules have not finished `register` within 30 s fails with `did not finish loading in 30 s`. A module that throws while loading fails it with `could not load:` and the error. |
| A handler: 5 s | A handler still running after 5 s is abandoned for that event and logged as `<event>: handler exceeded 5 s; abandoned the event`. `mod.stop` gets 2 s. |
| One event at a time | A mod handles its events in order, one at a time. |
| Queue: 2,048 events | Events wait for the mod in a bounded queue. When it is full, the newest event is dropped and counted by kind under `dropped events` in `splice mod list`. `stream.event`, `request.send` and `response.receive` may fill only 1,536 places, so a flood of them leaves room for session and turn events. A mod with events waiting that has not taken one in 30 s shows as `stuck`, and is left running. |
| Restarts: three in 10 minutes | A mod that exits on its own is restarted after 1 s, then 5 s, then 25 s. A fourth exit within 10 minutes marks it `failed` (`exited 4 times in 10 minutes`) until you enable it again. Events sent while it was down may be lost. |
| Store | 64 KiB per JSON value, 16 MiB per mod. |
| Log | 200 lines and 256 KiB a second; rotates at 8 MiB keeping one older copy. |
| Install | At most 20,000 files and 200 MiB per mod; files only, no symbolic links. |

### `GET /api/mods`

The catalog route on the management API, named in the contract as `GET /api/mods`. It lists every installed mod,
disabled and failed ones included, from the installed manifest without running mod code: the manifest's fields,
`runtime` (`ts`), `enabled`, `linked`, `hashChecked`, `state` and `why`, `dropped` and `droppedByEvent`,
`restartsInWindow`, `lastError`, and once loaded `notice`, `served`, `refused` and `declaredNotServed`. Reading it
takes the management key or the console's key. `POST /api/mods`, `PUT /api/mods/<name>` and
`DELETE /api/mods/<name>`, which `splice mod` uses, take the management key alone.

After a daemon handover, `handoverDropped` counts events the successor could not deliver and events the retiring
daemon could not transfer. Retrying a transfer after a lost reply does not send its events to mods twice. If the
successor cannot record the retiring daemon's count, the retiring daemon reports it in `daemon.log`.

Killing the old daemon with SIGKILL is a crash, not a graceful handover. Events still queued in its memory can be lost
without a count, and a `session.start` whose history had not been saved can be sent again with `seenBefore: true`.
The old mod's process group is cleaned up even if a successor never starts. A successor waits for that group to clear
before starting a replacement. Mods are not sandboxed; this does not cover a process that deliberately leaves the group.

## The contract

The rest of this page is the whole contract. Where a part of it is not served yet, it says so; the list above is
the full account.

## What a mod is

A mod is a small folder you install into Splice, laid out the way a Claude Code mod is. Under the full contract it
can:

- watch or change what passes through Splice: a session starting, a turn, a request on its way to a
  model, a tool call;
- add rows and pages to any app that shows Splice's sessions;
- give the model tools whose answers draw UI in the conversation.

Splice's core holds no code for any one mod. Everything a mod does goes through one function, `on`.

## Layout

```
<mod>/
  .splice-mod/mod.json      the manifest
  hooks/hooks.json          { "modules": ["./register.ts"] }
  hooks/register.ts         export const register: Register = on => { ... }
  tools/<name>.json         a tool's input schema (JSON Schema, type object)
  tools/<name>.md           a tool's description, as the model reads it
  tools/<name>.meta.json    a tool's _meta, optional
```

Every path in `mod.json` is relative to the mod's root, the folder you install. `tools/` and
`hooks/` sit at the root beside `.splice-mod/`, never inside it. Module paths in `hooks/hooks.json`
are relative to `hooks/`, and each module exports `register`.

Import the types only. A mod carries no runtime code from Splice:

```ts
import type { Register } from "<splice>/mods/contract/index.ts";
```

## `mod.json`

| field | |
|---|---|
| `name` | `[a-z][a-z0-9]*`, at most 40 characters, the same rule a Splice mode's name follows. It names the mod's MCP server and prefixes its rows. |
| `version`, `description` | Shown to the person at install. |
| `author`, `surfaces` | Optional. `surfaces` names the apps the mod adds to; absent means every app. |
| `capabilities` | Optional. What the mod says it does, shown to the person at install, from the list below. It is a description, not a wall: Splice serves a mod the events it registers for whatever this list says. |
| `tools` | Tool names. Each matches `[a-z][a-z0-9_]*` and contains no `__`, the rule a mode's tools follow. The model is offered the tool as `mcp__<mod>__<tool>`, and that whole name is at most 64 characters, so a 40-character mod name leaves 17 for the tool. Each tool has its files under `tools/`. |
| `pages` | `{ name, title }`: a page an app opens. `name` is 1–40 characters of `a–z`, `0–9` and `-`; `title` is shown before the mod answers. The mod draws it by answering `ui.render`. |
| `commands` | `{ name, description }`: a command a person runs. `name` is 1–40 characters of `a–z`, `0–9` and `-`. The mod answers `command.run`. |
| `views` | `{ uri, name, mimeType?, file? }`: a `ui://` view a tool's answer may return. `mimeType` defaults to `text/html;profile=mcp-app`. With `file`, the view is that static HTML, relative to the mod's root. Without it, the mod answers `ui.render` for that uri. |

For example, `campaignboard` and `reviews` are valid mod names; `campaign-board` is not.

The capabilities a mod may name, each shown to the person as one line:

| capability | the mod says it |
|---|---|
| `watch-sessions` | reads `session.start` and `session.end` |
| `change-launch` | changes a process's environment, arguments or MCP configuration |
| `watch-turns` | reads turn, request, stream and response events |
| `change-requests` | changes what is sent to a model or what comes back |
| `answer-tools` | answers tool calls on its own server |
| `add-rows` | adds or changes rows in the session list |
| `draw-ui` | draws pages, rows' views or tool views |
| `run-commands` | answers commands a person runs |
| `serve-http` | answers HTTP requests under its own path |

### Declared, never registered

> Not served yet: `pages`, `commands`, `tools` and `views` are each refused at load with "declared but not served
> yet".

There is no `$.tool.register`, no `$.command.register` and no `$.ui.register`. Each moved into
`mod.json`:

| instead of | declare it in `mod.json` | and answer it with |
|---|---|---|
| `$.tool.register` | `tools`, with each tool's files under `tools/` | a `tool.call` hook |
| `$.command.register` | `commands` | a `command.run` hook |
| `$.ui.register` | `pages` and `views` | a `ui.render` hook, or a view's static `file` |

The handle `$` keeps what changes while the mod runs: `store`, `clock`, `log`, `sessions` and
`ui.respond`. Anything a session or an app must see before the mod runs, or without it running, is
declared in `mod.json`. Anything that changes per request is answered by a hook.

The first reason is that Splice can list a mod's tools, commands and views without running its
code. `GET /api/mods` reads them from the installed manifest, so an app can show what a disabled,
failed or restarting mod offers, and a person can see what a mod adds before it ever runs.

The second reason is that a tool list has to stay the same for the whole conversation. Splice writes every
`tools/list` answer a session receives itself, and it refuses to carry a tool change partway through
a conversation. A request that removes a tool mid-conversation is declined, and the turn is not sent,
because Splice cannot make that removal hold. The tool list is also part of the prompt-cache key. If
tools were registered when a mod started, a session launched while that mod was restarting would get
a different list than its neighbour launched a second later. The two sessions would see different
tools and fall out of each other's cache, though nothing about them differs. Reading the list from
files makes it the same for every session that runs the same version of the mod.

## Tools

> Not served yet: no mod's tools are offered to a session, and a `tools` list in `mod.json` is refused at load.

`tools/<name>.json` is the input schema, `tools/<name>.md` is the description and
`tools/<name>.meta.json` is the `_meta`. Splice assembles them into one `tools/list` entry
(`ToolDefinition`) on the mod's own server, named after the mod. A tool's name is
`[a-z][a-z0-9_]*` with no `__`, and the name the model is offered, `mcp__<mod>__<tool>`, is at
most 64 characters. The schema's root `type` is `object`; other JSON
Schema keywords such as `oneOf` are preserved.

`_meta.ui.visibility` lists who may call the tool, `model`, `app` or both (`ToolVisibility`); absent
means both. `_meta.ui.resourceUri` names the view its answers draw in, which must be a view the
manifest declares. A tool meant only for views and row buttons is marked:

```json
{ "ui": { "visibility": ["app"] } }
```

A tool not marked for the model is left out of the `tools/list` Splice writes for that session, so
it is not in the list the model is offered. A call to it from a row's action or a view is still
answered. That is all the mark does, and it has two limits:

- **It walls only the path Splice serves**, the mod's server at `/mcp/<name>`. If the same action
  can be reached another way, through a command on the PATH, a file or a route, the mark does
  nothing about it. A session with a shell can still do whatever that shell can do. Do not treat an
  app-only mark as keeping a destructive action away from a session; guard the action where it
  actually lives.
- **It is Splice's list, not the host's doing.** Do not count on the host to honor the mark: one
  host was measured forwarding a view's call to a tool marked for the model alone.

## `on`, the one primitive

```ts
export const register: Register = (on) => {
  on("turn.complete", async ($, e, next) => {
    $.log(`Turn ${e.turn} ended: ${e.outcome}`);
    await next(e); // call this mod's next observer, not the turn
  }).catch(async ($, e, next) => {
    $.log(e.error.message);
    await next(e);
  });
};
```

`Register` is what this host runs: its handlers receive the metadata in [What a handler is told](#what-a-handler-is-told).
The full contract below, with its bodies, frames, tool arguments and answers that change a turn, is
`Register<"full">`, and its shapes are named `FullTurnStart`, `FullRequestSend`, `FullStreamEvent`, `FullToolCall`,
`FullResponseReceive` and `FullTurnComplete`. This host does not serve them: neither a handler's return nor a changed
value passed to `next` changes a turn.

Use a literal event name, or narrow a dynamic name to one event first. That name determines which filter
keys are valid and the types of `e`, `next(e)` and the handler's answer, including in `.catch`.

### Events

| event | filter | what it is |
|---|---|---|
| `mod.start` | | The mod loaded: `mod`, `version`, `served` (registered events this host supports), `refused` (why other requests are not served). |
| `mod.stop` | | The mod is about to unload: `mod`, `version`, `reason`. |
| `session.start` | | A process began serving a session: `session` (the harness's own session id, the `session_id` of `GET /api/sessions`), `cwd`, `labels`, and `seenBefore`, true when that id was seen earlier. |
| `session.end` | | The process serving that session is gone: `session`, `cwd` and `labels`. One id is one session, however many processes serve it. |
| `launch.prepare` | | Per process, before it starts: `session` when known, `cwd`, `labels`, `env`, `args`, `mcp`. To change the launch, pass a changed `e` to `next`. |
| `sessions.list` | | Splice is assembling normalized rows: `surface`, `allRows`. Wrap `await next(e)` to add rows or change them; `allRows` says whether the reader asked for mod rows. The legacy HTTP response does not use this chain's output. |
| `turn.start` | | A turn began: `session`, `turn`, `labels`, `model`. |
| `request.send` | | A request is about to go to the model: `session`, `turn`, `model`, `body`. Change `body` and pass it on. |
| `stream.event` | | One event of the model's stream: `session`, `turn`, `event`. Return `null` to drop it. |
| `tool.call` | `{ server?, tool? }` | `caller`, `session`, `turn?`, `server`, `tool`, `arguments`. A `model` call always carries `session`; an `app` call can be sessionless. Answer a `ToolResult`: `content`, `structuredContent?`, `isError?`, `_meta?`. |
| `response.receive` | | The whole response, assembled from the stream: `session`, `turn`, `model`, `status`, `body`. |
| `turn.complete` | | The turn ended: `session`, `turn`, `model`, `stopReason`, `usage` (`input`, `output`, `cacheRead`, `cacheWrite`; `input` includes the two cache counts). |
| `ui.render` | `{ surface?, slot? }`; add `page` only with `slot: "page"` | An app asks the mod to draw a page, a row or a view. |
| `command.run` | `{ command }` | A person ran one of the mod's commands: `command`, `args`, `session?`. Answer `{ text }`. |
| `http.request` | `{ path?, method? }` | A request under the mod's own path, `/mods/<name>/…`: `method`, `path` (the part after it), `query`, `headers`, `body?`. Answer `{ status, headers?, body? }`. |

For `tool.call`, the host binds the session from trusted launch or session provenance. A tool
argument or untrusted request metadata cannot supply that identity. An app call tied to a session
gets its identity from host context too. This host serves none of it: it tells a handler that a model
opened a tool block, as in [What a handler is told](#what-a-handler-is-told).

### The handle `$`

- `$.store`: `get`, `set`, `delete`, `list(prefix?)`. A durable key-value store that belongs to the
  mod alone, of JSON values up to 64 KiB each and 16 MiB in all. It survives restarts and removal.
- `$.clock`: `now()`, and `after(ms, fn)`, which returns a function that cancels it.
- `$.log(msg)`: writes a line to the mod's own log, `<logs>/mods/<name>.log`.
- `$.sessions`: `list()` returns every session's row; `invalidate()` tells readers the rows changed (not served
  yet).
- `$.ui.respond({ text, ui })`: the answer a `tool.call` handler returns when it carries both the
  words the model reads and a declared `ui://` view. Not served yet.

## App discovery and rows

> `GET /api/mods` is served; see [above](#get-apimods). Mod rows are not: `sessions.list` is refused at load, and
> `GET /api/sessions?kind=all` carries no mod rows yet.

On connect, an app reads **`GET /api/mods`** ([example](contract/examples/mod-catalog.json)) on the
management API.
The `{ mods }` catalog includes installed mods even when disabled or failed, so an app can show
what they offer without running them. Each entry carries the manifest's identity and declarations
(including surfaces, capabilities, pages, views and tool names) along with the runtime, enabled
flag and state. `runtime` is `ts`; `state` is one of `starting`, `running`, `restarting`, `stuck`,
`failed` or `disabled`. An app chooses which surfaces and UI it supports.

**`GET /api/sessions?kind=all`** returns **`{ rows }`** of normalized v2 rows
([example](contract/examples/sessions-list-response.json)). Plain `GET /api/sessions` stays on
the existing SessionsRoutes wire: `sessions` with `session_id` and `waiting_for`, plus `reading`,
`note_versions`, `gone_mark`, and, when present, `gone_held`, `gone_older` and `error`.
Splice builds that answer from host sessions alone without invoking `sessions.list`. No mod output
can change it, including a mod row incorrectly marked as a session.

The v2 `SessionRow` is either a `HostSessionRow` or a `ModSessionRow`. In TypeScript, check
`row.mod !== undefined` to narrow to a mod row. The fields include:

- `id`: for a session, `id` **is** the harness's own session id, the value `GET /api/sessions` has
  always answered as `session_id`. A row has no separate `session_id` field: key every lookup of a
  session on `id`. A mod's own rows are `<mod>:<id>`. A session id never contains `:`, so the two
  cannot collide.
- `kind`: `session` is reserved for host rows, which have no `mod`. A mod row has its own kind and
  a required `mod` naming its owner.
- `parent`: the `id` of the row this one sits under. When no row in the same answer carries that
  id, Splice keeps the row at the top level and reports that its parent was not found, naming the mod, the row and the
  missing id, where the person sees a mod's state. A hierarchy that silently collapsed would look the
  same as a mod with no hierarchy, so it is said instead.
- `title`.
- `status`, from one set every row shares: `working`, `waiting` (waiting on the person), `paused`,
  `done` or `failed`.
- `progress`: `{ done, total }`.
- `needs`: `{ text, deadline? }`. On a session's row it carries what `waiting_for` says; `deadline`
  is an ISO 8601 instant with its offset.
- `labels`: the session's labels, or the labels a mod gives its row, under the rules below.
- `actions`: up to four buttons. Each has a `label`, a `tool` on the mod's own server with its
  `arguments`, `confirm` to ask before calling, and at most one text `input`, which is collected
  and passed as `arguments[input.name]`. Use the bare tool name, not `mcp__<server>__<tool>`.

For each handler's changes, the host checks its installed manifest rather than trusting the row's
claim. New mod rows must name that mod, use its exact `<mod>:` prefix with a nonempty local ID and
use a kind other than `session`; native session IDs must come from the host listing. Splice rejects
a row that claims another mod's ownership, has more than four actions or names an action tool that
the owning mod did not declare or that is not visible to apps. These are display and compatibility
checks, not authorization. TypeScript checks the row shapes and action limit, not the installed
manifest or the host's session IDs.

See [`contract/examples/session-row.json`](contract/examples/session-row.json) and
[`contract/examples/mod-row.json`](contract/examples/mod-row.json).

## `ui.render`

> Not served yet: registering for `ui.render` is refused at load.

The request carries the `surface`, a `slot` ([page](contract/examples/ui-render-request.json),
[row](contract/examples/ui-render-row-request.json) or [view](contract/examples/ui-render-view-request.json)),
the `width` in CSS pixels, the `theme` (`light` or `dark`) and, when the page, row or view belongs
to one, the `session`. The slot is `{ kind: "page", page }`, `{ kind: "row", row }` or
`{ kind: "view", uri }`. The answer is `{ html, title, height? }`.
`html` must be a self-contained document: the app frames it sandboxed with no origin to load from,
so inline every style, script and image. An answer for a page name the manifest did not declare is
refused, and the person is told the mod's name and the page's.

## Environment variables

### `SPLICE_LABEL_<KEY>=<value>`

Set this in the shell you start a session from, for any command Splice launches. It arrives as `labels` on that
session's events and on its row: `SPLICE_LABEL_TICKET=ENG-412` becomes `labels.ticket = "ENG-412"`. Today that row
is the session's entry in `GET /api/sessions` and in `$.sessions.list()`.

- `KEY` is 1–32 characters of `A–Z`, `0–9` and `_`, starting with a letter, and arrives lowercased.
  Environment names are upper case by custom and case does not carry reliably between shells, so a
  label key has a single case on the wire.
- The value is up to 256 bytes of UTF-8 with no control characters.
- A session can have at most 16 labels.

A variable that breaks one of these rules is left out, and the launch prints which one and why, such as
`ignoring SPLICE_LABEL_TICKET: duplicate key`. The limits
exist because labels ride on session events and on the normalized row in
`GET /api/sessions?kind=all`, which apps poll. However a launcher sets them, a session's labels must stay at a few
kilobytes. Labels are keys a reader filters on, such as a ticket, a project or a lane, not a place
to carry documents.

### `SPLICE_MODS_OFF`

Read at launch. `1` turns off every mod; a comma-separated list of mod names turns off those mods.
A session launched with it is reported to none of the named mods: they hear none of its session or turn events,
and it is left out of their `$.sessions.list()`. Once mods offer servers, none of theirs will be offered to it. A
name that is not an installed mod is still honoured, and the launch warns
`SPLICE_MODS_OFF names a mod that is not installed: <name>`.

## The example

[`example/`](example/) is a complete mod, `reviews`, written against the whole contract. Installed today it loads and
its `session.start` handler runs. Its `tool.call` handlers load but never fire, since no session is offered the
mod's own tools yet, and everything else it registers or declares is refused at load
as listed [above](#what-is-not-served). A `session.start` hook opens a review for
a session launched with `SPLICE_LABEL_REVIEW=<title>`. `sessions.list` adds a row for each open
review, with Approve and Ask-for-changes buttons. The buttons call `resolve`, a tool marked for the
app alone. A `queue` page and a `board` view list every open review. `bun test mods` checks the
JSON examples against their declarations, compiles the configured hook modules, and checks that
buttons on a row the example produces target tools declared by the mod and marked app-only.
