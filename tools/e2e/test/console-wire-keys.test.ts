import { expect, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { findRepoRoot } from "../../gate/src/lib/repo.ts";
import ts from "typescript";
import { enumerate } from "../probes/console-wire-keys.ts";

// A virtual production file uses the real transports without writing into a peer-owned source tree.
function scan(source: string) {
  const path = join(findRepoRoot(import.meta.dir), "console-next/src/api/wire-probe-fixture.ts");
  const options: ts.CompilerOptions = {
    module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
    target: ts.ScriptTarget.ESNext,
    strict: true,
  };
  const host = ts.createCompilerHost(options);
  const getSourceFile = host.getSourceFile.bind(host);
  host.getSourceFile = (file, version, onError, fresh) => file === path
    ? ts.createSourceFile(file, source, version, true)
    : getSourceFile(file, version, onError, fresh);
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
});

test("a transport call without an explicit response type fails instead of disappearing from the denominator", () => {
  for (const call of ['read([], "/api/untyped")', 'aliased([], "/api/untyped")',
    'queries.read([], "/api/untyped")', 'request("/api/untyped")', "health()"]) {
    expect(() => scan(`${imports}\n${call};`)).toThrow("client/query call has no explicit response type");
  }
});

test("a typed transport call without a path fails instead of disappearing from the denominator", () => {
  for (const call of ["read<{ ok: boolean }>([])", "request<{ ok: boolean }>()"]) {
    expect(() => scan(`${imports}\n${call};`)).toThrow("client/query call has no path");
  }
});

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
