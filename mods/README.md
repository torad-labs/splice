# Writing a Splice mod

> **Nothing here is served today.** This folder holds the contract alone, its shapes as TypeScript
> declarations ([`contract/index.ts`](contract/index.ts)), one JSON example per shape
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
`hooks/` sit at the root beside `.splice-mod/`, never inside it.

Import the types only. A mod carries no runtime code from Splice:

```ts
import type { Register } from "<splice>/mods/contract/index.ts";
```

## `mod.json`

| field | |
|---|---|
| `name` | 1–40 characters of `a–z`, `0–9` and `-`, starting with a letter. It names the mod's MCP server and prefixes its rows. |
| `version`, `description` | Shown to the person at install. |
| `author`, `surfaces` | Optional. `surfaces` names the apps the mod adds to; absent means every app. |
| `capabilities` | Optional. What the mod says it does, shown to the person at install. It is a description, not a wall. |
| `tools` | Tool names. Each name has its files under `tools/`. |
| `pages` | `{ name, title }`: a page an app opens. The mod draws it by answering `ui.render`. |
| `commands` | `{ name, description }`: a command a person runs. The mod answers `command.run`. |
| `views` | `{ uri, name, mimeType?, file? }`: a `ui://` view a tool's answer may return. With `file`, the view is that static HTML. Without it, the mod answers `ui.render` for that uri. |

### Declared, never registered

The handle has no `tool.register`, no `command.register` and no `ui.register`. Anything a session or
an app must see before the mod runs, or without it running, is declared in `mod.json`. Anything
that changes per request is answered by a hook.

The reason is that a tool list has to stay the same for the whole conversation. Splice writes every
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
(`ToolDefinition`) on the mod's own server.

A tool meant only for views and row buttons is marked:

```json
{ "ui": { "visibility": ["app"] } }
```

Splice enforces this mark itself. It leaves the tool out of the `tools/list` it writes for the
session, so the model never sees the tool. A call from a row's action or a view is still answered.
The protection is that Splice leaves the tool off the list. It does not count on the host honoring
the mark: one host was measured forwarding a view's call to a tool marked for the model alone, so
whether a host honors the mark is not something to rely on.

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
answer by itself without calling `next`, or change what `next` returned.

### Events

| event | filter | what it is |
|---|---|---|
| `mod.start`, `mod.stop` | | The mod loaded or is about to unload. |
| `session.start` | | A process began serving a session: `session` (the harness's own session id, the `session_id` of `GET /api/sessions`), `cwd`, `labels`, and `seenBefore`, true when that id was seen earlier. |
| `session.end` | | The process serving that session is gone. One id is one session, a single row however many processes serve it. |
| `launch.prepare` | | Per process, before it starts: `env`, `args`, `mcp`. To change the launch, pass a changed `e` to `next`. |
| `sessions.list` | | `GET /api/sessions` is being answered. Wrap `await next(e)` to add rows or change them. |
| `turn.start` | | A turn began. |
| `request.send` | | A request is about to go to the model. Change `body` and pass it on. |
| `stream.event` | | One event of the model's stream. Return `null` to drop it. |
| `tool.call` | `{ server?, tool? }` | A tool call. `caller` is `model` or `app`. |
| `response.receive` | | The whole response, assembled from the stream. |
| `turn.complete` | | The turn ended: `stopReason`, `usage`. |
| `ui.render` | `{ surface?, slot?, page? }` | An app asks the mod to draw a page, a row or a view. |
| `command.run` | `{ command }` | A person ran one of the mod's commands. |
| `http.request` | `{ path?, method? }` | A request under the mod's own path, `/mods/<name>/…`. |

### The handle `$`

- `$.store`: `get`, `set`, `delete`, `list(prefix?)`. A durable key-value store that belongs to the
  mod alone.
- `$.clock`: `now()`, and `after(ms, fn)`, which returns a function that cancels it.
- `$.log(msg)`: writes a line to Splice's log under the mod's name.
- `$.sessions`: `list()` returns every row; `invalidate()` tells readers the rows changed.
- `$.ui.respond({ text, ui })`: the answer a `tool.call` handler returns when it carries both the
  words the model reads and a declared `ui://` view.

## Rows

`GET /api/sessions` returns sessions only, as it always has, so no existing reader is handed a row
it would mistake for a session. **`GET /api/sessions?kind=all`** returns every row: sessions and
every mod's own rows. This contract chose that spelling. `kind` is the row field the query selects
on, and `all` leaves room for a future `?kind=<a mod's kind>`.

A row (`SessionRow`) has:

- `id`: a mod's own rows are `<mod>:<id>`. A session id never contains `:`, so the two cannot
  collide.
- `kind`: `session`, or a kind the mod names.
- `parent`, `title`.
- `status`, from one set every row shares: `working`, `waiting` (waiting on the person), `paused`,
  `done` or `failed`.
- `progress`: `{ done, total }`.
- `needs`: `{ text, deadline? }`.
- `actions`: up to four buttons. Each has a `label`, a `tool` on the mod's own server with its
  `arguments`, `confirm` to ask before calling, and at most one text `input`, which is collected
  and passed as `arguments[input.name]`. An action may name only a tool its own mod declared.

See [`contract/examples/session-row.json`](contract/examples/session-row.json) and
[`contract/examples/mod-row.json`](contract/examples/mod-row.json).

## `ui.render`

The request carries the `surface`, a `slot` (`{ kind: "page", page }`, `{ kind: "row", row }` or
`{ kind: "view", uri }`), the `width` and the `theme`. The answer is `{ html, title, height? }`.
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
exist because labels ride on every event of the session and on its row in every `GET /api/sessions`
answer, which every app polls. However a launcher sets them, a session's labels must stay at a few
kilobytes. Labels are keys a reader filters on, such as a ticket, a project or a lane, not a place
to carry documents.

### `SPLICE_MODS_OFF`

Read at launch. `1` turns off every mod; a comma-separated list of mod names turns off those mods.
A session launched with it is reported to none of the named mods, and none of their servers is
offered to it.

## The example

[`example/`](example/) is a complete mod, `review-queue`. A `session.start` hook opens a review for
a session launched with `SPLICE_LABEL_REVIEW=<title>`. `sessions.list` adds a row for each open
review, with Approve and Ask-for-changes buttons. The buttons call `resolve`, a tool marked for the
app alone. A `queue` page and a `board` view list every open review. `bun test mods` checks every
JSON file of the example and every example in `contract/examples/` against its declaration, and
compiles `register.ts`, so a wrong field in any of them fails.
