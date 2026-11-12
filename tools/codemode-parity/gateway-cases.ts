import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, readdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { artifact, type CaseEvidence, type CaseId } from "./qualification";
import { candidateCommand } from "./candidate-command";

type Observation = { schema: number; caseId: string; nonce: string; phase: string; pid: number; scope: string; observed: any };
const user = (content: string) => ({ role: "user", content });
const assistant = (content: string) => ({ role: "assistant", content });
const native = (nonce: string) => [
  { type: "reasoning", id: `rs-${nonce}`, summary: [], encrypted_content: `opaque-${nonce}` },
  assistant("Same words. "), assistant("Same words."),
];
const nextModel = (nonce: string) => [user(`begin-${nonce}`), ...native(nonce), user(`next-${nonce}`)];
function validateAccepted(observed: any, nonce: string): void {
  const issued = observed.records[0].issued.flatMap((step: any) => step.calls);
  assert.deepEqual(issued.map((call: any) => call.clientId), observed.callbackIds ?? [observed.callbackId]);
  assert.deepEqual(observed.accepted, issued.map((call: any) => ({ id: call.runtimeId, output: `accepted-client-result-${nonce}`, isError: false })));
  for (const call of issued) {
    assert.equal(observed.records[0].results[call.clientId].output, `accepted-client-result-${nonce}`);
    assert.equal(observed.records[0].results[call.clientId].isError, false);
  }
}
function validateHistory(observed: any, nonce: string): void {
  assert.deepEqual(observed.initialClient, [user(`begin-${nonce}`)]);
  assert.deepEqual(observed.nativeResponse, native(nonce));
  assert.deepEqual(observed.nextClient, [user(`begin-${nonce}`), assistant("Same words. Same words."), user(`next-${nonce}`)]);
  assert.deepEqual(observed.nextModelInput, nextModel(nonce), "merged display text must acknowledge exact native items without reconstructing them");
}
/** Each classifier result must carry the raw request and its persisted callback/reasoning ownership witnesses. */
export function assertReplayOwnership(observed: any, nonce: string): void {
  const variants = ["callbackOnly", "unknownReasoningBeforeOwnedCallback", "deliveredReasoningWithValidatedDeclaration", "unknownReasoningWithDeclaration"];
  assert.ok(observed && typeof observed === "object");
  assert.deepEqual(Object.keys(observed).sort(), ["schema", "baseline", ...variants].sort());
  assert.equal(observed.schema, 1);
  const callback = (id: string) => [
    { type: "function_call", call_id: id, name: "Read", arguments: "{}" },
    { type: "function_call_output", call_id: id, output: `result-${nonce}` },
  ];
  const historical = native(nonce)[0];
  const baseline = [user(`begin-${nonce}`), ...callback("old"), historical, user(`continue-${nonce}`)];
  const digest = (item: unknown) => createHash("sha256").update(JSON.stringify(item)).digest("hex");
  assert.deepEqual(observed.baseline, baseline);
  const added = { type: "reasoning", id: `rs-added-${nonce}`, summary: [], encrypted_content: `opaque-added-${nonce}` };
  const declarationId = `ts_decl_replay-${nonce}`;
  const declaration = [added,
    { type: "tool_search_call", call_id: declarationId, execution: "client", arguments: { query: "Read" } },
    { type: "tool_search_output", call_id: declarationId, execution: "client", tools: [
      { type: "function", name: "Read", parameters: { type: "object" } },
    ] },
  ];
  for (const kind of variants) {
    const value = observed[kind];
    assert.ok(value && typeof value === "object", `${kind}: actual classifier observation missing`);
    assert.deepEqual(Object.keys(value).sort(), ["classification", "input", "record", "projection"].sort());
    const delivered = kind === "deliveredReasoningWithValidatedDeclaration";
    const extra = kind === "callbackOnly" ? [] : kind === "unknownReasoningBeforeOwnedCallback" ? [added] : declaration;
    assert.equal(value.classification, kind === "callbackOnly" || delivered ? "NONE" : "STEERING", `${kind}: production replay ownership classification`);
    assert.deepEqual(value.input, [...baseline, ...extra, ...callback("one"), ...callback("two")], `${kind}: raw classifier input`);
    const record = value.record;
    assert.ok(record && typeof record === "object", `${kind}: reloaded durable record missing`);
    assert.equal(record.id, `record-${nonce}`); assert.equal(record.key, `parity-${nonce}`);
    assert.equal(record.outerCallId, `exec-${nonce}`); assert.equal(record.source, "source");
    assert.equal(record.baselineLogicalCount, 1, `${kind}: model baseline must differ from the acknowledged client boundary`);
    assert.deepEqual(record.issued.flatMap((step: any) => step.calls).map((call: any) => [call.clientId, call.runtimeId, call.name, call.arguments]),
      [["one", "one", "Read", {}], ["two", "two", "Read", {}]]);
    assert.deepEqual(record.pending.map((call: any) => call.clientId), ["one", "two"]);
    assert.deepEqual(Object.keys(record.results).sort(), ["one", "two"]);
    for (const id of ["one", "two"]) {
      assert.equal(record.results[id].output, `result-${nonce}`); assert.equal(record.results[id].isError, false);
    }
    assert.deepEqual(record.nativeSegments.flatMap((segment: any) => segment.items), [historical]);
    assert.deepEqual(record.continuity, []); assert.deepEqual(record.continuityReplay, []);
    assert.deepEqual(record.issued.flatMap((step: any) => step.deliveredNative ?? []), delivered ? [added] : [], `${kind}: exact delivered reasoning witness`);
    const acknowledged = record.replayAnchors?.client?.acknowledgement;
    assert.deepEqual(acknowledged?.items?.map((item: any) => [item.digest, item.callbackId]),
      [baseline[0], baseline[1], baseline[2], baseline[4]].map((item: any) => [digest(item), item.type === "function_call_output" ? "old" : null]),
      `${kind}: durable acknowledgement must identify the exact wider client baseline`);
    assert.deepEqual(acknowledged?.native, [digest({ type: "reasoning", id: `rs-${nonce}` })]);
    assert.equal(record.replayAnchors.client.count, baseline.length);
    assert.equal(record.replayAnchors.client.digest,
      createHash("sha256").update(baseline.map(digest).join("")).digest("hex"));
    const projection = [{ logicalOffset: 3, items: [historical] },
      ...(extra.length ? [{ logicalOffset: 4, callbackId: "one", items: extra }] : [])];
    assert.deepEqual(value.projection, projection, `${kind}: validated declaration must bind to the owned Read callback`);
  }
}
/** A clear marker on a callback the old durable branch never issued cannot recover that branch's native source. */
export function assertUnacceptedClear(observed: any, nonce: string): void {
  assert.ok(observed && typeof observed === "object", "unaccepted-clear production observation missing");
  assert.deepEqual(Object.keys(observed).sort(), ["clientInput", "postedInput", "beforeRecords", "afterRecords", "callbackId", "oldRecordId", "oldOuterCallId"].sort());
  assert.equal(observed.callbackId, `toolu_splice_unaccepted-clear-${nonce}`);
  assert.equal(observed.oldOuterCallId, `outer-${nonce}`);
  assert.ok(typeof observed.oldRecordId === "string" && observed.oldRecordId.length > 0);
  assert.ok(Array.isArray(observed.beforeRecords) && observed.beforeRecords.length > 0);
  assert.ok(Array.isArray(observed.afterRecords));
  const authority = (records: any[]) => records.map(({ updatedAt, ...record }) => record);
  assert.deepEqual(authority(observed.afterRecords), authority(observed.beforeRecords),
    "unaccepted clear must retain original durable branch authority unchanged");
  observed.beforeRecords.forEach((record: any, index: number) => {
    const after = observed.afterRecords[index];
    assert.ok(Number.isFinite(record.updatedAt) && Number.isFinite(after.updatedAt) && after.updatedAt >= record.updatedAt,
      "request touch must retain a monotonic numeric timestamp");
  });
  const old = observed.beforeRecords.filter((record: any) => record.id === observed.oldRecordId);
  assert.equal(old.length, 1); assert.equal(old[0].outerCallId, observed.oldOuterCallId);
  assert.ok(typeof old[0].source === "string" && old[0].source.length > 0);
  for (const record of observed.beforeRecords) {
    const owned = [...Object.keys(record.results), ...record.pending.map((call: any) => call.clientId),
      ...record.issued.flatMap((step: any) => step.calls.map((call: any) => call.clientId))];
    assert.equal(owned.includes(observed.callbackId), false, "claimed unaccepted callback was actually owned by retained source");
  }
  const packets = (input: any) => {
    assert.ok(Array.isArray(input));
    return input.filter((item: any) => item.call_id === observed.callbackId);
  };
  const original = packets(observed.clientInput);
  assert.equal(original.length, 2, "raw unaccepted callback call/result pair missing or duplicated");
  assert.equal(original[0].type, "function_call"); assert.equal(original[0].name, "Read");
  assert.deepEqual(original[1], { type: "function_call_output", call_id: observed.callbackId, output: "[Old tool result content cleared]" });
  assert.deepEqual(packets(observed.postedInput), original, "unknown callback and actual clear marker must remain raw upstream");
  assert.equal(observed.postedInput.some((item: any) => item.call_id === observed.oldOuterCallId), false,
    "unaccepted callback resurrected the old source or its output");
}
const drivers: Array<{ id: CaseId; phases: string[]; fullFamily: boolean; validate(values: Observation[], nonce: string): void }> = [
  { id: "history.append-only", phases: ["single"], fullFamily: true, validate(values, nonce) { validateHistory(values[0]!.observed, nonce); } },
  { id: "history.replay-is-not-steering", phases: ["single"], fullFamily: true, validate(values, nonce) {
    const observed = values[0]!.observed;
    assert.equal(observed.callbackIds?.length, 2, "captured replay shape must own both concurrent callbacks");
    validateAccepted(observed, nonce);
    assert.ok(observed.postedInput.some((item: any) => item.id === `rs-${nonce}` && item.encrypted_content === `opaque-${nonce}`));
    assert.ok(observed.postedInput.some((item: any) => item.type === "custom_tool_call" && item.call_id === `outer-${nonce}`));
    assert.ok(observed.postedInput.some((item: any) => item.type === "custom_tool_call_output" && JSON.stringify(item).includes(`accepted-native-output-${nonce}`)));
    assert.equal(observed.postedInput.some((item: any) => observed.callbackIds.includes(item.call_id)), false);
    assert.ok(observed.callbackOnlyPreparedInput.some((item: any) => item.role === "assistant" && item.content === "Same words. Same words."));
    assert.equal(observed.callbackOnlyPreparedInput.some((item: any) => item.id === `rs-prior-${nonce}`), false);
    assert.ok(observed.postedInput.some((item: any) => item.id === `rs-prior-${nonce}` && item.encrypted_content === `opaque-prior-${nonce}`));
    assert.ok(observed.postedInput.some((item: any) => item.role === "assistant" && item.content === "Same words. "));
    assert.ok(observed.postedInput.some((item: any) => item.role === "assistant" && item.content === "Same words."));
    assert.equal(JSON.stringify(observed.postedInput).includes("Script terminated"), false);
    assert.equal(observed.records.length, 1); assert.equal(observed.records[0].phase, "COMPLETED");
  } },
  { id: "history.real-steering", phases: ["single"], fullFamily: true, validate(values, nonce) {
    const observed = values[0]!.observed;
    assertReplayOwnership(observed.replayOwnership, nonce);
    assert.ok(observed.newUser.postedInput.some((item: any) => item.role === "user" && item.content === `steer-${nonce}`));
    assert.ok(JSON.stringify(observed.newUser.records).includes("Script terminated"), "real new user steering must interrupt the pending source");
    const edited = observed.editedOldResult;
    assert.ok(edited.postedInput.some((item: any) => item.type === "function_call_output" && item.call_id === edited.callbackId && item.output === `edited-client-result-${nonce}`));
    assert.equal(edited.postedInput.some((item: any) => item.call_id === `outer-${nonce}` || item.id === `rs-${nonce}`), false,
      "edited accepted result must fork rather than recover another canonical script");
    assert.ok(observed.rewind.postedInput.some((item: any) => item.role === "user" && item.content === `start-${nonce}`));
    assert.equal(observed.rewind.postedInput.some((item: any) => item.role === "user" && item.content === `branch-${nonce}`), false);
    assert.equal(observed.compaction.compaction, true, "actual prepared turn must carry the compaction boundary");
    assert.ok(observed.compaction.postedInput.some((item: any) => item.role === "user" && item.content === `You have been tasked with summarizing conversations. Summary-${nonce}`));
    for (const variant of ["rewind", "compaction"]) assert.equal(observed[variant].postedInput.some((item: any) =>
      item.call_id === `outer-${nonce}` || item.id === `rs-${nonce}`), false, `${variant} retained native authority from the abandoned branch`);
  } },
  { id: "history.restart", phases: ["write", "read"], fullFamily: false, validate(values, nonce) {
    assert.notEqual(values[0]!.pid, values[1]!.pid, "restart fixture must execute in different JVMs");
    for (const value of values) {
      validateHistory(value.observed.history, nonce);
      assert.deepEqual(value.observed.unknownCallbackIds, []);
      assert.equal(value.observed.records.length, 1);
      const record = value.observed.records[0];
      assert.equal(record.id, `record-${nonce}`);
      assert.equal(record.results.one.output, `result-${nonce}`); assert.equal(record.results.two.output, `result-${nonce}`);
      assert.deepEqual(record.issued[0].calls.map((call: any) => call.clientId), ["one", "two"]);
    }
    for (const field of ["id", "key", "source", "results", "issued", "replayAnchors", "nativeSegments"]) {
      assert.deepEqual(values[1]!.observed.records[0][field], values[0]!.observed.records[0][field], `restart must preserve exact ${field}`);
    }
  } },
  { id: "history.cleared-results", phases: ["single"], fullFamily: true, validate(values, nonce) {
    const observed = values[0]!.observed;
    assertUnacceptedClear(observed.unacceptedClear, nonce);
    assert.deepEqual(observed.postedInput, [...observed.canonical, user(`continue-${nonce}`)]);
    assert.ok(observed.canonical.some((item: any) => item.type === "reasoning" && item.encrypted_content === `opaque-${nonce}`));
    assert.ok(observed.canonical.some((item: any) => item.type === "custom_tool_call" && item.call_id === `outer-${nonce}`));
    assert.ok(observed.canonical.some((item: any) => item.type === "custom_tool_call_output" && JSON.stringify(item).includes(`accepted-native-output-${nonce}`)));
    assert.equal(observed.postedInput.some((item: any) => item.call_id === observed.callbackId), false);
    assert.equal(JSON.stringify(observed.postedInput).includes("[Old tool result content cleared]"), false);
    validateAccepted(observed, nonce);
  } },
];
async function stateArtifacts(directory: string): Promise<string[]> {
  const paths: string[] = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) paths.push(...await stateArtifacts(path));
    else if (entry.isFile() && !entry.name.endsWith(".lock")) paths.push(path);
  }
  return paths;
}
export async function runGatewayCases(java: string, jar: string, evidence: string, runId: string, cases: CaseEvidence[]): Promise<void> {
  for (const driver of drivers) {
    const item = cases.find(candidate => candidate.id === driver.id)!;
    item.findings = [];
    const stateDirectory = resolve(evidence, `${runId}.${driver.id}.state`);
    await mkdir(stateDirectory, { recursive: true });
    const values: Observation[] = [];
    try {
      for (const phase of driver.phases) {
        const command = candidateCommand(java, jar, "--gateway-fixture", driver.id, runId, stateDirectory, phase);
        const started = performance.now();
        const child = Bun.spawn(command, { stdout: "pipe", stderr: "pipe" });
        const timer = setTimeout(() => child.kill("SIGKILL"), 15_000);
        let stdout = "", stderr = "", exitCode = -1;
        try { [stdout, stderr, exitCode] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]); }
        finally { clearTimeout(timer); }
        const path = resolve(evidence, `${runId}.${driver.id}.${phase}.json`);
        await writeFile(path, JSON.stringify({ schema: 1, runId, caseId: driver.id, command, elapsedMs: performance.now() - started,
          processPid: child.pid, exitCode, stdout, stderr }, null, 2) + "\n");
        item.artifacts.push(await artifact(path));
        assert.equal(exitCode, 0, stderr || "gateway diagnostic failed");
        const value: Observation = JSON.parse(stdout);
        assert.equal(value.schema, 1); assert.equal(value.caseId, driver.id); assert.equal(value.nonce, runId); assert.equal(value.phase, phase);
        assert.equal(value.pid, child.pid); assert.equal(value.scope, "production-provider-components");
        values.push(value);
      }
      driver.validate(values, runId);
      for (const path of await stateArtifacts(stateDirectory)) item.artifacts.push(await artifact(path));
      if (!driver.fullFamily) item.findings.push("Provider components survived a real JVM restart; daemon/client restart recovery remains unexecuted.");
    } catch (error) { item.findings.push(error instanceof Error ? error.message : String(error)); }
    item.state = item.findings.length ? "failed" : "executed";
    console.log(`${item.state === "executed" ? "EXECUTED" : "UNQUALIFIED"} ${item.id}`);
  }
}
