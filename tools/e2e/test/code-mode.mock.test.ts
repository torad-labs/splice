/** The code-mode MOCK's own net: the guidance/runner coupling assertion, the toggle TOML states and
 *  the toggle probe's request shape, all without a daemon or a jar. The gate leg is
 *  `bun tools/e2e code-mode mock --selftest`, which runs this file; the PACKAGED leg
 *  (`code-mode mock --artifact ... --receipt ...`) is the one that needs the fat jar.
 *
 *  V4-145 (carried): converted from code_mode_mock.py's MockTests; the one mock.patch is a swap on
 *  `mockSeams`, restored in `finally`.
 *
 *  Restructure PR 5: a real `bun test` file. The ASSERTIONS stay `check.*` from the compat layer,
 *  which compares the tagged Python tree (see code-mode.test.ts's note).
 */
import { describe, expect, test } from "bun:test";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { spawnSync } from "node:child_process";
import { join, resolve } from "node:path";
import { obj, type PyValue } from "../src/compat/python-json.ts";
import { check, get, ValueError } from "../src/compat/python-values.ts";
import {
  assertGuidance, CALLER_SYSTEM, GUIDANCE, mockConfigure, mockSeams, PROBE_TOOLS, runToggleProbe, TOGGLE_PROMPT,
  TOGGLE_RESPONSE, TOGGLE_SYSTEM,
} from "../src/commands/code-mode.ts";

const CLI = resolve(import.meta.dir, "../index.ts");
const int = (n: number): PyValue => ({ __pyNum: String(n), isFloat: false });

const P = (v: unknown): PyValue => {
  if (v === null || v === undefined) return null;
  if (typeof v === "boolean" || typeof v === "string") return v;
  if (typeof v === "number") return int(v);
  if (Array.isArray(v)) return v.map(P);
  return obj(Object.entries(v as Record<string, unknown>).map(([k, x]) => [k, P(x)]));
};

describe("mock", () => {
  test("guidance keeps one original developer item and runner", () => {
    const callerTools = [{ type: "function", name: "Read" }];
    const body = P({ input: [
      { type: "additional_tools", role: "developer", tools: [...callerTools, { type: "custom", name: "splice_exec" }] },
      { role: "developer", content: CALLER_SYSTEM + "\n\n" + GUIDANCE },
      { role: "user", content: "synthetic" },
    ] });
    assertGuidance(body, CALLER_SYSTEM, true);
  });

  test("disabled guidance is exactly the caller instruction without runner", async () => {
    const body = P({ input: [
      { type: "additional_tools", role: "developer", tools: [{ type: "function", name: "Read" }] },
      { role: "developer", content: TOGGLE_SYSTEM },
      { role: "user", content: TOGGLE_PROMPT },
    ] });
    assertGuidance(body, TOGGLE_SYSTEM, false);
    (get(body, "input") as PyValue[]).push(P({ role: "developer", content: "duplicate" }));
    await check.raises((e) => e instanceof ValueError, () => assertGuidance(body, TOGGLE_SYSTEM, false), /duplicated/);
  });

  test("toggle toml has true false and omitted states", () => {
    const directory = mkdtempSync(join(tmpdir(), "tmp"));
    try {
      for (const [index, [configured, expected]] of ([[null, null], [true, true], [false, false]] as const).entries()) {
        const current = join(directory, String(index));
        mkdirSync(current);
        const env = mockConfigure(current, 1, 2, 3, 4, configured);
        const quirks = (Bun.TOML.parse(readFileSync(env.SPLICE_CONFIG as string, "utf8")) as
          { providers: { code_mode: { quirks: Record<string, unknown> } } }).providers.code_mode.quirks;
        check.equal(expected, quirks.code_mode ?? null);
      }
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });

  test("toggle probe uses normal tools system and session header", async () => {
    const captured: [number, string, string, PyValue, [string, string][]][] = [];
    const reply = async (port: number, method: string, path: string, body?: PyValue, headers?: [string, string][] | null) => {
      captured.push([port, method, path, body ?? null, headers ?? []]);
      return P({ stop_reason: "end_turn", content: [{ type: "text", text: TOGGLE_RESPONSE }] });
    };
    const old = mockSeams.requestJson;
    mockSeams.requestJson = reply as never;
    try {
      await runToggleProbe(1234, "synthetic-bearer");
    } finally {
      mockSeams.requestJson = old;
    }
    const [, method, path, body, headers] = captured[0] as [number, string, string, PyValue, [string, string][]];
    expect([method, path]).toEqual(["POST", "/v1/messages"]);
    check.equal(PROBE_TOOLS, get(body, "tools"));
    check.equal(TOGGLE_SYSTEM, get(body, "system"));
    const header = (k: string) => headers.find(([name]: [string, string]) => name === k)?.[1];
    check.equal("Bearer synthetic-bearer", header("Authorization"));
    check.true(header("x-claude-code-session-id"));
  });

  // THE JAR IS AN INPUT, NEVER BUILT FROM HERE. The gate runs this leg with the gradle slot HELD,
  // so a nested gradle would deadlock or double-build. A missing jar must refuse before anything is
  // spawned, read or written: exit 2, the producer named, no receipt left behind, nothing on stdout
  // (runMock's first act on a real jar is to print the artifact's sha256, so an empty stdout is the
  // evidence that it never got that far).
  test("a missing fat jar is a harness failure (exit 2) that names the producer, never a nested gradle", () => {
    const receipt = join(mkdtempSync(join(tmpdir(), "code-mode-mock-")), "receipt.json");
    const r = spawnSync(process.execPath, [CLI, "code-mode", "mock", "--artifact", "/nonexistent/app-all.jar", "--receipt", receipt],
      { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    expect(r.status).toBe(2);
    expect(r.stderr).toContain("fat jar missing at /nonexistent/app-all.jar");
    expect(r.stderr).toContain("bash checks/gradle-slot.sh <tag> :app:shadowJar");
    expect(r.stdout).toBe("");
    expect(existsSync(receipt)).toBe(false);
  });
});
