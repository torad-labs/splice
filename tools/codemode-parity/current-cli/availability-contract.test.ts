import { expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { availabilityCatalog, availabilityFindings, availabilityScenario, logicalTerminalLines,
  unavailableExecutionMarker, availabilityWarning, compareAvailabilityBoundary } from "./availability-contract";
import type { CliRun, ObjectValue } from "./types";
import type { TerminalWarningCapture } from "./terminal-warnings";

const bytes = (value: unknown) => Buffer.from(JSON.stringify(value));
const sha = (value: Uint8Array) => createHash("sha256").update(value).digest("hex");

test("availability derives only the exact model mode and binds both byte streams", () => {
  const catalog = { models: [
    { slug: "other", tool_mode: "direct", opaque: { retained: null } },
    { slug: "selected", tool_mode: "code_mode_only", unknown: [1, "9007199254740993"] },
  ], unknown: { retained: true } };
  const original = bytes(catalog);
  const result = availabilityCatalog(original, "selected", "code_mode");
  expect(JSON.parse(Buffer.from(result.bytes).toString())).toEqual({ ...catalog, models: [
    catalog.models[0], { ...catalog.models[1], tool_mode: "code_mode" },
  ] });
  expect(JSON.parse(original.toString())).toEqual(catalog);
  expect(result.projection).toEqual({ model: "selected", originalSha256: sha(original),
    derivedSha256: sha(result.bytes), previous: "code_mode_only", toolMode: "code_mode",
    changes: ["models[1].tool_mode"] });
});

test("availability refuses ambiguous, unknown and lossy catalog inputs", () => {
  for (const value of [[], {}, { models: [] }, { models: [{ slug: "x" }] },
    { models: [{ slug: "x", tool_mode: "unknown" }] },
    { models: [{ slug: "x", tool_mode: "direct" }, { slug: "x", tool_mode: "direct" }] }]) {
    expect(() => availabilityCatalog(bytes(value), "x", "direct")).toThrow();
  }
  expect(() => availabilityCatalog(Buffer.from('{"models":[{"slug":"x","tool_mode":"direct"}],"opaque":9007199254740993}'), "x", "direct")).toThrow("round an integer");
});

function run(): CliRun {
  const scenario = availabilityScenario("code_mode_only");
  return { scenario, scenarioId: scenario.id, reference: { cli: { sha256: "pinned" } },
    cliProcess: { pid: 42, exit: 0, sha256: "pinned" }, completion: { sessionId: "owned-session" }, hostProcesses: [], observations: [{ input: [
      { type: "custom_tool_call_output", call_id: `probe-${scenario.id}-0`, output: "code-mode host is disabled" },
    ] }] } as unknown as CliRun;
}

function capture(text = "Code Mode is unavailable because code-mode host is disabled. Code mode will fail closed; enable `features.code_mode_host` and install `codex-code-mode-host`."): TerminalWarningCapture {
  return { schema: 1, command: "/warnings", pid: 42, sessionId: "owned-session", kept: true,
    frames: [{ index: 1, count: 1, rows: [{ text, wrapped: false }] }] };
}

test("availability needs actual retained warning details, attested completion and an unavailable result", () => {
  expect(availabilityFindings(run(), capture())).toEqual([]);
  expect(availabilityFindings(run(), capture("Warnings No warnings"))).toContain("actual retained warning viewer omitted the source availability warning");
  const child = run();
  child.hostProcesses.push({} as CliRun["hostProcesses"][number]);
  expect(availabilityFindings(child, capture())).toContain("disabled host exposed a native child");
  const unattested = run();
  unattested.cliProcess.sha256 = "changed";
  expect(availabilityFindings(unattested, capture())).toContain("source CLI process was not attested through completion");
  const missing = run();
  missing.observations = [];
  expect(availabilityFindings(missing, capture())).toContain("the real model history lacks the unavailable exec result");
  const executed = run();
  executed.observations[0]!.input[0]!.output = unavailableExecutionMarker;
  expect(availabilityFindings(executed, capture())).toContain("disabled code execution unexpectedly produced its marker");
  const wrongOwner = capture(); wrongOwner.sessionId = "foreign";
  expect(availabilityFindings(run(), wrongOwner)).toContain("actual retained warning viewer was not attested");
  const direct = run(); direct.scenario = availabilityScenario("direct"); direct.scenarioId = direct.scenario.id;
  direct.observations[0]!.input[0]!.call_id = `probe-${direct.scenarioId}-0`;
  direct.observations[0]!.input[0]!.output = "unsupported custom tool call: exec";
  expect(availabilityFindings(direct, capture("Warnings No warnings"))).toEqual([]);
  expect(availabilityFindings(direct, capture())).toContain("direct-mode control unexpectedly emitted a Code Mode availability warning");
  const missingPage = capture(); missingPage.frames[0]!.count = 2;
  expect(availabilityFindings(run(), missingPage)).toContain("actual retained warning viewer did not expose every owned warning");
  expect(availabilityFindings(run(), capture("Code Mode is unavailable because a different reason. Code mode will fail closed"))).toContain("actual retained warning viewer omitted the source availability warning");
  const wrongResult = run(); wrongResult.observations[0]!.input[0]!.output = "a generic startup failure";
  expect(availabilityFindings(wrongResult, capture())).toContain("the real unavailable exec result differs from the source refusal");
});

test("logical terminal warnings retain real newlines and join only display wraps", () => {
  expect(logicalTerminalLines([{ text: "first", wrapped: false }, { text: " half", wrapped: true },
    { text: "next", wrapped: false }])).toBe("first half\nnext");
});

test("availability comparison requires the real warning capture and concrete candidate refusal surface", () => {
  const directory = mkdtempSync(join(tmpdir(), "availability-contract-"));
  try {
    const source = run(); source.directory = directory;
    mkdirSync(join(directory, "terminal"));
    writeFileSync(join(directory, "terminal/availability-warning-view.json"), JSON.stringify(capture()));
    const candidate: ObjectValue = { callbacks: [], clientText: [""], declarations: [{ input: [{ type: "additional_tools",
      tools: [{ name: "exec" }, { name: "wait" }] }] }], availability: { host: "disabled", toolMode: "code_mode_only",
      reason: "code-mode host is disabled", noticeChannel: "head-log", notices: [`[code-mode] ${availabilityWarning("code_mode_only")}`] } };
    expect(() => compareAvailabilityBoundary(source, candidate)).not.toThrow();
    const changes: ObjectValue[] = [{ clientText: ["Invented assistant warning"] }, { callbacks: [{ name: "Ping" }] },
      { declarations: [{ input: [{ type: "additional_tools", tools: [{ name: "Ping" }] }] }] },
      { availability: { host: "disabled", toolMode: "code_mode_only", notices: [] } }];
    for (const change of changes) {
      expect(() => compareAvailabilityBoundary(source, { ...candidate, ...change })).toThrow();
    }
    const foreign = capture(); foreign.pid = 43;
    writeFileSync(join(directory, "terminal/availability-warning-view.json"), JSON.stringify(foreign));
    expect(() => compareAvailabilityBoundary(source, candidate)).toThrow("Source availability evidence differs");
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
