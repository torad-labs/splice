import { createHash } from "node:crypto";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import type { AvailabilityToolMode, CliRun, CliScenario } from "./types";
import type { ObjectValue } from "./types";
import type { TerminalWarningCapture } from "./terminal-warnings";

const digest = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");
const modes: AvailabilityToolMode[] = ["direct", "code_mode", "code_mode_only"];
export const unavailableExecutionMarker = "UNEXPECTED_CODEMODE_HOST_EXECUTION";

export function availabilityScenario(toolMode: AvailabilityToolMode): CliScenario {
  if (!modes.includes(toolMode)) throw Error("Unknown source tool mode");
  return {
    schema: 1,
    id: `host-unavailable-${toolMode.replaceAll("_", "-")}`,
    availability: { host: "disabled", toolMode },
    actions: [{ kind: "exec", source: `text(${JSON.stringify(unavailableExecutionMarker)});` }],
  };
}

/** Only a separately hashed negative catalog changes the selected model's actual typed tool_mode field. */
export function availabilityCatalog(original: Uint8Array, model: string, toolMode: AvailabilityToolMode): {
  bytes: Uint8Array;
  projection: { model: string; originalSha256: string; derivedSha256: string; previous: unknown; toolMode: AvailabilityToolMode; changes: string[] };
} {
  if (!modes.includes(toolMode)) throw Error("Unknown source tool mode");
  const text = Buffer.from(original).toString("utf8");
  // Never round a serde_json integer while deriving a controlled ModelInfo input.
  const numericText = text.replace(/"(?:\\.|[^"\\])*"/g, '""');
  for (const token of numericText.matchAll(/-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g)) {
    if (!/[.eE]/.test(token[0]) && !Number.isSafeInteger(Number(token[0]))) throw Error("Catalog derivation would round an integer");
  }
  const base: unknown = JSON.parse(text);
  if (!base || typeof base !== "object" || Array.isArray(base) || !Array.isArray((base as { models?: unknown }).models)) throw Error("Expected an actual model catalog object");
  const catalog = base as { models: Array<Record<string, unknown>> };
  const selected = catalog.models.map((entry, index) => ({ entry, index })).filter(({ entry }) => entry?.slug === model);
  if (selected.length !== 1) throw Error("Availability catalog requires one exact selected model");
  const { entry, index } = selected[0]!;
  if (!modes.includes(entry.tool_mode as AvailabilityToolMode)) throw Error("Selected model lacks its typed source tool_mode");
  const previous = entry.tool_mode;
  const derived = structuredClone(catalog);
  derived.models[index]!.tool_mode = toolMode;
  const restored = structuredClone(derived);
  restored.models[index]!.tool_mode = previous;
  if (JSON.stringify(restored) !== JSON.stringify(catalog)) throw Error("Availability catalog changed an unrelated source field");
  const bytes = Buffer.from(JSON.stringify(derived, null, 2) + "\n");
  return { bytes, projection: { model, originalSha256: digest(original), derivedSha256: digest(bytes), previous, toolMode,
    changes: previous === toolMode ? [] : [`models[${index}].tool_mode`] } };
}

/** Reference-only negative execution evidence is never a claim that a native code cell ran. */
export function availabilityFindings(run: CliRun, capture: TerminalWarningCapture): string[] {
  const availability = run.scenario.availability;
  if (!availability || availability.host !== "disabled" || !modes.includes(availability.toolMode)) return ["missing explicit source availability input"];
  const findings: string[] = [];
  if (run.hostProcesses.length) findings.push("disabled host exposed a native child");
  if (run.cliProcess.exit !== 0 || run.cliProcess.sha256 !== run.reference.cli.sha256) findings.push("source CLI process was not attested through completion");
  if (capture?.schema !== 1 || capture.command !== "/warnings" || capture.pid !== run.cliProcess.pid ||
    capture.sessionId !== run.completion.sessionId || capture.kept !== true || !capture.frames.length) return [...findings, "actual retained warning viewer was not attested"];
  const count = capture.frames[0]!.count;
  if (!Number.isSafeInteger(count) || count < 0 || count > 32 || capture.frames.length !== Math.max(1, count) ||
    capture.frames.some((frame, index) => frame.count !== count || frame.index !== (count ? index + 1 : 0))) {
    findings.push("actual retained warning viewer did not expose every owned warning");
  }
  const heading = "Code Mode is unavailable because";
  const warnings = capture.frames.map(frame => logicalTerminalLines(frame.rows).replace(/\s+/g, " "));
  const selected = warnings.filter(text => text.includes(heading));
  if (availability.toolMode === "direct") {
    if (selected.length) findings.push("direct-mode control unexpectedly emitted a Code Mode availability warning");
  } else if (selected.length !== 1 || !selected[0]!.includes(availabilityWarning(availability.toolMode)!)) findings.push("actual retained warning viewer omitted the source availability warning");
  const outputs = run.observations.flatMap(observation => observation.input).filter(item =>
    item.type === "custom_tool_call_output" && item.call_id === `probe-${run.scenarioId}-0`);
  if (!outputs.length) findings.push("the real model history lacks the unavailable exec result");
  for (const output of outputs) if (output.output !== unavailableResult(availability.toolMode)) findings.push("the real unavailable exec result differs from the source refusal");
  for (const output of outputs) if (JSON.stringify(output.output).includes(unavailableExecutionMarker)) findings.push("disabled code execution unexpectedly produced its marker");
  return findings;
}

export function unavailableResult(mode: AvailabilityToolMode): string {
  return mode === "code_mode_only" ? "code-mode host is disabled" : "unsupported custom tool call: exec";
}

export function availabilityWarning(mode: AvailabilityToolMode): string | null {
  if (mode === "direct") return null;
  const behavior = mode === "code_mode_only" ? "Code mode will fail closed" : "Falling back to direct tools";
  return `Code Mode is unavailable because code-mode host is disabled. ${behavior}; enable \`features.code_mode_host\` and install \`codex-code-mode-host\`.`;
}

/** The head log is the explicit notice surface; unavailable cases attest that no host was launched. */
export function compareAvailabilityBoundary(source: CliRun, candidate: ObjectValue): void {
  const mode = source.scenario.availability?.toolMode;
  assert.ok(mode, "Availability comparison requires its explicit source input");
  const capture = JSON.parse(readFileSync(resolve(source.directory, "terminal/availability-warning-view.json"), "utf8")) as TerminalWarningCapture;
  assert.deepEqual(availabilityFindings(source, capture), [], "Source availability evidence differs");
  const warning = availabilityWarning(mode);
  assert.deepEqual(candidate.availability, { host: "disabled", toolMode: mode, reason: "code-mode host is disabled",
    noticeChannel: "head-log", notices: warning ? [`[code-mode] ${warning}`] : [] }, "Production preflight/retained diagnostic differs");
  assert.deepEqual(candidate.callbacks, [], "Unavailable execution crossed the client permission boundary");
  assert.ok(Array.isArray(candidate.clientText) && candidate.clientText.every(value => value === ""), "Availability warning became synthetic model content");
  assert.ok(Array.isArray(candidate.declarations) && candidate.declarations.length, "No actual candidate declaration surface");
  for (const body of candidate.declarations) {
    assert.ok(body && typeof body === "object" && !Array.isArray(body) && Array.isArray(body.input));
    const first = body.input[0];
    assert.ok(first && typeof first === "object" && !Array.isArray(first) && first.type === "additional_tools" && Array.isArray(first.tools));
    const names = first.tools.map(tool => tool && typeof tool === "object" && !Array.isArray(tool) ? tool.name : null);
    if (mode === "code_mode_only") assert.deepEqual(names, ["exec", "wait"], "Only mode must retain its fail-closed surface");
    else assert.deepEqual(first.tools, [{ type: "function", name: "Ping", description: "Unused control-case catalog entry",
      parameters: { type: "object", properties: {} } }], "Direct fallback changed the actual advertised direct tool");
  }
}

/** Xterm's wrap flag distinguishes a display wrap from an actual newline in the warning. */
export function logicalTerminalLines(rows: Array<{ text: string; wrapped: boolean }>): string {
  return rows.reduce((text, row, index) => text + (index && !row.wrapped ? "\n" : "") + row.text, "");
}
