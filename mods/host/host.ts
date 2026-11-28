import { Database } from "bun:sqlite";
import { chmodSync, closeSync, mkdirSync, openSync, realpathSync, writeSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";
import type {
  EventName, Filter, Handle, HooksFile, HostSessionRow, Json, ModError, ModLifecycle, ModManifest, ModStart, ModStop, On, Register,
} from "../contract/index.ts";

const LINE_BYTES = 256 * 1024;
const VALUE_BYTES = 64 * 1024;
const STORE_BYTES = 16 * 1024 * 1024;
const EVENT_MS = 5000;
const STOP_MS = 2000;
const SESSION_CALLS = 16;
const protocol = process.argv[2] === undefined ? null : Number(process.argv[2]);
const served = new Set<string>([
  "mod.start", "mod.stop", "session.start", "session.end", "turn.start", "request.send",
  "stream.event", "tool.call", "response.receive", "turn.complete",
] satisfies EventName[]);
const unserved = {
  "sessions.list": "sessions.list is not served yet.",
  "ui.render": "ui.render is not served yet.",
  "command.run": "command.run is not served yet.",
  "http.request": "http.request is not served yet.",
  "launch.prepare": "launch.prepare is not served yet; Splice does not run mod code while a session launches.",
} satisfies Partial<Record<EventName, string>>;

type Payload = Readonly<Record<string, unknown>>;
type Next = (event: unknown) => Promise<void>;
type Handler = ($: Handle, event: Payload, next: Next) => unknown;
type Binding = { apply: Handler; filter?: Filter<"tool.call">; recover?: Handler };

function object(value: unknown): value is Payload {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function diagnostic(error: unknown): ModError {
  return error instanceof Error
    ? { message: error.message.slice(0, 2048), name: error.name, stack: error.stack?.slice(0, 4096) }
    : { message: String(error).slice(0, 2048) };
}

function frozen<T>(value: T): T {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) frozen(child);
  }
  return value;
}

/**
 * A mod runs only code from its own folder. Splice hashes the installed copy, and nothing beside it is hashed, so an
 * import that resolves elsewhere (an absolute path, a link, a tsconfig alias, a node_modules folder above the mod)
 * would change what runs without changing what was checked. Bun's built-in modules (bun:, node:) are not files and
 * stay available. The contract is imported as types, which Bun erases before resolving anything.
 *
 * Bun asks `onResolve` only about relative and absolute specifiers, so that hook names the importer and the line
 * that reached outside. `onLoad` is told about every file Bun is about to read, after links and aliases are
 * followed, so it is the check that holds; it fires only for a path outside the root.
 */
function confineImports(name: string, root: string) {
  const prefix = root + sep;
  const escaped = prefix.replace(/[.*+?^${}()|[\]\\]/g, match => "\\" + match);
  const advice = "A mod may import only files inside its own folder and Bun's built-in modules (bun: and node:); " +
    "put a dependency inside the mod, for example in its node_modules.";
  Bun.plugin({
    name: "splice-mod-root",
    setup(build) {
      build.onResolve({ filter: /^(?:\.\.?\/|\/)/ }, args => {
        const importer = args.importer.startsWith(prefix) ? args.importer : join(root, "hooks");
        const target = resolve(dirname(importer), args.path);
        if (target === root || target.startsWith(prefix)) return undefined;
        throw new Error(`Mod ${name}: ${relative(root, importer)} imports ${args.path}, which is outside the mod's folder. ${advice}`);
      });
      const outside = new RegExp("^(?!" + escaped + ")");
      build.onLoad({ filter: outside, namespace: "file" }, args => {
        throw new Error(`Mod ${name}: ${args.path} is outside the mod's folder, so it was not loaded. ` +
          `An import reached it through a link, a tsconfig alias, a node_modules folder above the mod or a file: URL. ${advice}`);
      });
    },
  });
}

class Pipe {
  readonly #calls = new Map<number, ReturnType<typeof Promise.withResolvers<readonly HostSessionRow[]>>>();
  #sequence = 0;
  #closed = false;

  send(message: Payload): boolean {
    const bytes = Buffer.from(JSON.stringify(message) + "\n");
    if (bytes.length > LINE_BYTES + 1) return false;
    try {
      let sent = 0;
      while (sent < bytes.length) sent += writeSync(1, bytes, sent, bytes.length - sent);
      return true;
    } catch {
      return false; // The daemon closing its other end ends this pipe, not the mod's stop hook.
    }
  }

  sessions(): Promise<readonly HostSessionRow[]> {
    if (this.#closed) return Promise.reject(new Error("The Splice mod pipe is closed."));
    if (this.#calls.size >= SESSION_CALLS) return Promise.reject(new Error("Too many pending session requests."));
    const id = ++this.#sequence;
    const call = Promise.withResolvers<readonly HostSessionRow[]>();
    this.#calls.set(id, call);
    if (!this.send({ type: "call", method: "sessions.list", id })) {
      this.#calls.delete(id);
      call.reject(new Error("Could not reach Splice's session registry."));
    }
    return call.promise;
  }

  reply(message: Payload) {
    if (typeof message.id !== "number") return;
    const call = this.#calls.get(message.id);
    if (!call) return;
    this.#calls.delete(message.id);
    if (Array.isArray(message.rows)) call.resolve(frozen(message.rows) as readonly HostSessionRow[]);
    else call.reject(new Error(typeof message.error === "string" ? message.error : "Splice did not return sessions."));
  }

  close() {
    this.#closed = true;
    for (const call of this.#calls.values()) call.reject(new Error("The Splice mod pipe is closed."));
    this.#calls.clear();
  }

  async read(receive: (message: Payload) => void) {
    const buffer = new Uint8Array(LINE_BYTES);
    let used = 0;
    const input = (protocol === null ? Bun.stdin : Bun.file(protocol)).stream();
    for await (const chunk of input) {
      let offset = 0;
      while (offset < chunk.length) {
        const newline = chunk.indexOf(10, offset);
        const end = newline === -1 ? chunk.length : newline;
        const length = end - offset;
        if (used + length <= buffer.length) buffer.set(chunk.subarray(offset, end), used);
        used = Math.min(buffer.length + 1, used + length);
        if (newline !== -1) {
          this.#readLine(buffer, used, receive);
          used = 0;
        }
        offset = end + 1;
      }
    }
    if (used) this.#readLine(buffer, used, receive);
  }

  #readLine(buffer: Uint8Array, used: number, receive: (message: Payload) => void) {
    const message = used <= buffer.length ? this.#decode(buffer.subarray(0, used)) : null;
    if (message) receive(message);
    else this.send({ type: "dropped", count: 1 });
  }

  #decode(bytes: Uint8Array): Payload | null {
    try {
      const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
      return object(value) ? value : null;
    } catch {
      return null;
    }
  }
}

type StoreHandle = Handle["store"];

class Store implements StoreHandle {
  readonly #db: Database;

  constructor(directory: string) {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    const file = join(directory, "store.sqlite");
    closeSync(openSync(file, "a", 0o600));
    chmodSync(file, 0o600);
    this.#db = new Database(file);
    const page = this.#db.query<{ page_size: number }, []>("PRAGMA page_size").get();
    if (!page) throw new Error("Could not read the mod store page size.");
    this.#db.exec(`PRAGMA max_page_count = ${Math.floor(STORE_BYTES / page.page_size)};
      CREATE TABLE IF NOT EXISTS values_by_key (key TEXT PRIMARY KEY, value TEXT NOT NULL);`);
  }

  async get(key: string): Promise<Json | undefined> {
    const row = this.#db.query<{ value: string }, [string]>("SELECT value FROM values_by_key WHERE key = ?").get(key);
    return row ? JSON.parse(row.value) as Json : undefined;
  }

  async set(key: string, value: Json): Promise<void> {
    const encoded = JSON.stringify(value);
    if (encoded === undefined || Buffer.byteLength(encoded) > VALUE_BYTES) {
      throw new Error("The mod store accepts JSON values of at most 64 KiB.");
    }
    try {
      this.#db.query("INSERT OR REPLACE INTO values_by_key VALUES (?, ?)").run(key, encoded);
    } catch (error) {
      if (object(error) && error.code === "SQLITE_FULL") throw new Error("The mod store is full (16 MiB).");
      throw error;
    }
  }

  async delete(key: string): Promise<void> {
    this.#db.query("DELETE FROM values_by_key WHERE key = ?").run(key);
  }

  async list(prefix = ""): Promise<readonly string[]> {
    const keys = this.#db.query<{ key: string }, []>("SELECT key FROM values_by_key ORDER BY key").all();
    return keys.map(row => row.key).filter(key => key.startsWith(prefix));
  }

  close() { this.#db.close(); }
}

class Hooks {
  readonly #byEvent = new Map<EventName, Binding[]>();
  readonly #warned = new Set<string>();
  readonly refused = new Set<string>();
  readonly on: On = ((event: EventName, filterOrHandler: unknown, handler?: Handler) => {
    const apply = (typeof filterOrHandler === "function" ? filterOrHandler : handler) as Handler;
    const filter = object(filterOrHandler) ? filterOrHandler as Filter<"tool.call"> : undefined;
    const binding = { apply, filter } as Binding;
    if (served.has(event)) {
      const bindings = this.#byEvent.get(event) ?? [];
      bindings.push(binding);
      this.#byEvent.set(event, bindings);
    } else {
      const reason = Object.hasOwn(unserved, event) ? unserved[event as keyof typeof unserved] : `${event} is not served yet.`;
      this.refused.add(`Mod ${this.name}: ${reason}`);
    }
    return { catch(recover: Handler) { binding.recover = recover; return this; } };
  }) as On;

  constructor(private readonly name: string, private readonly pipe: Pipe, private readonly handle: Handle) {}

  get events(): EventName[] { return Array.from(this.#byEvent.keys()); }

  async dispatch(name: string, payload: Payload, maxMs: number, abort?: AbortSignal) {
    const handlers = (this.#byEvent.get(name as EventName) ?? []).filter(binding => this.#matches(name, payload, binding.filter));
    if (abort?.aborted) return false;
    if (handlers.length === 0) return true;
    const event = frozen(payload);
    const finished = Promise.withResolvers<boolean>();
    let active = true;
    const abandon = () => { active = false; finished.resolve(false); };
    const timer = setTimeout(() => {
      this.pipe.send({ type: "timeout", event: name, message: `handler exceeded ${maxMs / 1000} s; abandoned the event` });
      active = false;
      finished.resolve(true);
    }, maxMs);
    abort?.addEventListener("abort", abandon, { once: true });
    const visit = async (index: number): Promise<void> => {
      const binding = handlers[index];
      if (!active || !binding) return;
      let rest: Promise<void> | undefined;
      const follow = () => rest ??= Promise.resolve().then(() => visit(index + 1));
      const invoke = async (handler: Handler, argument: Payload) => {
        const value = await handler(this.handle, argument, async changed => {
          if (!active) return;
          if (changed !== argument) this.#unchanged(name);
          await follow();
        });
        if (active && value !== undefined) this.#unchanged(name);
      };
      try {
        await invoke(binding.apply, event);
      } catch (error) {
        if (active) {
          const info = this.#error(name, error);
          if (binding.recover) {
            try { await invoke(binding.recover, frozen({ ...event, error: info })); }
            catch (caught) { if (active) this.#error(name, caught); }
          }
        }
      }
      if (active) await follow();
    };
    try {
      return await Promise.race([visit(0).then(() => true), finished.promise]);
    } finally {
      active = false;
      clearTimeout(timer);
      abort?.removeEventListener("abort", abandon);
    }
  }

  #matches(name: string, payload: Payload, filter?: Filter<"tool.call">): boolean {
    return name !== "tool.call" || !filter ||
      ((filter.server === undefined || filter.server === payload.server) &&
       (filter.tool === undefined || filter.tool === payload.tool));
  }

  #unchanged(event: string) {
    if (this.#warned.has(event)) return;
    this.#warned.add(event);
    this.handle.log(`A handler for ${event} passed a changed event to next() or returned a value; ` +
      "in this version of Splice that has no effect on the turn.");
  }

  #error(event: string, error: unknown): ModError {
    const info = diagnostic(error);
    this.pipe.send({ type: "error", event, message: info.message });
    return info;
  }
}

class Host {
  readonly #pipe = new Pipe();
  readonly #stop = Promise.withResolvers<string>();
  readonly #admission = Promise.withResolvers<boolean>();
  readonly #started = Promise.withResolvers<void>();
  readonly #abort = new AbortController();
  #hooks?: Hooks;
  #eventPending = false;

  constructor(private readonly name: string, private readonly root: string, private readonly data: string) {}

  async run(): Promise<number> {
    // Protocol stdout is reserved; an author's console output belongs in the mod's stderr log.
    console.log = console.info = console.debug = console.error.bind(console);
    void this.#pipe.read(message => this.#receive(message)).catch(error => {
      this.#pipe.send({ type: "error", event: "mod pipe", message: diagnostic(error).message });
    }).finally(() => {
      this.#pipe.close();
      this.#shutdown("daemon stop");
    });
    // The owner writes our pid and birth before releasing us to run installed code.
    if (!(await this.#admission.promise)) return 0;
    let store: Store | undefined;
    try {
      const root = realpathSync(this.root); // Bun reports real paths, and a linked mod's folder may be a link.
      confineImports(this.name, root);
      const manifest = await Bun.file(join(root, ".splice-mod/mod.json")).json() as ModManifest;
      store = new Store(this.data);
      const hooks = new Hooks(this.name, this.#pipe, this.#handle(store));
      await this.#load(hooks.on, root);
      if (this.#abort.signal.aborted) return 0;
      this.#hooks = hooks;
      const notice = `Mod ${this.name}: handlers observe only in this version of Splice.`;
      const refused = [...hooks.refused, ...["$.ui", "$.sessions.invalidate", "$.ui.respond"]
        .map(member => this.#notServed(member).message)];
      const declared = this.#declarations(manifest);
      this.#pipe.send({
        type: "ready", events: hooks.events, notice, refused, declared_not_served: declared, version: Bun.version,
      });
      const lifecycle = { mod: this.name, version: manifest.version } satisfies ModLifecycle;
      const start = { ...lifecycle, served: hooks.events, refused: [...refused, ...declared] } satisfies ModStart;
      await hooks.dispatch("mod.start", start, EVENT_MS, this.#abort.signal);
      this.#started.resolve();
      const reason = await this.#stop.promise;
      await hooks.dispatch("mod.stop", { ...lifecycle, reason } satisfies ModStop, STOP_MS);
      return 0;
    } catch (error) {
      this.#pipe.send({ type: "load-failed", why: diagnostic(error).message });
      return 1;
    } finally {
      store?.close();
    }
  }

  async #load(on: On, root: string) {
    const directory = resolve(root, "hooks");
    const file = Bun.file(join(directory, "hooks.json"));
    if (!await file.exists()) return;
    const { modules } = await file.json() as HooksFile;
    for (const specifier of modules) {
      const module = resolve(directory, specifier);
      if (!module.startsWith(directory + sep)) throw new Error(`${specifier} is outside hooks/.`);
      const { register } = await import(pathToFileURL(module).href) as { register: Register };
      if (typeof register !== "function") throw new Error(`${specifier} does not export register(on).`);
      await register(on);
    }
  }

  #receive(message: Payload) {
    if (message.type === "start") this.#admission.resolve(true);
    else if (message.type === "reply") this.#pipe.reply(message);
    else if (message.type === "stop") this.#shutdown(typeof message.reason === "string" ? message.reason : "daemon stop");
    else if (message.type === "event" && typeof message.name === "string" && object(message.payload) && !this.#eventPending) {
      this.#eventPending = true;
      void this.#event(message.name, message.payload);
    } else {
      this.#pipe.send({ type: "dropped", count: 1 });
    }
  }

  async #event(name: string, payload: Payload) {
    let handled = false;
    try {
      await this.#started.promise;
      handled = name === "mod.start" || name === "mod.stop" ||
        await this.#hooks?.dispatch(name, payload, EVENT_MS, this.#abort.signal) === true;
    } catch (error) {
      this.#pipe.send({ type: "error", event: name, message: diagnostic(error).message });
    } finally {
      this.#eventPending = false;
      this.#pipe.send({ type: handled ? "handled" : "aborted" });
    }
  }

  #shutdown(reason: string) {
    this.#admission.resolve(false);
    this.#stop.resolve(reason);
    this.#abort.abort();
  }

  #handle(store: Store): Handle {
    const handle: Handle = {
      store,
      clock: {
        now: Date.now,
        after: (ms, fn) => {
          const timer = setTimeout(() => {
            void Promise.resolve().then(fn).catch(error => {
              this.#pipe.send({ type: "error", event: "$.clock.after", message: diagnostic(error).message });
            });
          }, ms);
          return () => clearTimeout(timer);
        },
      },
      log: message => {
        if (!this.#pipe.send({ type: "log", message })) this.#pipe.send({ type: "log-dropped", count: 1 });
      },
      sessions: {
        list: () => this.#pipe.sessions(),
        invalidate: () => { throw this.#notServed("$.sessions.invalidate"); },
      },
      ui: { respond: () => { throw this.#notServed("$.ui.respond"); } },
    };
    return frozen(handle);
  }

  #notServed(item: string): Error { return new Error(`Mod ${this.name}: ${item} is not served yet.`); }

  #declarations(manifest: ModManifest): string[] {
    return [
      ...(manifest.pages ?? []).map(page => `pages: ${page.name}`),
      ...(manifest.commands ?? []).map(command => `commands: ${command.name}`),
      ...(manifest.tools ?? []).map(tool => `tools: ${tool}`),
      ...(manifest.views ?? []).map(view => `views: ${view.uri}`),
    ].map(item => `Mod ${this.name}: ${item} is declared but not served yet.`);
  }
}

async function main() {
  try {
    const { SPLICE_MOD_NAME: name, SPLICE_MOD_ROOT: root, SPLICE_MOD_DATA: data } = process.env;
    if (!name || !root || !data) throw new Error("The mod host must be started by Splice.");
    if (protocol !== null) void Bun.stdin.arrayBuffer().then(stopOwnGroup, stopOwnGroup);
    return await new Host(name, root, data).run();
  } catch (error) {
    new Pipe().send({ type: "load-failed", why: diagnostic(error).message });
    return 1;
  }
}

let cleanup: Promise<void> | undefined;
function stopOwnGroup(): Promise<void> {
  // In a guarded run this live host leads its own group. Keep that ID pinned
  // until the last signal, even if the guardian was killed and cannot reap us.
  return cleanup ??= (async () => {
    process.on("SIGTERM", () => {});
    process.kill(-process.pid, "SIGTERM");
    await Bun.sleep(250);
    process.kill(-process.pid, "SIGKILL");
  })();
}

const code = await main();
// Finish group cleanup even if the lifeline callback has not run yet.
if (protocol !== null) await stopOwnGroup();
process.exit(code);
