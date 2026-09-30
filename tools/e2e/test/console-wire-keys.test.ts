import { beforeAll, expect, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { findRepoRoot } from "../../gate/src/lib/repo.ts";
import ts from "typescript";
import { enumerate } from "../probes/console-wire-keys.ts";

// The scanner resolves declaration identity, not transport implementation. A minimal virtual
// project retains the real declaration paths and import binding without loading React or lib types.
// The separate replay below still exercises the actual production project from an unrelated cwd.
const api = join(findRepoRoot(import.meta.dir), "console-next/src/api");
const path = join(api, "wire-probe-fixture.ts");
const options: ts.CompilerOptions = {
  module: ts.ModuleKind.ESNext,
  moduleResolution: ts.ModuleResolutionKind.Bundler,
  target: ts.ScriptTarget.ESNext,
  strict: true,
  noLib: true,
  types: [],
};
let host: ts.CompilerHost;
let fixture = "";
beforeAll(() => {
  const declarations = new Map([
    [join(api, "queries.ts"), "export declare function read<T>(key: unknown, path: string): T;"],
    [join(api, "client.ts"), "export declare function request<T>(path: string): T; export declare function health<T>(): T;"],
  ]);
  const parsed = new Map([...declarations].map(([file, text]) =>
    [file, ts.createSourceFile(file, text, options.target!, true)]));
  host = ts.createCompilerHost(options);
  host.fileExists = (file) => file === path || declarations.has(file);
  host.readFile = (file) => file === path ? fixture : declarations.get(file);
  host.getSourceFile = (file, version) => file === path
    ? ts.createSourceFile(file, fixture, version, true)
    : parsed.get(file);
  host.resolveModuleNames = (names) => names.map((name) => {
    const file = join(api, name + ".ts");
    return declarations.has(file) ? { resolvedFileName: file } : undefined;
  });
});

// Minimal binding cases normally take milliseconds; allow loaded CI scheduling without relying
// on Bun's implicit five-second deadline. The full production replay has its own larger deadline.
const SCAN_TIMEOUT_MS = 10_000;

function scan(source: string) {
  fixture = source;
  return enumerate(ts.createProgram([path], options, host)).calls
    .filter((call) => call.file === "api/wire-probe-fixture.ts");
}

const imports = `
import { read, read as aliased } from "./queries";
import * as queries from "./queries";
import { request, health } from "./client";
`;

test("the replacement probe discovers direct, aliased and namespace reads with concrete response types", () => {
  const calls = scan(`${imports}
read<{ direct: boolean }>([], "/api/direct");
aliased<{ aliased: boolean }>([], "/api/aliased");
queries.read<{ namespaced: boolean }>([], "/api/namespaced");
request<{ requested: boolean }>("/api/requested");
health<{ healthy: boolean }>();
`);
  expect(calls.map((call) => call.templates?.[0]?.parts)).toEqual([
    ["/api/direct"], ["/api/aliased"], ["/api/namespaced"], ["/api/requested"], ["/health"],
  ]);
  expect(calls.map((call) => call.typeText)).toEqual([
    "{ direct: boolean }", "{ aliased: boolean }", "{ namespaced: boolean }",
    "{ requested: boolean }", "{ healthy: boolean }",
  ]);
}, SCAN_TIMEOUT_MS);

test("a transport call without an explicit response type fails instead of disappearing from the denominator", () => {
  for (const call of ['read([], "/api/untyped")', 'aliased([], "/api/untyped")',
    'queries.read([], "/api/untyped")', 'request("/api/untyped")', "health()"]) {
    expect(() => scan(`${imports}\n${call};`)).toThrow("client/query call has no explicit response type");
  }
}, SCAN_TIMEOUT_MS);

test("a typed transport call without a path fails instead of disappearing from the denominator", () => {
  for (const call of ["read<{ ok: boolean }>([])", "request<{ ok: boolean }>()"]) {
    expect(() => scan(`${imports}\n${call};`)).toThrow("client/query call has no path");
  }
}, SCAN_TIMEOUT_MS);

test("the relocated console probe discovers its sources from an unrelated cwd", () => {
  const replay = mkdtempSync(join(tmpdir(), "console-wire-keys-replay-"));
  try {
    const probe = join(findRepoRoot(import.meta.dir), "tools/e2e/probes/console-wire-keys.ts");
    const child = Bun.spawnSync([process.execPath, probe, "--replay", replay], {
      cwd: replay,
      stdio: ["ignore", "pipe", "pipe"],
    });
    // An empty replay must reach the payload checks, then fail for missing captures.
    expect(child.exitCode).toBe(1);
    expect(child.stdout.toString()).toContain("console-wire-keys: FAIL");
    expect(child.stdout.toString()).toContain("InstructionFilePreview '/api/topology/preview': no captured payload");
    expect(child.stdout.toString()).not.toMatch(/EXCLUDED [^\n]*InstructionFilePreview/);
    expect(child.stderr.toString()).not.toContain("Cannot read file");
  } finally {
    rmSync(replay, { recursive: true, force: true });
  }
}, 30_000);
