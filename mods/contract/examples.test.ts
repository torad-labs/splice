// Every JSON example of the mod contract, and every JSON file of the example mod, is checked against
// its declaration in index.ts by the TypeScript compiler: each file becomes `const x: T = <json>;`,
// so a misspelled field, a missing one or a value outside its set is a type error and fails here.
import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import ts from "typescript";

const here = import.meta.dir;
const contract = join(here, "index.ts");

/** Each JSON file and the declaration it must satisfy. */
const cases: ReadonlyArray<readonly [file: string, type: string]> = [
  [join(here, "examples/mod.json"), "ModManifest"],
  [join(here, "examples/hooks.json"), "HooksFile"],
  [join(here, "examples/session-row.json"), "SessionRow"],
  [join(here, "examples/mod-row.json"), "SessionRow"],
  [join(here, "examples/ui-render-request.json"), "UiRenderRequest"],
  [join(here, "examples/ui-render-answer.json"), "UiRenderAnswer"],
  [join(here, "examples/tool-definition.json"), "ToolDefinition"],
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
    expect(errors("ModManifest", manifest.replace("ui://standup/board", "https://standup/board")).length).toBeGreaterThan(0);
  });
});
