import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { capturedCommand } from "./current-core";
import { candidateCommand } from "./candidate-command";
import { artifact } from "./qualification";
import type { ExecutedVerifier } from "./current-coverage";

function outputItems(posted: any[], prefix: string): any[] {
  return posted.filter(item => ["custom_tool_call_output", "function_call_output"].includes(item.type) && String(item.call_id).startsWith(prefix));
}
function textParts(outputs: any[]): string[] {
  return outputs.flatMap(item => Array.isArray(item.output) ? item.output.slice(1).filter((part: any) => part.type === "input_text").map((part: any) => part.text) : []);
}
function execDescription(value: any): string {
  if (Array.isArray(value)) return value.map(execDescription).find(Boolean) ?? "";
  if (!value || typeof value !== "object") return "";
  if (value.name === "exec" && typeof value.description === "string") return value.description;
  return Object.values(value).map(execDescription).find(Boolean) ?? "";
}
export function assertCurrentHeadSettings(value: any, nonce: string): void {
  assert.equal(value.caseId, "runtime.head-settings"); assert.equal(value.nonce, nonce);
  for (const label of ["default", "configured"]) {
    const observed = value[label]; assert.equal(observed.outcome, "Success"); assert.equal(observed.waited, label === "configured");
    const description = execDescription(observed.declaration); assert.ok(description, "real provider declaration omitted exec");
    assert.match(description, label === "default" ? /Defaults to 30000 ms/ : /Defaults to 1 ms/);
    const post = observed.posted.at(-1); assert.ok(Array.isArray(post));
    const outputs = outputItems(post, `settings-${label}-${nonce}`).concat(outputItems(post, `settings-wait-${label}-${nonce}`));
    const texts = textParts(outputs); assert.ok(texts.includes(`settings-${label}-completed`));
    const catalog = texts.find(text => text.includes("Health check") && text.includes("Namespace guidance.")); assert.ok(catalog, "actual native ALL_TOOLS metadata omitted namespace/tool documentation");
    assert.equal(catalog.indexOf("Health check") < catalog.indexOf("Namespace guidance."), label === "configured", "head description order did not reach native tool metadata");
    const headers = outputs.map(item => item.output?.[0]?.text).filter((item: any) => typeof item === "string");
    if (label === "configured") assert.ok(headers.some((header: string) => /\(code-mode .* seconds; overhead .* seconds\)/.test(header)), "configured actual provider output omitted cell overhead");
    else assert.equal(headers.some((header: string) => header.includes("(code-mode ")), false);
  }
}
export function assertFailedToolOutputRestart(write: any, read: any, nonce: string): void {
  assert.equal(write.caseId, "runtime.failed-tool-output-write"); assert.equal(read.caseId, "runtime.failed-tool-output-read");
  assert.notEqual(write.pid, read.pid, "result reload must use a fresh actual JVM");
  assert.deepEqual(write.restored, []); assert.ok(write.retained.length > 0); assert.deepEqual(read.restored, write.retained);
  assert.deepEqual(read.callbacks, write.callbacks, "reload reissued or changed accepted callback ownership");
  assert.equal(write.callbacks.length, 1); assert.equal(write.callbacks[0].name, "hidden-tool");
  assert.deepEqual(write.callbacks[0].arguments, { value: nonce }); assert.equal(write.callbacks[0].is_error, true);
  const payload = `permission denied ordinary-client-value-${nonce}\nsecond-value`;
  for (const result of [...write.retained, ...read.restored, ...read.retained]) {
    assert.equal(result.is_error, true); assert.equal(result.disposition, "TOOL_OUTPUT"); assert.equal(result.origin, "GENERIC"); assert.equal(result.payload, payload);
  }
  for (const value of [write, read]) {
    const last = value.posted.at(-1); const outputs = outputItems(last, `ordinary-output-${nonce}`);
    assert.equal(outputs.length, 1); assert.deepEqual(textParts(outputs), [`ordinary-value=${payload}`]);
    assert.equal(textParts(outputs).some(text => text.includes("unexpected-rejection=")), false);
  }
}
export async function appendCurrentApiFlows(java: string, jar: string, host: string, evidence: string, runId: string, execution: ExecutedVerifier): Promise<void> {
  const root = resolve(evidence, `${runId}.current-api-flows`); await mkdir(root, { recursive: true });
  const run = async (id: string, state: string) => {
    const observed = await capturedCommand(candidateCommand(java, jar, "--native-gateway-fixture", id, runId, state, host), root, undefined, 30_000);
    const path = resolve(root, `${id}.json`); await writeFile(path, JSON.stringify({ schema: 2, runId, sourceRevision: execution.sourceRevision, ...observed }, null, 2) + "\n"); execution.artifacts.push(await artifact(path));
    assert.equal(observed.exitCode, 0, observed.stderr); const value = JSON.parse(observed.stdout);
    assert.equal(value.pid, observed.pid); assert.equal(value.schema, 1); assert.equal(value.nonce, runId); assert.equal(value.scope, "real-native-runtime-through-production-gateway"); return value;
  };
  try {
    const settingsState = resolve(root, "settings"); await mkdir(settingsState); assertCurrentHeadSettings(await run("runtime.head-settings", settingsState), runId);
    const resultState = resolve(root, "results"); await mkdir(resultState);
    const writer = await run("runtime.failed-tool-output-write", resultState), reader = await run("runtime.failed-tool-output-read", resultState);
    assertFailedToolOutputRestart(writer, reader, runId);
  } catch (error) { execution.state = "failed"; execution.findings.push(`Production current API flows: ${error instanceof Error ? error.message : String(error)}`); }
  execution.finishedAt = new Date().toISOString();
}
