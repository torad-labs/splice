import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { candidateCommand } from "./candidate-command";
import { artifact } from "./qualification";
import { capturedCommand } from "./current-core";
import type { ExecutedVerifier } from "./current-coverage";

function result(posted: any[], callId: string): any[] {
  const matches = posted.filter(item => ["custom_tool_call_output", "function_call_output"].includes(item.type) && item.call_id === callId);
  assert.equal(matches.length, 1, `deferred search must retain exactly one executed result for ${callId}`);
  assert.ok(Array.isArray(matches[0].output));
  assert.match(matches[0].output[0]?.text ?? "", /^Script completed\n/);
  return matches[0].output.slice(1);
}
export function assertDeferredDiscovery(value: any, nonce: string): void {
  assert.equal(value.schema, 1); assert.equal(value.caseId, "runtime.deferred-search"); assert.equal(value.nonce, nonce);
  assert.equal(value.scope, "real-native-runtime-through-production-gateway");
  assert.deepEqual(value.callbacks.map((item: any) => [item.name, item.arguments, item.result]), [
    ["hidden-tool", { value: `old-${nonce}` }, `client-result-0-${nonce}`], ["fresh.tool", { value: `new-${nonce}` }, `client-result-1-${nonce}`],
  ]);
  assert.equal(new Set(value.callbacks.map((item: any) => item.id)).size, 2, "callbacks must have distinct actual ownership");
  assert.ok(value.outcomes.every((item: any) => item === "Success"), "discovery flow did not finish all production turns");
  const posted = value.posted.at(-1); assert.ok(Array.isArray(posted));
  assert.equal(posted.some((item: any) => ["tool_search_call", "tool_search_output"].includes(item.type)), false, "nested discovery became unrelated top-level search history");
  for (const [version, name, resultStage] of [["old", "hidden_tool", 0], ["new", "fresh_tool", 1]] as const) {
    const content = result(posted, `discover-${version}-${nonce}`);
    assert.equal(content.length, 1); assert.equal(content[0].type, "input_text");
    const search = JSON.parse(content[0].text); assert.equal(search.tools.length, 1); assert.equal(search.tools[0].name, name);
    assert.match(search.tools[0].description, new RegExp(`${name}\\(args:`)); assert.match(search.tools[0].description, /value: string/);
    assert.match(search.tools[0].description, /Lookup value/); assert.match(search.tools[0].description, /Promise</);
    assert.deepEqual(result(posted, `invoke-${version}-${nonce}`), [{ type: "input_text", text: name }, { type: "input_text", text: `client-result-${resultStage}-${nonce}` }]);
  }
  for (const declaration of value.declarations) {
    const tools = (declaration.tools ?? []).flatMap((tool: any) => tool.type === "namespace" ? tool.tools : [tool]);
    const exec = tools.find((tool: any) => tool.name === "exec"); assert.ok(exec, "production declaration lost exec");
    assert.match(exec.description, /await tools\.tool_search/); assert.match(exec.description, /Shared MCP Types:/);
  }
}
export async function appendCurrentDiscovery(java: string, jar: string, host: string, evidence: string, runId: string, execution: ExecutedVerifier): Promise<void> {
  const directory = resolve(evidence, `${runId}.current-discovery`); await mkdir(directory, { recursive: true });
  try {
    const argv = candidateCommand(java, jar, "--native-gateway-fixture", "runtime.deferred-search", runId, directory, host);
    const observed = await capturedCommand(argv, directory, undefined, 30_000);
    const path = resolve(directory, "observed.json"); await writeFile(path, JSON.stringify({ schema: 2, runId, sourceRevision: execution.sourceRevision, ...observed }, null, 2) + "\n");
    execution.artifacts.push(await artifact(path)); assert.equal(observed.exitCode, 0, observed.stderr);
    const value = JSON.parse(observed.stdout); assert.equal(value.pid, observed.pid);
    assertDeferredDiscovery(value, runId);
  } catch (error) { execution.state = "failed"; execution.findings.push(`Production deferred discovery: ${error instanceof Error ? error.message : String(error)}`); }
  execution.finishedAt = new Date().toISOString();
}
