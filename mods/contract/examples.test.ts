// Every JSON example of the mod contract, and every JSON file of the example mod, is checked against
// its declaration in index.ts by the TypeScript compiler: each file becomes `const x: T = <json>;`,
// so a misspelled field, a missing one or a value outside its set is a type error and fails here.
import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import ts from "typescript";
import type {
  EventName, Handler, Handle, HooksFile, Json, ModManifest, ModStart, On, Register, SessionStart, ToolMeta, TurnComplete,
} from "./index.ts";

const here = import.meta.dir;
const contract = join(here, "index.ts");
const mod = join(here, "..", "example");

/** Each JSON file and the declaration it must satisfy. */
const cases: ReadonlyArray<readonly [file: string, type: string]> = [
  [join(here, "examples/mod.json"), "ModManifest"],
  [join(here, "examples/hooks.json"), "HooksFile"],
  [join(here, "examples/mod-catalog.json"), "ModCatalogResponse"],
  [join(here, "examples/sessions-list-response.json"), "SessionsListResponse"],
  [join(here, "examples/session-row.json"), "HostSessionRow"],
  [join(here, "examples/mod-row.json"), "ModSessionRow"],
  [join(here, "examples/mod-start.json"), "ModStart"],
  [join(here, "examples/mod-stop.json"), "ModStop"],
  [join(here, "examples/turn-start.json"), "TurnStart"],
  [join(here, "examples/request-send.json"), "RequestSend"],
  [join(here, "examples/response-receive.json"), "ResponseReceive"],
  [join(here, "examples/stream-event.json"), "StreamEvent"],
  [join(here, "examples/tool-call.json"), "ToolCall"],
  [join(here, "examples/turn-complete.json"), "TurnComplete"],
  [join(here, "examples/session-start.json"), "SessionStart"],
  [join(here, "examples/session-end.json"), "SessionEnd"],
  [join(here, "examples/ui-render-request.json"), "UiRenderRequest"],
  [join(here, "examples/ui-render-row-request.json"), "UiRenderRequest"],
  [join(here, "examples/ui-render-view-request.json"), "UiRenderRequest"],
  [join(here, "examples/ui-render-answer.json"), "UiRenderAnswer"],
  [join(here, "examples/tool-definition.json"), "ToolDefinition"],
  [join(here, "examples/tool-result.json"), "ToolResult"],
  [join(mod, ".splice-mod/mod.json"), "ModManifest"],
  [join(mod, "hooks/hooks.json"), "HooksFile"],
  [join(mod, "tools/resolve.json"), "ToolInputSchema"],
  [join(mod, "tools/resolve.meta.json"), "ToolMeta"],
  [join(mod, "tools/board.json"), "ToolInputSchema"],
];

/** Type errors of `const x: <type> = <json>;` compiled beside the contract. */
function errors(type: string, json: string): string[] {
  const probe = join(here, "__probe__.ts");
  const source = `import type * as Contract from "./index.ts";\nexport const x: Contract.${type} = ${json};\n`;
  const options: ts.CompilerOptions = {
    strict: true,
    noEmit: true,
    target: ts.ScriptTarget.ESNext,
    module: ts.ModuleKind.Preserve,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
    allowImportingTsExtensions: true,
    types: [],
    skipLibCheck: true,
  };
  const host = ts.createCompilerHost(options);
  const read = host.getSourceFile.bind(host);
  host.getSourceFile = (name, version, ...rest) =>
    name === probe ? ts.createSourceFile(name, source, version) : read(name, version, ...rest);
  const program = ts.createProgram([probe, contract], options, host);
  return ts
    .getPreEmitDiagnostics(program)
    .map((d) => ts.flattenDiagnosticMessageText(d.messageText, "\n"));
}

describe("every example satisfies its declaration", () => {
  for (const [file, type] of cases) {
    test(`${file.slice(here.length - "contract".length)} is a ${type}`, () => {
      expect(errors(type, readFileSync(file, "utf8"))).toEqual([]);
    });
  }
});

describe("a wrong field in an example is a type error", () => {
  const row = readFileSync(join(here, "examples/mod-row.json"), "utf8");
  test("a status outside the shared set", () => {
    expect(errors("SessionRow", row.replace('"waiting"', '"blocked"')).length).toBeGreaterThan(0);
  });
  test("a misspelled field", () => {
    expect(errors("SessionRow", row.replace('"needs"', '"need"')).length).toBeGreaterThan(0);
  });
  test("a view uri that is not ui://", () => {
    const manifest = readFileSync(join(here, "examples/mod.json"), "utf8");
    expect(errors("ModManifest", manifest.replace("ui://standups/board", "https://standup/board")).length).toBeGreaterThan(0);
  });
});

function readJson<T>(file: string): T {
  return JSON.parse(readFileSync(file, "utf8")) as T;
}

/** Every object under a `labels` key, wherever it sits in a JSON value. */
function labelSets(value: unknown, found: Record<string, unknown>[] = []): Record<string, unknown>[] {
  if (Array.isArray(value)) for (const item of value) labelSets(item, found);
  else if (value !== null && typeof value === "object") {
    for (const [key, child] of Object.entries(value)) {
      if (key === "labels" && child !== null && typeof child === "object" && !Array.isArray(child)) {
        found.push(child as Record<string, unknown>);
      }
      labelSets(child, found);
    }
  }
  return found;
}

describe("labels in every example follow the label rules", () => {
  // The rules are on `Labels` in index.ts: a lowercased key of 1-32 characters, a value of at most 256 bytes
  // with no control characters, at most 16 labels.
  for (const [file] of cases) {
    const sets = labelSets(readJson(file));
    if (sets.length === 0) continue;
    test(`${file.slice(here.length - "contract".length)}`, () => {
      for (const labels of sets) {
        expect(Object.keys(labels).length).toBeLessThanOrEqual(16);
        for (const [key, value] of Object.entries(labels)) {
          expect(key).toMatch(/^[a-z][a-z0-9_]{0,31}$/);
          expect(typeof value).toBe("string");
          expect(Buffer.byteLength(value as string)).toBeLessThanOrEqual(256);
          expect(value as string).not.toMatch(/\p{Cc}/u);
        }
      }
    });
  }
  test("a label key in upper case is caught", () => {
    expect(Object.keys({ TICKET: "42" })[0]).not.toMatch(/^[a-z][a-z0-9_]{0,31}$/);
  });
});

describe("the served examples say what the runner sends", () => {
  test("a turn's input includes what was read from and written to the cache", () => {
    const usage = readJson<TurnComplete>(join(here, "examples/turn-complete.json")).usage;
    expect(usage.cacheRead + usage.cacheWrite).toBeGreaterThan(0);
    expect(usage.input).toBeGreaterThanOrEqual(usage.cacheRead + usage.cacheWrite);
  });

  test("mod.start names itself among what is served, and each refusal begins with the mod's name", () => {
    const start = readJson<ModStart>(join(here, "examples/mod-start.json"));
    expect(start.served).toContain("mod.start");
    expect(start.refused.length).toBeGreaterThan(0);
    for (const refusal of start.refused) expect(refusal.startsWith(`Mod ${start.mod}: `)).toBe(true);
  });
});

describe("the example mod", () => {
  const manifest = readJson<ModManifest>(join(mod, ".splice-mod/mod.json"));
  const hooks = readJson<HooksFile>(join(mod, "hooks/hooks.json"));
  const modules = hooks.modules.map((module) => join(mod, "hooks", module));

  test("the declared hook modules exist and compile against the contract", () => {
    expect(modules.length).toBeGreaterThan(0);
    for (const module of modules) expect(statSync(module).isFile()).toBe(true);
    const options: ts.CompilerOptions = {
      strict: true,
      noEmit: true,
      target: ts.ScriptTarget.ESNext,
      module: ts.ModuleKind.Preserve,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      allowImportingTsExtensions: true,
      types: [],
      lib: ["lib.esnext.d.ts"],
      skipLibCheck: true,
    };
    const program = ts.createProgram(modules, options);
    const found = ts.getPreEmitDiagnostics(program).map((d) => ts.flattenDiagnosticMessageText(d.messageText, "\n"));
    expect(found).toEqual([]);
  });

  test("every tool it declares has its schema and description beside the manifest", () => {
    for (const tool of manifest.tools ?? []) {
      expect(readFileSync(join(mod, `tools/${tool}.json`), "utf8").length).toBeGreaterThan(0);
      expect(readFileSync(join(mod, `tools/${tool}.md`), "utf8").trim().length).toBeGreaterThan(0);
    }
  });

  test("the review row's actions use declared app-only tools on the example's own server", async () => {
    const start: Handler<"session.start">[] = [];
    const list: Handler<"sessions.list">[] = [];
    // This test drives just the two hooks involved in producing a review row, not the runner.
    const on: On<"full"> = (event: EventName, handler: unknown) => {
      if (event === "session.start") start.push(handler as Handler<"session.start">);
      if (event === "sessions.list") list.push(handler as Handler<"sessions.list">);
      return { catch() { return this; } };
    };
    for (const module of modules) {
      const { register } = await import(module) as { register: Register<"full"> };
      register(on);
    }

    const store = new Map<string, Json>();
    const handle: Handle = {
      store: {
        get: async (key) => store.get(key),
        set: async (key, value) => { store.set(key, value); },
        delete: async (key) => { store.delete(key); },
        list: async (prefix = "") => [...store.keys()].filter((key) => key.startsWith(prefix)),
      },
      sessions: { list: async () => [], invalidate: () => {} },
      clock: { now: Date.now, after: () => { throw new Error("this example does not schedule work"); } },
      log: () => {},
      ui: { respond: () => { throw new Error("this test does not call the board tool"); } },
    };
    const session = readJson<SessionStart>(join(here, "examples/session-start.json"));
    const startChain = start.reduceRight<Parameters<Handler<"session.start">>[2]>(
      (next, handler) => (event) => handler(handle, event, next), async () => {},
    );
    const listChain = list.reduceRight<Parameters<Handler<"sessions.list">>[2]>(
      (next, handler) => (event) => handler(handle, event, next), async () => [],
    );
    await startChain(session);
    const rows = await listChain({ surface: "console", allRows: true });
    expect(rows).toHaveLength(1);
    const row = rows[0]!;
    expect(row.mod).toBe(manifest.name);
    expect(row.id.startsWith(`${manifest.name}:`)).toBe(true);
    const actions = row.actions ?? [];
    expect(actions.length).toBeGreaterThan(0);
    for (const action of actions) {
      expect(manifest.tools ?? []).toContain(action.tool);
      const file = join(mod, `tools/${action.tool}.meta.json`);
      const meta = existsSync(file) ? readJson<ToolMeta>(file) : undefined;
      expect(meta?.ui?.visibility ?? ["model", "app"]).toEqual(["app"]);
    }
  });
});
