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

test("the production Sessions probe derives the optional closed account_state slot from the API type", () => {
  const root = findRepoRoot(import.meta.dir);
  const configPath = join(root, "console-next/tsconfig.json");
  const config = ts.readConfigFile(configPath, ts.sys.readFile);
  if (config.error !== undefined) throw new Error("console config must be readable");
  const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, join(root, "console-next"));
  const program = ts.createProgram(parsed.fileNames, parsed.options);
  const checker = program.getTypeChecker();
  const site = enumerate(program).calls.find(call => call.typeText === "SessionsPayload");
  if (site === undefined) throw new Error("the real Sessions client read must be enumerated");
  const sessions = checker.getPropertyOfType(site.type, "sessions");
  if (sessions === undefined) throw new Error("SessionsPayload must carry session rows");
  const row = checker.getIndexTypeOfType(checker.getTypeOfSymbol(sessions), ts.IndexKind.Number);
  if (row === undefined) throw new Error("the Sessions payload must carry an array");
  const state = checker.getPropertyOfType(row, "account_state");
  if (state === undefined) throw new Error("the probe must derive account_state from the production API type");
  expect(state.flags & ts.SymbolFlags.Optional).not.toBe(0);
  const type = checker.getTypeOfSymbol(state);
  const states = type.isUnion() ? type.types.filter(member => member.isStringLiteral()).map(member => (member as ts.StringLiteralType).value) : [];
  expect(states.sort()).toEqual(["history_limited", "known", "none"]);
}, 30_000);

test("the production Accounts probe derives the native-only closed profile_state slot from the API type", () => {
  const root = findRepoRoot(import.meta.dir);
  const configPath = join(root, "console-next/tsconfig.json");
  const config = ts.readConfigFile(configPath, ts.sys.readFile);
  if (config.error !== undefined) throw new Error("console config must be readable");
  const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, join(root, "console-next"));
  const program = ts.createProgram(parsed.fileNames, parsed.options);
  const checker = program.getTypeChecker();
  const site = enumerate(program).calls.find(call => call.typeText === "AccountsWire");
  if (site === undefined) throw new Error("the real Accounts client read must be enumerated");
  const accounts = checker.getPropertyOfType(site.type, "accounts");
  if (accounts === undefined) throw new Error("AccountsWire must carry account rows");
  const row = checker.getIndexTypeOfType(checker.getTypeOfSymbol(accounts), ts.IndexKind.Number);
  if (row === undefined) throw new Error("the Accounts payload must carry an array");
  const state = checker.getPropertyOfType(row, "profile_state");
  if (state === undefined) throw new Error("the probe must derive profile_state from the production API type");
  expect(state.flags & ts.SymbolFlags.Optional).not.toBe(0);
  const type = checker.getTypeOfSymbol(state);
  const states = type.isUnion() ? type.types.filter(member => member.isStringLiteral()).map(member => (member as ts.StringLiteralType).value) : [];
  expect(states.sort()).toEqual(["pending", "refused", "verified"]);
}, 30_000);

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
