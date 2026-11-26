# Writing a Splice mod

> **Nothing here is served today.** This folder holds the contract alone, its shapes as TypeScript
> declarations ([`contract/index.ts`](contract/index.ts)), worked JSON examples
> ([`contract/examples/`](contract/examples/)) and an example mod ([`example/`](example/)). Splice
> loads no mod yet. When the runner lands, a mod that uses an event Splice does not serve yet is
> refused when it loads, and the refusal names the mod and the event. It never loads without that
> event and leaves you guessing.

## What a mod is

A mod is a small folder you install into Splice, laid out the way a Claude Code mod is. It can:

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
  on(event, filter?, async ($, e, next) => {
    // before
    const res = await next(e); // the rest of the chain, ending in Splice's own behaviour
    // after
    return res;
  }).catch(async ($, e, next) => {
    // e.error says what the handler threw. Answer instead, or call next(e) to go on without it.
    return next(e);
  });
};
```

Each handler is one layer of an onion. It can act before `next`, change `e` before passing it on,
answer by itself without calling `next`, or change what `next` returned. Use a literal event name,
or narrow a dynamic name to one event first. That name determines which filter keys are valid and
the types of `e`, `next(e)` and the handler's answer, including in `.catch`.

### Events

| event | filter | what it is |
|---|---|---|
| `mod.start` | | The mod loaded: `mod`, `version`, `served` (registered events this host supports), `refused` (why other requests are not served). |
| `mod.stop` | | The mod is about to unload: `mod`, `version`, `reason`. |
| `session.start` | | A process began serving a session: `session` (the harness's own session id, the `session_id` of `GET /api/sessions`), `cwd`, `labels`, and `seenBefore`, true when that id was seen earlier. |
| `session.end` | | The process serving that session is gone: `session`, `cwd` and `labels`. One id is one session, however many processes serve it. |
| `launch.prepare` | | Per process, before it starts: `session` when known, `cwd`, `labels`, `env`, `args`, `mcp`. To change the launch, pass a changed `e` to `next`. |
| `sessions.list` | | Splice is assembling normalized rows: `surface`, `allRows`. Wrap `await next(e)` to add rows or change them; `allRows` says whether the reader asked for mod rows. The legacy HTTP response does not use this chain's output. |
| `turn.start` | | A turn began: `head`, requested `model`, `compact`, and `agent` (null when absent). |
| `request.send` | | One logical request round: upstream `model`, `round`, request `bytes`. |
| `stream.event` | | One client SSE frame: `seq`, `type`, `bytes`. |
| `tool.call` | `{ server?, tool? }` | A model opened a tool block: `caller: "model"`, tool call `id`, `server` (null for native tools), `tool`. It does not say that the tool ran. |
| `response.receive` | | One HTTP response: requested `model`, `status`, transport `attempt`. |
| `turn.complete` | | The turn ended: `model`, `outcome`, `durationMs`, `usage` (`input`, `output`, `cacheRead`, `cacheWrite`). |
| `ui.render` | `{ surface?, slot? }`; add `page` only with `slot: "page"` | An app asks the mod to draw a page, a row or a view. |
| `command.run` | `{ command }` | A person ran one of the mod's commands: `command`, `args`, `session?`. Answer `{ text }`. |
| `http.request` | `{ path?, method? }` | A request under the mod's own path, `/mods/<name>/…`: `method`, `path` (the part after it), `query`, `headers`, `body?`. Answer `{ status, headers?, body? }`. |

Each turn notification also carries `session` (null when not attributed), `turn`, `labels`, and
`at` (Unix milliseconds). These hooks observe; they cannot change a turn. They never carry
request or response bodies, stream data, or model tool arguments. App calls to a mod's own tool
are a separate, not-yet-served arm of `tool.call` with host-bound session context and arguments.

### The handle `$`

- `$.store`: `get`, `set`, `delete`, `list(prefix?)`. A durable key-value store that belongs to the
  mod alone.
- `$.clock`: `now()`, and `after(ms, fn)`, which returns a function that cancels it.
- `$.log(msg)`: writes a line to Splice's log under the mod's name.
- `$.sessions`: `list()` returns every row; `invalidate()` tells readers the rows changed.
- `$.ui.respond({ text, ui })`: the answer a `tool.call` handler returns when it carries both the
  words the model reads and a declared `ui://` view.

## App discovery and rows

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

Set this where a session is launched. It arrives as `labels` on that session's events and on its
row: `SPLICE_LABEL_TICKET=ENG-412` becomes `labels.ticket = "ENG-412"`.

- `KEY` is 1–32 characters of `A–Z`, `0–9` and `_`, starting with a letter, and arrives lowercased.
  Environment names are upper case by custom and case does not carry reliably between shells, so a
  label key has a single case on the wire.
- The value is up to 256 bytes of UTF-8 with no control characters.
- A session can have at most 16 labels.

A variable that breaks one of these rules is left out, and the person is told which one. The limits
exist because labels ride on session events and on the normalized row in
`GET /api/sessions?kind=all`, which apps poll. However a launcher sets them, a session's labels must stay at a few
kilobytes. Labels are keys a reader filters on, such as a ticket, a project or a lane, not a place
to carry documents.

### `SPLICE_MODS_OFF`

Read at launch. `1` turns off every mod; a comma-separated list of mod names turns off those mods.
A session launched with it is reported to none of the named mods, and none of their servers is
offered to it.

## The example

[`example/`](example/) is a complete mod, `reviews`. A `session.start` hook opens a review for
a session launched with `SPLICE_LABEL_REVIEW=<title>`. `sessions.list` adds a row for each open
review, with Approve and Ask-for-changes buttons. The buttons call `resolve`, a tool marked for the
app alone. A `queue` page and a `board` view list every open review. `bun test mods` checks the
JSON examples against their declarations, compiles the configured hook modules, and checks that
buttons on a row the example produces target tools declared by the mod and marked app-only.
