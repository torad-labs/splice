// Every JSON example of the mod contract, and every JSON file of the example mod, is checked against
// its declaration in index.ts by the TypeScript compiler: each file becomes `const x: T = <json>;`,
// so a misspelled field, a missing one or a value outside its set is a type error and fails here.
import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import ts from "typescript";
import type { EventName, Handler, Handle, HooksFile, Json, ModManifest, On, Register, SessionStart, ToolMeta } from "./index.ts";

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
  const source = `import type { ${type} } from "./index.ts";\nexport const x: ${type} = ${json};\n`;
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
    const on: On = (event: EventName, handler: unknown) => {
      if (event === "session.start") start.push(handler as Handler<"session.start">);
      if (event === "sessions.list") list.push(handler as Handler<"sessions.list">);
      return { catch() { return this; } };
    };
    for (const module of modules) {
      const { register } = await import(module) as { register: Register };
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
