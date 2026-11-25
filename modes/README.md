# Modes

A mode is a set of tools splice gives the model of every session on its door, with the guidance for using them and
the code that answers them. One folder holds all of it, and adding a mode changes no file outside that folder.

## The folder

```
modes/<name>/
  mode.toml                the manifest: what the mode is called, its tools, and who it is offered to
  prompt.md                optional: guidance for the model, placed once per session, the same bytes on every request
  tools/<tool>.json        each tool's input schema, a JSON Schema object with "type": "object"
  tools/<tool>.md          each tool's description, as the model reads it (trimmed)
  tools/<tool>.meta.json   optional: the tool's _meta, passed to the client untouched
  resources/               optional: the content of the resources the manifest declares (an mcp mode's ui:// apps)
  build.gradle.kts         one line: plugins { id("splice.mode") }
  src/main/kotlin/         the handler, in the package splice.modes.<name>
  src/test/kotlin/         the mode's tests: its manifest loads through the catalog, and its handler answers
```

`modes/api` is `:modes-api`, the contract every mode implements and the catalog that finds and checks modes.
`modes/template` is a working door mode to copy, and `modes/templateapp` a working mcp mode with an MCP App. Both
ship off.

## The manifest

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `name` | string | required | The mode's name: its folder's name. |
| `summary` | string | required | One line on what the mode gives the model, shown in the console and the list. |
| `server` | string | required | The model sees each tool as `mcp__<server>__<tool>`. |
| `handler` | string | required | The class that answers the mode's calls, e.g. `splice.modes.template.TemplateMode`. |
| `tools` | list of strings | required | The tools, in the order they are offered; each has `tools/<tool>.json` and `tools/<tool>.md`. |
| `kind` | `"door"` or `"mcp"` | `"door"` | `"door"`: splice holds and answers each call inside itself, and the client never sees it. `"mcp"`: the mode is a real MCP server the daemon answers under `server`, so the client sees its tools and calls as any MCP tool's (see "An mcp mode"). |
| `on` | boolean | `false` | Whether the mode is on for every session by default. splice.toml's `[modes.<name>]` overrides it. |
| `[heads]` `only` | list of heads | `[]` | Empty: every head. Otherwise the only heads the mode is ever offered on. |
| `[heads]` `off` | list of heads | `[]` | Heads the mode is never offered on. |
| `[callers]` `off` | list of callers | `[]` | Callers the mode is never offered to: `"door"` (Claude Code on splice's door, plain `claude`), `"head"` (a head's own command, e.g. `claude-astra`) or `"desktop"` (Claude Desktop's own sessions). |
| `hooks` | table | none | The door's moments the mode acts on: each `[hooks.<moment>]` table, and `order` among the modes' hooks. See "Hooks". |
| `[[resources]]` | tables | none | Only for `kind = "mcp"`: each resource the mode serves. |
| `uri` | string | required | The resource's `ui://…` uri. |
| `name` (in `[[resources]]`) | string | the last part of `uri` | The resource's name. |
| `mimeType` | string | required | e.g. `text/html;profile=mcp-app` for an MCP App. |
| `file` | string | none | Its content, a file under `resources/`; without it, the handler gives the content. |
| `_meta` | inline table | none | Passed to the client as written, e.g. `{ ui = { csp = { connectDomains = [] } } }`. |

Names: a mode's name is a lowercase letter, then lowercase letters and digits (`[a-z][a-z0-9]*`), so one word is the
folder, the Gradle id `:modes-<name>` and the package `splice.modes.<name>`. A `server` and each tool name are
`[a-z][a-z0-9_]*` with no `__` in them, and `mcp__<server>__<tool>` is at most 64 characters.

## How splice finds a mode

1. `settings.gradle.kts` includes every folder under `modes/` that holds a `mode.toml` as `:modes-<name>`, and `:app`
   depends on every one of them by the same listing.
2. The `splice.mode` convention plugin carries `mode.toml`, `prompt.md`, `tools/` and `resources/` as the mode's
   resources under `splice-modes/<name>/`, and writes the mode's `java.util.ServiceLoader` entry
   (`META-INF/services/splice.modes.api.Mode`) from `handler`.
3. The shadow jar merges every mode's service entry, so splice's jar carries every mode.
4. At boot the catalog loads every mode through the ServiceLoader, reads each one's manifest, tools and prompt from its
   own resources, and checks them. A broken mode refuses boot, with every finding listed at once.
5. One module-law line governs every mode: `:modes-* -> :core :modes-api`. A mode reaches no other mode and no feature.

## Adding a mode

1. Copy `modes/template` to `modes/<name>`, or `modes/templateapp` for an mcp mode.
2. Rename it in the manifest, the package and the class: every `template`, `Template` and `TEMPLATE` (or
   `templateapp`, `TemplateApp`) becomes your name.
3. Write the tools (`tools/<tool>.json`, `tools/<tool>.md`), list them in `tools`, and answer them in the handler.
4. Run `./gradlew :modes-<name>:test`: the mode loads through the catalog and its handler answers.
5. Build splice. The mode ships in the jar. No file outside the folder changes.

## Turning a mode on and off

The manifest's `on` is the default. splice.toml overrides it per mode:

```toml
[modes.<name>]
on = true                  # every head
heads = { kimi = false }   # per head
server_routes = true       # on a team server: key holders reach it at /server/mod/<name>/...
server_admin_only = true   # and only the server's admins
```

For a mode and a head, in this order: a head outside the manifest's non-empty `only`, or on its `off`, is never
offered the mode, and splice.toml cannot turn it on; otherwise `heads.<head>` decides, then `on`, then the
manifest's `on`. A caller on `[callers] off` is never offered the mode: the template's `off = ["desktop"]` leaves out
Claude Desktop's own sessions.

What the door adds is listed by `GET /api/modes`, by `splice status`, and on the console's Settings > Modes, where a
switch per head writes `[modes.<name>]`. `splice check-config` lists a broken mode's findings, and a
`[modes.<name>]` table naming no mode, as boot does.

## An mcp mode

A door mode's tools ride the model's request and are answered inside the turn, so the client never sees them. An
mcp mode (`kind = "mcp"`) is an MCP server the client itself connects to, so its tools are the client's own tools
and a client that draws MCP Apps can show its pages.

- **Where it is served.** The daemon answers the server itself, at the address it serves hosted MCP servers on
  (`/mcp/<server>`): there is no process to start. `initialize`, `tools/list`, `tools/call`, `resources/list` and
  `resources/read` are answered in the reply to each request.
- **How a client finds it.** Every launch on a head where the mode is on is handed one more `--mcp-config` file
  that names the server. `claude` on the door runs no head, so for it the mode's `on` decides, and a mode with a
  non-empty `[heads] only` is not handed to it. A caller on `[callers] off` gets no such file. A session already
  running when the mode is turned on does not see it until its next launch; turned off, the server stops answering
  at once.
- **What the client is told.** Each tool under its own name (the client shows `mcp__<server>__<tool>`), with its
  description, schema and `_meta` as the folder writes them. `prompt.md` is the server's `instructions`.
- **Pages.** A tool whose `tools/<tool>.meta.json` says `{ "ui": { "resourceUri": "ui://<server>/<page>" } }` names
  a `[[resources]]` entry. The page is the entry's `file` under `resources/`, or what the handler's
  `resource(uri)` gives for an entry with no file. A client that does not draw MCP Apps shows the tool's answer as
  any tool's result, so the answer has to stand without the page.
- **The session.** A call's `ModeSession.id` is the client's MCP session id, not the id `/api/sessions` gives the
  session, and its folder and model are not known: the MCP transport does not carry them.

## Hooks

A mode may act at the door's moments, as Claude Code's hooks do inside Claude Code, whatever harness sends the turn
and whatever provider answers it.

```toml
[hooks]
order = 10                 # among all modes' hooks of one moment; ties by mode name

[hooks.session_start]
timeout_s = 5              # 1 to 3600; default 30
on_failure = "closed"      # "open" (default): the turn goes on; "closed": it is refused

[hooks.request]
worker = { kind = "http", url = "http://127.0.0.1:9100/hook", auth_env = "HOOK_KEY" }
```

- **Moments.**
  - `session_start`: a session's first turn — add context at the end of its first user message, choose the model its
    turns run on, or refuse the session with a reason the person reads.
  - `before_tool_call`: before a harness/mode tool call runs — rewrite its input (`rewrite`) or refuse the turn.
  - `after_tool_call`: after a harness/mode tool call finishes — replace its text result (`replace`) or refuse the turn.
  - `on_stop`: when the model stops without calling a tool — continue the turn with a follow-up message (`continue`),
    replace the final text (`replace`), or refuse.
  - `request`: sees the whole request on its way to the provider and may replace it (`rewrite`) or refuse.
  - `response`: sees the whole response on its way back to the client and may rewrite its content (`rewrite`) or refuse.
  A manifest naming any other moment is a finding: a moment appears here when splice serves it, never as a name that
  does nothing.
- **Who answers.** Without a `worker`, the mode's handler answers in-process and must implement `ModeHooks`
  (`hook(call)`, answer `Pass` by default). `worker.kind = "http"` posts the call as JSON and reads one JSON line
  back; `worker.kind = "mcp"` calls a tool the MCP hub serves. Both receive `moment`, `session` and `body`, and answer
  `{"answer":"pass"}`, `context`/`model`/`refuse` (with `text`, `model`, `reason`), or `rewrite` (with `body`).
- **Time and failure.** Each hook has its own time limit. One that errs or runs out of time lets the turn go on and
  says so in splice's log, unless its manifest says `on_failure = "closed"`, which refuses the turn.
- **Order.** Every mode's hooks of one moment run in the order their manifests' `order` decides, ties by mode name.
- **Kept.** What a session-start hook places or chooses is recorded before the turn goes upstream, and placed again
  byte for byte on every later request of the session. A compaction that replaces the first message ends it.
- **What it is not.** A request hook shapes what the provider sees; it cannot run a tool for the harness. The harness
  still runs its own tools, on its own machine, with its own permissions.

`GET /api/modes` lists each mode's hooks with their moment, time limit, failure policy, order and worker.

## The contract

- The handler implements `splice.modes.api.Mode`. `answer(call)` gets a `ModeCall`: the tool's name as the manifest
  writes it, its input as a JSON object, the session, and the mode's store. It answers `ModeAnswer.Text` or
  `ModeAnswer.Error`, which the model reads as an error result. A handler that throws is answered for it.
- The session is `ModeSession(id, folder, head, model, caller)`: the id `/api/sessions` gives it, its folder when
  known, its head, its model, and its caller (`door`, `head` or `desktop`).
- `ModeStore` keeps text per session for the mode, under splice's state folder, and survives a restart.
- `resource(uri)` gives the content of a resource the manifest declares with no `file`; only an mcp mode is asked.
- `routes` lists the management routes the mode answers (`ModeRoute`: method, path, and a handler from a
  `ModeRequest` to a `ModeResponse`). splice serves them under `/api/` behind the management key; two modes, or a mode
  and splice, claiming one path refuses boot.
- `serve(request)` answers a team server's key holders under `/server/mod/<name>/...`, once splice.toml's
  `[modes.<name>] server_routes` is true (`server_admin_only = true` keeps it to the server's admins). The
  `ModRequest` names who is asking (`caller`: person, team, admin) and never their key, and carries the method, the
  path after the mode's name, a few headers, the query and the body; the answer is a `ModResponse` (status, content
  type, body), or null for a path the mode does not have. See docs/specs/server-mode.md, "Mode routes".
