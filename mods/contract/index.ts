/**
 * The Splice Mods v2 contract: every shape a mod and Splice exchange, as TypeScript declarations.
 *
 * A mod is a small folder a person installs into Splice. It watches or changes what passes through
 * Splice (a session starting, a turn, a tool call), adds rows and views to any app that shows
 * Splice's sessions, and gives the model tools that draw UI in a conversation. Splice's core holds
 * no code for any one mod.
 *
 * NOTHING HERE RUNS YET. This file is the shapes alone, so that Splice and the mods written against
 * it build against one file. When the runner lands, a mod using an event Splice does not serve yet
 * is refused at load, in words naming the mod and the event.
 *
 * The author's page is mods/README.md. A mod imports these declarations as types only:
 *
 *   import type { Register } from "<splice>/mods/contract/index.ts";
 *   export const register: Register = on => { on("session.start", async ($, e, next) => { ... }) };
 */

// ---------------------------------------------------------------------------------------------
// Values

/** A JSON value: what crosses between a mod and Splice is always plain JSON. */
export type Json = null | boolean | number | string | readonly Json[] | { readonly [key: string]: Json };
export type JsonObject = { readonly [key: string]: Json };

/** A view's address. A tool's answer names one; `ui.render` answers one; `mod.json` declares each. */
export type UiUri = `ui://${string}`;

/** A moment as an ISO 8601 string with its offset, e.g. "2026-11-25T18:00:00-06:00". */
export type Instant = string;

/**
 * Labels set where a session is launched, one environment variable each:
 * `SPLICE_LABEL_<KEY>=<value>` arrives here as `{ "<key>": "<value>" }`.
 *
 * Rules, checked at launch (a variable that breaks one is left out and said to the person by name):
 * - KEY is 1–32 characters of A–Z, 0–9 and `_`, starting with a letter. It arrives lowercased, so
 *   `SPLICE_LABEL_TICKET=…` is `labels.ticket`. Environment names are upper case by custom and case
 *   is not portable across shells, so the label key is one case on the wire and cannot collide.
 * - value is 0–256 bytes of UTF-8 with no control characters (no newline, no tab, no NUL).
 * - at most 16 labels per session.
 * Why bounded: labels ride on session events and on the normalized row in `GET /api/sessions?kind=all`,
 * read by apps polling the list, so a session's labels must stay a few kilobytes at
 * most however a launcher sets them; and they are for keys a reader filters on (a ticket, a
 * project, a lane), not a place to carry documents.
 */
export type Labels = { readonly [key: string]: string };

// ---------------------------------------------------------------------------------------------
// The package: .splice-mod/mod.json

/**
 * `.splice-mod/mod.json`. Every path in it is relative to the mod's root, the folder a person
 * installs, so `tools/` and `hooks/` sit at the root beside `.splice-mod/`, never inside it.
 *
 *   <mod>/
 *     .splice-mod/mod.json
 *     hooks/hooks.json         { "modules": ["./register.ts"] }
 *     hooks/register.ts        export const register: Register = on => { ... }
 *     tools/<name>.json        a tool's input schema (JSON Schema, type object)
 *     tools/<name>.md          a tool's description
 *     tools/<name>.meta.json   a tool's _meta, optional
 *
 * What a session or an app must see before, or apart from, the mod running is declared here; what
 * varies per request is answered by a hook. There is no tool, command or view registration at run
 * time (see mods/README.md for why).
 */
export interface ModManifest {
  /** The mod's name: `[a-z][a-z0-9]*`, at most 40 characters, the rule a mode's name follows, since
   *  it names the mod's MCP server and so sits in the model's `mcp__<server>__<tool>` names. It prefixes the
   *  mod's rows (`<name>:<id>`), names its MCP server, and is what `SPLICE_MODS_OFF` lists. */
  readonly name: string;
  /** Semantic version, e.g. "0.1.0". */
  readonly version: string;
  /** One or two sentences shown to the person at install. */
  readonly description: string;
  readonly author?: string;
  /** The apps the mod adds rows, pages or views to, by name, e.g. ["console", "desktop"]. Absent:
   *  every app that shows Splice's sessions. */
  readonly surfaces?: readonly string[];
  /** What the mod says it does, shown to the person at install. Not a wall: Splice serves a mod the
   *  events it registers for whatever this list says. */
  readonly capabilities?: readonly Capability[];
  /** Tool names, each `[a-z][a-z0-9_]*` with no `__`, read the way a mode's tools are read: the
   *  model is offered `mcp__<mod>__<tool>`, at most 64 characters in all. Each has `tools/<name>.json` (input schema) and `tools/<name>.md` (description),
   *  and may have `tools/<name>.meta.json` (its `_meta`). Splice offers them to a session on the
   *  mod's own MCP server. */
  readonly tools?: readonly string[];
  /** Pages an app opens. The mod answers `ui.render` with `slot: { kind: "page", page: name }`. */
  readonly pages?: readonly PageDeclaration[];
  /** Commands a person runs. The mod answers `command.run` with the command's name. */
  readonly commands?: readonly CommandDeclaration[];
  /** Views a tool's answer may return. */
  readonly views?: readonly ViewDeclaration[];
}

// App discovery uses the installed manifest, not a running hook.
export const MOD_CATALOG_PATH = "/api/mods";

/** `GET /api/mods`: the installed mods, including disabled and failed ones. */
export interface ModCatalogResponse {
  readonly mods: readonly ModCatalogEntry[];
}

/** The app-facing fields of a catalog entry. The runner may also supply operational diagnostics. */
export interface ModCatalogEntry extends ModManifest {
  readonly runtime: "ts";
  readonly enabled: boolean;
  readonly state: "starting" | "running" | "restarting" | "stuck" | "failed" | "disabled";
}

/** What a mod may say it does, each shown to the person at install as one line. */
export type Capability =
  | "watch-sessions" // reads session.start / session.end
  | "change-launch" // changes a process's environment, arguments or MCP configuration
  | "watch-turns" // reads turn, request, stream and response events
  | "change-requests" // changes what is sent to a model or what comes back
  | "answer-tools" // answers tool calls on its own server
  | "add-rows" // adds or changes rows in the session list
  | "draw-ui" // draws pages, rows' views or tool views
  | "run-commands" // answers commands a person runs
  | "serve-http"; // answers HTTP requests under its own path

export interface PageDeclaration {
  /** 1–40 characters of a–z, 0–9 and `-`. */
  readonly name: string;
  /** What the app shows as the page's title before the mod answers. */
  readonly title: string;
}

export interface CommandDeclaration {
  /** 1–40 characters of a–z, 0–9 and `-`. */
  readonly name: string;
  readonly description: string;
}

/**
 * A `ui://` view a tool's answer may return. With `file`, the view is that static HTML, relative to
 * the mod's root; without it, the mod answers `ui.render` with `slot: { kind: "view", uri }`.
 */
export interface ViewDeclaration {
  readonly uri: UiUri;
  readonly name: string;
  /** Default "text/html;profile=mcp-app". */
  readonly mimeType?: string;
  readonly file?: string;
}

/** `hooks/hooks.json`: the modules that export `register`, relative to `hooks/`. */
export interface HooksFile {
  readonly modules: readonly string[];
}

// ---------------------------------------------------------------------------------------------
// Tools: tools/<name>.json, tools/<name>.md, tools/<name>.meta.json

/** `tools/<name>.json`: the tool's input, as a JSON Schema whose type is object. */
export interface ToolInputSchema {
  readonly type: "object";
  readonly properties?: { readonly [name: string]: boolean | JsonObject };
  readonly required?: readonly string[];
  readonly additionalProperties?: boolean | JsonObject;
  readonly description?: string;
  readonly $schema?: string;
  readonly [keyword: string]: Json | undefined;
}

/** Who may call a tool: `model` is the model in the conversation, `app` is a row's action or a view. */
export type ToolVisibility = "model" | "app";

/**
 * `tools/<name>.meta.json`, the tool's `_meta`. `{ "ui": { "visibility": ["app"] } }` marks a tool
 * for views and actions only: Splice leaves it out of the `tools/list` it writes for the session,
 * and still answers a call to it from a row's action or a view. The wall is Splice's list, not the
 * host's handling of the mark.
 */
export interface ToolMeta {
  readonly ui?: {
    /** Absent: ["model", "app"]. */
    readonly visibility?: readonly ToolVisibility[];
    /** The view this tool's answers draw in. Must be a view the manifest declares. */
    readonly resourceUri?: UiUri;
  };
  readonly [key: string]: Json | undefined;
}

/** A tool as Splice assembles it from the three files and writes it into `tools/list`. */
export interface ToolDefinition {
  readonly name: string;
  readonly description: string;
  readonly inputSchema: ToolInputSchema;
  readonly _meta?: ToolMeta;
}

/** What a tool call answers, in MCP's shape. `$.ui.respond` builds one that carries a view. */
export interface ToolResult {
  readonly content: readonly ToolContent[];
  readonly structuredContent?: JsonObject;
  readonly isError?: boolean;
  readonly _meta?: JsonObject;
}

export type ToolContent =
  | { readonly type: "text"; readonly text: string }
  | { readonly type: "resource_link"; readonly uri: string; readonly name: string; readonly mimeType?: string };

// ---------------------------------------------------------------------------------------------
// Rows: the normalized v2 list, not the legacy SessionsRoutes wire

/**
 * Only `GET /api/sessions?kind=all` returns the v2 envelope below. Plain `GET /api/sessions`
 * stays on SessionsRoutes: `sessions` (with `session_id` and `waiting_for`), `reading`,
 * `note_versions`, `gone_mark` and, when present, `gone_held`, `gone_older` and `error`.
 * Build the legacy response from host sessions alone, without invoking the mod onion.
 */
export const ALL_ROWS_QUERY = "kind=all";

export interface SessionsListResponse {
  readonly rows: readonly SessionRow[];
}

/** The one status set every row shares, so an app draws every mod's rows alike. */
export type RowStatus =
  | "working"
  | "waiting" // waiting on the person
  | "paused"
  | "done"
  | "failed";

export type SessionRow = HostSessionRow | ModSessionRow;

export interface HostSessionRow extends RowFields {
  /** The harness's own session ID (the legacy wire's `session_id`), never containing `:`. */
  readonly id: string;
  readonly kind: "session";
  readonly mod?: never;
  readonly actions?: never;
}

/**
 * At the v2 boundary, Splice validates rows added or changed by each handler against that mod's
 * installed manifest, not against the row's own ownership claim. A new row must name the mod,
 * use its exact prefix and a nonempty local ID, and must not claim the host-reserved `session`
 * kind. A host row's identity must come from the host listing, not from mod output. Actions
 * must name local tools declared by that same mod and visible to apps; reject a row attributed
 * to another mod or with an invalid action or more than four actions. These checks protect
 * display and compatibility, not authorization to call a tool.
 */
export interface ModSessionRow extends RowFields {
  readonly id: `${string}:${string}`;
  /** A mod-defined kind, e.g. "task". The host reserves "session". */
  readonly kind: string;
  readonly mod: string;
  /** At most four, drawn as buttons in order. */
  readonly actions?: readonly [] | readonly [RowAction] | readonly [RowAction, RowAction]
    | readonly [RowAction, RowAction, RowAction] | readonly [RowAction, RowAction, RowAction, RowAction];
}

interface RowFields {
  /** The row this one sits under: a session's `id` or another row's `id`. When no row in the same
   *  answer carries that id, Splice keeps the row at the top level and reports that its parent was
   *  not found, naming the mod, the row and the missing id, where the person sees a mod's state. A
   *  hierarchy that silently collapses would look the same as a mod with no hierarchy. */
  readonly parent?: string;
  readonly title: string;
  readonly status: RowStatus;
  readonly progress?: RowProgress;
  /** What the row needs from the person. On a session's row this carries what `waiting_for` says. */
  readonly needs?: RowNeeds;
  /** The session's labels, or the labels a mod gives its row. */
  readonly labels?: Labels;
}

export interface RowProgress {
  readonly done: number;
  readonly total: number;
}

export interface RowNeeds {
  readonly text: string;
  readonly deadline?: Instant;
}

/**
 * A button a reader draws on a row. Pressing it calls `tool` on the row's own mod's server with
 * `arguments`, plus the input's value under `input.name` when there is an input. An action names
 * the bare name of a tool declared by its own mod and visible to apps, never a qualified MCP name.
 */
export interface RowAction {
  readonly label: string;
  readonly tool: string;
  readonly arguments?: JsonObject;
  /** Ask the person before calling. */
  readonly confirm?: boolean;
  /** At most one text input, collected before the call and passed as `arguments[input.name]`. */
  readonly input?: RowActionInput;
}

export interface RowActionInput {
  readonly name: string;
  readonly label: string;
  readonly placeholder?: string;
  readonly multiline?: boolean;
}

// ---------------------------------------------------------------------------------------------
// ui.render

export type UiSlot =
  | { readonly kind: "page"; readonly page: string }
  | { readonly kind: "row"; readonly row: string }
  | { readonly kind: "view"; readonly uri: UiUri };

export type Theme = "light" | "dark";

export interface UiRenderRequest {
  /** The app asking, e.g. "console" or "desktop". */
  readonly surface: string;
  readonly slot: UiSlot;
  /** Width the app frames the answer at, in CSS pixels. */
  readonly width: number;
  readonly theme: Theme;
  /** The session the view or row belongs to, when it belongs to one. */
  readonly session?: string;
}

/**
 * A self-contained HTML document the app frames sandboxed, with no origin to load from: inline
 * every style, script and image. An answer for a page name the manifest did not declare is refused
 * and said to the person with the mod's name and the page's.
 */
export interface UiRenderAnswer {
  readonly html: string;
  readonly title: string;
  /** CSS pixels. Absent: the app sizes the frame to the document. */
  readonly height?: number;
}

// ---------------------------------------------------------------------------------------------
// Events

export interface ModLifecycle {
  readonly mod: string;
  readonly version: string;
}

export interface SessionStart {
  /** The harness's own session id, the `session_id` of `GET /api/sessions`. The id is the session:
   *  it stays one row however many processes serve it. */
  readonly session: string;
  readonly cwd: string;
  readonly labels: Labels;
  /** Whether this id was seen before: a resumed session, or a second process serving it. */
  readonly seenBefore: boolean;
}

export interface SessionEnd {
  readonly session: string;
  readonly cwd: string;
  readonly labels: Labels;
}

/** Fires per process, before it starts. A handler changes the launch by passing a changed `e` to
 *  `next`; what the chain returns is what starts. */
export interface LaunchPrepare {
  readonly session?: string;
  readonly cwd: string;
  readonly labels: Labels;
  readonly env: { readonly [name: string]: string };
  readonly args: readonly string[];
  /** The MCP configuration the process receives, `{ mcpServers: { … } }`. */
  readonly mcp: JsonObject;
}

/** The normalized listing request. The legacy HTTP response does not consume this onion's output. */
export interface SessionsList {
  /** The surface asking. */
  readonly surface?: string;
  /** Whether the normalized listing includes mod rows, as in `?kind=all`. */
  readonly allRows: boolean;
}

export interface TurnStart {
  readonly session: string;
  readonly turn: string;
  readonly labels: Labels;
  readonly model: string;
}

/** A request on its way to the model. A handler changes it by passing a changed `e` to `next`. */
export interface RequestSend {
  readonly session: string;
  readonly turn: string;
  readonly model: string;
  /** The request body as the backend will receive it. */
  readonly body: JsonObject;
}

/** One event of the model's stream. Return what the client receives; return `null` to drop it. */
export interface StreamEvent {
  readonly session: string;
  readonly turn: string;
  readonly event: JsonObject;
}

/**
 * For a model call, the host binds `session` from trusted launch/session provenance. It must not
 * infer an identity from tool arguments or untrusted request metadata. App calls from views or
 * actions can be sessionless; when an app call has a session, it too comes from host context.
 */
export type ToolCall = ToolCallFields & (
  | { readonly caller: "model"; readonly session: string }
  | { readonly caller: "app"; readonly session?: string }
);

interface ToolCallFields {
  readonly turn?: string;
  /** The MCP server the tool is on. A mod's own server is named after the mod. */
  readonly server: string;
  readonly tool: string;
  readonly arguments: JsonObject;
}

export interface ResponseReceive {
  readonly session: string;
  readonly turn: string;
  readonly model: string;
  readonly status: number;
  /** The whole response, assembled from the stream. */
  readonly body: JsonObject;
}

export interface TurnComplete {
  readonly session: string;
  readonly turn: string;
  readonly model: string;
  readonly stopReason: string;
  readonly usage?: { readonly input: number; readonly output: number; readonly cacheRead: number; readonly cacheWrite: number };
}

export interface CommandRun {
  readonly command: string;
  /** What the person typed after the command's name. */
  readonly args: string;
  readonly session?: string;
}

export interface CommandAnswer {
  readonly text: string;
}

/** A request under the mod's own path, `/mods/<name>/…`. `path` is the part after it. */
export interface HttpRequest {
  readonly method: string;
  readonly path: string;
  readonly query: { readonly [name: string]: string };
  readonly headers: { readonly [name: string]: string };
  readonly body?: string;
}

export interface HttpAnswer {
  readonly status: number;
  readonly headers?: { readonly [name: string]: string };
  readonly body?: string;
}

/** Every event: its payload `e`, its filter, and what `next(e)` and the handler return. */
export interface Events {
  "mod.start": { payload: ModLifecycle; filter: never; result: void };
  "mod.stop": { payload: ModLifecycle; filter: never; result: void };
  "session.start": { payload: SessionStart; filter: never; result: void };
  "session.end": { payload: SessionEnd; filter: never; result: void };
  "launch.prepare": { payload: LaunchPrepare; filter: never; result: LaunchPrepare };
  "sessions.list": { payload: SessionsList; filter: never; result: readonly SessionRow[] };
  "turn.start": { payload: TurnStart; filter: never; result: void };
  "request.send": { payload: RequestSend; filter: never; result: RequestSend };
  "stream.event": { payload: StreamEvent; filter: never; result: StreamEvent | null };
  "tool.call": { payload: ToolCall; filter: { readonly server?: string; readonly tool?: string }; result: ToolResult };
  "response.receive": { payload: ResponseReceive; filter: never; result: ResponseReceive };
  "turn.complete": { payload: TurnComplete; filter: never; result: void };
  "ui.render": {
    payload: UiRenderRequest;
    filter: { readonly surface?: string } & (
      | { readonly slot: "page"; readonly page: string }
      | { readonly slot?: UiSlot["kind"]; readonly page?: never }
    );
    result: UiRenderAnswer;
  };
  "command.run": { payload: CommandRun; filter: { readonly command: string }; result: CommandAnswer };
  "http.request": { payload: HttpRequest; filter: { readonly path?: string; readonly method?: string }; result: HttpAnswer };
}

export type EventName = keyof Events;
export type Payload<E extends EventName> = Events[E]["payload"];
type KeysOfUnion<T> = T extends unknown ? keyof T : never;
type FilterKey = KeysOfUnion<Events[EventName]["filter"]>;
type EventFilter<F> = F extends object ? F & { readonly [K in Exclude<FilterKey, keyof F>]?: never } : never;

/** Keys belonging to other filters are invalid even when the filter is passed in a variable. */
export type Filter<E extends EventName> = EventFilter<Events[E]["filter"]>;
export type Result<E extends EventName> = Events[E]["result"];

/** The rest of the chain: the next mod's handler, or Splice's own behaviour at the end. */
export type Next<E extends EventName> = (e: Payload<E>) => Promise<Result<E>>;

/** A handler is a layer of an onion: it may act before `next`, change `e`, skip `next` and answer
 *  itself, or change what `next` returned. */
export type Handler<E extends EventName> = ($: Handle, e: Payload<E>, next: Next<E>) => Promise<Result<E>>;

/** What a handler threw, as the `.catch` handler receives it beside the event. */
export interface ModError {
  readonly message: string;
  readonly name?: string;
  readonly stack?: string;
}

/** Runs when the registration's handler throws or rejects. Its answer stands in for the handler's;
 *  calling `next` lets the event go on as though the handler were absent. */
export type CatchHandler<E extends EventName> = (
  $: Handle,
  e: Payload<E> & { readonly error: ModError },
  next: Next<E>,
) => Promise<Result<E>>;

export interface Registration<E extends EventName> {
  catch(handler: CatchHandler<E>): Registration<E>;
}

// Distribute over E, but compare each member with the whole inferred event type.
type SingleEvent<E extends EventName, Whole = E> = E extends EventName
  ? [Whole] extends [E] ? E : never
  : never;

/** Register one event at a time: narrow a dynamic name before calling. The event determines the
 *  filter, the payload and the result of both the handler and its error handler. */
export interface On {
  <E extends EventName>(event: E & NoInfer<SingleEvent<E>>, handler: Handler<NoInfer<E>>): Registration<E>;
  <E extends EventName>(
    event: E & NoInfer<SingleEvent<E>>,
    filter: Filter<NoInfer<E>>,
    handler: Handler<NoInfer<E>>,
  ): Registration<E>;
}

/** `hooks/register.ts` exports `register: Register`. Splice calls it once when the mod loads. */
export type Register = (on: On) => void;

// ---------------------------------------------------------------------------------------------
// The handle `$`

export interface Handle {
  /** The mod's own durable key-value store. Keys are the mod's alone. */
  readonly store: {
    get(key: string): Promise<Json | undefined>;
    set(key: string, value: Json): Promise<void>;
    delete(key: string): Promise<void>;
    /** Keys starting with `prefix`, every key when absent. */
    list(prefix?: string): Promise<readonly string[]>;
  };
  readonly clock: {
    /** Milliseconds since the Unix epoch. */
    now(): number;
    /** Runs `fn` after `ms` milliseconds; the returned function cancels it. */
    after(ms: number, fn: () => void | Promise<void>): () => void;
  };
  /** A line in Splice's log, under the mod's name. */
  log(msg: string): void;
  readonly sessions: {
    /** The `rows` from the current normalized listing, as in `GET /api/sessions?kind=all`. */
    list(): Promise<readonly SessionRow[]>;
    /** Tells every reader the rows changed, so they ask again and `sessions.list` runs again. */
    invalidate(): void;
  };
  readonly ui: {
    /** What a `tool.call` handler returns when its answer carries both the words the model reads
     *  and a declared `ui://` view. */
    respond(answer: { readonly text: string; readonly ui: UiUri }): ToolResult;
  };
}

// ---------------------------------------------------------------------------------------------
// Environment variables

/** `SPLICE_LABEL_<KEY>=<value>`: see `Labels` for the rules. */
export const LABEL_ENV_PREFIX = "SPLICE_LABEL_";

/**
 * `SPLICE_MODS_OFF`: `1` for every mod, or a comma-separated list of mod names, read at launch. A
 * session launched with it is told to no mod named, and no such mod's server is offered to it.
 */
export const MODS_OFF_ENV = "SPLICE_MODS_OFF";
