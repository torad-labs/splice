// tools/e2e/test/oracle.test.ts — the oracle verb's own net. Everything here runs without the
// daemon: the corpus integrity gate, the graders, the argv contract and the provenance arms.
// The live 11-scenario replay is the ladder's oracleReplay leg (tools/gate/config/ladder.json); this file
// is its oracleSelftest leg.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import {
  DIVERGENCE_EXIT,
  HARNESS_EXIT,
  ORACLE_DIR,
  canonicalize,
  corpusDrift,
  gradeUpstream,
  isSanctioned,
  jsonDiff,
  oracle,
  sanctionedFields,
  sanctionedScenarios,
} from "../src/commands/oracle.ts";

const CLI = resolve(import.meta.dir, "../index.ts");
const run = (...args: string[]) =>
  spawnSync(process.execPath, [CLI, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });

/** A scratch copy of the frozen corpus that a test may tamper with. */
function corpusCopy(): { dir: string; done: () => void } {
  const dir = mkdtempSync(join(tmpdir(), "oracle-test-"));
  cpSync(ORACLE_DIR, dir, { recursive: true });
  return { dir, done: () => rmSync(dir, { recursive: true, force: true }) };
}

describe("corpus integrity", () => {
  test("the committed corpus is intact (mock region and every fixture hash to the manifest)", () => {
    expect(corpusDrift(ORACLE_DIR)).toBeNull();
  });

  test("RED: one tampered fixture byte is FATAL and the replay exits 2 before touching a jar", async () => {
    const { dir, done } = corpusCopy();
    try {
      const f = join(dir, "basic.json");
      const fx = JSON.parse(readFileSync(f, "utf8")) as { expected_client_sse: string };
      fx.expected_client_sse += " ";
      writeFileSync(f, JSON.stringify(fx));
      expect(corpusDrift(dir)).toMatch(/^FATAL fixture tampered: basic\.json/);
      expect(await oracle(["replay", "--fixtures", dir, "--artifact", "/nonexistent/app-all.jar"])).toBe(HARNESS_EXIT);
    } finally {
      done();
    }
  });

  test("RED: an edited vendored mock region is FATAL mock drift", () => {
    const { dir, done } = corpusCopy();
    try {
      const f = join(dir, "mock-upstream.vendored.mjs");
      writeFileSync(f, readFileSync(f, "utf8").replace("import http from 'node:http';", "import http from 'node:http';\n// drift"));
      expect(corpusDrift(dir)).toMatch(/^FATAL mock drift/);
    } finally {
      done();
    }
  });

  test("a missing fat jar is a harness failure (exit 2) that names the producer, never a nested gradle", async () => {
    expect(await oracle(["replay", "--artifact", "/nonexistent/app-all.jar"])).toBe(HARNESS_EXIT);
  });
});

describe("graders", () => {
  test("canonicalize rewrites message ids and drops SSE keepalive comments only", () => {
    expect(canonicalize("id msg_17_2 and msg_9")).toBe("id msg_CANON and msg_CANON");
    expect(canonicalize("event: ping\n\n: ping\n\ndata: x\n\n")).toBe("event: ping\n\ndata: x\n\n");
  });

  test("jsonDiff reports absent keys, length mismatches and leaf changes by path", () => {
    expect(jsonDiff({ a: 1, b: [1, 2] }, { a: 2, b: [1] }, "r")).toEqual([
      { path: "r.a", exp: 1, obs: 2 },
      { path: "r.b.length", exp: 2, obs: 1 },
    ]);
    expect(jsonDiff({ a: 1 }, { a: 1, extra: true }, "r")).toEqual([{ path: "r.extra", exp: "<absent>", obs: true }]);
    expect(jsonDiff({ a: 1 }, { a: 1 })).toEqual([]);
  });

  const toml = `
[[divergence]]
field = "expected_upstream_requests[].prompt_cache_key"
status = "sanctioned"
expected_without_session_header = "fallback-key"

[[divergence]]
field = "expected_upstream_requests[].stream_options"
status = "sanctioned"
pinned_value = '{"include_obfuscation":false}'

[[divergence]]
field = "expected_upstream_requests[].wildcard"
status = "sanctioned"

[[divergence]]
field = "expected_upstream_requests[].store"
status = "kotlin-wrong"

[[scenario]]
name = "truncated"
status = "sanctioned"
pinned_sha256 = "abc"
pinned_upstream_sha256 = "def"

[[scenario]]
name = "basic"
status = "passing"
`;

  test("sanctionedFields keeps only sanctioned rows, with their runner-readable pins", () => {
    expect(sanctionedFields(toml)).toEqual([
      { field: "expected_upstream_requests[].prompt_cache_key", without: "fallback-key" },
      { field: "expected_upstream_requests[].stream_options", pinnedValue: '{"include_obfuscation":false}' },
      { field: "expected_upstream_requests[].wildcard" },
    ]);
    expect(sanctionedScenarios(toml)).toEqual({ truncated: { pinnedSha: "abc", pinnedUpstreamSha: "def" } });
  });

  test("a sanction is a pin, never a wildcard", () => {
    const s = sanctionedFields(toml);
    expect(isSanctioned({ path: "upstream[0].prompt_cache_key", exp: "x", obs: "fallback-key" }, s)).toBe(true);
    expect(isSanctioned({ path: "upstream[0].prompt_cache_key", exp: "x", obs: "other" }, s)).toBe(false);
    expect(isSanctioned({ path: "upstream[0].stream_options", exp: null, obs: { include_obfuscation: false } }, s)).toBe(true);
    expect(isSanctioned({ path: "upstream[0].stream_options", exp: null, obs: { include_obfuscation: true } }, s)).toBe(false);
    // no runner-readable pin => fail closed, whatever was observed
    expect(isSanctioned({ path: "upstream[0].wildcard", exp: 1, obs: 2 }, s)).toBe(false);
    // kotlin-wrong rows never sanction anything
    expect(isSanctioned({ path: "upstream[0].store", exp: false, obs: true }, s)).toBe(false);
  });

  test("gradeUpstream: count mismatch, unsanctioned diff, sanctioned diff", () => {
    const s = sanctionedFields(toml);
    let problems: string[] = [];
    gradeUpstream([{ a: 1 }], [], s, problems);
    expect(problems).toEqual(["upstream request count: expected 1, got 0"]);
    problems = [];
    gradeUpstream([{ a: 1, prompt_cache_key: "x" }], [{ a: 2, prompt_cache_key: "fallback-key" }], s, problems);
    expect(problems).toEqual(['upstream[0].a: expected 1, got 2']);
  });
});

describe("argv contract", () => {
  test("capture and check are the frozen corpus's provenance: exit 2 with the explanation", async () => {
    expect(await oracle(["capture"])).toBe(HARNESS_EXIT);
    const r = run("oracle", "check");
    expect(r.status).toBe(HARNESS_EXIT);
    expect(r.stderr).toContain("INOPERABLE");
    expect(r.stderr).toContain("P8-CUT");
  });

  test("an unknown arm or verb exits 2 and names the choices", () => {
    expect(run("oracle", "bogus").status).toBe(HARNESS_EXIT);
    const r = run("nothing");
    expect(r.status).toBe(2);
    expect(r.stderr).toContain("oracle");
  });

  test("help lists the oracle verb", () => {
    const r = run("--help");
    expect(r.status).toBe(0);
    expect(r.stdout).toContain("oracle [replay]");
  });

  test("RED: a present but valueless --artifact or --fixtures is refused before anything is read", () => {
    // Each arm is shaped so that the OLD reading (a bare option = its default) could not reach a
    // daemon either: the option that follows is swallowed as the value, so the jar or corpus is bogus.
    const artifact = run("oracle", "replay", "--artifact", "--fixtures", ORACLE_DIR);
    expect(artifact.status).toBe(HARNESS_EXIT);
    expect(artifact.stderr).toContain("--artifact takes a value");
    expect(artifact.stderr).not.toContain("fat jar missing");
    const both = run("oracle", "replay", "--fixtures", "--artifact");
    expect(both.status).toBe(HARNESS_EXIT);
    expect(both.stderr).toContain("--fixtures, --artifact take a value");
  });

  test("exit code vocabulary is stable", () => {
    expect(DIVERGENCE_EXIT).toBe(1);
    expect(HARNESS_EXIT).toBe(2);
  });
});
