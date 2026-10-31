import assert from "node:assert/strict";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { NativeReference, type HostFrame, type RuntimeDriver } from "./native-reference";
import { CurrentGrpcReference } from "./current-grpc-reference";
import { candidateCommand } from "./candidate-command";
import { artifact, sha256 } from "./qualification";
import type { ExecutedVerifier } from "./current-coverage";
import type { CurrentInventory } from "./current-inventory";
import type { CurrentReference } from "./current-readiness";

const value = (frame: HostFrame) => { assert.equal(frame.result?.status, "ok", JSON.stringify(frame)); return frame.result.value; };
function canonical(value: any, cells = new Map<string, string>()): any {
  if (Array.isArray(value)) return value.map(item => canonical(item, cells));
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).filter(([key]) => !["code_mode_host_duration_ns", "result_buffered_for_ns"].includes(key)).map(([key, item]) => {
    if (["cellId", "cell_id", "runtime_tool_call_id"].includes(key) && typeof item === "string") { if (!cells.has(item)) cells.set(item, `opaque-${cells.size}`); return [key, cells.get(item)]; }
    return [key, canonical(item, cells)];
  }));
}
async function execute(host: RuntimeDriver, sessionId: string, source: string, enabled_tools: any[] = [], max_output_tokens = 1000): Promise<any> {
  const operation = host.operation({ method: "session/execute", sessionId, request: { tool_call_id: `api-${host.transcript.length}`, enabled_tools, source, yield_time_ms: 1000, max_output_tokens } });
  value(await operation.response);
  return value(await host.wait(frame => frame.type === "execute/initialResponse" && frame.id === operation.id));
}
const definition = (kind: "function" | "freeform", name: string, namespace: string | null = null) => ({ name, tool_name: { name, namespace }, description: "Current API fixture", kind, input_schema: null, output_schema: null });
export function assertNullArgumentDiscriminator(observation: any[]): void {
  const response = observation.at(-1)?.output?.Result;
  assert.ok(response && response.error_text === null, "argument admission probe did not finish successfully");
  const items = response.content_items.filter((item: any) => item.type === "input_text").map((item: any) => JSON.parse(item.text));
  const omitted = items.find((item: any) => item.case === "omitted"), explicit = items.find((item: any) => item.case === "null");
  assert.equal(omitted?.value?.accepted, 1, "omitted function argument must reach the callback as an empty object");
  assert.deepEqual(omitted?.value?.input, {});
  assert.match(explicit?.error ?? "", /expects a JSON object/, "literal JSON null must remain distinct and be rejected before client dispatch");
  assert.equal(observation.filter(item => item.tool_kind === "function").length, 2, "only omitted and object function inputs may reach client callbacks");
}
export function assertTimingEvidence(observation: any): void {
  let observations = 0;
  const walk = (value: any) => {
    if (!value || typeof value !== "object") return;
    for (const [key, item] of Object.entries(value)) {
      if (["Result", "Yielded", "Terminated"].includes(key) && item && typeof item === "object" && "content_items" in item) {
        observations++;
        for (const field of ["code_mode_host_duration_ns", ...(key === "Result" ? ["result_buffered_for_ns"] : [])]) {
          const encoded: unknown = (item as Record<string, unknown>)[field];
          assert.ok(typeof encoded === "string" || typeof encoded === "number", `native observation omitted actual ${field}`);
          assert.match(String(encoded), /^\d+$/); assert.ok(BigInt(encoded as string | number) <= 18446744073709551615n, `native ${field} exceeded u64`);
        }
      }
      walk(item);
    }
  };
  walk(observation); assert.ok(observations > 0, "no native observations carried timing evidence");
}
export const currentProtocolCases: Array<{ id: string; run(host: RuntimeDriver): Promise<any> }> = [
  { id: "session-close-reopen-missing", async run(host) {
    await host.hello(); await host.open("current-api"); await host.open("sibling-api");
    const initial = await execute(host, "current-api", "store('persist', 'old'); text(load('persist')); text('suffix');");
    const cell = initial.Result?.cell_id; assert.ok(cell);
    const exhausted = value(await host.operation({ method: "session/wait", sessionId: "current-api", request: { cell_id: cell, yield_time_ms: 5 } }).response);
    const terminated = value(await host.operation({ method: "session/terminate", sessionId: "current-api", cellId: "nonexistent" }).response);
    const closed = value(await host.operation({ method: "session/shutdown", sessionId: "current-api" }).response);
    const missingSession = await host.operation({ method: "session/wait", sessionId: "current-api", request: { cell_id: "nonexistent", yield_time_ms: 5 } }).response;
    assert.equal(missingSession.result.status, "error", "closed session must differ from a missing cell");
    await host.open("current-api");
    const reset = await execute(host, "current-api", "text(load('persist'));");
    assert.deepEqual(reset.Result?.content_items, [{ type: "input_text", text: "undefined" }]);
    const sibling = await execute(host, "sibling-api", "text('sibling-still-live');");
    return { initial, exhausted, terminated, closed, missingSession, reset, sibling };
  } },
  { id: "nested-input-kind-namespace", async run(host) {
    await host.hello(); await host.open("current-api");
    const definitions = [definition("function", "invoke", "mcp__fixture__"), definition("freeform", "raw")];
    const operation = host.operation({ method: "session/execute", sessionId: "current-api", request: { tool_call_id: "current-inputs", enabled_tools: definitions,
      source: "for(const [label,args] of [['omitted',[]],['null',[null]],['object',[{}]],['array',[[]]],['values',[[1,true]]],['string',['raw']],['number',[7]],['boolean',[false]]]){try{text({case:label,value:await tools.invoke(...args)});}catch(e){text({case:label,error:String(e)});}} text(await tools.raw('freeform value'));", yield_time_ms: 1000, max_output_tokens: 1000 } });
    value(await operation.response); const invocations: any[] = [];
    let done = false;
    while (!done) {
      const frame = await host.wait(frame => frame.type === "delegate/request" || frame.type === "execute/initialResponse" && frame.id === operation.id);
      if (frame.type === "execute/initialResponse") { invocations.push({ output: value(frame) }); done = true; }
      else {
        assert.equal(frame.request.type, "tool/invoke"); invocations.push(frame.request.invocation);
        host.send({ type: "delegate/response", id: frame.id, result: { status: "ok", value: { type: "tool/result", result: { accepted: invocations.length, input: frame.request.invocation.input } } } });
      }
    }
    assert.ok(invocations.some(item => item.tool_kind === "freeform" && item.input === "freeform value"), "freeform callback input was not preserved");
    assertNullArgumentDiscriminator(invocations);
    return invocations;
  } },
  { id: "output-token-request-boundaries", async run(host) {
    await host.hello(); await host.open("current-api"); const observations = [];
    for (const budget of [0, 1, 20, 2147483647]) observations.push(await execute(host, "current-api", "text('🙂λ\\n'.repeat(30));", [], budget));
    return observations;
  } },
];

export async function runCurrentProtocol(java: string, jar: string, nativeHost: string, reference: string, evidence: string, runId: string, lock: CurrentReference, inventory: CurrentInventory): Promise<ExecutedVerifier> {
  const result: ExecutedVerifier = { id: "current.native-protocol", runId, startedAt: new Date().toISOString(), finishedAt: "", inventorySha256: inventory.denominatorSha256,
    sourceRevision: lock.sourceRevision, candidateJarSha256: sha256(await readFile(jar)), nativeHostSha256: lock.nativeHostSha256, state: "failed", findings: [], artifacts: [] };
  const directory = resolve(evidence, `${runId}.current-protocol`); await mkdir(directory, { recursive: true });
  for (const test of currentProtocolCases) {
    const outputs: any[] = [];
    for (const side of ["reference", "candidate"] as const) {
      const host = side === "reference" ? new CurrentGrpcReference(reference) : new NativeReference(java, candidateCommand(java, jar, nativeHost).slice(1));
      let observation: any, failure: string | null = null;
      try { observation = await test.run(host); assertTimingEvidence(observation); outputs.push(canonical(observation)); }
      catch (error) { failure = error instanceof Error ? error.message : String(error); result.findings.push(`${test.id} ${side}: ${failure}`); }
      finally { await host.stop(); }
      const path = resolve(directory, `${test.id}.${side}.json`);
      await writeFile(path, JSON.stringify({ schema: 2, runId, sourceRevision: lock.sourceRevision, id: test.id, side, observation, failure, transcript: host.transcript, stderr: host.stderr,
        ...(host instanceof CurrentGrpcReference ? { referenceTransport: "grpc+stdio", rawGrpc: host.rawGrpc } : {}),
      }, null, 2) + "\n"); result.artifacts.push(await artifact(path));
    }
    try { assert.equal(outputs.length, 2); assert.deepEqual(outputs[1], outputs[0], `${test.id}: current typed protocol outcome mismatch`); }
    catch (error) { result.findings.push(error instanceof Error ? error.message : String(error)); }
  }
  result.finishedAt = new Date().toISOString(); result.state = result.findings.length ? "failed" : "executed"; return result;
}
